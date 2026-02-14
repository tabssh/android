package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch
import java.io.File

/**
 * Activity to view application logs
 */
class LogViewerActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: LogEntryAdapter
    private val logEntries = mutableListOf<LogEntry>()
    
    data class LogEntry(
        val timestamp: String,
        val level: String,
        val tag: String,
        val message: String
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)
        
        // Setup toolbar
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle("Application Logs")
        }
        
        // Initialize views
        recyclerView = findViewById(R.id.recycler_logs)
        emptyView = findViewById(R.id.text_empty_logs)
        
        // Setup RecyclerView
        adapter = LogEntryAdapter(logEntries)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        
        // Load logs
        loadLogs()
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.log_viewer_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_copy -> {
                copyLogsToClipboard()
                true
            }
            R.id.action_filter -> {
                showFilterDialog()
                true
            }
            R.id.action_export -> {
                exportLogs()
                true
            }
            R.id.action_refresh -> {
                loadLogs()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Copy all logs to clipboard
     */
    private fun copyLogsToClipboard() {
        if (logEntries.isEmpty()) {
            android.widget.Toast.makeText(
                this,
                "No logs to copy",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        val logsText = buildString {
            append("TabSSH Application Logs\n")
            append("Copied: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n")
            append("=".repeat(60) + "\n\n")

            logEntries.forEach { log ->
                append("${log.timestamp} [${log.level}] ${log.tag}: ${log.message}\n")
            }
        }

        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("TabSSH Logs", logsText)
        clipboard.setPrimaryClip(clip)

        android.widget.Toast.makeText(
            this,
            "✓ Copied ${logEntries.size} log entries to clipboard",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
    
    /**
     * Load logs from internal storage
     */
    private fun loadLogs() {
        lifecycleScope.launch {
            try {
                logEntries.clear()
                
                // Read from log file if it exists
                val logFile = File(filesDir, "tabssh.log")
                if (logFile.exists()) {
                    logFile.readLines().forEach { line ->
                        parseLogLine(line)?.let { logEntries.add(it) }
                    }
                }
                
                // Also get recent in-memory logs from Logger
                Logger.getRecentLogs().forEach { log ->
                    logEntries.add(log)
                }
                
                if (logEntries.isEmpty()) {
                    recyclerView.visibility = View.GONE
                    emptyView.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility = View.VISIBLE
                    emptyView.visibility = View.GONE
                    adapter.notifyDataSetChanged()
                }
                
            } catch (e: Exception) {
                Logger.e("LogViewer", "Failed to load logs", e)
                android.widget.Toast.makeText(
                    this@LogViewerActivity,
                    "Failed to load logs: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Parse a log line into a LogEntry
     */
    private fun parseLogLine(line: String): LogEntry? {
        return try {
            // Expected format: 2025-12-19 12:34:56 [INFO] TAG: Message
            val parts = line.split(" ", limit = 4)
            if (parts.size >= 4) {
                val timestamp = "${parts[0]} ${parts[1]}"
                val level = parts[2].removeSurrounding("[", "]")
                val tagAndMessage = parts[3].split(": ", limit = 2)
                val tag = tagAndMessage.getOrNull(0) ?: "Unknown"
                val message = tagAndMessage.getOrNull(1) ?: parts[3]
                LogEntry(timestamp, level, tag, message)
            } else null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Show filter dialog
     */
    private fun showFilterDialog() {
        val filterOptions = arrayOf(
            "All Logs",
            "Errors Only",
            "Warnings",
            "Info",
            "Debug"
        )
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Filter Logs")
            .setItems(filterOptions) { _, which ->
                applyFilter(which)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Apply filter to logs
     */
    private fun applyFilter(filterIndex: Int) {
        val (filtered, filterName) = when (filterIndex) {
            0 -> Pair(Logger.getRecentLogs(), "All")
            1 -> Pair(Logger.getRecentLogs().filter { it.level == "ERROR" }, "ERROR")
            2 -> Pair(Logger.getRecentLogs().filter { it.level == "WARN" }, "WARN")
            3 -> Pair(Logger.getRecentLogs().filter { it.level == "INFO" }, "INFO")
            4 -> Pair(Logger.getRecentLogs().filter { it.level == "DEBUG" }, "DEBUG")
            else -> Pair(Logger.getRecentLogs(), "All")
        }

        logEntries.clear()
        logEntries.addAll(filtered)
        adapter.notifyDataSetChanged()

        // Update title to show current filter
        supportActionBar?.title = if (filterIndex == 0) {
            "Application Logs"
        } else {
            "Application Logs - $filterName"
        }

        if (logEntries.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }
    }
    
    /**
     * Export logs to file
     */
    private fun exportLogs() {
        lifecycleScope.launch {
            try {
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val filename = "tabssh_logs_$timestamp.txt"
                
                val file = File(getExternalFilesDir(null), filename)
                file.writeText(buildString {
                    append("TabSSH Application Logs\n")
                    append("Exported: $timestamp\n")
                    append("=".repeat(80) + "\n\n")
                    
                    logEntries.forEach { log ->
                        append("${log.timestamp} [${log.level}] ${log.tag}: ${log.message}\n")
                    }
                })
                
                android.widget.Toast.makeText(
                    this@LogViewerActivity,
                    "✓ Exported ${logEntries.size} log entries to $filename",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                
            } catch (e: Exception) {
                Logger.e("LogViewer", "Failed to export logs", e)
                android.widget.Toast.makeText(
                    this@LogViewerActivity,
                    "Failed to export logs: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Adapter for log entries
     */
    inner class LogEntryAdapter(private val logs: List<LogEntry>) : 
        RecyclerView.Adapter<LogEntryAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textTimestamp: TextView = view.findViewById(R.id.text_log_timestamp)
            val textLevel: TextView = view.findViewById(R.id.text_log_level)
            val textTag: TextView = view.findViewById(R.id.text_log_tag)
            val textMessage: TextView = view.findViewById(R.id.text_log_message)
        }
        
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log_entry, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val log = logs[position]
            
            holder.textTimestamp.text = log.timestamp
            holder.textLevel.text = log.level
            holder.textTag.text = log.tag
            holder.textMessage.text = log.message
            
            // Color code by level
            val color = when (log.level) {
                "ERROR" -> 0xFFF44336.toInt() // Red
                "WARN" -> 0xFFFF9800.toInt() // Orange
                "INFO" -> 0xFF4CAF50.toInt() // Green
                "DEBUG" -> 0xFF2196F3.toInt() // Blue
                else -> 0xFF757575.toInt() // Gray
            }
            holder.textLevel.setTextColor(color)
        }
        
        override fun getItemCount() = logs.size
    }
}
