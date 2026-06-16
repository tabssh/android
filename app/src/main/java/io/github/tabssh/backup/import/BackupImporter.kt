package io.github.tabssh.backup.import

import android.content.Context
import io.github.tabssh.backup.export.BackupExporter
import io.github.tabssh.crypto.keys.KeyStorage
import io.github.tabssh.crypto.storage.SecurePasswordManager
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
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject

/**
 * Handles importing data from backup.
 *
 * Reads the v2 entity-serialised format emitted by [BackupExporter].
 */
class BackupImporter(
    private val context: Context,
    private val database: TabSSHDatabase,
    private val preferenceManager: PreferenceManager,
    private val securePasswordManager: SecurePasswordManager? = null,
    private val keyStorage: KeyStorage? = null
) {

    companion object { private const val TAG = "BackupImporter" }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Restore everything present in [backupData]. Returns a per-table count of
     * rows inserted (skipping rows that already exist when [overwriteExisting]
     * is false).
     */
    suspend fun restoreBackupData(
        backupData: Map<String, String>,
        overwriteExisting: Boolean
    ): Map<String, Int> = withContext(Dispatchers.IO) {
        val out = mutableMapOf<String, Int>()

        suspend fun <R> table(
            key: String,
            label: String,
            doRestore: suspend (String) -> R,
            onResult: (R) -> Unit
        ) {
            val data = backupData[key]
            if (data == null) {
                Logger.d(TAG, "Skipping $label — not in backup")
            } else {
                val result = doRestore(data)
                onResult(result)
            }
        }

        table(BackupExporter.FILE_CONNECTIONS, "connections",
            { restoreConnections(it, overwriteExisting) }) { out["connections"] = it; Logger.d(TAG, "Restored $it connections") }
        table(BackupExporter.FILE_KEYS, "keys",
            { restoreKeys(it, overwriteExisting) }) { out["keys"] = it; Logger.d(TAG, "Restored $it keys") }
        backupData[BackupExporter.FILE_PREFERENCES]?.let {
            restorePreferences(it); out["preferences"] = 1
            Logger.d(TAG, "Restored preferences")
        } ?: Logger.d(TAG, "Skipping preferences — not in backup")
        table(BackupExporter.FILE_THEMES, "themes",
            { restoreThemes(it, overwriteExisting) }) { out["themes"] = it; Logger.d(TAG, "Restored $it themes") }
        table(BackupExporter.FILE_CERTIFICATES, "certificates",
            { restoreCertificates(it, overwriteExisting) }) { out["certificates"] = it; Logger.d(TAG, "Restored $it certificates") }
        table(BackupExporter.FILE_HOST_KEYS, "host_keys",
            { restoreHostKeys(it, overwriteExisting) }) { out["host_keys"] = it; Logger.d(TAG, "Restored $it host keys") }
        table(BackupExporter.FILE_IDENTITIES, "identities",
            { restoreIdentities(it, overwriteExisting) }) { out["identities"] = it; Logger.d(TAG, "Restored $it identities") }
        table(BackupExporter.FILE_GROUPS, "connection_groups",
            { restoreGroups(it, overwriteExisting) }) { out["connection_groups"] = it; Logger.d(TAG, "Restored $it connection groups") }
        table(BackupExporter.FILE_SNIPPETS, "snippets",
            { restoreSnippets(it, overwriteExisting) }) { out["snippets"] = it; Logger.d(TAG, "Restored $it snippets") }
        table(BackupExporter.FILE_HYPERVISORS, "hypervisors",
            { restoreHypervisors(it, overwriteExisting) }) { out["hypervisors"] = it; Logger.d(TAG, "Restored $it hypervisors") }
        table(BackupExporter.FILE_HYPERVISOR_ACCTS, "hypervisor_accounts",
            { restoreHypervisorAccounts(it, overwriteExisting) }) { out["hypervisor_accounts"] = it; Logger.d(TAG, "Restored $it hypervisor accounts") }
        table(BackupExporter.FILE_WORKSPACES, "workspaces",
            { restoreWorkspaces(it, overwriteExisting) }) { out["workspaces"] = it; Logger.d(TAG, "Restored $it workspaces") }
        table(BackupExporter.FILE_CLOUD_ACCOUNTS, "cloud_accounts",
            { restoreCloudAccounts(it, overwriteExisting) }) { out["cloud_accounts"] = it; Logger.d(TAG, "Restored $it cloud accounts") }
        table(BackupExporter.FILE_MACROS, "macros",
            { restoreMacros(it, overwriteExisting) }) { out["macros"] = it; Logger.d(TAG, "Restored $it macros") }
        table(BackupExporter.FILE_MONITOR_SLOTS, "monitor_slots",
            { restoreMonitorSlots(it, overwriteExisting) }) { out["monitor_slots"] = it; Logger.d(TAG, "Restored $it monitor slots") }
        table(BackupExporter.FILE_VNC_HOSTS, "vnc_hosts",
            { restoreVncHosts(it, overwriteExisting) }) { out["vnc_hosts"] = it; Logger.d(TAG, "Restored $it VNC hosts") }
        table(BackupExporter.FILE_VNC_IDENTITIES, "vnc_identities",
            { restoreVncIdentities(it, overwriteExisting) }) { out["vnc_identities"] = it; Logger.d(TAG, "Restored $it VNC identities") }
        backupData[BackupExporter.FILE_DASHBOARD]?.let {
            val n = restoreDashboardConfig(it, overwriteExisting)
            out["dashboard_config"] = n
            Logger.d(TAG, "Restored $n dashboard config keys")
        } ?: Logger.d(TAG, "Skipping dashboard config — not in backup (pre-v4)")

        // Secrets must be restored AFTER entity rows so all IDs are present.
        backupData[BackupExporter.FILE_SECRETS]?.let {
            restoreSecrets(it)
            out["secrets"] = 1
            Logger.d(TAG, "Restored credentials from secrets file")
        } ?: Logger.d(TAG, "Skipping secrets — not in backup (pre-v3 or unencrypted backup)")

        out
    }

    // ── Connections ──────────────────────────────────────────────────────────

    private suspend fun restoreConnections(data: String, overwriteExisting: Boolean): Int {
        val root = json.parseToJsonElement(data).jsonObject
        var count = 0
        val itemsArr = root["items"] as? JsonArray ?: run {
            Logger.w(TAG, "v2 backup missing 'items' in connections section")
            return 0
        }
        val items = json.decodeFromJsonElement(
            ListSerializer(ConnectionProfile.serializer()), itemsArr
        )
        val passwords: Map<String, String> = (root["passwords"] as? JsonObject)?.let { obj ->
            obj.mapValues { it.value.jsonPrimitive.content }
        } ?: emptyMap()
        for (c in items) {
            val existing = database.connectionDao().getConnection(c.id)
            if (existing != null && !overwriteExisting) continue
            database.connectionDao().insertConnection(c)
            passwords[c.id]?.let { b64 ->
                val pw = String(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP),
                    Charsets.UTF_8)
                preferenceManager.setConnectionPassword(c.id, pw)
            }
            count++
        }
        return count
    }

    // ── Keys ─────────────────────────────────────────────────────────────────

    private suspend fun restoreKeys(data: String, overwriteExisting: Boolean): Int {
        val root = json.parseToJsonElement(data).jsonObject
        var count = 0
        val itemsArr = root["items"] as? JsonArray ?: run {
            Logger.w(TAG, "v2 backup missing 'items' in keys section")
            return 0
        }
        val items = json.decodeFromJsonElement(
            ListSerializer(StoredKey.serializer()), itemsArr
        )
        for (k in items) {
            val existing = database.keyDao().getKey(k.keyId)
            if (existing != null && !overwriteExisting) continue
            database.keyDao().insertKey(k)
            count++
        }
        return count
    }

    // ── Themes ───────────────────────────────────────────────────────────────

    private suspend fun restoreThemes(data: String, overwriteExisting: Boolean): Int {
        val root = json.parseToJsonElement(data).jsonObject
        var count = 0
        val itemsArr = root["items"] as? JsonArray ?: run {
            Logger.w(TAG, "v2 backup missing 'items' in themes section")
            return 0
        }
        val items = json.decodeFromJsonElement(
            ListSerializer(ThemeDefinition.serializer()), itemsArr
        )
        for (t in items) {
            val existing = database.themeDao().getTheme(t.themeId)
            if (existing != null && !overwriteExisting) continue
            database.themeDao().insertTheme(t)
            count++
        }
        return count
    }

    // ── Certificates ─────────────────────────────────────────────────────────

    private suspend fun restoreCertificates(data: String, overwriteExisting: Boolean): Int {
        val root = json.parseToJsonElement(data).jsonObject
        var count = 0
        val itemsArr = root["items"] as? JsonArray ?: run {
            Logger.w(TAG, "v2 backup missing 'items' in certificates section")
            return 0
        }
        val items = json.decodeFromJsonElement(
            ListSerializer(TrustedCertificate.serializer()), itemsArr
        )
        for (c in items) {
            val existing = database.certificateDao().getCertificateByHostAndPort(c.hostname, c.port)
            if (existing != null && !overwriteExisting) continue
            database.certificateDao().insertCertificate(c)
            count++
        }
        return count
    }

    // ── Host keys ────────────────────────────────────────────────────────────

    private suspend fun restoreHostKeys(data: String, overwriteExisting: Boolean): Int {
        val root = json.parseToJsonElement(data).jsonObject
        var count = 0
        val itemsArr = root["items"] as? JsonArray ?: run {
            Logger.w(TAG, "v2 backup missing 'items' in host keys section")
            return 0
        }
        val items = json.decodeFromJsonElement(
            ListSerializer(HostKeyEntry.serializer()), itemsArr
        )
        for (h in items) {
            val existing = database.hostKeyDao().getHostKey(h.hostname, h.port)
            if (existing != null && !overwriteExisting) continue
            database.hostKeyDao().insertHostKey(h)
            count++
        }
        return count
    }

    // ── Identities ───────────────────────────────────────────────────────────

    private suspend fun restoreIdentities(data: String, overwriteExisting: Boolean): Int {
        val root = json.parseToJsonElement(data).jsonObject
        var count = 0
        val itemsArr = root["items"] as? JsonArray ?: run {
            Logger.w(TAG, "v2 backup missing 'items' in identities section")
            return 0
        }
        val items = json.decodeFromJsonElement(
            ListSerializer(Identity.serializer()), itemsArr
        )
        // Identity.password is null in this row — the exporter strips it because the
        // Keystore-encrypted-at-rest blob is not portable. The plaintext password is
        // restored from the secrets file (identity_{id}) when present in the backup.
        for (id in items) {
            val existing = database.identityDao().getIdentityById(id.id)
            if (existing != null && !overwriteExisting) continue
            database.identityDao().insert(id)
            count++
        }
        return count
    }

    // ── v2-only tables ───────────────────────────────────────────────────────

    private suspend fun restoreGroups(data: String, overwriteExisting: Boolean): Int =
        restoreV2List(data, ListSerializer(ConnectionGroup.serializer())) { g ->
            val existing = database.connectionGroupDao().getGroupById(g.id)
            if (existing != null && !overwriteExisting) return@restoreV2List false
            database.connectionGroupDao().insertGroup(g); true
        }

    private suspend fun restoreSnippets(data: String, overwriteExisting: Boolean): Int =
        restoreV2List(data, ListSerializer(Snippet.serializer())) { s ->
            val existing = database.snippetDao().getSnippetById(s.id)
            if (existing != null && !overwriteExisting) return@restoreV2List false
            database.snippetDao().insertSnippet(s); true
        }

    private suspend fun restoreHypervisors(data: String, overwriteExisting: Boolean): Int =
        // password column is blank in the backup row (Keystore-bound; not portable).
        // Restored from the secrets file (hypervisor_{id} or hypervisor_account_{id})
        // when present in the backup. All other config lands immediately.
        restoreV2List(data, ListSerializer(HypervisorProfile.serializer())) { h ->
            val existing = database.hypervisorDao().getById(h.id)
            if (existing != null && !overwriteExisting) return@restoreV2List false
            database.hypervisorDao().upsertForSync(h); true
        }

    private suspend fun restoreHypervisorAccounts(data: String, overwriteExisting: Boolean): Int =
        restoreV2List(data, ListSerializer(HypervisorAccount.serializer())) { a ->
            val existing = database.hypervisorAccountDao().getById(a.id)
            if (existing != null && !overwriteExisting) return@restoreV2List false
            database.hypervisorAccountDao().insert(a); true
        }

    private suspend fun restoreWorkspaces(data: String, overwriteExisting: Boolean): Int =
        restoreV2List(data, ListSerializer(Workspace.serializer())) { w ->
            val existing = database.workspaceDao().getById(w.id)
            if (existing != null && !overwriteExisting) return@restoreV2List false
            database.workspaceDao().upsert(w); true
        }

    private suspend fun restoreCloudAccounts(data: String, overwriteExisting: Boolean): Int =
        // Cloud API token is NOT in this entity row — it lives in SecurePasswordManager
        // under cloud_token_{id} and is restored from the secrets file when present.
        restoreV2List(data, ListSerializer(CloudAccount.serializer())) { c ->
            val existing = database.cloudAccountDao().getById(c.id)
            if (existing != null && !overwriteExisting) return@restoreV2List false
            database.cloudAccountDao().upsert(c); true
        }

    private suspend fun restoreMacros(data: String, overwriteExisting: Boolean): Int =
        restoreV2List(data, ListSerializer(Macro.serializer())) { m ->
            val existing = database.macroDao().getMacroById(m.id)
            if (existing != null && !overwriteExisting) return@restoreV2List false
            database.macroDao().insertMacro(m); true
        }

    private suspend fun restoreMonitorSlots(data: String, overwriteExisting: Boolean): Int =
        restoreV2List(data, ListSerializer(MonitorSlot.serializer())) { s ->
            val existing = database.monitorSlotDao().getById(s.id)
            if (existing != null && !overwriteExisting) return@restoreV2List false
            database.monitorSlotDao().insertOrReplace(s); true
        }

    private suspend fun restoreVncHosts(data: String, overwriteExisting: Boolean): Int =
        restoreV2List(data, ListSerializer(VncHost.serializer())) { h ->
            val existing = database.vncHostDao().getById(h.id)
            if (existing != null && !overwriteExisting) return@restoreV2List false
            database.vncHostDao().insert(h); true
        }

    private suspend fun restoreVncIdentities(data: String, overwriteExisting: Boolean): Int =
        // Password is NOT in this entity (it lives in SecurePasswordManager under
        // vnc_identity_{id}); restored from the secrets file when present in the backup.
        restoreV2List(data, ListSerializer(VncIdentity.serializer())) { vi ->
            val existing = database.vncIdentityDao().getById(vi.id)
            if (existing != null && !overwriteExisting) return@restoreV2List false
            database.vncIdentityDao().insert(vi); true
        }

    private suspend fun <T> restoreV2List(
        data: String,
        serializer: kotlinx.serialization.KSerializer<List<T>>,
        applyOne: suspend (T) -> Boolean
    ): Int {
        val root = json.parseToJsonElement(data).jsonObject
        val items = (root["items"] as? JsonArray) ?: run {
            Logger.w(TAG, "v2 list root missing 'items' key")
            return 0
        }
        val list = json.decodeFromJsonElement(serializer, items)
        var count = 0
        for (item in list) {
            try {
                if (applyOne(item)) count++
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to restore item from v2 list: ${e.message}")
            }
        }
        return count
    }

    // ── Secrets ──────────────────────────────────────────────────────────────

    /**
     * Restore all Keystore-backed credentials from [BackupExporter.FILE_SECRETS].
     *
     * Passwords are written back via [SecurePasswordManager.storePassword] using
     * the same alias strings the app uses at runtime, so every subsystem
     * (HypervisorPasswordStore, VMConsoleActivity, TabTerminalActivity, etc.)
     * picks them up on the next access without any migration code.
     *
     * SSH key JSch bytes are re-encrypted with fresh Keystore AES keys via
     * [KeyStorage.importKeyFromBackup] so the companion [StoredKey] metadata rows
     * (restored by [restoreKeys]) are immediately usable for SSH authentication.
     */
    private suspend fun restoreSecrets(data: String) {
        val pm = securePasswordManager
        val ks = keyStorage
        try {
            val root = json.parseToJsonElement(data).jsonObject

            // Passwords / tokens / OCI keys / connection passwords
            (root["passwords"] as? JsonObject)?.forEach { (alias, element) ->
                val value = element.jsonPrimitive.content
                if (value.isEmpty()) return@forEach
                try {
                    if (alias.startsWith("conn_pw_")) {
                        // Connection passwords live in PreferenceManager SharedPreferences,
                        // not SecurePasswordManager — route them to the correct store.
                        val connId = alias.removePrefix("conn_pw_")
                        preferenceManager.setConnectionPassword(connId, value)
                        Logger.d(TAG, "Restored connection password: $connId")
                    } else if (pm != null) {
                        pm.storePassword(alias, value,
                            SecurePasswordManager.StorageLevel.ENCRYPTED)
                        Logger.d(TAG, "Restored secret alias: $alias")
                    }
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to restore secret $alias: ${e.message}")
                }
            }
            if (pm == null) {
                Logger.w(TAG, "SecurePasswordManager unavailable — Keystore-backed passwords not restored")
            }

            // SSH private key JSch bytes
            if (ks != null) {
                (root["ssh_keys"] as? JsonObject)?.forEach { (keyId, element) ->
                    val b64 = element.jsonPrimitive.content
                    if (b64.isNotEmpty()) {
                        try {
                            val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                            ks.importKeyFromBackup(keyId, bytes)
                            Logger.d(TAG, "Restored SSH key: $keyId")
                        } catch (e: Exception) {
                            Logger.w(TAG, "Failed to restore SSH key $keyId: ${e.message}")
                        }
                    }
                }
            } else {
                Logger.w(TAG, "KeyStorage unavailable — SSH key bytes not restored")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to parse secrets file: ${e.message}")
        }
    }

    // ── Preferences ──────────────────────────────────────────────────────────

    private fun restorePreferences(data: String) {
        val root = JSONObject(data)
        root.optJSONObject("general")?.let { g ->
            preferenceManager.setAutoBackupEnabled(g.optBoolean("autoBackup", true))
            preferenceManager.setBackupFrequency(g.optString("backupFrequency", "weekly"))
            preferenceManager.setStartupBehavior(g.optString("startupBehavior", "last_session"))
            preferenceManager.setLanguage(g.optString("language", "system"))
        }
        root.optJSONObject("security")?.let { s ->
            preferenceManager.setPasswordStorageLevel(s.optString("passwordStorageLevel", "encrypted"))
            preferenceManager.setRequireBiometricForSensitive(s.optBoolean("requireBiometric", true))
            preferenceManager.setStrictHostKeyChecking(s.optBoolean("strictHostKeyChecking", true))
            preferenceManager.setClearClipboardTimeout(s.optInt("clearClipboardTimeout", 60))
            preferenceManager.setAutoLockOnBackground(s.optBoolean("autoLockEnabled", false))
            preferenceManager.setAutoLockTimeout(s.optInt("lockTimeout", 300))
            preferenceManager.setPasswordTTLHours(s.optInt("passwordTTLHours", 24))
            preferenceManager.setPreventScreenshots(s.optBoolean("preventScreenshots", false))
        }
        root.optJSONObject("terminal")?.let { t ->
            preferenceManager.setTerminalTheme(t.optString("theme", "dracula"))
            preferenceManager.setFontSize(t.optDouble("fontSize", 14.0).toFloat())
            preferenceManager.setFontFamily(t.optString("fontFamily", "Roboto Mono"))
            preferenceManager.setCursorStyle(t.optString("cursorStyle", "bar"))
            preferenceManager.setCursorBlinkEnabled(t.optBoolean("cursorBlink", true))
            preferenceManager.setScrollbackLines(t.optInt("scrollbackLines", 1000))
            preferenceManager.setBellNotificationEnabled(t.optBoolean("terminalBell", false))
            preferenceManager.setLineSpacing(t.optDouble("lineSpacing", 1.2).toFloat())
            preferenceManager.setReverseScrollDirection(t.optBoolean("reverseScroll", false))
            preferenceManager.setBellVibrate(t.optBoolean("bellVibrate", true))
            preferenceManager.setBellVisual(t.optBoolean("bellVisual", true))
            preferenceManager.setWordWrap(t.optBoolean("wordWrap", true))
            preferenceManager.setCopyOnSelect(t.optBoolean("copyOnSelect", true))
        }
        root.optJSONObject("ui")?.let { u ->
            preferenceManager.setMaxTabs(u.optInt("maxTabs", 10))
            preferenceManager.setConfirmTabClose(u.optBoolean("confirmTabClose", true))
            preferenceManager.setAppTheme(u.optString("appTheme", "system"))
            preferenceManager.setDynamicColors(u.optBoolean("dynamicColors", true))
            preferenceManager.setShowFunctionKeys(u.optBoolean("showFunctionKeys", true))
            preferenceManager.setFullscreenMode(u.optBoolean("fullscreenMode", false))
            preferenceManager.setKeepScreenOn(u.optBoolean("keepScreenOn", false))
        }
        root.optJSONObject("keyboard")?.let { k ->
            preferenceManager.setKeyboardRowCount(k.optInt("rowCount", 3))
            preferenceManager.setKeyboardLayoutVersion(k.optInt("layoutVersion", 0))
            preferenceManager.setKeyboardLayoutCustomized(k.optBoolean("layoutCustomized", false))
            val layoutJson = k.optString("layoutJson", "")
            preferenceManager.setKeyboardLayoutJson(if (layoutJson.isEmpty()) null else layoutJson)
        }

        // Notification and monitoring preferences are stored as raw keys in
        // the default SharedPreferences (no PreferenceManager wrappers). Write
        // them directly via the same editor path the Preference UI uses.
        val defaultPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        root.optJSONObject("notifications")?.let { n ->
            defaultPrefs.edit().apply {
                putBoolean("notifications_enabled",           n.optBoolean("notifications_enabled", true))
                putBoolean("show_connection_notifications",   n.optBoolean("show_connection_notifications", true))
                putBoolean("show_error_notifications",        n.optBoolean("show_error_notifications", true))
                putBoolean("show_file_transfer_notifications",n.optBoolean("show_file_transfer_notifications", true))
                putBoolean("notification_vibrate",            n.optBoolean("notification_vibrate", true))
            }.apply()
        }
        root.optJSONObject("monitoring")?.let { m ->
            defaultPrefs.edit().apply {
                putBoolean("monitoring_enabled",              m.optBoolean("monitoring_enabled", true))
                putBoolean("monitoring_run_in_battery_saver", m.optBoolean("monitoring_run_in_battery_saver", false))
                putBoolean("monitoring_notify_down",           m.optBoolean("monitoring_notify_down", true))
                putBoolean("monitoring_notify_recovery",       m.optBoolean("monitoring_notify_recovery", true))
                // ListPreference stores its value as String.
                putString("monitoring_alert_cooldown_minutes", m.optString("monitoring_alert_cooldown_minutes", "60"))
                // SeekBarPreference stores its value as Int — must restore as Int to avoid
                // ClassCastException when the Preference UI inflates.
                putInt("monitoring_default_cpu_threshold",     m.optInt("monitoring_default_cpu_threshold",    85))
                putInt("monitoring_default_memory_threshold",  m.optInt("monitoring_default_memory_threshold", 90))
                putInt("monitoring_default_disk_threshold",    m.optInt("monitoring_default_disk_threshold",   80))
            }.apply()
        }
        root.optJSONObject("connection")?.let { c ->
            preferenceManager.setDefaultUsername(c.optString("defaultUsername", "root"))
            preferenceManager.setDefaultPort(c.optInt("defaultPort", 22))
            preferenceManager.setConnectTimeout(c.optInt("connectTimeout", 15))
            preferenceManager.setAutoReconnect(c.optBoolean("autoReconnect", true))
            preferenceManager.setCompressionEnabled(c.optBoolean("compression", true))
            preferenceManager.setServerAliveIntervalSec(c.optInt("serverAliveIntervalSec", 60))
            preferenceManager.setX11ForwardingDefault(c.optBoolean("x11ForwardingDefault", false))
            preferenceManager.setAgentForwardingDefault(c.optBoolean("agentForwardingDefault", false))
        }
        root.optJSONObject("sync")?.let { s ->
            preferenceManager.setSyncFrequency(s.optString("frequency", "hourly"))
            preferenceManager.setSyncWifiOnly(s.optBoolean("wifiOnly", false))
            preferenceManager.setSyncOnChangeEnabled(s.optBoolean("onChangeEnabled", false))
            preferenceManager.setSyncConnectionsEnabled(s.optBoolean("syncConnections", true))
            preferenceManager.setSyncKeysEnabled(s.optBoolean("syncKeys", true))
            preferenceManager.setSyncIdentitiesEnabled(s.optBoolean("syncIdentities", true))
            preferenceManager.setSyncSnippetsEnabled(s.optBoolean("syncSnippets", true))
            preferenceManager.setSyncSettingsEnabled(s.optBoolean("syncSettings", true))
            preferenceManager.setSyncThemesEnabled(s.optBoolean("syncThemes", true))
            preferenceManager.setSyncHostKeysEnabled(s.optBoolean("syncHostKeys", true))
            preferenceManager.setSyncGroupsEnabled(s.optBoolean("syncGroups", true))
            preferenceManager.setSyncWorkspacesEnabled(s.optBoolean("syncWorkspaces", true))
            preferenceManager.setSyncMacrosEnabled(s.optBoolean("syncMacros", true))
            preferenceManager.setSyncMonitorSlotsEnabled(s.optBoolean("syncMonitorSlots", true))
            preferenceManager.setSyncHypervisorsEnabled(s.optBoolean("syncHypervisors", true))
            preferenceManager.setSyncHypervisorAccountsEnabled(s.optBoolean("syncHypervisorAccounts", true))
            preferenceManager.setSyncVncHostsEnabled(s.optBoolean("syncVncHosts", true))
            preferenceManager.setSyncVncIdentitiesEnabled(s.optBoolean("syncVncIdentities", true))
            preferenceManager.setSyncCloudAccountsEnabled(s.optBoolean("syncCloudAccounts", true))
            preferenceManager.setSyncCertificatesEnabled(s.optBoolean("syncCertificates", true))
            preferenceManager.setSyncDashboardEnabled(s.optBoolean("syncDashboard", false))
            preferenceManager.setAutoResolveConflicts(s.optBoolean("autoResolve", true))
        }
        root.optJSONObject("multiplexer")?.let { m ->
            defaultPrefs.edit().apply {
                putBoolean("enable_custom_gestures",  m.optBoolean("gestureEnabled", false))
                putString("gesture_multiplexer_type", m.optString("gestureType", "tmux"))
            }.apply()
            preferenceManager.setMultiplexerPrefix("tmux",   m.optString("prefixTmux",   "C-b"))
            preferenceManager.setMultiplexerPrefix("screen", m.optString("prefixScreen", "C-a"))
            preferenceManager.setMultiplexerPrefix("zellij", m.optString("prefixZellij", "C-g"))
        }
        root.optJSONObject("accessibility")?.let { a ->
            preferenceManager.setHighContrastMode(a.optBoolean("highContrast", false))
            preferenceManager.setLargeTouchTargets(a.optBoolean("largeTouchTargets", false))
            preferenceManager.setScreenReaderEnabled(a.optBoolean("screenReader", false))
        }
        root.optJSONObject("paste")?.let { p ->
            preferenceManager.setPasteService(p.optString("service", "stikked"))
            preferenceManager.setPasteMicrobinUrl(p.optString("microbinUrl", "https://mb.pste.us"))
            preferenceManager.setPasteLenpasteUrl(p.optString("lenpasteUrl", "https://lp.pste.us"))
            preferenceManager.setPasteStikkedUrl(p.optString("stikkedUrl", "https://pste.us"))
            preferenceManager.setPastebinApiKey(p.optString("pastebinApiKey", ""))
        }
        root.optJSONObject("proxy")?.let { p ->
            preferenceManager.setProxyEnabled(p.optBoolean("enabled", false))
            preferenceManager.setProxyType(p.optString("type", "SOCKS5"))
            preferenceManager.setProxyHost(p.optString("host", ""))
            preferenceManager.setProxyPort(p.optInt("port", 1080))
            val proxyUser = p.optString("username", "")
            preferenceManager.setProxyUsername(proxyUser.takeIf { it.isNotEmpty() })
            val proxyPass = p.optString("password", "")
            preferenceManager.setProxyPassword(proxyPass.takeIf { it.isNotEmpty() })
            val bypass = p.optString("bypassHosts", "")
            if (bypass.isNotEmpty()) {
                // Separator is "\n" (set by BackupExporter). Accept "," too for
                // backward-compat with backups written before this fix.
                val sep = if ('\n' in bypass) "\n" else ","
                preferenceManager.setProxyBypassHosts(bypass.split(sep).filter { it.isNotEmpty() })
            }
        }
    }

    // ── Dashboard config ──────────────────────────────────────────────────────

    /**
     * Restore multi-host dashboard groups and host membership from the flat
     * key→value JSON blob written by [BackupExporter.exportDashboardConfig].
     *
     * All values are strings (JSON blobs or comma-separated ID lists); the
     * "v" version key is silently skipped.  When [overwriteExisting] is false,
     * keys that already exist in the SharedPreferences are left untouched so
     * a partial restore does not clobber a user's current dashboard layout.
     */
    private fun restoreDashboardConfig(data: String, overwriteExisting: Boolean): Int {
        return try {
            val root = json.parseToJsonElement(data).jsonObject
            val dashPrefs = context.getSharedPreferences("multi_host_dashboard", android.content.Context.MODE_PRIVATE)
            val editor = dashPrefs.edit()
            var count = 0
            for ((k, v) in root) {
                if (k == "v") continue
                if (!overwriteExisting && dashPrefs.contains(k)) continue
                editor.putString(k, v.jsonPrimitive.content)
                count++
            }
            editor.apply()
            count
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to restore dashboard config: ${e.message}")
            0
        }
    }
}
