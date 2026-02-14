package io.github.tabssh.terminal

import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.ssh.connection.SSHConnection

/**
 * Manages terminal multiplexer (tmux/screen/zellij) detection and session handling.
 * Provides auto-attach, create new session, and detection functionality.
 */
class MultiplexerManager {

    companion object {
        private const val TAG = "MultiplexerManager"
    }

    /**
     * Multiplexer types supported
     */
    enum class MultiplexerType(val command: String, val displayName: String) {
        TMUX("tmux", "tmux"),
        SCREEN("screen", "screen"),
        ZELLIJ("zellij", "zellij"),
        NONE("", "None")
    }

    /**
     * Multiplexer connection mode
     */
    enum class Mode {
        OFF,          // Don't use multiplexer
        AUTO_ATTACH,  // Attach to existing session or create new
        CREATE_NEW,   // Always create new session
        ASK           // Ask user what to do
    }

    /**
     * Detected multiplexer session info
     */
    data class SessionInfo(
        val type: MultiplexerType,
        val sessionId: String,
        val sessionName: String,
        val attached: Boolean,
        val created: String? = null
    )

    /**
     * Result of multiplexer detection
     */
    data class DetectionResult(
        val available: List<MultiplexerType>,
        val sessions: List<SessionInfo>
    )

    /**
     * Detect which multiplexers are available on the remote host
     * and list any existing sessions.
     *
     * @param connection The SSH connection to use
     * @return DetectionResult with available multiplexers and sessions
     */
    suspend fun detectMultiplexers(connection: SSHConnection): DetectionResult {
        val available = mutableListOf<MultiplexerType>()
        val sessions = mutableListOf<SessionInfo>()

        try {
            // Check for tmux
            if (isCommandAvailable(connection, "tmux")) {
                available.add(MultiplexerType.TMUX)
                sessions.addAll(listTmuxSessions(connection))
            }

            // Check for screen
            if (isCommandAvailable(connection, "screen")) {
                available.add(MultiplexerType.SCREEN)
                sessions.addAll(listScreenSessions(connection))
            }

            // Check for zellij
            if (isCommandAvailable(connection, "zellij")) {
                available.add(MultiplexerType.ZELLIJ)
                sessions.addAll(listZellijSessions(connection))
            }

            Logger.d(TAG, "Detected multiplexers: ${available.map { it.displayName }}, sessions: ${sessions.size}")

        } catch (e: Exception) {
            Logger.e(TAG, "Error detecting multiplexers", e)
        }

        return DetectionResult(available, sessions)
    }

    /**
     * Check if a command is available on the remote host
     */
    private suspend fun isCommandAvailable(connection: SSHConnection, command: String): Boolean {
        return try {
            val result = connection.executeCommand("command -v $command > /dev/null 2>&1 && echo 'yes' || echo 'no'")
            result.trim() == "yes"
        } catch (e: Exception) {
            Logger.w(TAG, "Error checking command availability: $command", e)
            false
        }
    }

