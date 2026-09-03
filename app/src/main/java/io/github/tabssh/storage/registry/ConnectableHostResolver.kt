package io.github.tabssh.storage.registry

import io.github.tabssh.TabSSHApplication
import io.github.tabssh.cloud.CloudProviderType
import io.github.tabssh.cloud.newClient
import io.github.tabssh.containers.ContainerSessionManager
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.storage.database.entities.ConnectableHost
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.ContainerHost
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Resolves a [ConnectableHost] registry row to a launch-ready
 * [ConnectionProfile] — the single resolution path shared by every feature
 * that accepts registry-backed hosts (Panes, Multi-Host Monitoring, Port
 * Forwarding). Extracted from TabTerminalActivity.connectPaneMember so all
 * callers resolve identically:
 *
 * - connection-profile rows load the saved profile by id;
 * - cloud-instance rows re-fetch the live IP and build an ephemeral,
 *   never-persisted profile (creds come from the same
 *   `cloud_host_creds_{account}_{instance}` store CloudAccountManagerActivity
 *   writes);
 * - telnet rows build an ephemeral telnet profile sharing the TelnetHost's
 *   id so stored passwords resolve through the standard alias;
 * - container-host rows SSH to the machine running the container engine —
 *   the linked connection profile when one is set, otherwise the container
 *   feature's own ephemeral custom-endpoint profile
 *   ([ContainerSessionManager.resolveCustomProfile]).
 *
 * Invariant: every resolved profile satisfies `profile.id == host.id`
 * (saved/telnet rows share ids with their registry rows by construction,
 * container profiles use [ContainerHost.ephemeralProfileId] which IS the
 * registry id, and cloud ephemerals adopt the registry id explicitly), so
 * callers can key session pools, metric maps, and credential lookups on a
 * single id space.
 *
 * Returns null (never throws, except on cancellation) on any resolution
 * failure so callers can skip the member and continue.
 */
object ConnectableHostResolver {
    private const val TAG = "ConnectableHostResolver"

    /** Keystore alias for per-cloud-instance SSH credentials JSON. */
    fun cloudHostCredKey(accountId: String, instanceId: String) =
        "cloud_host_creds_${accountId}_${instanceId}"

    suspend fun resolveProfile(app: TabSSHApplication, host: ConnectableHost): ConnectionProfile? {
        return when (host.sourceType) {
            ConnectableHost.SOURCE_CONNECTION_PROFILE -> {
                withContext(Dispatchers.IO) { app.database.connectionDao().getConnectionById(host.id) }
                    ?: run {
                        Logger.w(TAG, "resolveProfile: connection profile ${host.id} not found")
                        null
                    }
            }
            ConnectableHost.SOURCE_CLOUD_INSTANCE -> resolveCloudInstance(app, host)
            ConnectableHost.SOURCE_TELNET_HOST -> {
                val telnetHost = withContext(Dispatchers.IO) { app.database.telnetHostDao().getById(host.id) }
                    ?: run {
                        Logger.w(TAG, "resolveProfile: telnet host ${host.id} not found")
                        return null
                    }
                // Ephemeral, unsaved ConnectionProfile — same id as the TelnetHost
                // row so a saved password (Keystore alias = bare id) is picked up
                // transparently by the normal SSH/Telnet connect path.
                ConnectionProfile(
                    id = telnetHost.id,
                    name = telnetHost.name,
                    host = telnetHost.host,
                    port = telnetHost.port,
                    username = telnetHost.username,
                    protocol = "telnet",
                    savePassword = telnetHost.savePassword
                )
            }
            ConnectableHost.SOURCE_CONTAINER_HOST -> resolveContainerHost(app, host)
            else -> {
                Logger.w(TAG, "resolveProfile: unknown source type ${host.sourceType}")
                null
            }
        }
    }

