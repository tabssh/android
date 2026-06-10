package io.github.tabssh.sync.data

import android.content.Context
import androidx.preference.PreferenceManager as AndroidPreferenceManager
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.keys.KeyStorage
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HostKeyEntry
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.storage.database.entities.ThemeDefinition
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.sync.metadata.SyncMetadataManager
import io.github.tabssh.sync.models.SyncDataPackage
import io.github.tabssh.sync.models.SyncItemCounts
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Collects data for sync operations
 */
class SyncDataCollector {

    companion object {
        private const val TAG = "SyncDataCollector"
    }

    private val context: Context
    private val database: TabSSHDatabase
    private val preferenceManager: PreferenceManager
    private val metadataManager: SyncMetadataManager

    // Credential managers — resolved from Application singleton so that
    // every sync upload includes Keystore-backed secrets (passwords, tokens,
    // SSH key material) inside the AES-GCM encrypted sync envelope.
    private val app: TabSSHApplication?
        get() = context.applicationContext as? TabSSHApplication

    // Simple constructor for SAF sync
    constructor(context: Context) {
        this.context = context
        this.database = TabSSHDatabase.getDatabase(context)
        this.preferenceManager = PreferenceManager(context)
        this.metadataManager = SyncMetadataManager(context)
    }

    // Full constructor for advanced use
    constructor(
        context: Context,
        database: TabSSHDatabase,
        preferenceManager: PreferenceManager,
        metadataManager: SyncMetadataManager
    ) {
        this.context = context
        this.database = database
        this.preferenceManager = preferenceManager
        this.metadataManager = metadataManager
    }

    /**
     * Collect all data for sync (alias for collectAllSyncData)
     */
    suspend fun collectAll(): SyncDataPackage = collectAllSyncData()

    /**
     * Collect all data for sync
     */
    suspend fun collectAllSyncData(): SyncDataPackage = withContext(Dispatchers.IO) {
        Logger.d(TAG, "Collecting all sync data")

        // Each collection is gated on the corresponding user toggle so that
        // turning off a category in Sync Settings actually stops it from being
        // included in the sync payload.
        val syncConns      = preferenceManager.isSyncConnectionsEnabled()
        val syncKeys       = preferenceManager.isSyncKeysEnabled()
        val syncThemes     = preferenceManager.isSyncThemesEnabled()
        val syncIdentities = preferenceManager.isSyncIdentitiesEnabled()
        val syncSnippets   = preferenceManager.isSyncSnippetsEnabled()
        val syncSettings   = preferenceManager.isSyncSettingsEnabled()

        val connections = if (syncConns)      collectConnections()   else emptyList()
        val keys        = if (syncKeys)       collectKeys()          else emptyList()
        val themes      = if (syncThemes)     collectThemes()        else emptyList()
        val preferences = if (syncSettings)   collectPreferences()   else emptyMap()
        val hostKeys = collectHostKeys()
        val workspaces = collectWorkspaces() // Wave 5.3
        val snippets    = if (syncSnippets)   collectSnippets()      else emptyList()
        val identities  = if (syncIdentities) collectIdentities()    else emptyList()
        val groups = collectGroups()          // Wave 5.4
        val hypervisors = collectHypervisors()  // Wave 7.1
        val certificates = collectCertificates() // Wave 7.1
        val macros = collectMacros()             // Wave 11
        val monitorSlots = collectMonitorSlots() // Wave 11
        val hypervisorAccounts = collectHypervisorAccounts() // Audit 2026-05-16
        val vncHosts = collectVncHosts()         // Wave 13
        val vncIdentities = collectVncIdentities() // Wave 13
        val cloudAccounts = collectCloudAccounts() // Wave 14

        val itemCounts = SyncItemCounts(
            connections = connections.size,
            keys = keys.size,
            themes = themes.size,
            preferences = preferences.size,
            hostKeys = hostKeys.size,
            workspaces = workspaces.size,
            snippets = snippets.size,
            identities = identities.size,
            groups = groups.size,
            hypervisors = hypervisors.size,
            certificates = certificates.size,
            macros = macros.size,
            monitorSlots = monitorSlots.size,
            hypervisorAccounts = hypervisorAccounts.size,
            vncHosts = vncHosts.size,
            vncIdentities = vncIdentities.size,
            cloudAccounts = cloudAccounts.size
        )

        val metadata = metadataManager.createSyncMetadata(itemCounts)
        val secrets = collectSecrets()

        Logger.d(TAG, "Collected ${itemCounts.total()} items for sync")

        SyncDataPackage(
            connections = connections,
            keys = keys,
            themes = themes,
            preferences = preferences,
            hostKeys = hostKeys,
            metadata = metadata,
            workspaces = workspaces,
            snippets = snippets,
            identities = identities,
            groups = groups,
            hypervisors = hypervisors,
            certificates = certificates,
            macros = macros,
            monitorSlots = monitorSlots,
            hypervisorAccounts = hypervisorAccounts,
            vncHosts = vncHosts,
            vncIdentities = vncIdentities,
            cloudAccounts = cloudAccounts,
            secrets = secrets
        )
    }

