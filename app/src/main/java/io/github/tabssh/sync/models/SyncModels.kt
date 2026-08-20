package io.github.tabssh.sync.models

import io.github.tabssh.storage.database.entities.CloudAccount
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HostKeyEntry
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.storage.database.entities.ThemeDefinition
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject

/**
 * Counts of synced items
 */
@Serializable
data class SyncItemCounts(
    val connections: Int = 0,
    val keys: Int = 0,
    val themes: Int = 0,
    val preferences: Int = 0,
    val hostKeys: Int = 0,
    // Wave 5.3
    val workspaces: Int = 0,
    // Wave 5.4
    val snippets: Int = 0,
    // Wave 5.4
    val identities: Int = 0,
    // Wave 5.4
    val groups: Int = 0,
    // Wave 7.1
    val hypervisors: Int = 0,
    // Wave 7.1
    val certificates: Int = 0,
    // Wave 11
    val macros: Int = 0,
    // Wave 11
    val monitorSlots: Int = 0,
    /** Wave 12 (2026-05-16 audit) — reusable hypervisor credential metadata.
     *  Token/password remains Keystore-bound and is NOT synced. */
    val hypervisorAccounts: Int = 0,
    /** Wave 13 (2026-05-17) — direct VNC hosts and VNC credential metadata. */
    val vncHosts: Int = 0,
    val vncIdentities: Int = 0,
    /** Wave 14 (2026-05-21) — cloud provider account metadata (token stays Keystore-bound). */
    val cloudAccounts: Int = 0,
    /** Multi-host dashboard config — groups and host membership from SharedPreferences. */
    val dashboard: Int = 0,
    /** Saved SSH port-forward rules. */
    val portForwards: Int = 0,
    /** Reusable network routes (proxies and SSH jump hosts). */
    val networkRoutes: Int = 0,
    /** Container subsystem: hosts, registry credentials, compose stacks, single-container
     *  run configs, and auto-update policies.
     *  `@JsonNames("dockerHosts")` accepts the development build's old field name on read;
     *  writes always emit `containerHosts`. */
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("dockerHosts")
    val containerHosts: Int = 0,
    val registryCredentials: Int = 0,
    val composeStacks: Int = 0,
    val singleContainerConfigs: Int = 0,
    val containerAutoUpdatePolicies: Int = 0
) {
    fun total(): Int = connections + keys + themes + preferences + hostKeys +
        workspaces + snippets + identities + groups + hypervisors + certificates +
        macros + monitorSlots + hypervisorAccounts + vncHosts + vncIdentities +
        cloudAccounts + dashboard + portForwards + networkRoutes + containerHosts + registryCredentials +
        composeStacks + singleContainerConfigs + containerAutoUpdatePolicies
}

/**
 * Metadata for a sync operation
 */
@Serializable
data class SyncMetadata(
    val deviceId: String,
    val deviceName: String,
    val deviceModel: String,
    val appVersion: String,
    val syncTimestamp: Long,
    val syncVersion: Long,
    val formatVersion: Int = 2,
    val encryptionVersion: Int = 1,
    val itemCounts: SyncItemCounts
)

/**
 * Package of data to sync
 */
