package io.github.tabssh.storage.registry

import io.github.tabssh.TabSSHApplication
import io.github.tabssh.cloud.CloudProviderType
import io.github.tabssh.cloud.newClient
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.ConnectableHost
import io.github.tabssh.utils.logging.Logger

/**
 * Pull/refresh helper that keeps the internal-only `connectable_hosts`
 * registry in sync with its sources — Hosts-tab [io.github.tabssh
 * .storage.database.entities.ConnectionProfile] rows, TelnetHost rows,
 * ContainerHost endpoints, and live Cloud Account
 * instances. Deliberately does NOT hook `ConnectionDao` insert/update/delete
 * call sites (too many, too easy to miss one) — instead callers (the Panes
 * member picker, and later the pane-launch path) call these functions
 * on-demand to re-pull fresh data before reading the registry.
 */
object ConnectableHostRegistry {
    private const val TAG = "ConnectableHostRegistry"

    /**
     * Reads every [io.github.tabssh.storage.database.entities.ConnectionProfile]
     * (ssh/telnet, mosh included via `moshMode`) and replaces all
     * `connection_profile`-sourced rows in the registry. Cheap full-table
     * read, always correct, zero missed-call-site risk.
     */
    suspend fun refreshConnectionProfiles(db: TabSSHDatabase) {
        val profiles = db.connectionDao().getAllConnectionsList()
        val hosts = profiles.map { profile ->
            ConnectableHost(
                id = profile.id,
                sourceType = ConnectableHost.SOURCE_CONNECTION_PROFILE,
                cloudAccountId = null,
                instanceId = null,
                name = profile.name,
                hostPreview = "${profile.username}@${profile.host}:${profile.port}",
                protocol = profile.protocol
            )
        }
        db.connectableHostDao().deleteBySourceType(ConnectableHost.SOURCE_CONNECTION_PROFILE)
        db.connectableHostDao().insertAll(hosts)
        Logger.d(TAG, "Refreshed ${hosts.size} connection-profile-backed connectable hosts")
    }

    /**
     * Reads every [io.github.tabssh.storage.database.entities.TelnetHost] and
     * replaces all `telnet_host`-sourced rows in the registry. Same cheap
     * full-table-read pattern as [refreshConnectionProfiles].
     */
    suspend fun refreshTelnetHosts(db: TabSSHDatabase) {
        val telnetHosts = db.telnetHostDao().getAllList()
        val hosts = telnetHosts.map { host ->
            ConnectableHost(
                id = host.id,
                sourceType = ConnectableHost.SOURCE_TELNET_HOST,
                cloudAccountId = null,
                instanceId = null,
                name = host.name,
                hostPreview = "${host.username}@${host.host}:${host.port}",
                protocol = "telnet"
            )
        }
        db.connectableHostDao().deleteBySourceType(ConnectableHost.SOURCE_TELNET_HOST)
        db.connectableHostDao().insertAll(hosts)
        Logger.d(TAG, "Refreshed ${hosts.size} telnet-host-backed connectable hosts")
    }