    /** Audit 2026-05-16 — hypervisor credential metadata. The actual
     *  password lives in SecurePasswordManager under
     *  `hypervisor_account_${id}` and is NOT in the synced row, so the
     *  account row arrives "unlocked-by-design" on the destination device
     *  and the user enters the password once there. */
    private suspend fun collectHypervisorAccounts(): List<io.github.tabssh.storage.database.entities.HypervisorAccount> {
        return try {
            database.hypervisorAccountDao().getAllAccountsList()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect hypervisor accounts", e)
            emptyList()
        }
    }

    /** Wave 13 — direct VNC host rows. No secrets; all columns are safe to sync. */
    private suspend fun collectVncHosts(): List<io.github.tabssh.storage.database.entities.VncHost> {
        return try {
            database.vncHostDao().getAllHostsList()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect VNC hosts", e)
            emptyList()
        }
    }

    /** Wave 13 — VNC identity metadata. Password remains Keystore-bound on each
     *  device under `vnc_identity_${id}` and is NOT in this row. */
    private suspend fun collectVncIdentities(): List<io.github.tabssh.storage.database.entities.VncIdentity> {
        return try {
            database.vncIdentityDao().getAllIdentitiesList()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect VNC identities", e)
            emptyList()
        }
    }

    private suspend fun collectHypervisors(): List<io.github.tabssh.storage.database.entities.HypervisorProfile> {
        return try {
            database.hypervisorDao().getAllList()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect hypervisors", e)
            emptyList()
        }
    }

    private suspend fun collectCertificates(): List<io.github.tabssh.storage.database.entities.TrustedCertificate> {
        return try {
            database.certificateDao().getAllCertificates().first()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect certificates", e)
            emptyList()
        }
    }

    private suspend fun collectSnippets(): List<io.github.tabssh.storage.database.entities.Snippet> {
        return try {
            database.snippetDao().getAllSnippets().first()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect snippets", e)
            emptyList()
        }
    }

    private suspend fun collectIdentities(): List<io.github.tabssh.storage.database.entities.Identity> {
        return try {
            database.identityDao().getAllIdentitiesList()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect identities", e)
            emptyList()
        }
    }

    private suspend fun collectGroups(): List<io.github.tabssh.storage.database.entities.ConnectionGroup> {
        return try {
            database.connectionGroupDao().getAllGroups().first()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect groups", e)
            emptyList()
        }
    }

    /** Wave 5.3 — collect Workspace rows. CloudAccount is intentionally
     *  NOT collected: its encrypted token is per-device hardware-keystore-bound
     *  via SecurePasswordManager, so a synced row without the matching token
     *  would be unusable on the destination device. */
    private suspend fun collectWorkspaces(): List<io.github.tabssh.storage.database.entities.Workspace> {
        return try {
            database.workspaceDao().getAll()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect workspaces", e)
            emptyList()
        }
    }

    /** Wave 11 — command macros (reusable multi-step sequences). */
    private suspend fun collectMacros(): List<io.github.tabssh.storage.database.entities.Macro> {
        return try {
            database.macroDao().getAllMacrosList()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect macros", e)
            emptyList()
        }
    }

    /** Wave 11 — per-host monitoring configuration (alert thresholds, intervals).
     *  MonitorSlot has no modifiedAt column so full-table sync is used. */
    private suspend fun collectMonitorSlots(): List<io.github.tabssh.storage.database.entities.MonitorSlot> {
        return try {
            database.monitorSlotDao().getAllSlots().first()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect monitor slots", e)
            emptyList()
        }
    }

