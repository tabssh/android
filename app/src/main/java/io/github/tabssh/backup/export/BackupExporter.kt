package io.github.tabssh.backup.export

import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.AuditLogEntry
import io.github.tabssh.storage.database.entities.CloudAccount
import io.github.tabssh.storage.database.entities.ComposeStack
import io.github.tabssh.storage.database.entities.ConnectionGroup
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.ContainerAutoUpdatePolicy
import io.github.tabssh.storage.database.entities.DockerHost
import io.github.tabssh.storage.database.entities.HostKeyEntry
import io.github.tabssh.storage.database.entities.HypervisorAccount
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.storage.database.entities.Identity
import io.github.tabssh.storage.database.entities.Macro
import io.github.tabssh.storage.database.entities.MonitorSlot
import io.github.tabssh.storage.database.entities.NetworkRoute
import io.github.tabssh.storage.database.entities.PortForward
import io.github.tabssh.storage.database.entities.RegistryCredential
import io.github.tabssh.storage.database.entities.SingleContainerConfig
import io.github.tabssh.storage.database.entities.Snippet
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.storage.database.entities.TabSession
import io.github.tabssh.storage.database.entities.ThemeDefinition
import io.github.tabssh.storage.database.entities.TrustedCertificate
import io.github.tabssh.storage.database.entities.VncHost
import io.github.tabssh.storage.database.entities.VncIdentity
import io.github.tabssh.storage.database.entities.Workspace
import io.github.tabssh.crypto.keys.KeyStorage
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.storage.preferences.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject

/**
 * Handles exporting data for backup.
 *
 * Per-entity wire shape — the only shape this class has ever written and the
 * only one [io.github.tabssh.backup.import.BackupImporter] reads:
 *
 *     {
 *       "v": <BackupManager.BACKUP_VERSION>,
 *       "items": [ <full entity JSON>, ... ]
 *     }
 *
 * Each entity in `items` is the kotlinx.serialization JSON of the Room
 * `@Entity` data class, so every column round-trips losslessly. There is
 * exactly one backup format; an archive that is not it is rejected rather
 * than best-effort parsed.
 *
 * Secrets policy (mirrors §9 sync coverage matrix). A backup always contains
 * every secret — encryption is a file-level choice, never a content one:
 *   - Connection password (per-host)              — exported in secrets.json (conn_pw_{id}).
 *   - SSH private key material                    — exported in secrets.json (ssh_keys map).
 *   - StoredKey.certificate (public OpenSSH cert) — included in keys.json; non-secret.
 *   - SSH key passphrase                          — exported in secrets.json (key_passphrase_{keyId}).
 *   - Identity.password                           — exported in secrets.json (identity_{id});
 *                                                   the Identity row itself has password=null.
 *   - CloudAccount API token                      — exported in secrets.json (cloud_token_{id}).
 *   - Hypervisor per-host password                — exported in secrets.json (hypervisor_{id});
 *                                                   the HypervisorProfile row has no password field.
 *   - HypervisorAccount password                  — exported in secrets.json (hypervisor_account_{id}).
 *   - OCI PEM private key + passphrase            — exported in secrets.json
 *                                                   (oci_private_key_account_{id} /
 *                                                   oci_passphrase_account_{id}).
 *   - VNC host/identity passwords                 — exported in secrets.json (vnc_host_{id} / vnc_identity_{id}).
 *   - Docker host + registry credentials          — exported in secrets.json (docker_host_{id} / registry_credential_{id}).
 *
 * Tables intentionally excluded from backup:
 *   - sync_state — per-device sync bookkeeping; meaningless on another device
 *     and rebuilt from scratch by the next sync pass.
 *
 * `tab_sessions` and `audit_log` ARE backed up (a backup restores one device
 * to its exact prior state) but are deliberately NOT synced — see the
 * exclusion comment in SyncDataCollector.
 */
