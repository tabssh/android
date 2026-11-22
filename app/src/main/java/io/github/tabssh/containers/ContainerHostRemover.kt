package io.github.tabssh.containers

import android.content.Context
import androidx.room.withTransaction
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ContainerHost
import kotlinx.coroutines.flow.first

/** Removes a container host together with everything that hangs off it (stacks, configs, update policies). */
object ContainerHostRemover {

    suspend fun remove(app: TabSSHApplication, appContext: Context, host: ContainerHost) {
            // Non-DB teardown stays outside the transaction —
            // session release and Keystore clears must not hold
            // the DB transaction open.
            ContainerSessionManager.release(app, host.id)
            // Custom-endpoint password lives only in the
            // Keystore — clear it so a future row id reuse
            // can't inherit this host's secret.
            io.github.tabssh.crypto.storage.ContainerHostPasswordStore
                .clear(appContext, host.id)
            // Convention cascade — the docker_* child tables have
            // no real FKs, so clean them up alongside the host.
            app.database.withTransaction {
                // Tombstone every cascaded child before the bulk
                // delete removes it — deleteForHost() gives back
                // no rows, so the natural keys must be captured first.
                app.database.composeStackDao().getStacksForHostList(host.id).forEach { stack ->
                    io.github.tabssh.sync.tombstone.TombstoneRecorder.record(
                        appContext, io.github.tabssh.sync.tombstone.TombstoneRecorder.COMPOSE_STACK,
                        io.github.tabssh.sync.tombstone.TombstoneRecorder.naturalKey(stack))
                }
                app.database.singleContainerConfigDao().getConfigsForHostList(host.id).forEach { config ->
                    io.github.tabssh.sync.tombstone.TombstoneRecorder.record(
                        appContext, io.github.tabssh.sync.tombstone.TombstoneRecorder.SINGLE_CONTAINER_CONFIG,
                        io.github.tabssh.sync.tombstone.TombstoneRecorder.naturalKey(config))
                }
                app.database.containerAutoUpdatePolicyDao().getPoliciesForHost(host.id).first().forEach { policy ->
                    io.github.tabssh.sync.tombstone.TombstoneRecorder.record(
                        appContext, io.github.tabssh.sync.tombstone.TombstoneRecorder.CONTAINER_AUTO_UPDATE_POLICY,
                        io.github.tabssh.sync.tombstone.TombstoneRecorder.naturalKey(policy))
                }
                app.database.composeStackDao().deleteForHost(host.id)
                app.database.singleContainerConfigDao().deleteForHost(host.id)
                app.database.containerAutoUpdatePolicyDao().deleteForHost(host.id)
                app.database.containerHostDao().delete(host)
                // Cascade: drop the connectable-hosts registry row and strip the id from saved pane groups
                io.github.tabssh.storage.registry.ConnectableHostRegistry.removeContainerHost(app.database, host.id)
                io.github.tabssh.sync.tombstone.TombstoneRecorder.record(
                    appContext, io.github.tabssh.sync.tombstone.TombstoneRecorder.CONTAINER_HOST,
                    io.github.tabssh.sync.tombstone.TombstoneRecorder.naturalKey(host))
            }
        io.github.tabssh.storage.registry.HostLinkInspector.stripFromDashboards(
            appContext, ContainerHost.ALIAS_PREFIX + host.id
        )
    }
}