    /** Wave 14 — cloud provider account metadata rows. Token lives in
     *  SecurePasswordManager under `cloud_token_${id}` and is captured by
     *  [collectSecrets]. */
    private suspend fun collectCloudAccounts(): List<io.github.tabssh.storage.database.entities.CloudAccount> {
        return try {
            database.cloudAccountDao().getAll()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect cloud accounts", e)
            emptyList()
        }
    }

    /**
     * Collect data changed since timestamp
     */
    suspend fun collectChangedSince(timestamp: Long): SyncDataPackage = withContext(Dispatchers.IO) {
        Logger.d(TAG, "Collecting data changed since: $timestamp")

        val connections = collectConnections().filter { it.modifiedAt > timestamp }
        val keys = collectKeys().filter { it.modifiedAt > timestamp }
        val themes = collectThemes().filter { it.modifiedAt > timestamp }
        val hostKeys = collectHostKeys().filter { it.modifiedAt > timestamp }
        val workspaces = collectWorkspaces().filter { it.modifiedAt > timestamp }
        val snippets = collectSnippets().filter { it.modifiedAt > timestamp }
        val identities = collectIdentities().filter { it.modifiedAt > timestamp }
        val groups = collectGroups().filter { it.modifiedAt > timestamp }
        // Wave 7.1 — neither HypervisorProfile nor TrustedCertificate has a
        // modifiedAt column. They're low-volume tables (a few rows per user)
        // so include them all in delta payloads — cheap, never wrong.
        val hypervisors = collectHypervisors()
        val certificates = collectCertificates()
        // Wave 11 — Macro has modifiedAt; MonitorSlot does not (low-volume,
        // always include in full).
        val macros = collectMacros().filter { it.modifiedAt > timestamp }
        val monitorSlots = collectMonitorSlots()
        val hypervisorAccounts = collectHypervisorAccounts().filter { it.modifiedAt > timestamp }
        // Wave 13 — both tables have modifiedAt; delta-filter them.
        val vncHosts = collectVncHosts().filter { it.modifiedAt > timestamp }
        val vncIdentities = collectVncIdentities().filter { it.modifiedAt > timestamp }
        // Wave 14 — CloudAccount has no modifiedAt column; always include all rows
        // (low-volume, rarely more than a few entries per user).
        val cloudAccounts = collectCloudAccounts()

        val preferences = if (hasPreferencesChanged(timestamp)) {
            collectPreferences()
        } else {
            emptyMap()
        }

        val itemCounts = SyncItemCounts(
            connections = connections.size,
            keys = keys.size,
            themes = themes.size,
            preferences = preferences.size,
            hostKeys = hostKeys.size,
            workspaces = workspaces.size,
            snippets = snippets.size,
            identities = identities.size,
            groups = groups.size,
            hypervisors = hypervisors.size,
            certificates = certificates.size,
            macros = macros.size,
            monitorSlots = monitorSlots.size,
            hypervisorAccounts = hypervisorAccounts.size,
            vncHosts = vncHosts.size,
            vncIdentities = vncIdentities.size,
            cloudAccounts = cloudAccounts.size
        )

        val metadata = metadataManager.createSyncMetadata(itemCounts)
        val secrets = collectSecrets()

        Logger.d(TAG, "Collected ${itemCounts.total()} changed items")

        SyncDataPackage(
            connections = connections,
            keys = keys,
            themes = themes,
            preferences = preferences,
            hostKeys = hostKeys,
            metadata = metadata,
            workspaces = workspaces,
            snippets = snippets,
            identities = identities,
            groups = groups,
            hypervisors = hypervisors,
            certificates = certificates,
            macros = macros,
            monitorSlots = monitorSlots,
            hypervisorAccounts = hypervisorAccounts,
            vncHosts = vncHosts,
            vncIdentities = vncIdentities,
            cloudAccounts = cloudAccounts,
            secrets = secrets
        )
    }

