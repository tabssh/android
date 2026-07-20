package io.github.tabssh.automation

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * WorkManager replacement for the old `TaskerIntentService`. Dispatched
 * from [TaskerActionReceiver] which translates the broadcast intent
 * into [androidx.work.Data].
 *
 * Action constants are kept as `io.github.tabssh.action.*` to preserve
 * the public Tasker integration contract — Tasker users have these
 * strings configured in their tasks and we must not break them.
 *
 * Broadcasts (`event.*`) are emitted directly with `sendBroadcast` so
 * Tasker / Locale plug-ins listening for them keep working.
 */
class TaskerWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "TaskerWorker"

        // Public actions — Tasker tasks reference these strings, do not rename.
        const val ACTION_CONNECT = "io.github.tabssh.action.CONNECT"
        const val ACTION_DISCONNECT = "io.github.tabssh.action.DISCONNECT"
        const val ACTION_SEND_COMMAND = "io.github.tabssh.action.SEND_COMMAND"
        const val ACTION_SEND_KEYS = "io.github.tabssh.action.SEND_KEYS"

        // Public events.
        const val EVENT_CONNECTED = "io.github.tabssh.event.CONNECTED"
        const val EVENT_DISCONNECTED = "io.github.tabssh.event.DISCONNECTED"
        const val EVENT_COMMAND_RESULT = "io.github.tabssh.event.COMMAND_RESULT"
        const val EVENT_ERROR = "io.github.tabssh.event.ERROR"

        // Data keys (also reused as intent extra keys for back-compat).
        const val KEY_ACTION = "action"
        const val KEY_CONNECTION_ID = "connection_id"
        const val KEY_CONNECTION_NAME = "connection_name"
        const val KEY_COMMAND = "command"
        const val KEY_KEYS = "keys"
        const val KEY_RESULT = "result"
        const val KEY_ERROR = "error"
        const val KEY_WAIT_FOR_RESULT = "wait_for_result"
        const val KEY_TIMEOUT_MS = "timeout_ms"

        const val DEFAULT_TIMEOUT_MS = 30_000L
    }

    private val app: TabSSHApplication by lazy { applicationContext as TabSSHApplication }

    override suspend fun doWork(): Result {
        // Tasker-disabled / device-locked gates — same behaviour as
        // the old JobIntentService.
        if (!app.preferencesManager.isTaskerEnabled()) {
            Logger.w(TAG, "Tasker integration is disabled")
            broadcastError("Tasker integration is disabled in settings")
            return Result.success()
        }
        if (app.preferencesManager.isTaskerRequireUnlockEnabled()) {
            val km = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            if (km != null && km.isDeviceSecure && km.isKeyguardLocked) {
                val action = inputData.getString(KEY_ACTION) ?: "unknown"
                Logger.w(TAG, "Action blocked: device is locked")
                logTaskerEvent("blocked_locked", action)
                broadcastError("Device is locked — unlock to allow Tasker actions")
                return Result.success()
            }
        }

        return try {
            when (inputData.getString(KEY_ACTION)) {
                ACTION_CONNECT -> handleConnect()
                ACTION_DISCONNECT -> handleDisconnect()
                ACTION_SEND_COMMAND -> handleSendCommand()
                ACTION_SEND_KEYS -> handleSendKeys()
                else -> {
                    val action = inputData.getString(KEY_ACTION)
                    Logger.w(TAG, "Unknown action: $action")
                    broadcastError("Unknown action: $action")
                }
            }
            Result.success()
        } catch (e: Exception) {
            Logger.e(TAG, "Work failed", e)
            broadcastError("Action failed: ${e.message}")
            Result.failure()
        }
    }

    private fun isConnectionAllowed(profileId: String): Boolean {
        val allowed = app.preferencesManager.getTaskerAllowedConnections()
        if (allowed.isEmpty()) return true
        return allowed.contains(profileId)
    }

    private fun logTaskerEvent(event: String, detail: String) {
        if (!app.preferencesManager.isTaskerLogEventsEnabled()) return
        Logger.i(TAG, "[tasker] $event: $detail")
    }

    private fun defaultCommandTimeoutMs(): Long {
        return app.preferencesManager.getTaskerCommandTimeoutMs().toLong().coerceAtLeast(1000L)
    }

    private suspend fun resolveProfile(): ConnectionProfile? {
        // ConnectionProfile.id is a String UUID — the underlying SQLite
        // column is TEXT. The receiver normalises both Long and String
        // wire forms into a String here.
        val connectionId = inputData.getString(KEY_CONNECTION_ID)
        val connectionName = inputData.getString(KEY_CONNECTION_NAME)
        val profile = when {
            !connectionId.isNullOrEmpty() -> app.database.connectionDao().getConnection(connectionId)
            !connectionName.isNullOrEmpty() -> app.database.connectionDao().getByName(connectionName)
            else -> {
                broadcastError("No connection ID or name provided")
                return null
            }
        }
        if (profile == null) {
            broadcastError("Connection not found")
            return null
        }
        if (!isConnectionAllowed(profile.id)) {
            logTaskerEvent("blocked_not_allowed", "${profile.id}/${profile.name}")
            broadcastError("Connection '${profile.name}' is not allowed for Tasker")
            return null
        }
        return profile
    }

    private suspend fun handleConnect() {
        val profile = resolveProfile() ?: return
        // Re-use an existing tab if one already exists for this profile.
        val existing = app.tabManager.getAllTabs().find { it.profile.id == profile.id }
        if (existing != null) {
            broadcastConnected(profile)
            logTaskerEvent("connect", "${profile.id}/${profile.name} (reused tab)")
            return
        }
        try {
            // Use the long-lived application scope: the worker scope is
            // cancelled in doWork()'s finally block, which would tear down
            // the SSH read loop the instant the broadcast returns.
            val connection = SSHConnection(profile, app.applicationScope, applicationContext)
            connection.connect()
            val cursorStyle = app.preferencesManager.getCursorStyleInt()
            val tab = app.tabManager.createTab(profile, cursorStyle)
            if (tab == null) {
                connection.disconnect()
                broadcastError("Tab limit reached — close an existing session first")
                return
            }
            tab.connect(connection)
            broadcastConnected(profile)
            logTaskerEvent("connect", "${profile.id}/${profile.name}")
            Logger.i(TAG, "Connected to ${profile.name}")
        } catch (e: Exception) {
            Logger.e(TAG, "Connection failed", e)
            broadcastError("Connection failed: ${e.message}")
        }
    }

    private suspend fun handleDisconnect() {
        val profile = resolveProfile() ?: return
        val tab = app.tabManager.getAllTabs().find { it.profile.id == profile.id }
        if (tab != null) {
            tab.disconnect()
            broadcastDisconnected(profile)
            logTaskerEvent("disconnect", "${profile.id}/${profile.name}")
        } else {
            broadcastError("No active connection")
        }
    }

    private suspend fun handleSendCommand() {
        val command = inputData.getString(KEY_COMMAND)
        if (command.isNullOrEmpty()) {
            broadcastError("No command provided")
            return
        }
        val profile = resolveProfile() ?: return
        val waitForResult = inputData.getBoolean(KEY_WAIT_FOR_RESULT, false)
        val timeoutMs = inputData.getLong(KEY_TIMEOUT_MS, defaultCommandTimeoutMs())

        var tab = app.tabManager.getAllTabs().find { it.profile.id == profile.id }
        if (tab == null) {
            // Use the long-lived application scope (see handleConnect comment).
            val connection = SSHConnection(profile, app.applicationScope, applicationContext)
            connection.connect()
            val cursorStyle = app.preferencesManager.getCursorStyleInt()
            tab = app.tabManager.createTab(profile, cursorStyle)
            tab?.connect(connection)
        }
        if (tab == null) {
            broadcastError("Failed to create tab")
            return
        }

        tab.termuxBridge.sendText("$command\n")
        logTaskerEvent("send_command", "${profile.id}/${profile.name}: $command")

        if (waitForResult) {
            try {
                withTimeout(timeoutMs) {
                    delay(500)
                    val output = tab.termuxBridge.getScreenContent()
                    broadcastCommandResult(profile, command, output)
                }
            } catch (_: TimeoutCancellationException) {
                broadcastError("Command timeout")
            }
        } else {
            broadcastCommandResult(profile, command, "Command sent")
        }
    }

    private suspend fun handleSendKeys() {
        val keys = inputData.getString(KEY_KEYS)
        if (keys.isNullOrEmpty()) {
            broadcastError("No keys provided")
            return
        }
        val profile = resolveProfile() ?: return
        val tab = app.tabManager.getAllTabs().find { it.profile.id == profile.id }
        if (tab != null) {
            tab.termuxBridge.sendText(parseKeySequence(keys))
            logTaskerEvent("send_keys", "${profile.id}/${profile.name}: $keys")
        } else {
            broadcastError("No active connection")
        }
    }

    private fun parseKeySequence(keys: String): String = when (keys.uppercase()) {
        "ENTER" -> "\n"
        "TAB" -> "\t"
        "ESC", "ESCAPE" -> ""
        "CTRL+C" -> ""
        "CTRL+D" -> ""
        "CTRL+Z" -> ""
        else -> keys
    }

    private fun broadcastConnected(profile: ConnectionProfile) {
        applicationContext.sendBroadcast(Intent(EVENT_CONNECTED).apply {
            putExtra(KEY_CONNECTION_ID, profile.id)
            putExtra(KEY_CONNECTION_NAME, profile.name)
        })
    }

    private fun broadcastDisconnected(profile: ConnectionProfile) {
        applicationContext.sendBroadcast(Intent(EVENT_DISCONNECTED).apply {
            putExtra(KEY_CONNECTION_ID, profile.id)
            putExtra(KEY_CONNECTION_NAME, profile.name)
        })
    }

    private fun broadcastCommandResult(profile: ConnectionProfile, command: String, result: String) {
        applicationContext.sendBroadcast(Intent(EVENT_COMMAND_RESULT).apply {
            putExtra(KEY_CONNECTION_ID, profile.id)
            putExtra(KEY_CONNECTION_NAME, profile.name)
            putExtra(KEY_COMMAND, command)
            putExtra(KEY_RESULT, result)
        })
    }

    private fun broadcastError(error: String) {
        applicationContext.sendBroadcast(Intent(EVENT_ERROR).apply {
            putExtra(KEY_ERROR, error)
        })
    }

    // SSH read-loop coroutines are launched on app.applicationScope (which
    // lives for the process lifetime), so the worker can return immediately
    // without orphaning IO. Each tab is owned by TabManager from the moment
    // tab.connect() returns.
}