    /**
     * Reads every [io.github.tabssh.storage.database.entities.ContainerHost]
     * with a reachable SSH endpoint (a linked connection profile or a custom
     * host/user endpoint) and replaces all `container_host`-sourced rows in
     * the registry. Registry id = `ContainerHost.ephemeralProfileId()` — the
     * exact alias the container feature already keys its ephemeral profiles
     * and stored credentials on. Hosts with neither a linked connection nor
     * a custom endpoint (e.g. local-socket-only) are skipped: there is no
     * machine to SSH into.
     */
    suspend fun refreshContainerHosts(db: TabSSHDatabase) {
        val containerHosts = db.containerHostDao().getAllList()
        val profilesById = db.connectionDao().getAllConnectionsList().associateBy { it.id }
        val hosts = containerHosts.mapNotNull { host ->
            val preview = when {
                host.linkedConnectionId != null -> {
                    val linked = profilesById[host.linkedConnectionId] ?: return@mapNotNull null
                    "${linked.username}@${linked.host}:${linked.port}"
                }
                host.usesCustomEndpoint() ->
                    "${host.customUsername ?: "root"}@${host.customHost}:${host.customPort ?: 22}"
                else -> return@mapNotNull null
            }
            ConnectableHost(
                id = host.ephemeralProfileId(),
                sourceType = ConnectableHost.SOURCE_CONTAINER_HOST,
                cloudAccountId = null,
                instanceId = null,
                name = host.name,
                hostPreview = preview,
                protocol = "ssh"
            )
        }
        db.connectableHostDao().deleteBySourceType(ConnectableHost.SOURCE_CONTAINER_HOST)
        db.connectableHostDao().insertAll(hosts)
        Logger.d(TAG, "Refreshed ${hosts.size} container-host-backed connectable hosts")
    }

    /**
     * Mirrors the exact token + client pattern used by
     * `CloudAccountsFragment.refreshAccount()`: retrieves the stored bearer
     * token, resolves the provider client, fetches live instances, keeps
     * only instances with a non-null IP, and replaces this account's cached
     * registry rows. Network/auth failures are caught here so refreshing one
     * account never aborts refreshing the rest — same resilience posture as
     * `CloudAccountsFragment.refreshAccount`'s own try/catch.
     */
    suspend fun refreshCloudInstances(db: TabSSHDatabase, app: TabSSHApplication, cloudAccountId: String) {
        val account = db.cloudAccountDao().getById(cloudAccountId)
        if (account == null) {
            Logger.w(TAG, "refreshCloudInstances: no cloud account found for id=$cloudAccountId")
            return
        }
        try {
            val token = app.securePasswordManager.retrievePassword("cloud_token_${account.id}")
            if (token.isNullOrBlank()) {
                Logger.w(TAG, "refreshCloudInstances: no stored token for account=${account.name}")
                return
            }
            val providerType = CloudProviderType.fromTag(account.provider)
            if (providerType == null) {
                Logger.w(TAG, "refreshCloudInstances: unknown provider tag=${account.provider}")
                return
            }
            val instances = providerType.newClient().fetchLiveInstances(token)
                .filter { it.ip != null || it.privateIp != null }
            val hosts = instances.map { instance ->
                val previewAddress = instance.ip ?: instance.privateIp ?: "?"
                ConnectableHost(
                    id = "cloud:${account.id}:${instance.id}",
                    sourceType = ConnectableHost.SOURCE_CLOUD_INSTANCE,
                    cloudAccountId = account.id,
                    instanceId = instance.id,
                    name = instance.name,
                    hostPreview = "$previewAddress (${instance.region ?: providerType.tag})",
                    protocol = "ssh"
                )
            }
            db.connectableHostDao().deleteByCloudAccount(account.id)
            db.connectableHostDao().insertAll(hosts)
            Logger.d(TAG, "Refreshed ${hosts.size} cloud-instance-backed connectable hosts for account=${account.name}")
        } catch (e: Exception) {
            Logger.e(TAG, "refreshCloudInstances failed for account=${account.name}", e)
        }
    }

    /**
     * Convenience entry point for the picker-open call site — refreshes
     * connection-profile rows plus every enabled [io.github.tabssh.storage
     * .database.entities.CloudAccount]'s instances.
     */
    suspend fun refreshAll(db: TabSSHDatabase, app: TabSSHApplication) {
        refreshConnectionProfiles(db)
        refreshTelnetHosts(db)
        refreshContainerHosts(db)
        val enabledAccounts = db.cloudAccountDao().getAll().filter { it.enabled }
        for (account in enabledAccounts) {
            refreshCloudInstances(db, app, account.id)
        }
    }

