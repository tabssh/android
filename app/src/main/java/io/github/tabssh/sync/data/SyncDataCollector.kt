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
import io.github.tabssh.storage.database.entities.SyncShadow
import io.github.tabssh.storage.database.entities.SyncTombstone
import io.github.tabssh.sync.metadata.SyncMetadataManager
import io.github.tabssh.sync.models.SyncDataPackage
import io.github.tabssh.sync.models.SyncItemCounts
import io.github.tabssh.sync.tombstone.TombstoneRecorder
import io.github.tabssh.utils.SharedPrefsCodec
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

        /**
         * Named SharedPreferences files carrying user data outside the Room DB.
         * Must stay in step with the equivalent list in BackupExporter.
         */
        val NAMED_PREF_FILES = listOf("TabSSH", "cluster_commands", "snippet_var_recall")
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
        val syncConns              = preferenceManager.isSyncConnectionsEnabled()
        val syncKeys               = preferenceManager.isSyncKeysEnabled()
        val syncThemes             = preferenceManager.isSyncThemesEnabled()
        val syncIdentities         = preferenceManager.isSyncIdentitiesEnabled()
        val syncSnippets           = preferenceManager.isSyncSnippetsEnabled()
        val syncSettings           = preferenceManager.isSyncSettingsEnabled()
        val syncHostKeys           = preferenceManager.isSyncHostKeysEnabled()
        val syncGroups             = preferenceManager.isSyncGroupsEnabled()
        val syncWorkspaces         = preferenceManager.isSyncWorkspacesEnabled()
        val syncMacros             = preferenceManager.isSyncMacrosEnabled()
        val syncMonitorSlots       = preferenceManager.isSyncMonitorSlotsEnabled()
        val syncHypervisors        = preferenceManager.isSyncHypervisorsEnabled()
        val syncHypervisorAccounts = preferenceManager.isSyncHypervisorAccountsEnabled()
        val syncVncHosts           = preferenceManager.isSyncVncHostsEnabled()
        val syncVncIdentities      = preferenceManager.isSyncVncIdentitiesEnabled()
        val syncCloudAccounts      = preferenceManager.isSyncCloudAccountsEnabled()
        val syncCertificates       = preferenceManager.isSyncCertificatesEnabled()
        val syncDashboard          = preferenceManager.isSyncDashboardEnabled()
        val syncPortForwards       = preferenceManager.isSyncPortForwardsEnabled()
        val syncNetworkRoutes      = preferenceManager.isSyncNetworkRoutesEnabled()
        val syncDocker             = preferenceManager.isSyncDockerEnabled()

        val connections        = if (syncConns)              collectConnections()        else emptyList()
        val keys               = if (syncKeys)               collectKeys()               else emptyList()
        val themes             = if (syncThemes)             collectThemes()             else emptyList()
        val preferences        = if (syncSettings)           collectPreferences()        else emptyMap()
        val hostKeys           = if (syncHostKeys)           collectHostKeys()           else emptyList()
        val workspaces         = if (syncWorkspaces)         collectWorkspaces()         else emptyList()
        val snippets           = if (syncSnippets)           collectSnippets()           else emptyList()
        val identities         = if (syncIdentities)         collectIdentities()         else emptyList()
        val groups             = if (syncGroups)             collectGroups()             else emptyList()
        val hypervisors        = if (syncHypervisors)        collectHypervisors()        else emptyList()
        val certificates       = if (syncCertificates)       collectCertificates()       else emptyList()
        val macros             = if (syncMacros)             collectMacros()             else emptyList()
        val monitorSlots       = if (syncMonitorSlots)       collectMonitorSlots()       else emptyList()
        val hypervisorAccounts = if (syncHypervisorAccounts) collectHypervisorAccounts() else emptyList()
        val vncHosts           = if (syncVncHosts)           collectVncHosts()           else emptyList()
        val vncIdentities      = if (syncVncIdentities)      collectVncIdentities()      else emptyList()
        val cloudAccounts      = if (syncCloudAccounts)      collectCloudAccounts()      else emptyList()
        val dashboardConfig    = if (syncDashboard)          collectDashboardConfig()    else emptyMap()
        val namedPrefFiles     = if (syncSettings)           collectNamedPreferenceFiles() else emptyMap()
        val portForwards       = if (syncPortForwards)       collectPortForwards()       else emptyList()
        val networkRoutes      = if (syncNetworkRoutes)      collectNetworkRoutes()      else emptyList()
        val dockerHosts                = if (syncDocker) collectDockerHosts()                else emptyList()
        val registryCredentials        = if (syncDocker) collectRegistryCredentials()        else emptyList()
        val composeStacks              = if (syncDocker) collectComposeStacks()               else emptyList()
        val singleContainerConfigs     = if (syncDocker) collectSingleContainerConfigs()      else emptyList()
        val containerAutoUpdatePolicies = if (syncDocker) collectContainerAutoUpdatePolicies() else emptyList()

        val itemCounts = SyncItemCounts(
            connections        = connections.size,
            keys               = keys.size,
            themes             = themes.size,
            preferences        = preferences.size,
            hostKeys           = hostKeys.size,
            workspaces         = workspaces.size,
            snippets           = snippets.size,
            identities         = identities.size,
            groups             = groups.size,
            hypervisors        = hypervisors.size,
            certificates       = certificates.size,
            macros             = macros.size,
            monitorSlots       = monitorSlots.size,
            hypervisorAccounts = hypervisorAccounts.size,
            vncHosts           = vncHosts.size,
            vncIdentities      = vncIdentities.size,
            cloudAccounts      = cloudAccounts.size,
            dashboard          = dashboardConfig.size,
            portForwards       = portForwards.size,
            networkRoutes      = networkRoutes.size,
            dockerHosts                     = dockerHosts.size,
            registryCredentials             = registryCredentials.size,
            composeStacks                   = composeStacks.size,
            singleContainerConfigs          = singleContainerConfigs.size,
            containerAutoUpdatePolicies     = containerAutoUpdatePolicies.size
        )

        val metadata = metadataManager.createSyncMetadata(itemCounts)
        val secrets = collectSecrets()
        val tombstones = collectTombstones()

        Logger.d(TAG, "Collected ${itemCounts.total()} items + ${tombstones.size} tombstones for sync")

        SyncDataPackage(
            connections        = connections,
            keys               = keys,
            themes             = themes,
            preferences        = preferences,
            hostKeys           = hostKeys,
            metadata           = metadata,
            workspaces         = workspaces,
            snippets           = snippets,
            identities         = identities,
            groups             = groups,
            hypervisors        = hypervisors,
            certificates       = certificates,
            macros             = macros,
            monitorSlots       = monitorSlots,
            hypervisorAccounts = hypervisorAccounts,
            vncHosts           = vncHosts,
            vncIdentities      = vncIdentities,
            cloudAccounts      = cloudAccounts,
            dashboardConfig    = dashboardConfig,
            namedPreferenceFiles = namedPrefFiles,
            portForwards       = portForwards,
            networkRoutes      = networkRoutes,
            dockerHosts                 = dockerHosts,
            registryCredentials         = registryCredentials,
            composeStacks               = composeStacks,
            singleContainerConfigs      = singleContainerConfigs,
            containerAutoUpdatePolicies = containerAutoUpdatePolicies,
            secrets            = secrets,
            tombstones         = tombstones
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

    /** Saved SSH port-forward rules. No secrets; all columns are safe to sync. */
    private suspend fun collectPortForwards(): List<io.github.tabssh.storage.database.entities.PortForward> {
        return try {
            database.portForwardDao().getAllList()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect port forwards", e)
            emptyList()
        }
    }

    /** Reusable network routes (proxies and SSH jump hosts). No secrets; all columns are safe to sync. */
    private suspend fun collectNetworkRoutes(): List<io.github.tabssh.storage.database.entities.NetworkRoute> {
        return try {
            database.networkRouteDao().getAllList()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect network routes", e)
            emptyList()
        }
    }

    /** Docker host connection profiles. Custom-endpoint password travels via secrets. */
    private suspend fun collectDockerHosts(): List<io.github.tabssh.storage.database.entities.DockerHost> {
        return try {
            database.dockerHostDao().getAllList()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect Docker hosts", e)
            emptyList()
        }
    }

    /** Private registry credential metadata. The secret itself travels via secrets. */
    private suspend fun collectRegistryCredentials(): List<io.github.tabssh.storage.database.entities.RegistryCredential> {
        return try {
            database.registryCredentialDao().getAllList()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect registry credentials", e)
            emptyList()
        }
    }

    /** Compose stack definitions tracked per Docker host. No secrets. */
    private suspend fun collectComposeStacks(): List<io.github.tabssh.storage.database.entities.ComposeStack> {
        return try {
            database.composeStackDao().getAllList()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect compose stacks", e)
            emptyList()
        }
    }

    /** Single-container run configs tracked per Docker host. No secrets. */
    private suspend fun collectSingleContainerConfigs(): List<io.github.tabssh.storage.database.entities.SingleContainerConfig> {
        return try {
            database.singleContainerConfigDao().getAllList()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect single-container configs", e)
            emptyList()
        }
    }

    /** Container/stack auto-update policies — user config, not audit data. */
    private suspend fun collectContainerAutoUpdatePolicies(): List<io.github.tabssh.storage.database.entities.ContainerAutoUpdatePolicy> {
        return try {
            database.containerAutoUpdatePolicyDao().getAllList()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect container auto-update policies", e)
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

    /**
     * Named SharedPreferences files with user data that lives outside both the
     * Room DB and the default preference store. Backed up already — they sync
     * too, so a second device does not silently lose sort orders, saved cluster
     * commands, or snippet variable recall.
     */
    private fun collectNamedPreferenceFiles(): Map<String, Map<String, JsonObject>> {
        val out = mutableMapOf<String, Map<String, JsonObject>>()
        NAMED_PREF_FILES.forEach { name ->
            try {
                val sp = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                val values = SharedPrefsCodec.encodeAll(sp)
                if (values.isNotEmpty()) out[name] = values
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to collect SharedPreferences file $name: ${e.message}")
            }
        }
        return out
    }

    /** Dashboard groups and host-membership keys from the `multi_host_dashboard`
     *  SharedPreferences file. Values are type-tagged because the file mixes
     *  string blobs with a Boolean collapse flag. */
    private fun collectDashboardConfig(): Map<String, JsonObject> {
        return try {
            val sp = context.getSharedPreferences("multi_host_dashboard", android.content.Context.MODE_PRIVATE)
            SharedPrefsCodec.encodeAll(sp)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect dashboard config", e)
            emptyMap()
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

        val syncConns              = preferenceManager.isSyncConnectionsEnabled()
        val syncKeys               = preferenceManager.isSyncKeysEnabled()
        val syncThemes             = preferenceManager.isSyncThemesEnabled()
        val syncIdentities         = preferenceManager.isSyncIdentitiesEnabled()
        val syncSnippets           = preferenceManager.isSyncSnippetsEnabled()
        val syncSettings           = preferenceManager.isSyncSettingsEnabled()
        val syncHostKeys           = preferenceManager.isSyncHostKeysEnabled()
        val syncGroups             = preferenceManager.isSyncGroupsEnabled()
        val syncWorkspaces         = preferenceManager.isSyncWorkspacesEnabled()
        val syncMacros             = preferenceManager.isSyncMacrosEnabled()
        val syncMonitorSlots       = preferenceManager.isSyncMonitorSlotsEnabled()
        val syncHypervisors        = preferenceManager.isSyncHypervisorsEnabled()
        val syncHypervisorAccounts = preferenceManager.isSyncHypervisorAccountsEnabled()
        val syncVncHosts           = preferenceManager.isSyncVncHostsEnabled()
        val syncVncIdentities      = preferenceManager.isSyncVncIdentitiesEnabled()
        val syncCloudAccounts      = preferenceManager.isSyncCloudAccountsEnabled()
        val syncCertificates       = preferenceManager.isSyncCertificatesEnabled()
        val syncDashboard          = preferenceManager.isSyncDashboardEnabled()
        val syncPortForwards       = preferenceManager.isSyncPortForwardsEnabled()
        val syncNetworkRoutes      = preferenceManager.isSyncNetworkRoutesEnabled()
        val syncDocker             = preferenceManager.isSyncDockerEnabled()

        val connections = if (syncConns)      collectConnections().filter { it.modifiedAt > timestamp } else emptyList()
        val keys        = if (syncKeys)       collectKeys().filter { it.modifiedAt > timestamp }        else emptyList()
        val themes      = if (syncThemes)     collectThemes().filter { it.modifiedAt > timestamp }      else emptyList()
        val hostKeys    = if (syncHostKeys)   collectHostKeys().filter { it.modifiedAt > timestamp }    else emptyList()
        val workspaces  = if (syncWorkspaces) collectWorkspaces().filter { it.modifiedAt > timestamp }  else emptyList()
        val snippets    = if (syncSnippets)   collectSnippets().filter { it.modifiedAt > timestamp }    else emptyList()
        val identities  = if (syncIdentities) collectIdentities().filter { it.modifiedAt > timestamp }  else emptyList()
        val groups      = if (syncGroups)     collectGroups().filter { it.modifiedAt > timestamp }      else emptyList()
        // Wave 7.1 — neither HypervisorProfile nor TrustedCertificate has a
        // modifiedAt column. They're low-volume tables (a few rows per user)
        // so include them all in delta payloads — cheap, never wrong.
        val hypervisors  = if (syncHypervisors)  collectHypervisors()  else emptyList()
        val certificates = if (syncCertificates) collectCertificates() else emptyList()
        // Wave 11 — Macro has modifiedAt; MonitorSlot does not (low-volume, always include in full).
        val macros       = if (syncMacros)       collectMacros().filter { it.modifiedAt > timestamp } else emptyList()
        val monitorSlots = if (syncMonitorSlots)  collectMonitorSlots() else emptyList()
        val hypervisorAccounts = if (syncHypervisorAccounts)
            collectHypervisorAccounts().filter { it.modifiedAt > timestamp } else emptyList()
        // Wave 13 — both tables have modifiedAt; delta-filter them.
        val vncHosts      = if (syncVncHosts)      collectVncHosts().filter { it.modifiedAt > timestamp }      else emptyList()
        val vncIdentities = if (syncVncIdentities) collectVncIdentities().filter { it.modifiedAt > timestamp } else emptyList()
        // Wave 14 — CloudAccount has no modifiedAt column; always include all rows (low-volume).
        val cloudAccounts = if (syncCloudAccounts) collectCloudAccounts() else emptyList()
        // Dashboard — SharedPrefs has no per-key timestamp; include all keys when enabled.
        val dashboardConfig = if (syncDashboard) collectDashboardConfig() else emptyMap()
        // PortForward has modifiedAt; delta-filter it like the other simple entities.
        val portForwards = if (syncPortForwards)
            collectPortForwards().filter { it.modifiedAt > timestamp } else emptyList()
        // NetworkRoute has modifiedAt; delta-filter it like the other simple entities.
        val networkRoutes = if (syncNetworkRoutes)
            collectNetworkRoutes().filter { it.modifiedAt > timestamp } else emptyList()
        // DockerHost/RegistryCredential/ContainerAutoUpdatePolicy have no modifiedAt
        // column — low-volume, always include in full like HypervisorProfile above.
        // ComposeStack/SingleContainerConfig have updatedAt, so delta-filter them.
        val dockerHosts = if (syncDocker) collectDockerHosts() else emptyList()
        val registryCredentials = if (syncDocker) collectRegistryCredentials() else emptyList()
        val composeStacks = if (syncDocker)
            collectComposeStacks().filter { it.updatedAt > timestamp } else emptyList()
        val singleContainerConfigs = if (syncDocker)
            collectSingleContainerConfigs().filter { it.updatedAt > timestamp } else emptyList()
        val containerAutoUpdatePolicies = if (syncDocker) collectContainerAutoUpdatePolicies() else emptyList()

        val preferences = if (syncSettings && hasPreferencesChanged(timestamp)) {
            collectPreferences()
        } else {
            emptyMap()
        }
        // Named SharedPreferences files have no per-key timestamp — include all
        // keys whenever settings sync is on, same policy as the dashboard.
        val namedPrefFiles = if (syncSettings) collectNamedPreferenceFiles() else emptyMap()

        val itemCounts = SyncItemCounts(
            connections        = connections.size,
            keys               = keys.size,
            themes             = themes.size,
            preferences        = preferences.size,
            hostKeys           = hostKeys.size,
            workspaces         = workspaces.size,
            snippets           = snippets.size,
            identities         = identities.size,
            groups             = groups.size,
            hypervisors        = hypervisors.size,
            certificates       = certificates.size,
            macros             = macros.size,
            monitorSlots       = monitorSlots.size,
            hypervisorAccounts = hypervisorAccounts.size,
            vncHosts           = vncHosts.size,
            vncIdentities      = vncIdentities.size,
            cloudAccounts      = cloudAccounts.size,
            dashboard          = dashboardConfig.size,
            portForwards       = portForwards.size,
            networkRoutes      = networkRoutes.size,
            dockerHosts                     = dockerHosts.size,
            registryCredentials             = registryCredentials.size,
            composeStacks                   = composeStacks.size,
            singleContainerConfigs          = singleContainerConfigs.size,
            containerAutoUpdatePolicies     = containerAutoUpdatePolicies.size
        )

        val metadata = metadataManager.createSyncMetadata(itemCounts)
        val secrets = collectSecrets()
        // Tombstones are never delta-filtered: a deletion must propagate on
        // every sync until every peer has seen it, and rows are purged by age
        // (purgeOlderThan), not by "changed since last sync".
        val tombstones = collectTombstones()

        Logger.d(TAG, "Collected ${itemCounts.total()} changed items + ${tombstones.size} tombstones")

        SyncDataPackage(
            connections        = connections,
            keys               = keys,
            themes             = themes,
            preferences        = preferences,
            hostKeys           = hostKeys,
            metadata           = metadata,
            workspaces         = workspaces,
            snippets           = snippets,
            identities         = identities,
            groups             = groups,
            hypervisors        = hypervisors,
            certificates       = certificates,
            macros             = macros,
            monitorSlots       = monitorSlots,
            hypervisorAccounts = hypervisorAccounts,
            vncHosts           = vncHosts,
            vncIdentities      = vncIdentities,
            cloudAccounts      = cloudAccounts,
            dashboardConfig    = dashboardConfig,
            namedPreferenceFiles = namedPrefFiles,
            portForwards       = portForwards,
            networkRoutes      = networkRoutes,
            dockerHosts                 = dockerHosts,
            registryCredentials         = registryCredentials,
            composeStacks               = composeStacks,
            singleContainerConfigs      = singleContainerConfigs,
            containerAutoUpdatePolicies = containerAutoUpdatePolicies,
            secrets            = secrets,
            tombstones         = tombstones
        )
    }

    // ---------------------------------------------------------------------
    // H6 — soft-delete tombstones
    //
    // A deletion only propagates if the peer applying our payload is told the
    // row is gone; the upload-only union would otherwise resurrect it. Two
    // sources feed the payload:
    //   1. Explicit tombstones recorded at each delete site (TombstoneRecorder).
    //   2. A diff-at-collect backstop that compares current live keys against
    //      the sync_shadow baseline and tombstones anything that vanished
    //      without an explicit record (best-effort safety net).
    // Only sync-enabled categories contribute — a disabled category is never
    // tombstoned. The shadow baseline is refreshed by snapshotState() AFTER a
    // successful apply, never mid-cycle.
    // ---------------------------------------------------------------------

    /** Every tombstone-eligible entity type, paired with its sync toggle. */
    private fun enabledTombstoneTypes(): Set<String> {
        val out = mutableSetOf<String>()
        // Secrets carry no category toggle of their own: each alias family is
        // gated individually at apply time by SyncDataApplier.isSecretAliasEnabled.
        out += TombstoneRecorder.SECRET
        if (preferenceManager.isSyncConnectionsEnabled())        out += TombstoneRecorder.CONNECTION
        if (preferenceManager.isSyncKeysEnabled())               out += TombstoneRecorder.KEY
        if (preferenceManager.isSyncThemesEnabled())             out += TombstoneRecorder.THEME
        if (preferenceManager.isSyncHostKeysEnabled())           out += TombstoneRecorder.HOST_KEY
        if (preferenceManager.isSyncWorkspacesEnabled())         out += TombstoneRecorder.WORKSPACE
        if (preferenceManager.isSyncSnippetsEnabled())           out += TombstoneRecorder.SNIPPET
        if (preferenceManager.isSyncIdentitiesEnabled())         out += TombstoneRecorder.IDENTITY
        if (preferenceManager.isSyncGroupsEnabled())             out += TombstoneRecorder.GROUP
        if (preferenceManager.isSyncHypervisorsEnabled())        out += TombstoneRecorder.HYPERVISOR
        if (preferenceManager.isSyncCertificatesEnabled())       out += TombstoneRecorder.CERTIFICATE
        if (preferenceManager.isSyncMacrosEnabled())             out += TombstoneRecorder.MACRO
        if (preferenceManager.isSyncMonitorSlotsEnabled())       out += TombstoneRecorder.MONITOR_SLOT
        if (preferenceManager.isSyncHypervisorAccountsEnabled()) out += TombstoneRecorder.HYPERVISOR_ACCOUNT
        if (preferenceManager.isSyncVncHostsEnabled())           out += TombstoneRecorder.VNC_HOST
        if (preferenceManager.isSyncVncIdentitiesEnabled())      out += TombstoneRecorder.VNC_IDENTITY
        if (preferenceManager.isSyncCloudAccountsEnabled())      out += TombstoneRecorder.CLOUD_ACCOUNT
        if (preferenceManager.isSyncPortForwardsEnabled())       out += TombstoneRecorder.PORT_FORWARD
        if (preferenceManager.isSyncNetworkRoutesEnabled())      out += TombstoneRecorder.NETWORK_ROUTE
        if (preferenceManager.isSyncDockerEnabled()) {
            out += TombstoneRecorder.DOCKER_HOST
            out += TombstoneRecorder.REGISTRY_CREDENTIAL
            out += TombstoneRecorder.COMPOSE_STACK
            out += TombstoneRecorder.SINGLE_CONTAINER_CONFIG
            out += TombstoneRecorder.CONTAINER_AUTO_UPDATE_POLICY
        }
        return out
    }

    /** Current stable cross-device keys for one entity type. Matches the keys
     *  used by TombstoneRecorder at the delete sites so live/shadow/tombstone
     *  all speak the same key space. */
    private suspend fun liveKeys(type: String): List<String> = when (type) {
        TombstoneRecorder.CONNECTION        -> collectConnections().map { it.id }
        TombstoneRecorder.KEY               -> collectKeys().map { it.keyId }
        TombstoneRecorder.THEME             -> collectThemes().map { it.themeId }
        TombstoneRecorder.HOST_KEY          -> collectHostKeys().map { it.id }
        TombstoneRecorder.WORKSPACE         -> collectWorkspaces().map { it.id }
        TombstoneRecorder.SNIPPET           -> collectSnippets().map { it.id }
        TombstoneRecorder.IDENTITY          -> collectIdentities().map { it.id }
        TombstoneRecorder.GROUP             -> collectGroups().map { it.id }
        TombstoneRecorder.HYPERVISOR        -> collectHypervisors().map { TombstoneRecorder.naturalKey(it) }
        TombstoneRecorder.CERTIFICATE       -> collectCertificates().map { it.id }
        TombstoneRecorder.MACRO             -> collectMacros().map { it.id }
        TombstoneRecorder.MONITOR_SLOT      -> collectMonitorSlots().map { it.id }
        TombstoneRecorder.HYPERVISOR_ACCOUNT -> collectHypervisorAccounts().map { TombstoneRecorder.naturalKey(it) }
        TombstoneRecorder.VNC_HOST          -> collectVncHosts().map { it.id }
        TombstoneRecorder.VNC_IDENTITY      -> collectVncIdentities().map { it.id }
        TombstoneRecorder.CLOUD_ACCOUNT     -> collectCloudAccounts().map { it.id }
        TombstoneRecorder.PORT_FORWARD      -> collectPortForwards().map { it.id }
        TombstoneRecorder.NETWORK_ROUTE     -> collectNetworkRoutes().map { it.id }
        TombstoneRecorder.DOCKER_HOST       -> collectDockerHosts().map { TombstoneRecorder.naturalKey(it) }
        TombstoneRecorder.REGISTRY_CREDENTIAL -> collectRegistryCredentials().map { TombstoneRecorder.naturalKey(it) }
        TombstoneRecorder.COMPOSE_STACK     -> collectComposeStacks().map { TombstoneRecorder.naturalKey(it) }
        TombstoneRecorder.SINGLE_CONTAINER_CONFIG -> collectSingleContainerConfigs().map { TombstoneRecorder.naturalKey(it) }
        TombstoneRecorder.CONTAINER_AUTO_UPDATE_POLICY -> collectContainerAutoUpdatePolicies().map { TombstoneRecorder.naturalKey(it) }
        TombstoneRecorder.SECRET            -> liveSecretAliases()
        else -> emptyList()
    }

    /**
     * Every sync wire alias for which this device currently holds a secret,
     * ungated — the per-family sync toggles decide what is *sent*, but the live
     * set must reflect reality or the backstop would tombstone a credential
     * merely because its category is switched off.
     *
     * Throws when the Keystore is unreachable so the caller skips the type
     * rather than mistaking "cannot read" for "everything was deleted".
     */
    private suspend fun liveSecretAliases(): List<String> {
        val pm = app?.securePasswordManager
            ?: throw IllegalStateException("SecurePasswordManager unavailable — secret live set unknown")
        val out = mutableListOf<String>()
        // Alias families whose Keystore alias IS the wire alias.
        val wirePrefixes = listOf(
            "identity_", "hypervisor_", "hypervisor_account_",
            "oci_private_key_account_", "oci_passphrase_account_",
            "vnc_identity_", "vnc_host_", "cloud_token_",
            "key_passphrase_", "docker_host_", "registry_credential_"
        )
        pm.storedAliases().forEach { alias ->
            if (wirePrefixes.any { alias.startsWith(it) }) out += alias
        }
        // Connection passwords: Keystore alias is the bare connection id, so it
        // is only recognisable by cross-referencing the connection table.
        database.connectionDao().getAllConnections().first().forEach { c ->
            if (pm.hasStoredPassword(c.id)) out += "conn_pw_${c.id}"
        }
        // SSH private key material lives in KeyStorage, not SecurePasswordManager.
        database.keyDao().getAllKeys().first().forEach { key -> out += "ssh_key_${key.keyId}" }
        return out
    }

    /** Backstop: for each enabled type, tombstone any key present in the shadow
     *  baseline but no longer live. Uses recordIfAbsent so an accurate explicit
     *  tombstone (correct deletedAt) is never clobbered. Does NOT refresh the
     *  shadow — that happens post-apply via snapshotState(). */
    private suspend fun runBackstop(enabled: Set<String>) {
        val tombDao = database.syncTombstoneDao()
        val shadowDao = database.syncShadowDao()
        val deviceId = metadataManager.getDeviceId()
        val now = System.currentTimeMillis()
        for (type in enabled) {
            try {
                val live = liveKeys(type).toSet()
                val shadow = shadowDao.getKeys(type).toSet()
                val vanished = shadow - live
                for (key in vanished) {
                    tombDao.recordIfAbsent(SyncTombstone(type, key, now, deviceId))
                }
                if (vanished.isNotEmpty()) {
                    Logger.d(TAG, "Backstop tombstoned ${vanished.size} vanished $type row(s)")
                }
            } catch (e: Exception) {
                Logger.w(TAG, "Backstop failed for type=$type: ${e.message}")
            }
        }
    }

    /** Run the backstop, then return every tombstone for a sync-enabled type. */
    private suspend fun collectTombstones(): List<SyncTombstone> {
        return try {
            val enabled = enabledTombstoneTypes()
            runBackstop(enabled)
            database.syncTombstoneDao().getAll().filter { it.entityType in enabled }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect tombstones", e)
            emptyList()
        }
    }

    /** Refresh the shadow baseline to the current live keys for every
     *  tombstone-eligible type. MUST be called only AFTER a successful
     *  merge+apply, so the next backstop diff is taken against post-apply
     *  reality. Never call mid-cycle. */
    suspend fun snapshotState() = withContext(Dispatchers.IO) {
        val shadowDao = database.syncShadowDao()
        val allTypes = listOf(
            TombstoneRecorder.CONNECTION, TombstoneRecorder.KEY, TombstoneRecorder.THEME,
            TombstoneRecorder.HOST_KEY, TombstoneRecorder.WORKSPACE, TombstoneRecorder.SNIPPET,
            TombstoneRecorder.IDENTITY, TombstoneRecorder.GROUP, TombstoneRecorder.HYPERVISOR,
            TombstoneRecorder.CERTIFICATE, TombstoneRecorder.MACRO, TombstoneRecorder.MONITOR_SLOT,
            TombstoneRecorder.HYPERVISOR_ACCOUNT, TombstoneRecorder.VNC_HOST,
            TombstoneRecorder.VNC_IDENTITY, TombstoneRecorder.CLOUD_ACCOUNT,
            TombstoneRecorder.PORT_FORWARD, TombstoneRecorder.NETWORK_ROUTE,
            TombstoneRecorder.DOCKER_HOST,
            TombstoneRecorder.REGISTRY_CREDENTIAL, TombstoneRecorder.COMPOSE_STACK,
            TombstoneRecorder.SINGLE_CONTAINER_CONFIG, TombstoneRecorder.CONTAINER_AUTO_UPDATE_POLICY,
            TombstoneRecorder.SECRET
        )
        for (type in allTypes) {
            try {
                val rows = liveKeys(type).map { SyncShadow(type, it) }
                shadowDao.clearType(type)
                if (rows.isNotEmpty()) shadowDao.putAll(rows)
            } catch (e: Exception) {
                Logger.w(TAG, "snapshotState failed for type=$type: ${e.message}")
            }
        }
    }

    /**
     * Collect all Keystore-backed credentials.
     *
     * Returns a flat map of alias → plaintext value. SSH private key bytes are
     * base64-encoded and stored under key "ssh_key_{keyId}". The map is empty
     * when neither SecurePasswordManager nor KeyStorage is accessible (e.g. in
     * unit tests). Values are plaintext because the sync payload is AES-GCM
     * encrypted before it leaves the device.
     *
     * Unlike the backup exporter (which always exports everything), each
     * alias family here is gated by the sync toggle that owns its category —
     * a credential whose category is excluded from sync must not leak into
     * the sync payload even though it always belongs in a backup.
     */
    private suspend fun collectSecrets(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val pm: SecurePasswordManager? = app?.securePasswordManager
        val ks: KeyStorage? = app?.keyStorage
        val syncIdentities  = preferenceManager.isSyncIdentitiesEnabled()
        val syncConnections = preferenceManager.isSyncConnectionsEnabled()
        val syncKeys         = preferenceManager.isSyncKeysEnabled()
        val syncHypervisors  = preferenceManager.isSyncHypervisorsEnabled()
        val syncHypervisorAccounts = preferenceManager.isSyncHypervisorAccountsEnabled()
        val syncVncHosts      = preferenceManager.isSyncVncHostsEnabled()
        val syncVncIdentities = preferenceManager.isSyncVncIdentitiesEnabled()
        val syncCloudAccounts = preferenceManager.isSyncCloudAccountsEnabled()
        val syncDocker = preferenceManager.isSyncDockerEnabled()
        try {
            if (pm != null) {
                // Identity passwords — alias: identity_{id}
                if (syncIdentities) {
                    database.identityDao().getAllIdentitiesList().forEach { id ->
                        pm.retrievePassword("identity_${id.id}")
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { out["identity_${id.id}"] = it }
                    }
                }

                // Per-host hypervisor passwords (no linked account)
                if (syncHypervisors) {
                    database.hypervisorDao().getAllList()
                        .filter { it.accountId == null }
                        .forEach { h ->
                            pm.retrievePassword("hypervisor_${h.id}")
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { out["hypervisor_${h.id}"] = it }
                        }
                }

                // Hypervisor account passwords + OCI secrets
                if (syncHypervisorAccounts) {
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
                }

                // VNC identity passwords — alias: vnc_identity_{id}
                if (syncVncIdentities) {
                    database.vncIdentityDao().getAllIdentitiesList().forEach { vi ->
                        pm.retrievePassword("vnc_identity_${vi.id}")
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { out["vnc_identity_${vi.id}"] = it }
                    }
                }

                // VNC host passwords — alias: vnc_host_{id}
                if (syncVncHosts) {
                    database.vncHostDao().getAllHostsList().forEach { vh ->
                        pm.retrievePassword("vnc_host_${vh.id}")
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { out["vnc_host_${vh.id}"] = it }
                    }
                }

                // Cloud account tokens — alias: cloud_token_{id}
                if (syncCloudAccounts) {
                    database.cloudAccountDao().getAll().forEach { ca ->
                        pm.retrievePassword("cloud_token_${ca.id}")
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { out["cloud_token_${ca.id}"] = it }
                    }
                }

                // SSH key passphrases — alias: key_passphrase_{keyId}
                if (syncKeys) {
                    database.keyDao().getAllKeys().first().forEach { key ->
                        pm.retrievePassword("key_passphrase_${key.keyId}")
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { out["key_passphrase_${key.keyId}"] = it }
                    }
                }

                // Docker host custom-endpoint passwords — alias: docker_host_{id}
                if (syncDocker) {
                    database.dockerHostDao().getAllList()
                        .filter { it.usesCustomEndpoint() }
                        .forEach { h ->
                            pm.retrievePassword("docker_host_${h.id}")
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { out["docker_host_${h.id}"] = it }
                        }
                }

                // Registry credential secrets — alias: registry_credential_{id}
                if (syncDocker) {
                    database.registryCredentialDao().getAllList().forEach { c ->
                        pm.retrievePassword("registry_credential_${c.id}")
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { out["registry_credential_${c.id}"] = it }
                    }
                }

                // Connection passwords — the Keystore alias is the bare connection
                // id, which is what the SSH path reads. Wire alias: conn_pw_{id}.
                if (syncConnections) {
                    database.connectionDao().getAllConnections().first()
                        .filter { c -> c.getAuthTypeEnum() == io.github.tabssh.ssh.auth.AuthType.PASSWORD }
                        .forEach { c ->
                            pm.retrievePassword(c.id)
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { out["conn_pw_${c.id}"] = it }
                        }
                }
            }

            // SSH private key JSch bytes — stored as "ssh_key_{keyId}"
            if (ks != null && syncKeys) {
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
            prefs["paste"]          = anyMapToJsonObject(collectPastePreferences())
            prefs["audit"]          = anyMapToJsonObject(collectAuditPreferences())
            prefs["tasker"]         = anyMapToJsonObject(collectTaskerPreferences())
            prefs["logging"]        = anyMapToJsonObject(collectLoggingPreferences())
            prefs["docker"]         = anyMapToJsonObject(collectDockerPreferences())
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
            "lockTimeout" to preferenceManager.getAutoLockTimeout(),
            "passwordTTLHours" to preferenceManager.getPasswordTTLHours(),
            "preventScreenshots" to preferenceManager.isPreventScreenshots()
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
            "terminalBell" to preferenceManager.isBellNotificationEnabled(),
            "lineSpacing" to preferenceManager.getLineSpacing(),
            "reverseScroll" to preferenceManager.isReverseScrollDirection(),
            "bellVibrate" to preferenceManager.isBellVibrate(),
            "bellVisual" to preferenceManager.isBellVisual(),
            "wordWrap" to preferenceManager.isWordWrap(),
            "copyOnSelect" to preferenceManager.isCopyOnSelect()
        )
    }

    private fun collectUIPreferences(): Map<String, Any> {
        return mapOf(
            "maxTabs" to preferenceManager.getMaxTabs(),
            "confirmTabClose" to preferenceManager.isConfirmTabClose(),
            "appTheme" to preferenceManager.getAppTheme(),
            "dynamicColors" to preferenceManager.isDynamicColors(),
            "fullscreenMode" to preferenceManager.isFullscreenMode(),
            "keepScreenOn" to preferenceManager.isKeepScreenOn()
        )
    }

    private fun collectConnectionPreferences(): Map<String, Any> {
        return mapOf(
            "defaultUsername" to preferenceManager.getDefaultUsername(),
            "defaultPort" to preferenceManager.getDefaultPort(),
            "connectTimeout" to preferenceManager.getConnectTimeout(),
            "autoReconnect" to preferenceManager.isAutoReconnect(),
            "compression" to preferenceManager.isCompressionEnabled(),
            "serverAliveIntervalSec" to preferenceManager.getServerAliveIntervalSec(),
            "x11ForwardingDefault" to preferenceManager.isX11ForwardingDefault(),
            "agentForwardingDefault" to preferenceManager.isAgentForwardingDefault(),
            "fileOpenSizeLimitMb" to preferenceManager.getFileOpenSizeLimitMb(),
            // Empty string means "no default route".
            "defaultRouteId" to (preferenceManager.getDefaultRouteId() ?: "")
        )
    }

    private fun collectSyncPreferences(): Map<String, Any> {
        return mapOf(
            "frequency"             to preferenceManager.getSyncFrequency(),
            "wifiOnly"              to preferenceManager.isSyncWifiOnly(),
            "onChangeEnabled"       to preferenceManager.isSyncOnChangeEnabled(),
            "syncConnections"       to preferenceManager.isSyncConnectionsEnabled(),
            "syncKeys"              to preferenceManager.isSyncKeysEnabled(),
            "syncIdentities"        to preferenceManager.isSyncIdentitiesEnabled(),
            "syncSnippets"          to preferenceManager.isSyncSnippetsEnabled(),
            "syncSettings"          to preferenceManager.isSyncSettingsEnabled(),
            "syncThemes"            to preferenceManager.isSyncThemesEnabled(),
            "syncHostKeys"          to preferenceManager.isSyncHostKeysEnabled(),
            "syncGroups"            to preferenceManager.isSyncGroupsEnabled(),
            "syncWorkspaces"        to preferenceManager.isSyncWorkspacesEnabled(),
            "syncMacros"            to preferenceManager.isSyncMacrosEnabled(),
            "syncMonitorSlots"      to preferenceManager.isSyncMonitorSlotsEnabled(),
            "syncHypervisors"       to preferenceManager.isSyncHypervisorsEnabled(),
            "syncHypervisorAccounts" to preferenceManager.isSyncHypervisorAccountsEnabled(),
            "syncVncHosts"          to preferenceManager.isSyncVncHostsEnabled(),
            "syncVncIdentities"     to preferenceManager.isSyncVncIdentitiesEnabled(),
            "syncCloudAccounts"     to preferenceManager.isSyncCloudAccountsEnabled(),
            "syncCertificates"      to preferenceManager.isSyncCertificatesEnabled(),
            "syncDashboard"         to preferenceManager.isSyncDashboardEnabled(),
            "syncPortForwards"      to preferenceManager.isSyncPortForwardsEnabled(),
            "syncNetworkRoutes"     to preferenceManager.isSyncNetworkRoutesEnabled(),
            "syncDocker"            to preferenceManager.isSyncDockerEnabled(),
            "autoResolve"           to preferenceManager.isAutoResolveConflictsEnabled()
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
        "largeTouchTargets" to preferenceManager.isLargeTouchTargets(),
        "screenReader"      to preferenceManager.isScreenReaderEnabled()
    )

    private fun collectPastePreferences(): Map<String, Any> = mapOf(
        "service"        to preferenceManager.getPasteService(),
        "microbinUrl"    to preferenceManager.getPasteMicrobinUrl(),
        "lenpasteUrl"    to preferenceManager.getPasteLenpasteUrl(),
        "stikkedUrl"     to preferenceManager.getPasteStikkedUrl(),
        "pastebinApiKey" to preferenceManager.getPastebinApiKey()
    )

    /** Audit-log policy (preferences_audit.xml) — user policy, so it syncs. */
    private fun collectAuditPreferences(): Map<String, Any> = mapOf(
        "enabled"     to preferenceManager.isAuditLogEnabled(),
        "maxSizeMb"   to preferenceManager.getAuditLogMaxSizeMb(),
        "maxAgeDays"  to preferenceManager.getAuditLogMaxAgeDays(),
        "logCommands" to preferenceManager.isAuditLogCommandsEnabled(),
        "logOutput"   to preferenceManager.isAuditLogOutputEnabled()
    )

    /**
     * Tasker/Locale plugin policy (preferences_tasker.xml). These gate an
     * exported receiver, so they are security policy and must stay identical
     * across the user's devices.
     */
    private fun collectTaskerPreferences(): Map<String, Any> = mapOf(
        "enabled"          to preferenceManager.isTaskerEnabled(),
        "requireUnlock"    to preferenceManager.isTaskerRequireUnlockEnabled(),
        "includeOutput"    to preferenceManager.isTaskerIncludeOutputEnabled(),
        "logEvents"        to preferenceManager.isTaskerLogEventsEnabled(),
        "commandTimeoutMs" to preferenceManager.getTaskerCommandTimeoutMs(),
        // Empty list = all connections allowed. Newline-joined so the value
        // stays a JSON primitive like every other entry in this map.
        "allowedConnections" to preferenceManager.getTaskerAllowedConnections().joinToString("\n")
    )

    /** Diagnostics (preferences_logging.xml). */
    private fun collectLoggingPreferences(): Map<String, Any> = mapOf(
        "debugLogging"      to preferenceManager.isDebugLoggingEnabled(),
        "debugLogLevel"     to preferenceManager.getDebugLogLevel(),
        "logKeystrokeBytes" to preferenceManager.isLogKeystrokeBytesEnabled(),
        "hostLogging"       to preferenceManager.isHostLoggingEnabled(),
        "hostLogMaxSizeMb"  to preferenceManager.getHostLogMaxSizeMb()
    )

    private fun collectDockerPreferences(): Map<String, Any> = mapOf(
        "updateCheckEnabled" to preferenceManager.isDockerUpdateCheckEnabled()
    )

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
            cloudAccounts     = try { database.cloudAccountDao().getAll().size } catch (_: Exception) { 0 },
            dashboard         = try { collectDashboardConfig().size } catch (_: Exception) { 0 },
            portForwards      = try { database.portForwardDao().getAllList().size } catch (_: Exception) { 0 },
            networkRoutes     = try { database.networkRouteDao().getAllList().size } catch (_: Exception) { 0 },
            dockerHosts       = try { database.dockerHostDao().getAllList().size } catch (_: Exception) { 0 },
            registryCredentials = try { database.registryCredentialDao().getAllList().size } catch (_: Exception) { 0 },
            composeStacks     = try { database.composeStackDao().getAllList().size } catch (_: Exception) { 0 },
            singleContainerConfigs = try { database.singleContainerConfigDao().getAllList().size } catch (_: Exception) { 0 },
            containerAutoUpdatePolicies = try { database.containerAutoUpdatePolicyDao().getAllList().size } catch (_: Exception) { 0 }
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