    /**
     * List tmux sessions
     */
    private suspend fun listTmuxSessions(connection: SSHConnection): List<SessionInfo> {
        val sessions = mutableListOf<SessionInfo>()
        try {
            val output = connection.executeCommand("tmux list-sessions -F '#{session_id}:#{session_name}:#{session_attached}:#{session_created}' 2>/dev/null")
            if (output.isNotBlank() && !output.contains("no server running")) {
                output.lines().filter { it.isNotBlank() }.forEach { line ->
                    val parts = line.split(":")
                    if (parts.size >= 3) {
                        sessions.add(SessionInfo(
                            type = MultiplexerType.TMUX,
                            sessionId = parts[0],
                            sessionName = parts[1],
                            attached = parts[2] == "1",
                            created = parts.getOrNull(3)
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Error listing tmux sessions", e)
        }
        return sessions
    }

    /**
     * List screen sessions
     */
    private suspend fun listScreenSessions(connection: SSHConnection): List<SessionInfo> {
        val sessions = mutableListOf<SessionInfo>()
        try {
            val output = connection.executeCommand("screen -ls 2>/dev/null | grep -E '\\s+[0-9]+\\.' | awk '{print \$1}'")
            if (output.isNotBlank()) {
                output.lines().filter { it.isNotBlank() }.forEach { line ->
                    // Format: pid.name (Attached/Detached)
                    val parts = line.trim().split(".")
                    if (parts.size >= 2) {
                        val attached = line.contains("(Attached)")
                        sessions.add(SessionInfo(
                            type = MultiplexerType.SCREEN,
                            sessionId = parts[0],
                            sessionName = parts.getOrNull(1)?.substringBefore("\t") ?: parts[0],
                            attached = attached
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Error listing screen sessions", e)
        }
        return sessions
    }

    /**
     * List zellij sessions
     */
    private suspend fun listZellijSessions(connection: SSHConnection): List<SessionInfo> {
        val sessions = mutableListOf<SessionInfo>()
        try {
            val output = connection.executeCommand("zellij list-sessions 2>/dev/null")
            if (output.isNotBlank() && !output.contains("No active sessions")) {
                output.lines().filter { it.isNotBlank() }.forEach { line ->
                    val name = line.trim()
                    sessions.add(SessionInfo(
                        type = MultiplexerType.ZELLIJ,
                        sessionId = name,
                        sessionName = name,
                        attached = line.contains("(current)")
                    ))
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Error listing zellij sessions", e)
        }
        return sessions
    }

    /**
     * Get the command to attach to a session or create a new one
     *
     * @param type The multiplexer type
     * @param sessionName Optional session name
     * @param createNew If true, always create new session
     * @return The command string to execute
     */
    fun getAttachOrCreateCommand(
        type: MultiplexerType,
        sessionName: String? = null,
        createNew: Boolean = false
    ): String {
        val name = sessionName?.takeIf { it.isNotBlank() } ?: "main"

        return when (type) {
            MultiplexerType.TMUX -> {
                if (createNew) {
                    "tmux new-session -s '$name'"
                } else {
                    // Attach to existing or create new
                    "tmux new-session -A -s '$name'"
                }
            }
            MultiplexerType.SCREEN -> {
                if (createNew) {
                    "screen -S '$name'"
                } else {
                    // Attach to existing or create new (-R = reattach if possible, create otherwise)
                    "screen -R '$name'"
                }
            }
            MultiplexerType.ZELLIJ -> {
                if (createNew) {
                    "zellij --session '$name'"
                } else {
                    // Attach to existing or create new
                    "zellij attach '$name' --create"
                }
            }
            MultiplexerType.NONE -> ""
        }
    }

    /**
     * Get the detach command for a multiplexer
     */
    fun getDetachCommand(type: MultiplexerType): String {
        return when (type) {
            MultiplexerType.TMUX -> "\u0002d"      // Ctrl+B, d
            MultiplexerType.SCREEN -> "\u0001d"    // Ctrl+A, d
            MultiplexerType.ZELLIJ -> "\u0007d"    // Ctrl+G, d (depends on config)
            MultiplexerType.NONE -> ""
        }
    }

    /**
     * Convert mode string to enum
     */
    fun parseMode(modeString: String?): Mode {
        return when (modeString?.uppercase()) {
            "AUTO_ATTACH" -> Mode.AUTO_ATTACH
            "CREATE_NEW" -> Mode.CREATE_NEW
            "ASK" -> Mode.ASK
            else -> Mode.OFF
        }
    }

    /**
     * Get the preferred multiplexer type from settings
     */
    fun getPreferredType(typeString: String?): MultiplexerType {
        return when (typeString?.lowercase()) {
            "tmux" -> MultiplexerType.TMUX
            "screen" -> MultiplexerType.SCREEN
            "zellij" -> MultiplexerType.ZELLIJ
            else -> MultiplexerType.TMUX  // Default to tmux
        }
    }
}