class BackupExporter(
    private val context: android.content.Context,
    private val database: TabSSHDatabase,
    private val preferenceManager: PreferenceManager,
    /** Required for [collectBackupData] to gather stored credentials into the backup. */
    private val securePasswordManager: SecurePasswordManager? = null,
    private val keyStorage: KeyStorage? = null
) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    companion object {
        /**
         * One format, one version constant. [io.github.tabssh.backup.BackupManager]
         * owns it; the per-entity `"v"` field and the archive's own `"v"` field
         * are always the same number.
         */
        const val WIRE_VERSION = io.github.tabssh.backup.BackupManager.BACKUP_VERSION

        // File names — these are also referenced by BackupManager. Keep in sync.
        const val FILE_CONNECTIONS       = "connections.json"
        const val FILE_KEYS              = "keys.json"
        const val FILE_PREFERENCES       = "preferences.json"
        const val FILE_THEMES            = "themes.json"
        const val FILE_CERTIFICATES      = "certificates.json"
        const val FILE_HOST_KEYS         = "host_keys.json"
        const val FILE_IDENTITIES        = "identities.json"
        const val FILE_GROUPS            = "connection_groups.json"
        const val FILE_SNIPPETS          = "snippets.json"
        const val FILE_HYPERVISORS       = "hypervisors.json"
        const val FILE_HYPERVISOR_ACCTS  = "hypervisor_accounts.json"
        const val FILE_WORKSPACES        = "workspaces.json"
        const val FILE_CLOUD_ACCOUNTS    = "cloud_accounts.json"
        const val FILE_MACROS            = "macros.json"
        const val FILE_MONITOR_SLOTS     = "monitor_slots.json"
        const val FILE_VNC_HOSTS         = "vnc_hosts.json"
        const val FILE_VNC_IDENTITIES    = "vnc_identities.json"
        const val FILE_PORT_FORWARDS     = "port_forwards.json"
        /**
         * Reusable network routes (proxies + SSH jump hosts) for the Routing &
         * Forwarding feature. Non-secret metadata only — a route carries no
         * password (proxies auth by username; jump hosts reuse the connection's
         * own credentials at connect time). Connections reference a route by id
         * via their `route_id` column, which rides along in [FILE_CONNECTIONS].
         */
        const val FILE_NETWORK_ROUTES    = "network_routes.json"
        /**
         * Docker subsystem — hosts, private registry credentials, compose stacks,
         * single-container run configs, and container/stack auto-update policies.
         * Custom-endpoint SSH passwords and registry secrets live in [FILE_SECRETS]
         * under `docker_host_{id}` / `registry_credential_{id}`.
         */
        const val FILE_DOCKER_HOSTS      = "docker_hosts.json"
        const val FILE_REGISTRY_CREDENTIALS = "registry_credentials.json"
        const val FILE_COMPOSE_STACKS    = "compose_stacks.json"
        const val FILE_SINGLE_CONTAINER_CONFIGS = "single_container_configs.json"
        const val FILE_CONTAINER_AUTO_UPDATE_POLICIES = "container_auto_update_policies.json"
        /**
         * Multi-host dashboard configuration — dashboard groups and per-group
         * host membership.  Stored in the `multi_host_dashboard` SharedPreferences
         * file (not the Room DB), so it must be backed up and restored separately.
         */
        const val FILE_DASHBOARD         = "dashboard_config.json"
        /**
         * Non-default SharedPreferences files with user data outside the Room DB:
         * "TabSSH" (host list sort orders), "cluster_commands" (saved cluster
         * command history), "snippet_var_recall" (last-used snippet variable
         * values). Values are type-tagged by
         * [io.github.tabssh.utils.SharedPrefsCodec] because these files hold
         * more than strings.
         */
        const val FILE_PREFS_TABSSH             = "prefs_tabssh.json"
        const val FILE_PREFS_CLUSTER_COMMANDS   = "prefs_cluster_commands.json"
        const val FILE_PREFS_SNIPPET_VAR_RECALL = "prefs_snippet_var_recall.json"
        /**
         * Backup-only snapshot state — never synced between devices (open tabs
         * and audit history are local-device concerns, not shared data). Lets a
         * restore reproduce exactly which tabs were open and the full audit
         * trail at capture time.
         */
        const val FILE_TAB_SESSIONS      = "tab_sessions.json"
        const val FILE_AUDIT_LOG         = "audit_log.json"
        /**
         * All credentials — Keystore-backed passwords, tokens, OCI PEM keys,
         * SSH key JSch bytes, and connection passwords. Always written by
         * [BackupManager.createBackup]; the user decides whether to encrypt
         * the backup file with a password.
         */
        const val FILE_SECRETS           = "secrets.json"
    }

    /**
     * Collect every backed-up table as a name→JSON map. Caller (BackupManager)
     * decides whether to encrypt and how to write to disk.
     *
     * A backup always contains absolutely everything, unencrypted at the content
     * level: restoring must reproduce the exact app state at capture time. All
     * credentials (Keystore passwords, tokens, OCI keys, SSH key bytes, connection
     * passwords) are always gathered — encryption is a file-level option the user
     * controls, never a content choice.
     */
    suspend fun collectBackupData(): Map<String, String> = withContext(Dispatchers.IO) {
        val out = mutableMapOf<String, String>()

        out[FILE_CONNECTIONS]      = exportConnections()
        out[FILE_KEYS]             = exportKeys()
        out[FILE_PREFERENCES]      = exportPreferences()
        out[FILE_THEMES]           = exportThemes()
        out[FILE_CERTIFICATES]     = exportCertificates()
        out[FILE_HOST_KEYS]        = exportHostKeys()
        out[FILE_IDENTITIES]       = exportIdentities()
        out[FILE_GROUPS]           = exportGroups()
        out[FILE_SNIPPETS]         = exportSnippets()
        out[FILE_HYPERVISORS]      = exportHypervisors()
        out[FILE_HYPERVISOR_ACCTS] = exportHypervisorAccounts()
        out[FILE_WORKSPACES]       = exportWorkspaces()
        out[FILE_CLOUD_ACCOUNTS]   = exportCloudAccounts()
        out[FILE_MACROS]           = exportMacros()
        out[FILE_MONITOR_SLOTS]    = exportMonitorSlots()
        out[FILE_VNC_HOSTS]        = exportVncHosts()
        out[FILE_VNC_IDENTITIES]   = exportVncIdentities()
        out[FILE_PORT_FORWARDS]    = exportPortForwards()
        out[FILE_NETWORK_ROUTES]   = exportNetworkRoutes()
        out[FILE_DOCKER_HOSTS]     = exportDockerHosts()
        out[FILE_REGISTRY_CREDENTIALS] = exportRegistryCredentials()
        out[FILE_COMPOSE_STACKS]   = exportComposeStacks()
        out[FILE_SINGLE_CONTAINER_CONFIGS] = exportSingleContainerConfigs()
        out[FILE_CONTAINER_AUTO_UPDATE_POLICIES] = exportContainerAutoUpdatePolicies()
        out[FILE_DASHBOARD]        = exportDashboardConfig()
        out[FILE_PREFS_TABSSH]             = exportSharedPrefs("TabSSH")
        out[FILE_PREFS_CLUSTER_COMMANDS]   = exportSharedPrefs("cluster_commands")
        out[FILE_PREFS_SNIPPET_VAR_RECALL] = exportSharedPrefs("snippet_var_recall")
        out[FILE_TAB_SESSIONS]     = exportTabSessions()
        out[FILE_AUDIT_LOG]        = exportAuditLog()
        out[FILE_SECRETS]          = exportSecrets()

        out
    }

    // ── Per-entity helpers ───────────────────────────────────────────────────

    /**
     * Connection rows only. Connection passwords are carried by secrets.json
     * under `conn_pw_{id}` — one home for every secret, so encryption and
     * restore ordering apply to all of them uniformly.
     */
    private suspend fun exportConnections(): String {
        val list = database.connectionDao().getAllConnections().first()
        return encodeEntities(ListSerializer(ConnectionProfile.serializer()), list)
    }

    private suspend fun exportKeys(): String =
        encodeEntities(ListSerializer(StoredKey.serializer()), database.keyDao().getAllKeys().first())

    private suspend fun exportThemes(): String =
        encodeEntities(ListSerializer(ThemeDefinition.serializer()), database.themeDao().getAllThemes().first())

    private suspend fun exportCertificates(): String =
        encodeEntities(ListSerializer(TrustedCertificate.serializer()),
            database.certificateDao().getAllCertificates().first())

    private suspend fun exportHostKeys(): String =
        encodeEntities(ListSerializer(HostKeyEntry.serializer()),
            database.hostKeyDao().getAllHostKeys().first())

    private suspend fun exportIdentities(): String =
        // Identity.password is intentionally re-set to null on the way out:
        // it's an encrypted-at-rest blob bound to this device's Keystore; a
        // different device cannot decrypt it. User re-enters the password
        // on restore.
        encodeEntities(
            ListSerializer(Identity.serializer()),
            database.identityDao().getAllIdentitiesList().map { it.copy(password = null) }
        )

    private suspend fun exportGroups(): String =
        encodeEntities(ListSerializer(ConnectionGroup.serializer()),
            database.connectionGroupDao().getAllGroups().first())

    private suspend fun exportSnippets(): String =
        encodeEntities(ListSerializer(Snippet.serializer()),
            database.snippetDao().getAllSnippets().first())

    private suspend fun exportHypervisors(): String =
        // Metadata rows only — the password lives in SecurePasswordManager under
        // `hypervisor_${id}` / `hypervisor_account_${id}` and is captured by
        // exportSecrets(). The entity carries no password field to blank.
        encodeEntities(
            ListSerializer(HypervisorProfile.serializer()),
            database.hypervisorDao().getAllList()
        )

    private suspend fun exportHypervisorAccounts(): String =
        encodeEntities(ListSerializer(HypervisorAccount.serializer()),
            database.hypervisorAccountDao().getAllAccountsList())

    private suspend fun exportWorkspaces(): String =
        encodeEntities(ListSerializer(Workspace.serializer()),
            database.workspaceDao().getAll())

    private suspend fun exportCloudAccounts(): String =
        // Metadata row only — the API token lives in SecurePasswordManager under
        // `cloud_token_${id}` and is captured by exportSecrets() for encrypted backups.
        encodeEntities(ListSerializer(CloudAccount.serializer()),
            database.cloudAccountDao().getAll())

    private suspend fun exportMacros(): String =
        encodeEntities(ListSerializer(Macro.serializer()),
            database.macroDao().getAllMacrosList())

    private suspend fun exportMonitorSlots(): String =
        encodeEntities(ListSerializer(MonitorSlot.serializer()),
            database.monitorSlotDao().getAllSlots().first())

    private suspend fun exportVncHosts(): String =
        encodeEntities(ListSerializer(VncHost.serializer()),
            database.vncHostDao().getAllHostsList())

    private suspend fun exportVncIdentities(): String =
        // Password lives in Keystore under `vnc_identity_${id}` — it is NOT in
        // this entity, so no scrubbing needed. All other fields are safe to export.
        // The actual password value is captured in exportSecrets() when the backup
        // is encrypted so restore does not require user re-entry.
        encodeEntities(ListSerializer(VncIdentity.serializer()),
            database.vncIdentityDao().getAllIdentitiesList())

    private suspend fun exportTabSessions(): String =
        // Backup-only: open tabs are local-device state, never synced.
        encodeEntities(ListSerializer(TabSession.serializer()),
            database.tabSessionDao().getActiveSessionsList())

    private suspend fun exportAuditLog(): String =
        // Backup-only: audit history is local-device state, never synced.
        encodeEntities(ListSerializer(AuditLogEntry.serializer()),
            database.auditLogDao().getAllFlow().first())

    private suspend fun exportPortForwards(): String =
        encodeEntities(ListSerializer(PortForward.serializer()),
            database.portForwardDao().getAllList())

    private suspend fun exportNetworkRoutes(): String =
        encodeEntities(ListSerializer(NetworkRoute.serializer()),
            database.networkRouteDao().getAllList())

    private suspend fun exportDockerHosts(): String =
        // No secret column — custom-endpoint passwords live in exportSecrets()
        // under `docker_host_{id}`. All row fields are safe to export as-is.
        encodeEntities(ListSerializer(DockerHost.serializer()),
            database.dockerHostDao().getAllList())

    private suspend fun exportRegistryCredentials(): String =
        // No secret column — the credential value lives in exportSecrets()
        // under `registry_credential_{id}`.
        encodeEntities(ListSerializer(RegistryCredential.serializer()),
            database.registryCredentialDao().getAllList())

    private suspend fun exportComposeStacks(): String =
        encodeEntities(ListSerializer(ComposeStack.serializer()),
            database.composeStackDao().getAllList())

    private suspend fun exportSingleContainerConfigs(): String =
        encodeEntities(ListSerializer(SingleContainerConfig.serializer()),
            database.singleContainerConfigDao().getAllList())

    private suspend fun exportContainerAutoUpdatePolicies(): String =
        // User config, not audit data — ContainerAutoUpdatePolicy syncs like
        // the rest of the Docker subsystem, unlike AuditLogEntry/TabSession.
        encodeEntities(ListSerializer(ContainerAutoUpdatePolicy.serializer()),
            database.containerAutoUpdatePolicyDao().getAllList())

    /**
     * Export the multi-host dashboard configuration — groups JSON and per-group
     * host membership — from the `multi_host_dashboard` SharedPreferences file.
     *
     * Most values are strings (JSON blobs or comma-separated ID lists), but
     * `dash_ungrouped_collapsed` is a Boolean, so values are type-tagged by
     * [io.github.tabssh.utils.SharedPrefsCodec] rather than flattened.
     */
    private fun exportDashboardConfig(): String {
        val dashPrefs = context.getSharedPreferences("multi_host_dashboard", android.content.Context.MODE_PRIVATE)
        val obj = buildJsonObject {
            put("v", WIRE_VERSION)
            io.github.tabssh.utils.SharedPrefsCodec.encodeAll(dashPrefs).forEach { (k, v) ->
                put(k, v)
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * Export a non-default SharedPreferences file as a flat string map, same
     * wire shape as [exportDashboardConfig] (`{"v":WIRE_VERSION,"<key>":"<value>",...}`).
     */
    private fun exportSharedPrefs(prefsName: String): String {
        val prefs = context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE)
        val obj = buildJsonObject {
            put("v", WIRE_VERSION)
            // Type-tagged: a SharedPreferences value is not always a String
            // (cluster_commands holds a Set<String>), and a flattened value
            // makes the next typed read throw ClassCastException.
            io.github.tabssh.utils.SharedPrefsCodec.encodeAll(prefs).forEach { (k, v) ->
                put(k, v)
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    // ── Secrets (encrypted backup only) ─────────────────────────────────────

    /**
     * Gather every Keystore-backed credential into a single JSON object.
     *
     * Covered credential namespaces:
     *   `identity_{id}`                  — SSH identity password (SecurePasswordManager)
     *   `hypervisor_{id}`                — per-host hypervisor password (SecurePasswordManager)
     *   `hypervisor_account_{id}`        — hypervisor account password (SecurePasswordManager)
     *   `oci_private_key_account_{id}`   — OCI API private key PEM (SecurePasswordManager)
     *   `oci_passphrase_account_{id}`    — OCI API key passphrase (SecurePasswordManager)
     *   `vnc_identity_{id}`              — VNC identity password (SecurePasswordManager)
     *   `vnc_host_{id}`                  — VNC host password (SecurePasswordManager)
     *   `cloud_token_{id}`               — cloud provider API token (SecurePasswordManager)
     *   `key_passphrase_{keyId}`         — SSH private key passphrase (SecurePasswordManager)
     *   `conn_pw_{id}`                   — SSH connection password (SecurePasswordManager,
     *                                      stored under the bare connection id)
     *   `docker_host_{id}`               — Docker host custom-endpoint SSH password (SecurePasswordManager)
     *   `registry_credential_{id}`       — private registry credential secret (SecurePasswordManager)
     *
     * SSH private key JSch bytes are exported separately under `ssh_keys` keyed
     * by [StoredKey.keyId], re-encrypted under the backup password by the outer
     * [BackupManager] AES-GCM envelope.  Only keys that have a stored JSch byte
     * blob are exported; keys with only PKCS#8 DER (pre-JSch-byte era) are
     * skipped with a warning and must be re-imported manually after restore.
     *
     * Empty / null values are omitted — no point carrying dead entries.
     */
    private suspend fun exportSecrets(): String {
        val passwords = mutableMapOf<String, String>()
        val sshKeys   = mutableMapOf<String, String>()
        val pm = securePasswordManager

        if (pm != null) {
            // Identity passwords — alias: identity_{id}
            database.identityDao().getAllIdentitiesList().forEach { id ->
                pm.retrievePassword("identity_${id.id}")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { passwords["identity_${id.id}"] = it }
            }

            // Per-host hypervisor passwords (accountId == null path)
            database.hypervisorDao().getAllList()
                .filter { it.accountId == null }
                .forEach { h ->
                    pm.retrievePassword("hypervisor_${h.id}")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { passwords["hypervisor_${h.id}"] = it }
                }

            // Hypervisor account passwords + OCI secrets
            database.hypervisorAccountDao().getAllAccountsList().forEach { a ->
                pm.retrievePassword("hypervisor_account_${a.id}")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { passwords["hypervisor_account_${a.id}"] = it }
                pm.retrievePassword("oci_private_key_account_${a.id}")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { passwords["oci_private_key_account_${a.id}"] = it }
                pm.retrievePassword("oci_passphrase_account_${a.id}")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { passwords["oci_passphrase_account_${a.id}"] = it }
            }

            // VNC identity passwords — alias: vnc_identity_{id}
            database.vncIdentityDao().getAllIdentitiesList().forEach { vi ->
                pm.retrievePassword("vnc_identity_${vi.id}")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { passwords["vnc_identity_${vi.id}"] = it }
            }

            // VNC host passwords — alias: vnc_host_{id}
            database.vncHostDao().getAllHostsList().forEach { vh ->
                pm.retrievePassword("vnc_host_${vh.id}")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { passwords["vnc_host_${vh.id}"] = it }
            }

            // Cloud account tokens — alias: cloud_token_{id}
            database.cloudAccountDao().getAll().forEach { ca ->
                pm.retrievePassword("cloud_token_${ca.id}")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { passwords["cloud_token_${ca.id}"] = it }
            }

            // SSH key passphrases — alias: key_passphrase_{keyId}
            database.keyDao().getAllKeys().first().forEach { key ->
                pm.retrievePassword("key_passphrase_${key.keyId}")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { passwords["key_passphrase_${key.keyId}"] = it }
            }

            // Docker host custom-endpoint passwords — alias: docker_host_{id}
            database.dockerHostDao().getAllList()
                .filter { it.usesCustomEndpoint() }
                .forEach { h ->
                    pm.retrievePassword("docker_host_${h.id}")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { passwords["docker_host_${h.id}"] = it }
                }

            // Registry credential secrets — alias: registry_credential_{id}
            database.registryCredentialDao().getAllList().forEach { c ->
                pm.retrievePassword("registry_credential_${c.id}")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { passwords["registry_credential_${c.id}"] = it }
            }

            // Connection passwords — Keystore alias is the bare connection id
            // (what the runtime SSH path reads). Wire alias: conn_pw_{id}.
            database.connectionDao().getAllConnections().first()
                .filter { it.getAuthTypeEnum() == AuthType.PASSWORD }
                .forEach { c ->
                    pm.retrievePassword(c.id)
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { passwords["conn_pw_${c.id}"] = it }
                }
        }

        // SSH private key JSch bytes
        keyStorage?.let { ks ->
            database.keyDao().getAllKeys().first().forEach { key ->
                val bytes = ks.retrieveJSchBytes(key.keyId)
                if (bytes != null) {
                    sshKeys[key.keyId] = android.util.Base64.encodeToString(
                        bytes, android.util.Base64.NO_WRAP
                    )
                } else {
                    // Route through Logger so this lands in the sanitized app
                    // log too, not just logcat — users who hit a partial backup
                    // need this line in their bug report.
                    Logger.w("BackupExporter",
                        "No JSch bytes for key ${key.keyId} (${key.name}) — skipped in backup")
                }
            }
        }

        val obj = buildJsonObject {
            put("v", WIRE_VERSION)
            put("passwords", JsonObject(passwords.mapValues { JsonPrimitive(it.value) }))
            put("ssh_keys",  JsonObject(sshKeys.mapValues  { JsonPrimitive(it.value) }))
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    // ── Preferences ──────────────────────────────────────────────────────────

    private fun exportPreferences(): String {
        // Preferences are grouped by settings screen rather than entity-serialised
        // because they are not a Room entity. Every group here has a matching
        // reader in BackupImporter.restorePreferences.
        val root = JSONObject()
        root.put("v", WIRE_VERSION)

        root.put("general", JSONObject().apply {
            put("autoBackup", preferenceManager.isAutoBackupEnabled())
            put("backupFrequency", preferenceManager.getBackupFrequency())
            put("startupBehavior", preferenceManager.getStartupBehavior())
            put("language", preferenceManager.getLanguage())
        })

        root.put("security", JSONObject().apply {
            put("passwordStorageLevel", preferenceManager.getPasswordStorageLevel())
            put("requireBiometric", preferenceManager.isRequireBiometricForSensitive())
            put("strictHostKeyChecking", preferenceManager.isStrictHostKeyChecking())
            put("clearClipboardTimeout", preferenceManager.getClearClipboardTimeout())
            put("autoLockEnabled", preferenceManager.isAutoLockOnBackground())
            put("lockTimeout", preferenceManager.getAutoLockTimeout())
            put("passwordTTLHours", preferenceManager.getPasswordTTLHours())
            put("preventScreenshots", preferenceManager.isPreventScreenshots())
        })

        root.put("terminal", JSONObject().apply {
            put("theme", preferenceManager.getTerminalTheme())
            put("fontSize", preferenceManager.getFontSize())
            put("fontFamily", preferenceManager.getFontFamily())
            put("cursorStyle", preferenceManager.getCursorStyle())
            put("cursorBlink", preferenceManager.isCursorBlinkEnabled())
            put("scrollbackLines", preferenceManager.getScrollbackLines())
            put("terminalBell", preferenceManager.isBellNotificationEnabled())
            put("lineSpacing", preferenceManager.getLineSpacing())
            put("reverseScroll", preferenceManager.isReverseScrollDirection())
            put("bellVibrate", preferenceManager.isBellVibrate())
            put("bellVisual", preferenceManager.isBellVisual())
            put("wordWrap", preferenceManager.isWordWrap())
            put("copyOnSelect", preferenceManager.isCopyOnSelect())
        })

        root.put("ui", JSONObject().apply {
            put("maxTabs", preferenceManager.getMaxTabs())
            put("confirmTabClose", preferenceManager.isConfirmTabClose())
            put("appTheme", preferenceManager.getAppTheme())
            put("dynamicColors", preferenceManager.isDynamicColors())
            put("fullscreenMode", preferenceManager.isFullscreenMode())
            put("keepScreenOn", preferenceManager.isKeepScreenOn())
        })

        root.put("keyboard", JSONObject().apply {
            put("rowCount", preferenceManager.getKeyboardRowCount())
            put("layoutVersion", preferenceManager.getKeyboardLayoutVersion())
            put("layoutCustomized", preferenceManager.isKeyboardLayoutCustomized())
            val layoutJson = preferenceManager.getKeyboardLayoutJson()
            if (!layoutJson.isNullOrEmpty()) put("layoutJson", layoutJson)
        })

        // Notification preferences (keys from preferences_general.xml).
        // Read from default SharedPreferences directly — these keys have no
        // PreferenceManager wrappers beyond the computed compound methods.
        val defaultPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        root.put("notifications", JSONObject().apply {
            put("notifications_enabled",            defaultPrefs.getBoolean("notifications_enabled", true))
            put("show_connection_notifications",     defaultPrefs.getBoolean("show_connection_notifications", true))
            put("show_error_notifications",          defaultPrefs.getBoolean("show_error_notifications", true))
            put("show_file_transfer_notifications",  defaultPrefs.getBoolean("show_file_transfer_notifications", true))
            put("notification_vibrate",              defaultPrefs.getBoolean("notification_vibrate", true))
        })

        // Monitoring preferences (keys from preferences_monitoring.xml).
        // Per-host monitoring config (thresholds, intervals) lives in MonitorSlot
        // rows, which are exported separately. These are the app-wide defaults
        // and the master enable switch.
        root.put("monitoring", JSONObject().apply {
            put("monitoring_enabled",                     defaultPrefs.getBoolean("monitoring_enabled", true))
            put("monitoring_run_in_battery_saver",        defaultPrefs.getBoolean("monitoring_run_in_battery_saver", false))
            put("monitoring_notify_down",                  defaultPrefs.getBoolean("monitoring_notify_down", true))
            put("monitoring_notify_recovery",              defaultPrefs.getBoolean("monitoring_notify_recovery", true))
            put("monitoring_alert_cooldown_minutes",       defaultPrefs.getString("monitoring_alert_cooldown_minutes", "60"))
            // SeekBarPreference stores its value as Int — read as Int so that restore
            // writes the correct type and the Preference UI does not crash.
            put("monitoring_default_cpu_threshold",        defaultPrefs.getInt("monitoring_default_cpu_threshold", 0))
            put("monitoring_default_memory_threshold",     defaultPrefs.getInt("monitoring_default_memory_threshold", 0))
            put("monitoring_default_disk_threshold",       defaultPrefs.getInt("monitoring_default_disk_threshold", 0))
        })

        root.put("connection", JSONObject().apply {
            put("defaultUsername",       preferenceManager.getDefaultUsername())
            put("defaultPort",           preferenceManager.getDefaultPort())
            put("connectTimeout",        preferenceManager.getConnectTimeout())
            put("autoReconnect",         preferenceManager.isAutoReconnect())
            put("compression",           preferenceManager.isCompressionEnabled())
            put("serverAliveIntervalSec", preferenceManager.getServerAliveIntervalSec())
            put("x11ForwardingDefault",  preferenceManager.isX11ForwardingDefault())
            put("agentForwardingDefault", preferenceManager.isAgentForwardingDefault())
            put("fileOpenSizeLimitMb",   preferenceManager.getFileOpenSizeLimitMb())
            // Global default NetworkRoute id — empty string means "no default".
            put("defaultRouteId",        preferenceManager.getDefaultRouteId() ?: "")
        })

        root.put("sync", JSONObject().apply {
            put("frequency",              preferenceManager.getSyncFrequency())
            put("wifiOnly",               preferenceManager.isSyncWifiOnly())
            put("onChangeEnabled",        preferenceManager.isSyncOnChangeEnabled())
            put("syncConnections",        preferenceManager.isSyncConnectionsEnabled())
            put("syncKeys",               preferenceManager.isSyncKeysEnabled())
            put("syncIdentities",         preferenceManager.isSyncIdentitiesEnabled())
            put("syncSnippets",           preferenceManager.isSyncSnippetsEnabled())
            put("syncSettings",           preferenceManager.isSyncSettingsEnabled())
            put("syncThemes",             preferenceManager.isSyncThemesEnabled())
            put("syncHostKeys",           preferenceManager.isSyncHostKeysEnabled())
            put("syncGroups",             preferenceManager.isSyncGroupsEnabled())
            put("syncWorkspaces",         preferenceManager.isSyncWorkspacesEnabled())
            put("syncMacros",             preferenceManager.isSyncMacrosEnabled())
            put("syncMonitorSlots",       preferenceManager.isSyncMonitorSlotsEnabled())
            put("syncHypervisors",        preferenceManager.isSyncHypervisorsEnabled())
            put("syncHypervisorAccounts", preferenceManager.isSyncHypervisorAccountsEnabled())
            put("syncVncHosts",           preferenceManager.isSyncVncHostsEnabled())
            put("syncVncIdentities",      preferenceManager.isSyncVncIdentitiesEnabled())
            put("syncCloudAccounts",      preferenceManager.isSyncCloudAccountsEnabled())
            put("syncCertificates",       preferenceManager.isSyncCertificatesEnabled())
            put("syncDashboard",          preferenceManager.isSyncDashboardEnabled())
            put("syncDocker",             preferenceManager.isSyncDockerEnabled())
            put("syncPortForwards",       preferenceManager.isSyncPortForwardsEnabled())
            put("syncNetworkRoutes",      preferenceManager.isSyncNetworkRoutesEnabled())
            put("autoResolve",            preferenceManager.isAutoResolveConflictsEnabled())
        })

        // Audit log policy (preferences_audit.xml). User policy, not per-device
        // state — the log rows themselves are backed up separately in audit_log.
        root.put("audit", JSONObject().apply {
            put("enabled",       preferenceManager.isAuditLogEnabled())
            put("maxSizeMb",     preferenceManager.getAuditLogMaxSizeMb())
            put("maxAgeDays",    preferenceManager.getAuditLogMaxAgeDays())
            put("logCommands",   preferenceManager.isAuditLogCommandsEnabled())
            put("logOutput",     preferenceManager.isAuditLogOutputEnabled())
        })

        // Tasker/Locale plugin policy (preferences_tasker.xml). These gate an
        // exported receiver, so losing them on restore would silently change the
        // app's external attack surface.
        root.put("tasker", JSONObject().apply {
            put("enabled",            preferenceManager.isTaskerEnabled())
            put("requireUnlock",      preferenceManager.isTaskerRequireUnlockEnabled())
            put("includeOutput",      preferenceManager.isTaskerIncludeOutputEnabled())
            put("logEvents",          preferenceManager.isTaskerLogEventsEnabled())
            put("commandTimeoutMs",   preferenceManager.getTaskerCommandTimeoutMs())
            // Empty set = all connections allowed.
            put("allowedConnections",
                org.json.JSONArray(preferenceManager.getTaskerAllowedConnections().toList()))
        })

        // Diagnostics (preferences_logging.xml).
        root.put("logging", JSONObject().apply {
            put("debugLogging",     preferenceManager.isDebugLoggingEnabled())
            put("debugLogLevel",    preferenceManager.getDebugLogLevel())
            put("logKeystrokeBytes", preferenceManager.isLogKeystrokeBytesEnabled())
            put("hostLogging",      preferenceManager.isHostLoggingEnabled())
            put("hostLogMaxSizeMb", preferenceManager.getHostLogMaxSizeMb())
        })

        root.put("docker", JSONObject().apply {
            put("updateCheckEnabled", preferenceManager.isDockerUpdateCheckEnabled())
        })

        // Multiplexer key bindings: gesture type/enable in default SharedPreferences;
        // per-type prefix overrides in PreferenceManager.
        root.put("multiplexer", JSONObject().apply {
            put("gestureEnabled", defaultPrefs.getBoolean("enable_custom_gestures", false))
            put("gestureType",    defaultPrefs.getString("gesture_multiplexer_type", "tmux"))
            put("prefixTmux",     preferenceManager.getMultiplexerPrefix("tmux"))
            put("prefixScreen",   preferenceManager.getMultiplexerPrefix("screen"))
            put("prefixZellij",   preferenceManager.getMultiplexerPrefix("zellij"))
        })

        root.put("accessibility", JSONObject().apply {
            put("highContrast",      preferenceManager.isHighContrastMode())
            put("largeTouchTargets", preferenceManager.isLargeTouchTargets())
            put("screenReader",      preferenceManager.isScreenReaderEnabled())
        })

        root.put("paste", JSONObject().apply {
            put("service",       preferenceManager.getPasteService())
            put("microbinUrl",   preferenceManager.getPasteMicrobinUrl())
            put("lenpasteUrl",   preferenceManager.getPasteLenpasteUrl())
            put("stikkedUrl",    preferenceManager.getPasteStikkedUrl())
            put("pastebinApiKey", preferenceManager.getPastebinApiKey())
        })

        // No "proxy" group: the global proxy preferences were superseded by
        // NetworkRoute rows (exported in network_routes.json) and no longer
        // drive any connection. They are not exported, not synced, and the
        // plaintext proxy_password key they carried is gone.

        return root.toString(2)
    }

    // ── Generic entity wrapper ───────────────────────────────────────────────

    private fun <T> encodeEntities(
        serializer: kotlinx.serialization.KSerializer<List<T>>,
        list: List<T>
    ): String {
        val itemsArray = json.encodeToJsonElement(serializer, list)
        val obj = buildJsonObject {
            put("v", WIRE_VERSION)
            put("items", itemsArray)
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }
}
