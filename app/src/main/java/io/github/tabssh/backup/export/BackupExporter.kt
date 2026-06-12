package io.github.tabssh.backup.export

import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.CloudAccount
import io.github.tabssh.storage.database.entities.ConnectionGroup
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HostKeyEntry
import io.github.tabssh.storage.database.entities.HypervisorAccount
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.storage.database.entities.Identity
import io.github.tabssh.storage.database.entities.Macro
import io.github.tabssh.storage.database.entities.MonitorSlot
import io.github.tabssh.storage.database.entities.Snippet
import io.github.tabssh.storage.database.entities.StoredKey
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
 * Wire format v2 — written by this class on every export:
 *
 *     {
 *       "v": 2,
 *       "items": [ <full entity JSON>, ... ]
 *     }
 *
 * Each entity in `items` is the kotlinx.serialization JSON of the Room
 * `@Entity` data class, so every column round-trips losslessly. This
 * replaces the v1 hand-rolled per-field JSON that silently dropped two
 * dozen ConnectionProfile fields and the colour columns of
 * ThemeDefinition on restore.
 *
 * BackupImporter remains backward compatible with v1.
 *
 * Secrets policy (mirrors §9 sync coverage matrix):
 *   - Connection password (per-host)              — exported in secrets.json (conn_pw_{id}).
 *   - SSH private key material                    — exported in secrets.json (ssh_keys map).
 *   - StoredKey.certificate (public OpenSSH cert) — included in keys.json; non-secret.
 *   - Identity.password                           — exported in secrets.json (identity_{id});
 *                                                   the Identity row itself has password=null.
 *   - CloudAccount API token                      — exported in secrets.json (cloud_token_{id}).
 *   - HypervisorProfile.password                  — exported in secrets.json (hypervisor_{id}).
 *   - OCI PEM private key                         — exported in secrets.json (oci_private_key_{id}).
 *
 * Tables intentionally excluded from backup:
 *   - tab_sessions   — runtime state, regenerated on next open.
 *   - sync_state     — per-device sync bookkeeping; meaningless on another device.
 *   - audit_log      — device-local; can be large; user can export separately.
 */