@Serializable
data class SyncDataPackage(
    val connections: List<ConnectionProfile> = emptyList(),
    val keys: List<StoredKey> = emptyList(),
    val themes: List<ThemeDefinition> = emptyList(),
    val preferences: Map<String, JsonElement> = emptyMap(),
    val hostKeys: List<HostKeyEntry> = emptyList(),
    val metadata: SyncMetadata,
    /** Wave 5.3 — workspaces sync as plain last-write-wins. */
    val workspaces: List<io.github.tabssh.storage.database.entities.Workspace> = emptyList(),
    /** Wave 5.4 — snippets / identities / groups, last-write-wins. */
    val snippets: List<io.github.tabssh.storage.database.entities.Snippet> = emptyList(),
    val identities: List<io.github.tabssh.storage.database.entities.Identity> = emptyList(),
    val groups: List<io.github.tabssh.storage.database.entities.ConnectionGroup> = emptyList(),
    /** Wave 7.1 — hypervisors / trusted_certificates, last-write-wins.
     *  Caveat: HypervisorProfile uses an autoGenerate Long PK, so cross-device
     *  ID collisions could overwrite an unrelated row on the destination.
     *  Mitigation: typical users have ≤ 5 hypervisors and rarely sync mid-edit.
     *  Documented in AI.md §9.4. */
    val hypervisors: List<io.github.tabssh.storage.database.entities.HypervisorProfile> = emptyList(),
    val certificates: List<io.github.tabssh.storage.database.entities.TrustedCertificate> = emptyList(),
    /** Wave 11 — macros / monitor_slots, last-write-wins. */
    val macros: List<io.github.tabssh.storage.database.entities.Macro> = emptyList(),
    val monitorSlots: List<io.github.tabssh.storage.database.entities.MonitorSlot> = emptyList(),
    /** Wave 12 (2026-05-16 audit) — reusable hypervisor credential metadata.
     *  Sync covers the row (name/username/realm); the password itself stays
     *  Keystore-bound on each device under `hypervisor_account_${id}`. */
    val hypervisorAccounts: List<io.github.tabssh.storage.database.entities.HypervisorAccount> = emptyList(),
    /** Wave 13 (2026-05-17) — direct VNC hosts (metadata only; Keystore password not transferred)
     *  and VNC identity metadata rows. */
    val vncHosts: List<io.github.tabssh.storage.database.entities.VncHost> = emptyList(),
    val vncIdentities: List<io.github.tabssh.storage.database.entities.VncIdentity> = emptyList(),
    /** Wave 14 — cloud provider account metadata. Token stays Keystore-bound on each
     *  device under `cloud_token_${id}` and is transferred via the secrets map only
     *  in encrypted sync payloads. */
    val cloudAccounts: List<CloudAccount> = emptyList(),
    /** Multi-host dashboard groups and host membership from the `multi_host_dashboard`
     *  SharedPreferences file.  Map keys are raw SharedPrefs keys (e.g.
     *  `dash_groups_json`, `dash_hosts_<groupId>`); values are type-tagged by
     *  [io.github.tabssh.utils.SharedPrefsCodec] because the file mixes strings
     *  with a Boolean.
     *  Empty when the sync_dashboard switch is off (per-device, the default). */
    val dashboardConfig: Map<String, JsonObject> = emptyMap(),
    /** Named SharedPreferences files holding user data outside the Room DB:
     *  `TabSSH` (host list sort orders), `cluster_commands` (saved cluster
     *  command history), `snippet_var_recall` (last-used snippet variable
     *  values). Outer key is the SharedPreferences file name, inner map is that
     *  file's raw key → type-tagged value ([io.github.tabssh.utils.SharedPrefsCodec];
     *  `cluster_commands` stores a Set<String>). Gated by the sync_settings toggle. */
    val namedPreferenceFiles: Map<String, Map<String, JsonObject>> = emptyMap(),
    /** Saved SSH port-forward rules, last-write-wins REPLACE on UUID PK. */
    val portForwards: List<io.github.tabssh.storage.database.entities.PortForward> = emptyList(),
    /** Reusable network routes (proxies and SSH jump hosts), last-write-wins REPLACE on UUID PK. */
    val networkRoutes: List<io.github.tabssh.storage.database.entities.NetworkRoute> = emptyList(),
    /** Container subsystem — all use autoGenerate Long PKs, so cross-device ID collisions
     *  are possible (same caveat as HypervisorProfile above, documented in AI.md §9.4).
     *  ContainerHost's custom-endpoint SSH password and RegistryCredential's secret stay
     *  Keystore-bound under `container_host_{id}` / `registry_credential_{id}` and travel
     *  via the secrets map only.
     *  `@JsonNames("dockerHosts")` accepts the development build's old field name on read;
     *  writes always emit `containerHosts`. */
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("dockerHosts")
    val containerHosts: List<io.github.tabssh.storage.database.entities.ContainerHost> = emptyList(),
    val registryCredentials: List<io.github.tabssh.storage.database.entities.RegistryCredential> = emptyList(),
    val composeStacks: List<io.github.tabssh.storage.database.entities.ComposeStack> = emptyList(),
    val singleContainerConfigs: List<io.github.tabssh.storage.database.entities.SingleContainerConfig> = emptyList(),
    val containerAutoUpdatePolicies: List<io.github.tabssh.storage.database.entities.ContainerAutoUpdatePolicy> = emptyList(),
    /** Keystore-backed credentials — included only in encrypted sync payloads.
     *  Map keys are alias names; values are plaintext (safe inside AES-GCM envelope).
     *  SSH private key material is base64-encoded JSch bytes under key
     *  `"ssh_key_{keyId}"`. All other entries are password/token strings.
     *  Default empty so old sync files (without this field) deserialize cleanly. */
    val secrets: Map<String, String> = emptyMap(),
    /** H6 — soft-delete tombstones. Each row records that a synced entity was
     *  deleted (entity_type + stable cross-device key + deletedAt + device_id),
     *  so a peer applying this payload removes its own copy instead of the
     *  upload-only union resurrecting it. Default empty so old sync files
     *  (without this field) deserialize cleanly. */
    val tombstones: List<io.github.tabssh.storage.database.entities.SyncTombstone> = emptyList()
)

/**
 * Encrypted data container
 */
