package io.github.tabssh.backup.export

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
 *   - Connection password (per-host)              — included only when the caller
 *                                                   sets `includePasswords=true`.
 *   - SSH private key material                    — never exported (Keystore-bound).
 *   - StoredKey.certificate (public OpenSSH cert) — included; non-secret.
 *   - Identity.password                           — never exported (encrypted-at-rest
 *                                                   value is Keystore-bound to this
 *                                                   device; unusable on another).
 *   - CloudAccount API token                      — never exported (Keystore-bound).
 *   - HypervisorProfile.password                  — never exported (Keystore-bound).
 *   - OCI PEM private key                         — never exported (Keystore-bound).
 *
 * Tables intentionally excluded from backup:
 *   - tab_sessions   — runtime state, regenerated on next open.
 *   - sync_state     — per-device sync bookkeeping; meaningless on another device.
 *   - audit_log      — device-local; can be large; user can export separately.
 */
class BackupExporter(
    private val context: android.content.Context,
    private val database: TabSSHDatabase,
    private val preferenceManager: PreferenceManager
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
    }

    /**
     * Collect every backed-up table as a name→JSON map. Caller (BackupManager)
     * decides whether to encrypt and how to write to disk.
     */
    suspend fun collectBackupData(includePasswords: Boolean): Map<String, String> = withContext(Dispatchers.IO) {
        val out = mutableMapOf<String, String>()

        out[FILE_CONNECTIONS]      = exportConnections(includePasswords)
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

        out
    }

    // ── Per-entity helpers ───────────────────────────────────────────────────

    private suspend fun exportConnections(includePasswords: Boolean): String {
        val list = database.connectionDao().getAllConnections().first()
        // Most connections need no sidecar; only when includePasswords AND the
        // host actually has a saved password do we attach a parallel passwords map.
        val passwordSidecar: Map<String, String>? = if (includePasswords) {
            list.filter { it.authType.equals("password", ignoreCase = true) }
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
        // Token lives in SecurePasswordManager under `cloud_token_${id}` and
        // is not exported. The metadata row tells the restoring device which
        // providers were configured; user re-enters the token after restore.
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
        encodeEntities(ListSerializer(VncIdentity.serializer()),
            database.vncIdentityDao().getAllIdentitiesList())

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
        })

        root.put("terminal", JSONObject().apply {
            put("theme", preferenceManager.getTerminalTheme())
            put("fontSize", preferenceManager.getFontSize())
            put("fontFamily", preferenceManager.getFontFamily())
            put("cursorStyle", preferenceManager.getCursorStyle())
            put("cursorBlink", preferenceManager.isCursorBlinkEnabled())
            put("scrollbackLines", preferenceManager.getScrollbackLines())
        })

        root.put("ui", JSONObject().apply {
            put("maxTabs", preferenceManager.getMaxTabs())
            put("confirmTabClose", preferenceManager.isConfirmTabClose())
            put("appTheme", preferenceManager.getAppTheme())
            put("dynamicColors", preferenceManager.isDynamicColors())
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
            put("monitoring_default_cpu_threshold",        defaultPrefs.getString("monitoring_default_cpu_threshold", ""))
            put("monitoring_default_memory_threshold",     defaultPrefs.getString("monitoring_default_memory_threshold", ""))
            put("monitoring_default_disk_threshold",       defaultPrefs.getString("monitoring_default_disk_threshold", ""))
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
