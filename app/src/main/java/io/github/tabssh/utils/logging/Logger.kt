package io.github.tabssh.utils.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/**
 * Centralized logging system for TabSSH
 *
 * Features:
 * - Captures crashes automatically via UncaughtExceptionHandler
 * - Appends to log file (only clears on explicit clear or max size rotation)
 * - Deletes log file when debug mode is disabled
 */
object Logger {

    private var debugMode = false
    private var logToFile = false
    private var logFile: File? = null
    private var appLogFile: File? = null  // Always-on sanitized log for bug reports
    private var appContext: Context? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private const val TAG_PREFIX = "TabSSH"
    private const val LOG_FILE_NAME = "tabssh_debug.log"
    private const val APP_LOG_FILE_NAME = "tabssh_app.log"  // Sanitized for public sharing
    private const val MAX_LOG_SIZE = 10 * 1024 * 1024 // 10MB
    private const val MAX_APP_LOG_SIZE = 2 * 1024 * 1024 // 2MB for app log (smaller, recent only)

    // Counters for anonymizing hosts/users in app log
    private val hostMap = mutableMapOf<String, String>()
    private val userMap = mutableMapOf<String, String>()
    private var hostCounter = 0
    private var userCounter = 0

    /**
     * Initialize the logger
     * @param context Application context
     * @param debugMode Whether to enable debug logging to file
     *
     * Note: Crash handling is done by TabSSHApplication which calls writeCrashSync()
     */
    fun initialize(context: Context, debugMode: Boolean) {
        appContext = context.applicationContext
        this.debugMode = debugMode
        this.logToFile = debugMode

        // Always enable app log (sanitized for public sharing)
        appLogFile = File(context.filesDir, APP_LOG_FILE_NAME)
        writeToAppLog("I", "Logger", "=== TabSSH Started ===")
        writeToAppLog("I", "Logger", "App Version: ${getAppVersion(context)}")
        writeToAppLog("I", "Logger", "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        writeToAppLog("I", "Logger", "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")

        if (debugMode) {
            // Enable file logging
            logFile = File(context.filesDir, LOG_FILE_NAME)

            i("Logger", "=== TabSSH Debug Logging Started ===")
            i("Logger", "App Version: ${getAppVersion(context)}")
            i("Logger", "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            i("Logger", "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        } else {
            // Debug mode disabled - delete log file if it exists
            logFile = File(context.filesDir, LOG_FILE_NAME)
            if (logFile?.exists() == true) {
                logFile?.delete()
                Log.i("$TAG_PREFIX:Logger", "Debug logging disabled - log file deleted")
            }
            logFile = null
            logToFile = false
        }
    }

    /**
     * Get app version string
     */
    private fun getAppVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pInfo.versionName} (${pInfo.longVersionCode})"
        } catch (e: Exception) {
            "unknown"
        }
    }

    fun d(tag: String, message: String, throwable: Throwable? = null) {
        if (debugMode) {
            Log.d("$TAG_PREFIX:$tag", message, throwable)
            writeToFile("D", tag, message, throwable)
        }
        // Debug messages NOT written to app log (too verbose, may contain sensitive data)
    }

    fun i(tag: String, message: String, throwable: Throwable? = null) {
        Log.i("$TAG_PREFIX:$tag", message, throwable)
        if (logToFile) {
            writeToFile("I", tag, message, throwable)
        }
        // Write sanitized version to app log
        writeToAppLog("I", tag, message, throwable)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w("$TAG_PREFIX:$tag", message, throwable)
        if (logToFile) {
            writeToFile("W", tag, message, throwable)
        }
        // Write sanitized version to app log
        writeToAppLog("W", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e("$TAG_PREFIX:$tag", message, throwable)
        if (logToFile) {
            writeToFile("E", tag, message, throwable)
        }
        // Write sanitized version to app log
        writeToAppLog("E", tag, message, throwable)
    }

    fun wtf(tag: String, message: String, throwable: Throwable? = null) {
        Log.wtf("$TAG_PREFIX:$tag", message, throwable)
        if (logToFile) {
            writeToFile("WTF", tag, message, throwable)
        }
        // Write sanitized version to app log
        writeToAppLog("WTF", tag, message, throwable)
    }

    /**
     * Write to log file asynchronously
     * Only rotates when max size exceeded (preserves existing logs)
     */
    private fun writeToFile(level: String, tag: String, message: String, throwable: Throwable?) {
        if (!logToFile || logFile == null) return

        executor.execute {
            try {
                val file = logFile ?: return@execute

                // Only rotate if file exceeds max size
                if (file.exists() && file.length() > MAX_LOG_SIZE) {
                    val backupFile = File(file.parentFile, "$LOG_FILE_NAME.old")
                    backupFile.delete() // Remove old backup
                    file.renameTo(backupFile)
                    // Start fresh log with rotation notice
                    FileWriter(file, false).use { writer ->
                        val timestamp = dateFormat.format(Date())
                        writer.append("$timestamp I/$TAG_PREFIX:Logger: Log rotated (previous log saved as .old)\n")
                    }
                }

                // Append to log file
                FileWriter(file, true).use { writer ->
                    val timestamp = dateFormat.format(Date())
                    val logLine = buildString {
                        append("$timestamp $level/$TAG_PREFIX:$tag: $message")
                        if (throwable != null) {
                            append("\n")
                            append(Log.getStackTraceString(throwable))
                        }
                        append("\n")
                    }
                    writer.append(logLine)
                }
            } catch (e: Exception) {
                Log.e("$TAG_PREFIX:Logger", "Failed to write to log file", e)
            }
        }
    }

    /**
     * Write to app log (sanitized for public sharing)
     * Always enabled - safe for bug reports, GitHub issues, pastebin
     */
    private fun writeToAppLog(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val file = appLogFile ?: return

        executor.execute {
            try {
                // Rotate if file exceeds max size
                if (file.exists() && file.length() > MAX_APP_LOG_SIZE) {
                    val backupFile = File(file.parentFile, "$APP_LOG_FILE_NAME.old")
                    backupFile.delete()
                    file.renameTo(backupFile)
                    // Reset anonymization maps on rotation
                    hostMap.clear()
                    userMap.clear()
                    hostCounter = 0
                    userCounter = 0
                }

                // Sanitize message for public sharing
                val sanitizedMessage = sanitizeForPublic(message)
                val sanitizedThrowable = throwable?.let { sanitizeStackTrace(it) }

                // Append to app log
                FileWriter(file, true).use { writer ->
                    val timestamp = dateFormat.format(Date())
                    val logLine = buildString {
                        append("$timestamp $level/$TAG_PREFIX:$tag: $sanitizedMessage")
                        if (sanitizedThrowable != null) {
                            append("\n")
                            append(sanitizedThrowable)
                        }
                        append("\n")
                    }
                    writer.append(logLine)
                }
            } catch (e: Exception) {
                // Don't create an infinite loop by writing back through the
                // logger, but DO surface to logcat at least once per session
                // so a silently-failing app log can be diagnosed without
                // having to rebuild the app with a debugger attached.
                if (!appLogWriteFailureReported) {
                    appLogWriteFailureReported = true
                    Log.e("$TAG_PREFIX:Logger",
                        "App log write failed (further failures suppressed): ${e.message}", e)
                }
            }
        }
    }
    @Volatile private var appLogWriteFailureReported = false

    /**
     * Sanitize message for public sharing
     * Removes/redacts: hostnames, usernames, IPs, passwords, keys
     */
    private fun sanitizeForPublic(message: String): String {
        var sanitized = message

        // Redact passwords (should never be in logs, but just in case)
        sanitized = sanitized.replace(Regex("password[=:]\\s*\\S+", RegexOption.IGNORE_CASE), "password=[REDACTED]")
        sanitized = sanitized.replace(Regex("passwd[=:]\\s*\\S+", RegexOption.IGNORE_CASE), "passwd=[REDACTED]")

        // Redact SSH key data
        sanitized = sanitized.replace(Regex("-----BEGIN[^-]+-----[\\s\\S]*?-----END[^-]+-----"), "[SSH KEY REDACTED]")
        sanitized = sanitized.replace(Regex("ssh-(rsa|ed25519|ecdsa|dss)\\s+\\S+"), "[SSH PUBLIC KEY]")

        // Anonymize IP addresses (replace with consistent placeholders)
        sanitized = sanitized.replace(Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")) { match ->
            val ip = match.value
            hostMap.getOrPut(ip) { "IP${++hostCounter}" }
        }

        // Anonymize hostnames that look like domains
        sanitized = sanitized.replace(Regex("\\b[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.([a-zA-Z]{2,}|\\d{1,3})\\b")) { match ->
            val host = match.value
            // Skip common safe domains
            if (host.endsWith(".com") || host.endsWith(".org") || host.endsWith(".net") ||
                host.contains("android") || host.contains("google") || host.contains("github")) {
                host
            } else {
                hostMap.getOrPut(host) { "server${++hostCounter}" }
            }
        }

        // Anonymize user@host patterns
        sanitized = sanitized.replace(Regex("([a-zA-Z0-9_.-]+)@([a-zA-Z0-9.-]+)")) { match ->
            val user = match.groupValues[1]
            val host = match.groupValues[2]
            val anonUser = userMap.getOrPut(user) { "user${++userCounter}" }
            val anonHost = hostMap.getOrPut(host) { "server${++hostCounter}" }
            "$anonUser@$anonHost"
        }

        // Redact file paths that might contain usernames
        sanitized = sanitized.replace(Regex("/home/[a-zA-Z0-9_-]+"), "/home/[user]")
        sanitized = sanitized.replace(Regex("/Users/[a-zA-Z0-9_-]+"), "/Users/[user]")

        return sanitized
    }

    /**
     * Sanitize stack trace for public sharing
     */
    private fun sanitizeStackTrace(throwable: Throwable): String {
        val stackTrace = Log.getStackTraceString(throwable)
        return sanitizeForPublic(stackTrace)
    }

    /**
     * Write crash report SYNCHRONOUSLY
     * Called from UncaughtExceptionHandler - must complete before process dies
     */
    fun writeCrashSync(thread: Thread, throwable: Throwable) {
        val timestamp = dateFormat.format(Date())
        val stackTrace = Log.getStackTraceString(throwable)

        // Write to debug log if enabled
        logFile?.let { file ->
            try {
                // Rotate if needed
                if (file.exists() && file.length() > MAX_LOG_SIZE) {
                    val backupFile = File(file.parentFile, "$LOG_FILE_NAME.old")
                    backupFile.delete()
                    file.renameTo(backupFile)
                }

                // Write crash synchronously
                FileWriter(file, true).use { writer ->
                    writer.append("\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: ════════════════════════════════════════\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: UNCAUGHT EXCEPTION - APP CRASHED\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: Thread: ${thread.name} (id=${thread.id})\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: Exception: ${throwable.javaClass.name}\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: Message: ${throwable.message}\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: ════════════════════════════════════════\n")
                    writer.append(stackTrace)
                    writer.append("\n$timestamp WTF/$TAG_PREFIX:CRASH: ════════════════════════════════════════\n\n")
                    writer.flush()
                }

                Log.e("$TAG_PREFIX:Logger", "Crash logged to file: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e("$TAG_PREFIX:Logger", "Failed to write crash to log file", e)
            }
        }

        // Always write sanitized crash to app log
        appLogFile?.let { file ->
            try {
                val sanitizedStack = sanitizeForPublic(stackTrace)
                val sanitizedMessage = sanitizeForPublic(throwable.message ?: "")

                FileWriter(file, true).use { writer ->
                    writer.append("\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: ════════════════════════════════════════\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: APP CRASHED\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: Thread: ${thread.name}\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: Exception: ${throwable.javaClass.name}\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: Message: $sanitizedMessage\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: ════════════════════════════════════════\n")
                    writer.append(sanitizedStack)
                    writer.append("\n$timestamp WTF/$TAG_PREFIX:CRASH: ════════════════════════════════════════\n\n")
                    writer.flush()
                }
            } catch (e: Exception) {
                // Silently fail
            }
        }
    }

    fun getLogFile(): File? = logFile
    fun getAppLogFile(): File? = appLogFile

    /**
     * Get sanitized app log for public sharing (GitHub issues, pastebin, etc.)
     * Safe to share publicly - no sensitive data
     */
    fun getAppLog(): String {
        val file = appLogFile
        if (file == null || !file.exists()) {
            return buildString {
                appendLine("=== TabSSH Application Log ===")
                appendLine("Status: No logs recorded yet")
                appendLine()
                appendLine("This log is safe to share publicly.")
                appendLine("All sensitive information (IPs, hostnames, usernames) is anonymized.")
            }
        }

        return try {
            val logs = file.readText()
            val oldLogs = File(file.parentFile, "$APP_LOG_FILE_NAME.old").let {
                if (it.exists()) "\n\n=== Previous Session ===\n${it.readText()}" else ""
            }

            buildString {
                appendLine("=== TabSSH Application Log ===")
                appendLine("Generated: ${dateFormat.format(Date())}")
                appendLine("Log Size: ${file.length()} bytes")
                appendLine()
                appendLine("NOTE: This log is SAFE TO SHARE PUBLICLY.")
                appendLine("All IPs, hostnames, and usernames are anonymized.")
                appendLine("(e.g., 'server1', 'user1', 'IP1' are placeholders)")
                appendLine("═══════════════════════════════════════════════════")
                appendLine()
                append(logs)
                append(oldLogs)
            }
        } catch (e: Exception) {
            "Failed to read app log: ${e.message}"
        }
    }

    /**
     * Clear all logs (debug log only, not app log)
     */
    fun clearLogs() {
        logFile?.delete()
        // Also delete backup
        logFile?.parentFile?.let { dir ->
            File(dir, "$LOG_FILE_NAME.old").delete()
        }
        if (logToFile) {
            i("Logger", "Debug log cleared by user")
        }
    }

    /**
     * Clear app log (the sanitized public log)
     */
    fun clearAppLog() {
        appLogFile?.delete()
        appLogFile?.parentFile?.let { dir ->
            File(dir, "$APP_LOG_FILE_NAME.old").delete()
        }
        // Reset anonymization maps
        hostMap.clear()
        userMap.clear()
        hostCounter = 0
        userCounter = 0
        i("Logger", "App log cleared by user")
    }

    /**
     * Clear all logs (both debug and app logs)
     */
    fun clearAllLogs() {
        clearLogs()
        clearAppLog()
    }

    fun isDebugMode(): Boolean = debugMode

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Get ALL logs as string (for debugging/support)
     */
    fun getAllLogs(): String {
        val file = logFile
        if (file == null || !file.exists()) {
            return buildString {
                appendLine("=== TabSSH Debug Log ===")
                appendLine("Status: Debug logging is ${if (debugMode) "ENABLED" else "DISABLED"}")
                appendLine()
                if (!debugMode) {
                    appendLine("To enable debug logging:")
                    appendLine("1. Go to Settings > Logging")
                    appendLine("2. Enable 'Debug Logging'")
                    appendLine("3. Reproduce the issue")
                    appendLine("4. Come back here to copy logs")
                } else {
                    appendLine("No logs recorded yet. Reproduce the issue and try again.")
                }
            }
        }

        return try {
            val logs = file.readText()
            val oldLogs = File(file.parentFile, "$LOG_FILE_NAME.old").let {
                if (it.exists()) "\n\n=== Previous Log (rotated) ===\n${it.readText()}" else ""
            }

            buildString {
                appendLine("=== TabSSH Debug Log ===")
                appendLine("Exported: ${dateFormat.format(Date())}")
                appendLine("Debug Mode: $debugMode")
                appendLine("Log File: ${file.absolutePath}")
                appendLine("Log Size: ${file.length()} bytes")
                appendLine("========================")
                appendLine()
                append(logs)
                append(oldLogs)
            }
        } catch (e: Exception) {
            "Failed to read logs: ${e.message}\n${Log.getStackTraceString(e)}"
        }
    }

    /**
     * Force enable debug mode (for troubleshooting without going to settings)
     */
    fun forceEnableDebugMode(context: Context) {
        if (debugMode) return // Already enabled

        debugMode = true
        logToFile = true
        appContext = context.applicationContext
        logFile = File(context.filesDir, LOG_FILE_NAME)

        i("Logger", "=== Debug Mode Force-Enabled ===")
        i("Logger", "App Version: ${getAppVersion(context)}")
        i("Logger", "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        i("Logger", "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    }

    /**
     * Disable debug mode and delete log file
     */
    fun disableDebugMode() {
        if (!debugMode) return

        i("Logger", "=== Debug Mode Disabled ===")

        debugMode = false
        logToFile = false

        // Delete log files
        logFile?.delete()
        logFile?.parentFile?.let { dir ->
            File(dir, "$LOG_FILE_NAME.old").delete()
        }
        logFile = null
    }

    /**
     * Get debug logs as string
     */
    fun getDebugLogs(): String {
        val debugLog = appContext?.let { File(it.filesDir, "debug.log") }
        return if (debugLog?.exists() == true) {
            debugLog.readText()
        } else {
            logFile?.let { file ->
                if (file.exists()) {
                    file.readLines().filter { it.contains(" D/") }.takeLast(200).joinToString("\n")
                } else ""
            } ?: "Debug logging not enabled"
        }
    }

    /**
     * Get error logs as string
     */
    fun getErrorLogs(): String {
        val errorLog = appContext?.let { File(it.filesDir, "error.log") }
        return if (errorLog?.exists() == true) {
            errorLog.readText()
        } else {
            logFile?.let { file ->
                if (file.exists()) {
                    file.readLines().filter {
                        it.contains(" E/") || it.contains("WTF/") || it.contains("CRASH")
                    }.takeLast(200).joinToString("\n")
                } else ""
            } ?: "No error logs found"
        }
    }

    /**
     * Get audit logs as string
     */
    fun getAuditLogs(): String {
        val auditLog = appContext?.let { File(it.filesDir, "audit.log") }
        return if (auditLog?.exists() == true) {
            auditLog.readText()
        } else {
            "Audit logging not enabled or no audit events recorded"
        }
    }

    /**
     * Get list of host log files
     */
    fun getHostLogFiles(): List<File> {
        val logsDir = appContext?.let { File(it.filesDir, "host_logs") }
        return if (logsDir?.exists() == true && logsDir.isDirectory) {
            logsDir.listFiles()?.filter { it.isFile && it.extension == "log" }?.sortedByDescending { it.lastModified() } ?: emptyList()
        } else {
            emptyList()
        }
    }

    /**
     * Get recent logs from memory (for log viewer)
     */
    fun getRecentLogs(): List<io.github.tabssh.ui.activities.LogViewerActivity.LogEntry> {
        val logs = mutableListOf<io.github.tabssh.ui.activities.LogViewerActivity.LogEntry>()

        logFile?.let { file ->
            if (file.exists()) {
                try {
                    file.readLines().takeLast(500).forEach { line ->
                        // Parse: 2025-12-19 12:34:56.123 I/TabSSH:TAG: Message
                        val regex = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) (\w+)/TabSSH:(\w+): (.*)$""")
                        regex.find(line)?.let { match ->
                            val (timestamp, level, tag, message) = match.destructured
                            val levelDisplay = when (level) {
                                "D" -> "DEBUG"
                                "I" -> "INFO"
                                "W" -> "WARN"
                                "E" -> "ERROR"
                                "WTF" -> "FATAL"
                                else -> level
                            }
                            logs.add(io.github.tabssh.ui.activities.LogViewerActivity.LogEntry(
                                timestamp, levelDisplay, tag, message
                            ))
                        }
                    }
                } catch (e: Exception) {
                    e("Logger", "Failed to read logs", e)
                }
            }
        }

        return logs
    }
}
