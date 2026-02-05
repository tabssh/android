package io.github.tabssh.automation

import android.app.IntentService
import android.content.Intent
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.utils.Logger
import kotlinx.coroutines.*

/**
 * Tasker integration service for automation
 * 
 * Supports intent-based actions:
 * - io.github.tabssh.action.CONNECT - Connect to server
 * - io.github.tabssh.action.DISCONNECT - Disconnect from server
 * - io.github.tabssh.action.SEND_COMMAND - Execute command
 * - io.github.tabssh.action.SEND_KEYS - Send key sequence
 * 
 * Broadcasts events:
 * - io.github.tabssh.event.CONNECTED - Connection established
 * - io.github.tabssh.event.DISCONNECTED - Connection closed
 * - io.github.tabssh.event.COMMAND_RESULT - Command output
 * - io.github.tabssh.event.ERROR - Error occurred
 */
class TaskerIntentService : IntentService("TaskerIntentService") {

    companion object {
        // Actions
        const val ACTION_CONNECT = "io.github.tabssh.action.CONNECT"
        const val ACTION_DISCONNECT = "io.github.tabssh.action.DISCONNECT"
        const val ACTION_SEND_COMMAND = "io.github.tabssh.action.SEND_COMMAND"
        const val ACTION_SEND_KEYS = "io.github.tabssh.action.SEND_KEYS"
        
        // Events (broadcasts)
        const val EVENT_CONNECTED = "io.github.tabssh.event.CONNECTED"
        const val EVENT_DISCONNECTED = "io.github.tabssh.event.DISCONNECTED"
        const val EVENT_COMMAND_RESULT = "io.github.tabssh.event.COMMAND_RESULT"
        const val EVENT_ERROR = "io.github.tabssh.event.ERROR"
        
        // Extras
        const val EXTRA_CONNECTION_ID = "connection_id"
        const val EXTRA_CONNECTION_NAME = "connection_name"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_KEYS = "keys"
        const val EXTRA_RESULT = "result"
        const val EXTRA_ERROR = "error"
        const val EXTRA_WAIT_FOR_RESULT = "wait_for_result"
        const val EXTRA_TIMEOUT_MS = "timeout_ms"
        
        // Default timeout for command execution (30 seconds)
        const val DEFAULT_TIMEOUT_MS = 30000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var app: TabSSHApplication

    override fun onCreate() {
        super.onCreate()
        app = application as TabSSHApplication
        Logger.d("TaskerIntentService", "Service created")
    }

    override fun onHandleIntent(intent: Intent?) {
        if (intent == null) {
            Logger.w("TaskerIntentService", "Received null intent")
            return
        }

        // Check if Tasker integration is enabled
        if (!app.preferencesManager.getBoolean("tasker_enabled",true)){
            Logger.w("TaskerIntentService", "Tasker integration is disabled")
            broadcastError("Tasker integration is disabled in settings")
            return
        }

        when (intent.action) {
            ACTION_CONNECT -> handleConnect(intent)
            ACTION_DISCONNECT -> handleDisconnect(intent)
            ACTION_SEND_COMMAND -> handleSendCommand(intent)
            ACTION_SEND_KEYS -> handleSendKeys(intent)
            else -> {
                Logger.w("TaskerIntentService", "Unknown action: ${intent.action}")
                broadcastError("Unknown action: ${intent.action}")
            }
        }
    }

    private fun handleConnect(intent: Intent) {
        val connectionId = intent.getLongExtra(EXTRA_CONNECTION_ID, -1)
        val connectionName = intent.getStringExtra(EXTRA_CONNECTION_NAME)

        Logger.d("TaskerIntentService", "Connect request - ID: $connectionId, Name: $connectionName")

        runBlocking {
            try {
                val profile = when {
                    connectionId > 0 -> app.database.connectionDao().getById(connectionId)
                    !connectionName.isNullOrEmpty()->app.database.connectionDao().getByName(connectionName)
                    else -> {
                        broadcastError("No connection ID or name provided")
                        return@runBlocking
                    }
                }

                if (profile == null) {
                    broadcastError("Connection not found")
                    return@runBlocking
                }

                val connection = SSHConnection(this@TaskerIntentService, profile, app)
                connection.connect()
                broadcastConnected(profile)
                
                Logger.i("TaskerIntentService", "Connected to ${profile.name}")

            } catch (e: Exception) {
                Logger.e("TaskerIntentService", "Connection failed", e)
                broadcastError("Connection failed: ${e.message}")
            }
        }
    }

    private fun handleDisconnect(intent: Intent) {
        val connectionId = intent.getLongExtra(EXTRA_CONNECTION_ID, -1)
        val connectionName = intent.getStringExtra(EXTRA_CONNECTION_NAME)

        runBlocking {
            try {
                val profile = when {
                    connectionId > 0 -> app.database.connectionDao().getById(connectionId)
                    !connectionName.isNullOrEmpty()->app.database.connectionDao().getByName(connectionName)
                    else -> {
                        broadcastError("No connection ID or name provided")
                        return@runBlocking
                    }
                }

                if (profile == null) {
                    broadcastError("Connection not found")
                    return@runBlocking
                }

                val tab = app.tabManager.getTabs().find { it.profile.id == profile.id }
                
                if (tab != null) {
                    tab.disconnect()
                    broadcastDisconnected(profile)
                } else {
                    broadcastError("No active connection")
                }

            } catch (e: Exception) {
                Logger.e("TaskerIntentService", "Disconnect failed", e)
                broadcastError("Disconnect failed: ${e.message}")
            }
        }
    }

    private fun handleSendCommand(intent: Intent) {
        val connectionId = intent.getLongExtra(EXTRA_CONNECTION_ID, -1)
        val connectionName = intent.getStringExtra(EXTRA_CONNECTION_NAME)
        val command = intent.getStringExtra(EXTRA_COMMAND)
        val waitForResult = intent.getBooleanExtra(EXTRA_WAIT_FOR_RESULT, false)
        val timeoutMs = intent.getLongExtra(EXTRA_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)

        if (command.isNullOrEmpty()) {
            broadcastError("No command provided")
            return
        }

        runBlocking {
            try {
                val profile = when {
                    connectionId > 0 -> app.database.connectionDao().getById(connectionId)
                    !connectionName.isNullOrEmpty()->app.database.connectionDao().getByName(connectionName)
                    else -> {
                        broadcastError("No connection ID or name provided")
                        return@runBlocking
                    }
                }

                if (profile == null) {
                    broadcastError("Connection not found")
                    return@runBlocking
                }

                var tab = app.tabManager.getTabs().find { it.profile.id == profile.id }
                
                if (tab == null) {
                    val connection = SSHConnection(this@TaskerIntentService, profile, app)
                    connection.connect()
                    tab = app.tabManager.createTab(profile, connection)
                }

                tab.terminal.sendText("$command\n")

                if (waitForResult) {
                    withTimeout(timeoutMs) {
                        delay(500)
                        val output = tab.terminal.getScreenText()
                        broadcastCommandResult(profile, command, output)
                    }
                } else {
                    broadcastCommandResult(profile, command, "Command sent")
                }

            } catch (e: TimeoutCancellationException) {
                broadcastError("Command timeout")
            } catch (e: Exception) {
                Logger.e("TaskerIntentService", "Command failed", e)
                broadcastError("Command failed: ${e.message}")
            }
        }
    }

    private fun handleSendKeys(intent: Intent) {
        val connectionId = intent.getLongExtra(EXTRA_CONNECTION_ID, -1)
        val connectionName = intent.getStringExtra(EXTRA_CONNECTION_NAME)
        val keys = intent.getStringExtra(EXTRA_KEYS)

        if (keys.isNullOrEmpty()) {
            broadcastError("No keys provided")
            return
        }

        runBlocking {
            try {
                val profile = when {
                    connectionId > 0 -> app.database.connectionDao().getById(connectionId)
                    !connectionName.isNullOrEmpty()->app.database.connectionDao().getByName(connectionName)
                    else -> {
                        broadcastError("No connection ID or name provided")
                        return@runBlocking
                    }
                }

                if (profile == null) {
                    broadcastError("Connection not found")
                    return@runBlocking
                }

                val tab = app.tabManager.getTabs().find { it.profile.id == profile.id }
                
                if (tab != null) {
                    val sequence = parseKeySequence(keys)
                    tab.terminal.sendText(sequence)
                } else {
                    broadcastError("No active connection")
                }

            } catch (e: Exception) {
                Logger.e("TaskerIntentService", "Send keys failed", e)
                broadcastError("Send keys failed: ${e.message}")
            }
        }
    }

    private fun parseKeySequence(keys: String): String {
        return when (keys.uppercase()) {
            "ENTER" -> "\n"
            "TAB" -> "\t"
            "ESC", "ESCAPE" -> "\u001B"
            "CTRL+C" -> "\u0003"
            "CTRL+D" -> "\u0004"
            "CTRL+Z" -> "\u001A"
            else -> keys
        }
    }

    private fun broadcastConnected(profile: ConnectionProfile) {
        val intent = Intent(EVENT_CONNECTED).apply {
            putExtra(EXTRA_CONNECTION_ID, profile.id)
            putExtra(EXTRA_CONNECTION_NAME, profile.name)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDisconnected(profile: ConnectionProfile) {
        val intent = Intent(EVENT_DISCONNECTED).apply {
            putExtra(EXTRA_CONNECTION_ID, profile.id)
            putExtra(EXTRA_CONNECTION_NAME, profile.name)
        }
        sendBroadcast(intent)
    }

    private fun broadcastCommandResult(profile: ConnectionProfile, command: String, result: String) {
        val intent = Intent(EVENT_COMMAND_RESULT).apply {
            putExtra(EXTRA_CONNECTION_ID, profile.id)
            putExtra(EXTRA_CONNECTION_NAME, profile.name)
            putExtra(EXTRA_COMMAND, command)
            putExtra(EXTRA_RESULT, result)
        }
        sendBroadcast(intent)
    }

    private fun broadcastError(error: String) {
        val intent = Intent(EVENT_ERROR).apply {
            putExtra(EXTRA_ERROR, error)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