    private suspend fun resolveCloudInstance(app: TabSSHApplication, host: ConnectableHost): ConnectionProfile? {
        val accountId = host.cloudAccountId
        val instanceId = host.instanceId
        if (accountId == null || instanceId == null) {
            Logger.w(TAG, "resolveProfile: cloud host ${host.id} missing accountId/instanceId")
            return null
        }
        val account = withContext(Dispatchers.IO) { app.database.cloudAccountDao().getById(accountId) }
        if (account == null) {
            Logger.w(TAG, "resolveProfile: cloud account $accountId not found")
            return null
        }
        val token = withContext(Dispatchers.IO) {
            app.securePasswordManager.retrievePassword("cloud_token_${account.id}")
        }
        if (token.isNullOrBlank()) {
            Logger.w(TAG, "resolveProfile: no stored token for cloud account ${account.name}")
            return null
        }
        val providerType = CloudProviderType.fromTag(account.provider)
        if (providerType == null) {
            Logger.w(TAG, "resolveProfile: unknown provider tag ${account.provider}")
            return null
        }
        val liveInstance = try {
            withContext(Dispatchers.IO) { providerType.newClient().fetchLiveInstances(token) }
                .firstOrNull { it.id == instanceId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "resolveProfile: fetchLiveInstances failed for ${account.name}", e)
            null
        }
        val ip = liveInstance?.ip ?: liveInstance?.privateIp
        if (liveInstance == null || ip.isNullOrBlank()) {
            Logger.w(TAG, "resolveProfile: no live IP for cloud instance $instanceId")
            return null
        }
        val credsJson = withContext(Dispatchers.IO) {
            app.securePasswordManager.retrievePassword(cloudHostCredKey(account.id, instanceId))
        }
        val creds = credsJson?.let {
            runCatching { JSONObject(it) }
                .onFailure { e -> Logger.w(TAG, "resolveProfile: stored creds JSON corrupt", e) }
                .getOrNull()
        }
        val username = creds?.optString("username").takeIf { !it.isNullOrBlank() } ?: "root"
        val password = creds?.optString("password").takeIf { !it.isNullOrBlank() }
        val identityId = creds?.optString("identityId").takeIf { !it.isNullOrBlank() }
        val port = creds?.optInt("port", 22)?.takeIf { it in 1..65535 } ?: 22
        // The ephemeral profile reuses the registry row's id (and the
        // session-only password alias matches it) so every resolved profile,
        // regardless of source, satisfies `profile.id == host.id` — callers
        // (pane launch, monitoring pumps, port forwards) can key session
        // pools, metric maps, and credential lookups on one id space.
        if (password != null) {
            withContext(Dispatchers.IO) {
                app.securePasswordManager.storePassword(
                    host.id, password, SecurePasswordManager.StorageLevel.SESSION_ONLY
                )
            }
        }
        return ConnectionProfile(
            id = host.id,
            name = liveInstance.name,
            host = ip,
            port = port,
            username = username,
            identityId = identityId
        )
    }

    private suspend fun resolveContainerHost(app: TabSSHApplication, host: ConnectableHost): ConnectionProfile? {
        val rowId = host.id.removePrefix(ContainerHost.ALIAS_PREFIX).toLongOrNull()
        if (rowId == null) {
            Logger.w(TAG, "resolveProfile: malformed container-host registry id ${host.id}")
            return null
        }
        val containerHost = withContext(Dispatchers.IO) { app.database.containerHostDao().getById(rowId) }
        if (containerHost == null) {
            Logger.w(TAG, "resolveProfile: container host $rowId not found")
            return null
        }
        // Same precedence as ContainerSessionManager.acquire: a linked saved
        // connection wins; otherwise the custom endpoint's ephemeral profile.
        val linkedId = containerHost.linkedConnectionId
        if (linkedId != null) {
            val linked = withContext(Dispatchers.IO) { app.database.connectionDao().getConnectionById(linkedId) }
            if (linked != null) return linked
            Logger.w(TAG, "resolveProfile: linked connection $linkedId for container host $rowId not found")
            return null
        }
        return withContext(Dispatchers.IO) { ContainerSessionManager.resolveCustomProfile(app, containerHost) }
            ?: run {
                Logger.w(TAG, "resolveProfile: container host $rowId has no usable SSH endpoint")
                null
            }
    }
}