    /**
     * Collect all Keystore-backed credentials.
     *
     * Returns a flat map of alias → plaintext value. SSH private key bytes are
     * base64-encoded and stored under key "ssh_key_{keyId}". The map is empty
     * when neither SecurePasswordManager nor KeyStorage is accessible (e.g. in
     * unit tests). Values are plaintext because the sync payload is AES-GCM
     * encrypted before it leaves the device.
     */
    private suspend fun collectSecrets(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val pm: SecurePasswordManager? = app?.securePasswordManager
        val ks: KeyStorage? = app?.keyStorage
        try {
            if (pm != null) {
                // Identity passwords — alias: identity_{id}
                database.identityDao().getAllIdentitiesList().forEach { id ->
                    pm.retrievePassword("identity_${id.id}")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { out["identity_${id.id}"] = it }
                }

                // Per-host hypervisor passwords (no linked account)
                database.hypervisorDao().getAllList()
                    .filter { it.accountId == null }
                    .forEach { h ->
                        pm.retrievePassword("hypervisor_${h.id}")
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { out["hypervisor_${h.id}"] = it }
                    }

                // Hypervisor account passwords + OCI secrets
                database.hypervisorAccountDao().getAllAccountsList().forEach { a ->
                    pm.retrievePassword("hypervisor_account_${a.id}")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { out["hypervisor_account_${a.id}"] = it }
                    pm.retrievePassword("oci_private_key_account_${a.id}")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { out["oci_private_key_account_${a.id}"] = it }
                    pm.retrievePassword("oci_passphrase_account_${a.id}")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { out["oci_passphrase_account_${a.id}"] = it }
                }

                // VNC identity passwords — alias: vnc_identity_{id}
                database.vncIdentityDao().getAllIdentitiesList().forEach { vi ->
                    pm.retrievePassword("vnc_identity_${vi.id}")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { out["vnc_identity_${vi.id}"] = it }
                }

                // Cloud account tokens — alias: cloud_token_{id}
                database.cloudAccountDao().getAll().forEach { ca ->
                    pm.retrievePassword("cloud_token_${ca.id}")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { out["cloud_token_${ca.id}"] = it }
                }
            }

            // Connection passwords — stored in PreferenceManager SharedPreferences under
            // "password_{connectionId}" (not SecurePasswordManager). Alias: conn_pw_{id}.
            database.connectionDao().getAllConnections().first()
                .filter { c -> c.getAuthTypeEnum() == io.github.tabssh.ssh.auth.AuthType.PASSWORD }
                .forEach { c ->
                    preferenceManager.getConnectionPassword(c.id)
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { out["conn_pw_${c.id}"] = it }
                }

            // SSH private key JSch bytes — stored as "ssh_key_{keyId}"
            if (ks != null) {
                database.keyDao().getAllKeys().first().forEach { key ->
                    val bytes = ks.retrieveJSchBytes(key.keyId)
                    if (bytes != null) {
                        out["ssh_key_${key.keyId}"] = android.util.Base64.encodeToString(
                            bytes, android.util.Base64.NO_WRAP
                        )
                    } else {
                        Logger.w(TAG, "No JSch bytes for key ${key.keyId} (${key.name}) — skipped in sync")
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "collectSecrets failed: ${e.message}")
        }
        return out
    }

    /**
     * Collect connection profiles
     */
    private suspend fun collectConnections(): List<ConnectionProfile> {
        return try {
            val connectionsFlow = database.connectionDao().getAllConnections()
            connectionsFlow.first()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect connections", e)
            emptyList()
        }
    }

    /**
     * Collect SSH keys
     */
    private suspend fun collectKeys(): List<StoredKey> {
        return try {
            val keysFlow = database.keyDao().getAllKeys()
            keysFlow.first()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect keys", e)
            emptyList()
        }
    }

    /**
     * Collect themes
     */
    private suspend fun collectThemes(): List<ThemeDefinition> {
        return try {
            val themesFlow = database.themeDao().getAllThemes()
            themesFlow.first()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect themes", e)
            emptyList()
        }
    }

    /**
     * Collect host keys
     */
    private suspend fun collectHostKeys(): List<HostKeyEntry> {
        return try {
            val hostKeysFlow = database.hostKeyDao().getAllHostKeys()
            hostKeysFlow.first()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect host keys", e)
            emptyList()
        }
    }

    /**
     * Collect preferences as map.
     *
     * Each `collectXPreferences()` returns a `Map<String, Any>` with
     * mixed value types (Boolean / Int / Long / String). kotlinx.serialization
     * has no default serializer for `Any`, so we convert each map by
     * hand into a `JsonObject` of `JsonPrimitive`s — that's what was
     * silently failing the whole sync upload before.
     */
    private fun collectPreferences(): Map<String, JsonElement> {
        val prefs = mutableMapOf<String, JsonElement>()
        try {
            prefs["general"] = anyMapToJsonObject(collectGeneralPreferences())
            prefs["security"] = anyMapToJsonObject(collectSecurityPreferences())
            prefs["terminal"] = anyMapToJsonObject(collectTerminalPreferences())
            prefs["ui"] = anyMapToJsonObject(collectUIPreferences())
            prefs["connection"] = anyMapToJsonObject(collectConnectionPreferences())
            prefs["sync"] = anyMapToJsonObject(collectSyncPreferences())
            prefs["keyboard"] = anyMapToJsonObject(collectKeyboardPreferences())
            // Notification and monitoring: only include keys that differ from
            // their system defaults so a device with all-defaults does not
            // overwrite custom values on the receiving device.
            val notifPrefs = collectNotificationPreferences()
            if (notifPrefs.isNotEmpty()) prefs["notifications"] = anyMapToJsonObject(notifPrefs)
            val monitorPrefs = collectMonitoringPreferences()
            if (monitorPrefs.isNotEmpty()) prefs["monitoring"] = anyMapToJsonObject(monitorPrefs)
            prefs["multiplexer"]    = anyMapToJsonObject(collectMultiplexerPreferences())
            prefs["accessibility"]  = anyMapToJsonObject(collectAccessibilityPreferences())
            prefs["proxy"]          = anyMapToJsonObject(collectProxyPreferences())
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect preferences", e)
        }

        return prefs
    }

    private fun anyMapToJsonObject(map: Map<String, Any?>): JsonObject =
        JsonObject(map.mapValues { (_, v) -> anyToJson(v) })

    private fun anyToJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private fun collectGeneralPreferences(): Map<String, Any> {
        return mapOf(
            "autoBackup" to preferenceManager.isAutoBackupEnabled(),
            "backupFrequency" to preferenceManager.getBackupFrequency(),
            "startupBehavior" to preferenceManager.getStartupBehavior(),
            "language" to preferenceManager.getLanguage()
        )
    }

    private fun collectSecurityPreferences(): Map<String, Any> {
        return mapOf(
            "passwordStorageLevel" to preferenceManager.getPasswordStorageLevel(),
            "requireBiometric" to preferenceManager.isRequireBiometricForSensitive(),
            "strictHostKeyChecking" to preferenceManager.isStrictHostKeyChecking(),
            "clearClipboardTimeout" to preferenceManager.getClearClipboardTimeout(),
            "autoLockEnabled" to preferenceManager.isAutoLockOnBackground(),
            "lockTimeout" to preferenceManager.getAutoLockTimeout()
        )
    }

    private fun collectTerminalPreferences(): Map<String, Any> {
        return mapOf(
            "theme" to preferenceManager.getTerminalTheme(),
            "fontSize" to preferenceManager.getFontSize(),
            "fontFamily" to preferenceManager.getFontFamily(),
            "cursorStyle" to preferenceManager.getCursorStyle(),
            "cursorBlink" to preferenceManager.isCursorBlinkEnabled(),
            "scrollbackLines" to preferenceManager.getScrollbackLines(),
            "terminalBell" to preferenceManager.isBellNotificationEnabled()
        )
    }

    private fun collectUIPreferences(): Map<String, Any> {
        return mapOf(
            "maxTabs" to preferenceManager.getMaxTabs(),
            "confirmTabClose" to preferenceManager.isConfirmTabClose(),
            "appTheme" to preferenceManager.getAppTheme(),
            "dynamicColors" to preferenceManager.isDynamicColors()
        )
    }

    private fun collectConnectionPreferences(): Map<String, Any> {
        return mapOf(
            "defaultUsername" to preferenceManager.getDefaultUsername(),
            "defaultPort" to preferenceManager.getDefaultPort(),
            "connectTimeout" to preferenceManager.getConnectTimeout(),
            "autoReconnect" to preferenceManager.isAutoReconnect(),
            "compression" to preferenceManager.isCompressionEnabled()
        )
    }

    private fun collectSyncPreferences(): Map<String, Any> {
        return mapOf(
            "frequency" to preferenceManager.getSyncFrequency(),
            "wifiOnly" to preferenceManager.isSyncWifiOnly(),
            "onChangeEnabled" to preferenceManager.isSyncOnChangeEnabled(),
            "syncConnections" to preferenceManager.isSyncConnectionsEnabled(),
            "syncKeys" to preferenceManager.isSyncKeysEnabled(),
            "syncIdentities" to preferenceManager.isSyncIdentitiesEnabled(),
            "syncSnippets" to preferenceManager.isSyncSnippetsEnabled(),
            "syncSettings" to preferenceManager.isSyncSettingsEnabled(),
            "syncThemes" to preferenceManager.isSyncThemesEnabled()
        )
    }

    /** Keyboard layout preferences — always included (layout is always meaningful if set). */
    private fun collectKeyboardPreferences(): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        out["rowCount"] = preferenceManager.getKeyboardRowCount()
        out["layoutVersion"] = preferenceManager.getKeyboardLayoutVersion()
        out["layoutCustomized"] = preferenceManager.isKeyboardLayoutCustomized()
        // Only include the JSON blob when the user has saved a custom layout.
        val layoutJson = preferenceManager.getKeyboardLayoutJson()
        if (!layoutJson.isNullOrEmpty()) out["layoutJson"] = layoutJson
        return out
    }

    /**
     * Notification preferences — only keys that differ from their system defaults
     * are included so syncing a "default" device does not clobber custom values
     * on the receiving device.
     */
    private fun collectNotificationPreferences(): Map<String, Any> {
        val defaultPrefs = AndroidPreferenceManager.getDefaultSharedPreferences(context)
        val out = mutableMapOf<String, Any>()
        val notifEnabled = defaultPrefs.getBoolean("notifications_enabled", true)
        if (!notifEnabled) out["notifications_enabled"] = notifEnabled
        val connNotif = defaultPrefs.getBoolean("show_connection_notifications", true)
        if (!connNotif) out["show_connection_notifications"] = connNotif
        val errNotif = defaultPrefs.getBoolean("show_error_notifications", true)
        if (!errNotif) out["show_error_notifications"] = errNotif
        val fileNotif = defaultPrefs.getBoolean("show_file_transfer_notifications", true)
        if (!fileNotif) out["show_file_transfer_notifications"] = fileNotif
        val vibrate = defaultPrefs.getBoolean("notification_vibrate", true)
        if (!vibrate) out["notification_vibrate"] = vibrate
        return out
    }

    /**
     * Monitoring preferences — only keys that differ from their system defaults
     * are included (same "sync if non-default" policy as notifications).
     */
    private fun collectMonitoringPreferences(): Map<String, Any> {
        val defaultPrefs = AndroidPreferenceManager.getDefaultSharedPreferences(context)
        val out = mutableMapOf<String, Any>()
        val monitorEnabled = defaultPrefs.getBoolean("monitoring_enabled", true)
        if (!monitorEnabled) out["monitoring_enabled"] = monitorEnabled
        val batterySaver = defaultPrefs.getBoolean("monitoring_run_in_battery_saver", false)
        if (batterySaver) out["monitoring_run_in_battery_saver"] = batterySaver
        val notifyDown = defaultPrefs.getBoolean("monitoring_notify_down", true)
        if (!notifyDown) out["monitoring_notify_down"] = notifyDown
        val notifyRecovery = defaultPrefs.getBoolean("monitoring_notify_recovery", true)
        if (!notifyRecovery) out["monitoring_notify_recovery"] = notifyRecovery
        val cooldown = defaultPrefs.getString("monitoring_alert_cooldown_minutes", "60") ?: "60"
        if (cooldown != "60") out["monitoring_alert_cooldown_minutes"] = cooldown
        // SeekBarPreference stores its value as Int — read as Int to avoid
        // ClassCastException when the Preference UI inflates after a sync.
        val cpuThresh = defaultPrefs.getInt("monitoring_default_cpu_threshold", 0)
        if (cpuThresh > 0) out["monitoring_default_cpu_threshold"] = cpuThresh
        val memThresh = defaultPrefs.getInt("monitoring_default_memory_threshold", 0)
        if (memThresh > 0) out["monitoring_default_memory_threshold"] = memThresh
        val diskThresh = defaultPrefs.getInt("monitoring_default_disk_threshold", 0)
        if (diskThresh > 0) out["monitoring_default_disk_threshold"] = diskThresh
        return out
    }

    private fun collectMultiplexerPreferences(): Map<String, Any> {
        val defaultPrefs = AndroidPreferenceManager.getDefaultSharedPreferences(context)
        return mapOf(
            "gestureEnabled" to defaultPrefs.getBoolean("enable_custom_gestures", false),
            "gestureType"    to (defaultPrefs.getString("gesture_multiplexer_type", "tmux") ?: "tmux"),
            "prefixTmux"     to preferenceManager.getMultiplexerPrefix("tmux"),
            "prefixScreen"   to preferenceManager.getMultiplexerPrefix("screen"),
            "prefixZellij"   to preferenceManager.getMultiplexerPrefix("zellij")
        )
    }

    private fun collectAccessibilityPreferences(): Map<String, Any> = mapOf(
        "highContrast"      to preferenceManager.isHighContrastMode(),
        "largeTouchTargets" to preferenceManager.isLargeTouchTargets()
    )

    private fun collectProxyPreferences(): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        out["enabled"]     = preferenceManager.isProxyEnabled()
        out["type"]        = preferenceManager.getProxyType()
        out["host"]        = preferenceManager.getProxyHost()
        out["port"]        = preferenceManager.getProxyPort()
        val user = preferenceManager.getProxyUsername()
        if (!user.isNullOrEmpty()) out["username"] = user
        val pass = preferenceManager.getProxyPassword()
        if (!pass.isNullOrEmpty()) out["password"] = pass
        val bypass = preferenceManager.getProxyBypassHosts()
        if (bypass.isNotEmpty()) out["bypassHosts"] = bypass.joinToString("\n")
        return out
    }

