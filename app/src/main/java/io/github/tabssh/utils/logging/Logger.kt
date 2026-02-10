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
 */
object Logger {
    
    private var debugMode = false
    private var logToFile = false
    private var logFile: File? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    
    private const val TAG_PREFIX = "TabSSH"
    private const val LOG_FILE_NAME = "tabssh.log"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024 // 5MB
    
    fun initialize(context: Context, debugMode: Boolean) {
        this.debugMode = debugMode
        this.logToFile = debugMode // Only log to file in debug mode
        
        if (logToFile) {
            logFile = File(context.filesDir, LOG_FILE_NAME)
            i("Logger", "Logger initialized with file logging enabled")
        } else {
            i("Logger", "Logger initialized")
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
        writeToFile("I", tag, message, throwable)
    }
    
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w("$TAG_PREFIX:$tag", message, throwable)
        writeToFile("W", tag, message, throwable)
    }
    
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e("$TAG_PREFIX:$tag", message, throwable)
        writeToFile("E", tag, message, throwable)
    }
    
    fun wtf(tag: String, message: String, throwable: Throwable? = null) {
        Log.wtf("$TAG_PREFIX:$tag", message, throwable)
        writeToFile("WTF", tag, message, throwable)
    }
    
    private fun writeToFile(level: String, tag: String, message: String, throwable: Throwable?) {
        if (!logToFile || logFile == null) return
        
        executor.execute {
            try {
                val file = logFile!!
                
                // Rotate log if it's too large
                if (file.exists() && file.length() > MAX_LOG_SIZE) {
                    val backupFile = File(file.parentFile, "$LOG_FILE_NAME.old")
                    file.renameTo(backupFile)
                }
                
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
    
    fun getLogFile(): File? = logFile
    
    fun clearLogs() {
        logFile?.delete()
        i("Logger", "Log file cleared")
    }
    
    fun isDebugMode(): Boolean = debugMode
    
    /**
     * Get recent logs from memory (for log viewer)
     */
    fun getRecentLogs(): List<io.github.tabssh.ui.activities.LogViewerActivity.LogEntry> {
        val logs = mutableListOf<io.github.tabssh.ui.activities.LogViewerActivity.LogEntry>()
        
        logFile?.let { file ->
            if (file.exists()) {
                try {
                    file.readLines().takeLast(500).forEach { line ->
                        // Parse: 2025-12-19 12:34:56.123 [I] TAG: Message
                        val timestampEnd = line.indexOf(" [")
                        val levelEnd = line.indexOf("] ", timestampEnd)
                        val tagEnd = line.indexOf(": ", levelEnd)
                        
                        if (timestampEnd > 0 && levelEnd > 0 && tagEnd > 0) {
                            val timestamp = line.substring(0, timestampEnd)
                            val level = when (line.substring(timestampEnd + 2, levelEnd)) {
                                "D" -> "DEBUG"
                                "I" -> "INFO"
                                "W" -> "WARN"
                                "E" -> "ERROR"
                                else -> "UNKNOWN"
                            }
                            val tag = line.substring(levelEnd + 2, tagEnd)
                            val message = line.substring(tagEnd + 2)
                            
                            logs.add(io.github.tabssh.ui.activities.LogViewerActivity.LogEntry(
                                timestamp, level, tag, message
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