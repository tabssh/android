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
    private var appContext: Context? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private const val TAG_PREFIX = "TabSSH"
    private const val LOG_FILE_NAME = "tabssh_debug.log"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024 // 5MB

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
    }

    fun i(tag: String, message: String, throwable: Throwable? = null) {
        Log.i("$TAG_PREFIX:$tag", message, throwable)
        if (logToFile) {
            writeToFile("I", tag, message, throwable)
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w("$TAG_PREFIX:$tag", message, throwable)
        if (logToFile) {
            writeToFile("W", tag, message, throwable)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e("$TAG_PREFIX:$tag", message, throwable)
        if (logToFile) {
            writeToFile("E", tag, message, throwable)
        }
    }

    fun wtf(tag: String, message: String, throwable: Throwable? = null) {
        Log.wtf("$TAG_PREFIX:$tag", message, throwable)
        if (logToFile) {
            writeToFile("WTF", tag, message, throwable)
        }
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
     * Write crash report SYNCHRONOUSLY
     * Called from UncaughtExceptionHandler - must complete before process dies
     */
    fun writeCrashSync(thread: Thread, throwable: Throwable) {
        val file = logFile ?: return
        try {
            // Rotate if needed
            if (file.exists() && file.length() > MAX_LOG_SIZE) {
                val backupFile = File(file.parentFile, "$LOG_FILE_NAME.old")
                backupFile.delete()
                file.renameTo(backupFile)
            }

            // Write crash synchronously
            FileWriter(file, true).use { writer ->
                val timestamp = dateFormat.format(Date())
                writer.append("\n")
                writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: ════════════════════════════════════════\n")
                writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: UNCAUGHT EXCEPTION - APP CRASHED\n")
                writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: Thread: ${thread.name} (id=${thread.id})\n")
                writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: Exception: ${throwable.javaClass.name}\n")
                writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: Message: ${throwable.message}\n")
                writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: ════════════════════════════════════════\n")
                writer.append(Log.getStackTraceString(throwable))
                writer.append("\n$timestamp WTF/$TAG_PREFIX:CRASH: ════════════════════════════════════════\n\n")
                writer.flush()
            }

            Log.e("$TAG_PREFIX:Logger", "Crash logged to file: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("$TAG_PREFIX:Logger", "Failed to write crash to log file", e)
        }
    }

    fun getLogFile(): File? = logFile

    /**
     * Clear all logs
     */
    fun clearLogs() {
        logFile?.delete()
        // Also delete backup
        logFile?.parentFile?.let { dir ->
            File(dir, "$LOG_FILE_NAME.old").delete()
        }
        if (logToFile) {
            i("Logger", "Log file cleared by user")
        }
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