    /**
     * Eager cascade for a deleted Hosts-tab connection: removes its
     * `connectable_hosts` row and strips its id out of every saved
     * [io.github.tabssh.storage.database.entities.PaneGroup]'s
     * `memberHostIds`. Deleting a pane group never deletes a connection, but
     * the reverse must stay accurate — a deleted connection must not linger
     * as a dangling pane-group member. Call this immediately after every
     * `ConnectionDao` delete (single, bulk, sync-tombstone, sync merge-loss,
     * dedup collapse) — the one exception is a full-table wipe ahead of a
     * backup "replace" restore, where `pane_groups` itself is wiped and
     * reimported atomically, so no dangling-reference window exists.
     */
    suspend fun removeConnectionProfile(db: TabSSHDatabase, connectionId: String) {
        db.connectableHostDao().deleteById(connectionId)
        stripMemberHostId(db, connectionId)
        Logger.d(TAG, "Removed connection-profile-backed connectable host id=$connectionId")
    }

    /**
     * Eager cascade for a deleted [io.github.tabssh.storage.database.entities
     * .TelnetHost]: removes its `connectable_hosts` row and strips its id out
     * of every saved PaneGroup's `memberHostIds`. Same call-site rule as
     * [removeConnectionProfile].
     */
    suspend fun removeTelnetHost(db: TabSSHDatabase, telnetHostId: String) {
        db.connectableHostDao().deleteById(telnetHostId)
        stripMemberHostId(db, telnetHostId)
        Logger.d(TAG, "Removed telnet-host-backed connectable host id=$telnetHostId")
    }

    /**
     * Eager cascade for a deleted [io.github.tabssh.storage.database.entities
     * .ContainerHost]: removes its `connectable_hosts` row and strips its id
     * out of every saved PaneGroup's `memberHostIds`. Same call-site rule as
     * [removeConnectionProfile]. Takes the ContainerHost's numeric row id.
     */
    suspend fun removeContainerHost(db: TabSSHDatabase, containerHostRowId: Long) {
        val registryId = io.github.tabssh.storage.database.entities.ContainerHost.ALIAS_PREFIX + containerHostRowId
        db.connectableHostDao().deleteById(registryId)
        stripMemberHostId(db, registryId)
        Logger.d(TAG, "Removed container-host-backed connectable host id=$registryId")
    }

    /**
     * Eager cascade for a deleted Cloud Account: removes every
     * `connectable_hosts` row sourced from that account and strips each of
     * their ids out of every saved [io.github.tabssh.storage.database
     * .entities.PaneGroup]'s `memberHostIds`. Same call-site rule as
     * [removeConnectionProfile].
     */
    suspend fun removeCloudAccount(db: TabSSHDatabase, cloudAccountId: String) {
        val staleIds = db.connectableHostDao().getAllList()
            .filter { it.sourceType == ConnectableHost.SOURCE_CLOUD_INSTANCE && it.cloudAccountId == cloudAccountId }
            .map { it.id }
        db.connectableHostDao().deleteByCloudAccount(cloudAccountId)
        for (id in staleIds) {
            stripMemberHostId(db, id)
        }
        Logger.d(TAG, "Removed ${staleIds.size} cloud-instance-backed connectable hosts for account=$cloudAccountId")
    }

    private suspend fun stripMemberHostId(db: TabSSHDatabase, hostId: String) {
        val allGroups = db.paneGroupDao().getAllList()
        val affectedGroups = allGroups.filter { group ->
            hostId in group.memberHostIds || group.windows.any { it.hostId == hostId }
        }
        for (group in affectedGroups) {
            // Strip every window referencing this host (a host may appear in
            // more than one window), plus the legacy flat list so
            // resolvedWindows() compat synthesis doesn't resurrect it.
            db.paneGroupDao().update(
                group.copy(
                    memberHostIds = group.memberHostIds - hostId,
                    windows = group.windows.filterNot { it.hostId == hostId }
                )
            )
        }
    }
}
