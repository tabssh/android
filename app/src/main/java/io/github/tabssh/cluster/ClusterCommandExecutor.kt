package io.github.tabssh.cluster
import io.github.tabssh.utils.logging.Logger

import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ssh.connection.SSHConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

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

    /**
     * Tracks the currently-running fan-out so [cancelAll] can interrupt the
     * coroutineScope mid-flight (e.g. the user taps the Cancel button while
     * SSH connects are still being established). Previously this field was
     * declared but never assigned, so `cancelAll()` was a silent no-op and
     * the UI's Cancel button did nothing.
     *
     * `@Volatile` because the assignment in [executeOnCluster] happens on
     * an IO worker but the cancel call comes from the UI thread.
     */
    @Volatile
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

        // Wave 4.e — Live streaming: emit a fresh ExecutionProgress as soon as
        // each host finishes, not when the whole maxConcurrent batch does.
        // Concurrency is still bounded by maxConcurrent via a Semaphore so we
        // don't fan out 10000 hosts at once on a phone.
        val sem = Semaphore(maxConcurrent.coerceAtLeast(1))
        val mutex = Mutex()

        try {
            coroutineScope {
                // Publish the parent Job so cancelAll() can interrupt the
                // whole fan-out, not just observe it as a no-op.
                executionJob = coroutineContext[Job]
                val deferreds = connections.map { profile ->
                    async {
                        sem.withPermit {
                            val r = executeOnSingleServer(profile, command, timeoutMs)
                            mutex.withLock {
                                results.add(r)
                                val succeeded = results.count { it.success }
                                val failed = results.count { !it.success }
                                _progress.value = ExecutionProgress(
                                    total = connections.size,
                                    completed = results.size,
                                    inProgress = connections.size - results.size,
                                    succeeded = succeeded,
                                    failed = failed,
                                    results = results.toList()
                                )
                            }
                        }
                    }
                }
                deferreds.awaitAll()
            }
        } finally {
            executionJob = null
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
        
        // Hoist connection + scope so the finally{} block guarantees teardown
        // even if connect()/executeCommand() throws (network error, auth, timeout).
        // Previously a throw from connect() leaked the JSch Session and the
        // SupervisorJob scope until process death.
        var connection: SSHConnection? = null
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return try {
            withTimeout(timeoutMs) {
                val c = SSHConnection(profile, scope, app)
                connection = c
                // Inherit the app-wide host-key callbacks (set on
                // SSHSessionManager by TabSSHApplication). Without them
                // JSch rejects any host whose fingerprint isn't already
                // pinned in host_keys, which made cluster commands fail
                // for any not-yet-trusted server in the list. Now the
                // standard "host key changed" / "first time seeing this
                // host" dialog fires inline and the user can accept on
                // the spot — same UX as a normal connect.
                c.hostKeyChangedCallback = app.sshSessionManager.hostKeyChangedCallback
                c.newHostKeyCallback = app.sshSessionManager.newHostKeyCallback
                c.connect()
                val output = c.executeCommand(command)

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
        } finally {
            // NonCancellable: guarantee JSch teardown even when the outer
            // coroutine is cancelled (e.g. cancelAll() fires mid-connect).
            withContext(NonCancellable) {
                try {
                    connection?.disconnect()
                } catch (e: Exception) {
                    Logger.w("ClusterCommand", "Disconnect after error failed: ${e.message}")
                }
            }
            scope.cancel()
        }
    }

    fun cancelAll() {
        executionJob?.cancel()
        Logger.i("ClusterCommand", "All executions cancelled")
    }
}
