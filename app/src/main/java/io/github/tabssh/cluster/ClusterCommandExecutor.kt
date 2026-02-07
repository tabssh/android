package io.github.tabssh.cluster
import io.github.tabssh.utils.logging.Logger

import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ssh.connection.SSHConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Execute commands on multiple servers simultaneously
 */
class ClusterCommandExecutor(private val app: TabSSHApplication) {

    data class ExecutionResult(
        val profile: ConnectionProfile,
        val success: Boolean,
        val output: String,
        val error: String? = null,
        val executionTimeMs: Long
    )

    data class ExecutionProgress(
        val total: Int,
        val completed: Int,
        val inProgress: Int,
        val succeeded: Int,
        val failed: Int,
        val results: List<ExecutionResult> = emptyList()
    )

    private val _progress = MutableStateFlow(ExecutionProgress(0, 0, 0, 0, 0))
    val progress: StateFlow<ExecutionProgress> = _progress.asStateFlow()

    private var executionJob: Job? = null

    suspend fun executeOnCluster(
        connections: List<ConnectionProfile>,
        command: String,
        timeoutMs: Long = 60000L,
        maxConcurrent: Int = 10
    ): List<ExecutionResult> = withContext(Dispatchers.IO) {
        
        Logger.i("ClusterCommand", "Executing on ${connections.size} servers: $command")
        
        val results = mutableListOf<ExecutionResult>()
        
        _progress.value = ExecutionProgress(
            total = connections.size,
            completed = 0,
            inProgress = 0,
            succeeded = 0,
            failed = 0
        )

        connections.chunked(maxConcurrent).forEach { batch ->
            val batchResults = batch.map { profile ->
                async {
                    executeOnSingleServer(profile, command, timeoutMs)
                }
            }.awaitAll()
            
            results.addAll(batchResults)
            
            val succeeded = results.count { it.success }
            val failed = results.count { !it.success}
            
            _progress.value = ExecutionProgress(
                total = connections.size,
                completed = results.size,
                inProgress = connections.size - results.size,
                succeeded = succeeded,
                failed = failed,
                results = results.toList()
            )
        }

        Logger.i("ClusterCommand", "Complete: ${results.count { it.success }}/${connections.size} succeeded")
        
        results
    }

    private suspend fun executeOnSingleServer(
        profile: ConnectionProfile,
        command: String,
        timeoutMs: Long
    ): ExecutionResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            withTimeout(timeoutMs) {
                val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                val connection = SSHConnection(profile, scope, app)
                connection.connect()
                val output = connection.executeCommand(command)
                connection.disconnect()

                val executionTime = System.currentTimeMillis() - startTime
                
                ExecutionResult(
                    profile = profile,
                    success = true,
                    output = output,
                    executionTimeMs = executionTime
                )
            }
        } catch (e: TimeoutCancellationException) {
            val executionTime = System.currentTimeMillis() - startTime
            
            ExecutionResult(
                profile = profile,
                success = false,
                output = "",
                error = "Timeout after ${timeoutMs}ms",
                executionTimeMs = executionTime
            )
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            Logger.e("ClusterCommand", "Failed on ${profile.name}", e)
            
            ExecutionResult(
                profile = profile,
                success = false,
                output = "",
                error = e.message ?: "Unknown error",
                executionTimeMs = executionTime
            )
        }
    }

    fun cancelAll() {
        executionJob?.cancel()
        Logger.i("ClusterCommand", "All executions cancelled")
    }
}
