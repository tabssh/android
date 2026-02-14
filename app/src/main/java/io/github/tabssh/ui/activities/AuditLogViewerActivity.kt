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
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.AuditLogEntry
import io.github.tabssh.ui.adapters.AuditLogAdapter
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch

/**
 * Activity to view audit log history
 */
class AuditLogViewerActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: AuditLogAdapter
    private val app by lazy { application as TabSSHApplication }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audit_log_viewer)
        
        // Setup toolbar
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle("Audit Logs")
        }
        
        // Initialize views
        recyclerView = findViewById(R.id.recycler_audit_logs)
        emptyView = findViewById(R.id.text_empty_logs)
        
        // Setup RecyclerView
        adapter = AuditLogAdapter(emptyList())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        
        // Load logs
        loadAuditLogs()
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.audit_log_menu, menu)
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
                loadAuditLogs()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Copy all audit logs to clipboard
     */
    private fun copyLogsToClipboard() {
        lifecycleScope.launch {
            try {
                val logs = app.database.auditLogDao().getRecent(1000)

                if (logs.isEmpty()) {
                    android.widget.Toast.makeText(
                        this@AuditLogViewerActivity,
                        "No logs to copy",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val logsText = buildString {
                    append("TabSSH Audit Logs\n")
                    append("Copied: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n")
                    append("=".repeat(60) + "\n\n")

                    logs.forEach { log ->
                        append("${log.timestamp} [${log.eventType}] ${log.connectionId ?: "N/A"}: ${log.command ?: ""}\n")
                        if (!log.output.isNullOrEmpty()) {
                            append("  Output: ${log.output}\n")
                        }
                    }
                }

                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("TabSSH Audit Logs", logsText)
                clipboard.setPrimaryClip(clip)

                android.widget.Toast.makeText(
                    this@AuditLogViewerActivity,
                    "✓ Copied ${logs.size} audit log entries to clipboard",
                    android.widget.Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                Logger.e("AuditLogViewer", "Failed to copy logs", e)
                android.widget.Toast.makeText(
                    this@AuditLogViewerActivity,
                    "Failed to copy logs: ${e.message}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    /**
     * Load audit logs from database
     */
    private fun loadAuditLogs() {
        lifecycleScope.launch {
            try {
                val logs = app.database.auditLogDao().getRecent(1000)
                
                if (logs.isEmpty()) {
                    recyclerView.visibility = View.GONE
                    emptyView.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility = View.VISIBLE
                    emptyView.visibility = View.GONE
                    adapter.updateLogs(logs)
                }
                
            } catch (e: Exception) {
                Logger.e("AuditLogViewer", "Failed to load audit logs", e)
                android.widget.Toast.makeText(
                    this@AuditLogViewerActivity,
                    "Failed to load logs: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Show filter dialog
     */
    private fun showFilterDialog() {
        val filterOptions = arrayOf(
            "All Events",
            "Authentication",
            "Connections",
            "File Transfers",
            "Configuration Changes",
            "Errors Only"
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
        lifecycleScope.launch {
            try {
                val (logs, filterName) = when (filterIndex) {
                    0 -> Pair(app.database.auditLogDao().getRecent(1000), "All")
                    1 -> Pair(app.database.auditLogDao().getByEventType("AUTH%"), "Authentication")
                    2 -> Pair(app.database.auditLogDao().getByEventType("CONNECT%"), "Connections")
                    3 -> Pair(app.database.auditLogDao().getByEventType("SFTP%"), "File Transfers")
                    4 -> Pair(app.database.auditLogDao().getByEventType("CONFIG%"), "Config Changes")
                    5 -> Pair(app.database.auditLogDao().getByEventType("ERROR%"), "Errors")
                    else -> Pair(app.database.auditLogDao().getRecent(1000), "All")
                }

                adapter.updateLogs(logs)

                // Update title to show current filter
                supportActionBar?.title = if (filterIndex == 0) {
                    "Audit Logs"
                } else {
                    "Audit Logs - $filterName"
                }

            } catch (e: Exception) {
                Logger.e("AuditLogViewer", "Failed to filter logs", e)
            }
        }
    }
    
    /**
     * Export logs to file
     */
    private fun exportLogs() {
        lifecycleScope.launch {
            try {
                val logs = app.database.auditLogDao().getRecent(1000)
                
                // Create CSV content
                val csv = buildString {
                    append("Timestamp,Connection,Session,EventType,Command,Output\n")
                    logs.forEach { log ->
                        append("${log.timestamp},")
                        append("${log.connectionId},")
                        append("${log.sessionId},")
                        append("${log.eventType},")
                        append("\"${log.command ?: ""}\",")
                        append("\"${log.output ?: ""}\"\n")
                    }
                }
                
                // Save to file
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val filename = "audit_logs_$timestamp.csv"
                
                val file = java.io.File(getExternalFilesDir(null), filename)
                file.writeText(csv)
                
                android.widget.Toast.makeText(
                    this@AuditLogViewerActivity,
                    "✓ Exported ${logs.size} logs to $filename",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                
            } catch (e: Exception) {
                Logger.e("AuditLogViewer", "Failed to export logs", e)
                android.widget.Toast.makeText(
                    this@AuditLogViewerActivity,
                    "Failed to export logs: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