data class EncryptedData(
    val ciphertext: ByteArray,
    val iv: ByteArray,
    val salt: ByteArray,
    val authTag: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EncryptedData

        if (!ciphertext.contentEquals(other.ciphertext)) return false
        if (!iv.contentEquals(other.iv)) return false
        if (!salt.contentEquals(other.salt)) return false
        if (authTag != null) {
            if (other.authTag == null) return false
            if (!authTag.contentEquals(other.authTag)) return false
        } else if (other.authTag != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + (authTag?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Merge strategy options
 */
enum class MergeStrategy {
    MERGE,
    KEEP_LOCAL,
    KEEP_REMOTE,
    KEEP_BOTH,
    SKIP
}

/**
 * Conflict types
 */
enum class ConflictType {
    FIELD_MODIFIED_BOTH_SIDES,
    DELETED_MODIFIED,
    CREATED_DUPLICATE,
    PREFERENCE_DIVERGED
}

/**
 * A conflict between local and remote data
 */
data class Conflict(
    val entityType: String,
    val entityId: String,
    val conflictType: ConflictType,
    val field: String? = null,
    val localValue: Any? = null,
    val remoteValue: Any? = null,
    val baseValue: Any? = null,
    /** Full local entity, when available — [ConflictResolver] applies from
     *  this rather than [localValue], which for FIELD_MODIFIED_BOTH_SIDES
     *  conflicts holds only the raw scalar of the conflicting field. */
    val localEntity: Any? = null,
    /** Full remote entity, when available — see [localEntity]. */
    val remoteEntity: Any? = null,
    val localTimestamp: Long = 0,
    val remoteTimestamp: Long = 0,
    val autoResolvable: Boolean = false,
    val description: String = ""
) {
    /**
     * Options offered by the picker for this conflict. Host keys can only
     * ever have one trusted fingerprint per host, so — despite sharing
     * FIELD_MODIFIED_BOTH_SIDES with connection/key/theme field conflicts —
     * `host_key` never offers KEEP_BOTH. SKIP is dropped for
     * FIELD_MODIFIED_BOTH_SIDES entirely: the picker always resolves to a
     * definite outcome (keep local / keep remote / keep both) rather than
     * leaving the field unresolved.
     */
    fun getResolutionOptions(): List<ConflictResolutionOption> {
        return when (conflictType) {
            ConflictType.FIELD_MODIFIED_BOTH_SIDES -> if (entityType == "host_key") {
                listOf(
                    ConflictResolutionOption.KEEP_LOCAL,
                    ConflictResolutionOption.KEEP_REMOTE
                )
            } else {
                listOf(
                    ConflictResolutionOption.KEEP_LOCAL,
                    ConflictResolutionOption.KEEP_REMOTE,
                    ConflictResolutionOption.KEEP_BOTH
                )
            }
            ConflictType.DELETED_MODIFIED -> listOf(
                ConflictResolutionOption.KEEP_LOCAL,
                ConflictResolutionOption.KEEP_REMOTE
            )
            ConflictType.CREATED_DUPLICATE -> listOf(
                ConflictResolutionOption.KEEP_LOCAL,
                ConflictResolutionOption.KEEP_REMOTE,
                ConflictResolutionOption.KEEP_BOTH
            )
            ConflictType.PREFERENCE_DIVERGED -> listOf(
                ConflictResolutionOption.KEEP_LOCAL,
                ConflictResolutionOption.KEEP_REMOTE
            )
        }
    }

    /**
     * LWW preselection: the side with the newer timestamp wins. Equal (or
     * both-zero) timestamps default to keep-local — mirrors
     * [io.github.tabssh.sync.merge.ConflictResolver.autoResolveConflicts]'s
     * comparison exactly so the picker's default matches what auto-resolve
     * would have chosen.
     */
    fun preselectedResolution(): ConflictResolutionOption =
        if (remoteTimestamp > localTimestamp) {
            ConflictResolutionOption.KEEP_REMOTE
        } else {
            ConflictResolutionOption.KEEP_LOCAL
        }
}

/**
 * Conflict resolution options
 */
enum class ConflictResolutionOption {
    KEEP_LOCAL,
    KEEP_REMOTE,
    KEEP_BOTH,
    SKIP
}

/**
 * Result of a conflict resolution
 */
data class ConflictResolution(
    val conflict: Conflict,
    val resolution: ConflictResolutionOption,
    val applyToAll: Boolean = false
)

/**
 * Result of a merge operation
 */
data class MergeResult<T>(
    val merged: List<T>,
    val conflicts: List<Conflict> = emptyList(),
    val deleted: List<String> = emptyList(),
    val added: List<T> = emptyList(),
    val updated: List<T> = emptyList()
) {
    fun hasConflicts(): Boolean = conflicts.isNotEmpty()

    fun isSuccessful(): Boolean = conflicts.isEmpty()
}

/**
 * Field-level conflict information
 */
data class FieldConflict(
    val field: String,
    val baseValue: Any?,
    val localValue: Any?,
    val remoteValue: Any?,
    val localTimestamp: Long = 0,
    val remoteTimestamp: Long = 0
)

/**
 * Sync configuration
 */
data class SyncConfiguration(
    val enabled: Boolean = false,
    val wifiOnly: Boolean = true,
    val syncFrequencyMinutes: Int = 60,
    val syncConnections: Boolean = true,
    val syncKeys: Boolean = true,
    val syncSettings: Boolean = true,
    val syncThemes: Boolean = true,
    val autoResolveConflicts: Boolean = true,
    val requiresCharging: Boolean = false,
    val batteryNotLowRequired: Boolean = true
)

/**
 * Device information for sync
 */
data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val deviceModel: String,
    val androidVersion: String,
    val appVersion: String
)
