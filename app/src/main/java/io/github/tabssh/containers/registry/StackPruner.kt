package io.github.tabssh.containers.registry

import android.content.Context
import androidx.room.withTransaction
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.containers.transport.ContainerTransport
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.ContainerHost
import io.github.tabssh.sync.tombstone.TombstoneRecorder
import io.github.tabssh.utils.logging.Logger

/**
 * Forgets compose stacks whose directory no longer exists on the host. A stopped stack is kept
 * (its directory is still there), but a stack whose directory was deleted outside the app has
 * nothing left to update or start, so its tracked row, tombstone-recorded deletion and update
 * policies are removed. Only a definite "no such directory" removes anything: a permission or
 * transport failure leaves the stack alone.
 */
class StackPruner(private val context: Context, private val database: TabSSHDatabase) {

    /** Removes every tracked stack of [host] whose directory is gone; returns the removed stack names. */
    suspend fun prune(host: ContainerHost, transport: ContainerTransport): List<String> {
        val removed = mutableListOf<String>()
        for (stack in database.composeStackDao().getStacksForHostList(host.id)) {
            if (directoryExists(transport, stack.remotePath)) continue
            val policyDao = database.containerAutoUpdatePolicyDao()
            database.withTransaction {
                database.composeStackDao().delete(stack)
                TombstoneRecorder.record(
                    context.applicationContext,
                    TombstoneRecorder.COMPOSE_STACK,
                    TombstoneRecorder.naturalKey(stack)
                )
                policyDao.getAllList()
                    .filter {
                        it.containerHostId == host.id &&
                            it.scope == HostUpdateScanner.SCOPE_STACK &&
                            it.containerNameOrStackName == stack.name
                    }
                    .forEach { policyDao.deleteById(it.id) }
            }
            Logger.i(TAG, "Removed stack ${stack.name}: ${stack.remotePath} no longer exists on ${host.name}")
            removed += stack.name
        }
        return removed
    }

    companion object {
        private const val TAG = "StackPruner"

        /** False only when the host definitively reports that [path] does not exist. */
        suspend fun directoryExists(transport: ContainerTransport, path: String): Boolean =
            transport.listRemoteDir(path) !is ContainerResult.NotFound
    }
}