class BackupExporter(
    private val context: android.content.Context,
    private val database: TabSSHDatabase,
    private val preferenceManager: PreferenceManager,
    /** Non-null only when [collectBackupData] is called with includeSecrets=true. */
    private val securePasswordManager: SecurePasswordManager? = null,
    private val keyStorage: KeyStorage? = null
) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    companion object {
        const val WIRE_VERSION = 2

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
        /**
         * Multi-host dashboard configuration — dashboard groups and per-group
         * host membership.  Stored in the `multi_host_dashboard` SharedPreferences
         * file (not the Room DB), so it must be backed up and restored separately.
         */
        const val FILE_DASHBOARD         = "dashboard_config.json"
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
     * @param includePasswords Legacy flag — SSH connection passwords are included
     *   in the `connections.json` sidecar when true. Superseded by [includeSecrets]
     *   which covers all credential types; when [includeSecrets] is true this flag
     *   is implicitly treated as true so the two paths are consistent.
     * @param includeSecrets   When true, all credentials (Keystore passwords, tokens,
     *   OCI keys, SSH key bytes, connection passwords) are gathered into [FILE_SECRETS].
     *   Always true in [BackupManager.createBackup] — the user controls encryption.
     */
    suspend fun collectBackupData(
        includePasswords: Boolean,
        includeSecrets: Boolean = false
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val out = mutableMapOf<String, String>()

        out[FILE_CONNECTIONS]      = exportConnections(includePasswords || includeSecrets)
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
        out[FILE_DASHBOARD]        = exportDashboardConfig()
        if (includeSecrets) out[FILE_SECRETS] = exportSecrets()

        out
    }

    // ── Per-entity helpers ───────────────────────────────────────────────────

    private suspend fun exportConnections(includePasswords: Boolean): String {
        val list = database.connectionDao().getAllConnections().first()
        // Most connections need no sidecar; only when includePasswords AND the
        // host actually has a saved password do we attach a parallel passwords map.
        val passwordSidecar: Map<String, String>? = if (includePasswords) {
            list.filter { it.getAuthTypeEnum() == AuthType.PASSWORD }
                .mapNotNull { p ->
                    val pw = preferenceManager.getConnectionPassword(p.id) ?: return@mapNotNull null
                    p.id to android.util.Base64.encodeToString(
                        pw.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
                    )
                }.toMap().takeIf { it.isNotEmpty() }
        } else null

        return encodeEntities(
            ListSerializer(ConnectionProfile.serializer()),
            list,
            extras = passwordSidecar?.let { sidecar ->
                buildJsonObject {
                    put("passwords", JsonObject(sidecar.mapValues { JsonPrimitive(it.value) }))
                }
            }
        )
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
        // HypervisorProfile.password is the inline legacy fallback; with
        // account_id introduced in v27 the live value lives in
        // SecurePasswordManager under `hypervisor_${id}`/`hypervisor_account_${id}`.
        // Either way, the in-table value is Keystore-encrypted-at-rest and
        // not portable. Blank it on export.
        encodeEntities(
            ListSerializer(HypervisorProfile.serializer()),
            database.hypervisorDao().getAllList().map { it.copy(password = "") }
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

    /**
     * Export the multi-host dashboard configuration — groups JSON and per-group
     * host membership — from the `multi_host_dashboard` SharedPreferences file.
     *
     * All values are stored as strings (JSON blobs or comma-separated ID lists)
     * so the flat key→value map round-trips without type ambiguity.
     */
    private fun exportDashboardConfig(): String {
        val dashPrefs = context.getSharedPreferences("multi_host_dashboard", android.content.Context.MODE_PRIVATE)
        val obj = buildJsonObject {
            put("v", WIRE_VERSION)
            dashPrefs.all.forEach { (k, v) ->
                put(k, v?.toString() ?: "")
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
     *   `cloud_token_{id}`               — cloud provider API token (SecurePasswordManager)
     *   `conn_pw_{id}`                   — SSH connection password (PreferenceManager)
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

            // Cloud account tokens — alias: cloud_token_{id}
            database.cloudAccountDao().getAll().forEach { ca ->
                pm.retrievePassword("cloud_token_${ca.id}")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { passwords["cloud_token_${ca.id}"] = it }
            }
        }

        // Connection passwords — stored in PreferenceManager SharedPreferences under
        // "password_{connectionId}" (not SecurePasswordManager). Alias: conn_pw_{id}.
        database.connectionDao().getAllConnections().first()
            .filter { it.getAuthTypeEnum() == AuthType.PASSWORD }
            .forEach { c ->
                preferenceManager.getConnectionPassword(c.id)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { passwords["conn_pw_${c.id}"] = it }
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
                    android.util.Log.w("BackupExporter",
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
        // Preferences stay in v1 hand-rolled shape because they're not a Room
        // entity. BackupImporter parses the same shape it always did.
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
            put("showFunctionKeys", preferenceManager.isShowFunctionKeys())
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
            put("autoResolve",            preferenceManager.isAutoResolveConflictsEnabled())
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

        // Proxy configuration. Password is in plain SharedPreferences (not Keystore-
        // backed) so it round-trips safely. Encrypted backups wrap the whole payload
        // in AES-GCM, protecting it on disk.
        root.put("proxy", JSONObject().apply {
            put("enabled",     preferenceManager.isProxyEnabled())
            put("type",        preferenceManager.getProxyType())
            put("host",        preferenceManager.getProxyHost())
            put("port",        preferenceManager.getProxyPort())
            put("username",    preferenceManager.getProxyUsername() ?: "")
            put("password",    preferenceManager.getProxyPassword() ?: "")
            // Use "\n" as separator — commas are valid inside bypass-list entries
            // (e.g. "*.example.com,10.0.0.0/8" stored as one item). Newlines cannot
            // appear in a hostname or CIDR and survive JSON string serialisation.
            put("bypassHosts", preferenceManager.getProxyBypassHosts().joinToString("\n"))
        })

        return root.toString(2)
    }

    // ── Generic v2 entity wrapper ────────────────────────────────────────────

    private fun <T> encodeEntities(
        serializer: kotlinx.serialization.KSerializer<List<T>>,
        list: List<T>,
        extras: JsonObject? = null
    ): String {
        val itemsArray = json.encodeToJsonElement(serializer, list)
        val obj = buildJsonObject {
            put("v", WIRE_VERSION)
            put("items", itemsArray)
            extras?.forEach { (k, v) -> put(k, v) }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }
}