    /**
     * Check if preferences have changed since timestamp
     */
    private fun hasPreferencesChanged(timestamp: Long): Boolean {
        val lastModified = preferenceManager.getPreferencesLastModified()
        return lastModified > timestamp
    }

    /**
     * Get item counts for current data
     */
    suspend fun getItemCounts(): SyncItemCounts = withContext(Dispatchers.IO) {
        SyncItemCounts(
            connections       = database.connectionDao().getConnectionCount(),
            keys              = database.keyDao().getKeyCount(),
            themes            = database.themeDao().getThemeCount(),
            preferences       = collectPreferences().size,
            hostKeys          = database.hostKeyDao().getHostKeyCount(),
            workspaces        = try { database.workspaceDao().getAll().size } catch (_: Exception) { 0 },
            snippets          = try { database.snippetDao().getAllSnippets().first().size } catch (_: Exception) { 0 },
            identities        = try { database.identityDao().getAllIdentitiesList().size } catch (_: Exception) { 0 },
            groups            = try { database.connectionGroupDao().getAllGroups().first().size } catch (_: Exception) { 0 },
            hypervisors       = try { database.hypervisorDao().getAllList().size } catch (_: Exception) { 0 },
            certificates      = try { database.certificateDao().getAllCertificates().first().size } catch (_: Exception) { 0 },
            macros            = try { database.macroDao().getAllMacrosList().size } catch (_: Exception) { 0 },
            monitorSlots      = try { database.monitorSlotDao().getAllSlots().first().size } catch (_: Exception) { 0 },
            hypervisorAccounts= try { database.hypervisorAccountDao().getAllAccountsList().size } catch (_: Exception) { 0 },
            vncHosts          = try { database.vncHostDao().getAllHostsList().size } catch (_: Exception) { 0 },
            vncIdentities     = try { database.vncIdentityDao().getAllIdentitiesList().size } catch (_: Exception) { 0 },
            cloudAccounts     = try { database.cloudAccountDao().getAll().size } catch (_: Exception) { 0 }
        )
    }

    /**
     * Collect specific connection by ID
     */
    suspend fun collectConnection(id: String): ConnectionProfile? {
        return try {
            database.connectionDao().getConnectionById(id)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect connection: $id", e)
            null
        }
    }

    /**
     * Collect specific key by ID
     */
    suspend fun collectKey(id: String): StoredKey? {
        return try {
            database.keyDao().getKeyById(id)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect key: $id", e)
            null
        }
    }

    /**
     * Collect specific theme by ID
     */
    suspend fun collectTheme(id: String): ThemeDefinition? {
        return try {
            database.themeDao().getThemeById(id)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect theme: $id", e)
            null
        }
    }
}
