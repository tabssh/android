package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.ui.adapters.AuditLogAdapter
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tabssh.utils.tabSSHApp

/**
 * Activity to view audit log history
 */
class AuditLogViewerActivity : TabSSHActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: AuditLogAdapter
    private val app by lazy { tabSSHApp }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audit_log_viewer)
        
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setTitle(R.string.audit_log_viewer_title)
        
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
                        getString(R.string.log_viewer_no_logs_to_copy),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val logsText = buildString {
                    append(getString(R.string.audit_log_clipboard_label) + "\n")
                    append(getString(R.string.audit_log_clipboard_copied_at, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())) + "\n")
                    append("=".repeat(60) + "\n\n")

                    logs.forEach { log ->
                        append("${log.timestamp} [${log.eventType}] ${log.connectionId}: ${log.command ?: ""}\n")
                        if (!log.output.isNullOrEmpty()) {
                            append("  Output: ${log.output}\n")
                        }
                    }
                }

                io.github.tabssh.utils.ClipboardHelper.copy(this@AuditLogViewerActivity, getString(R.string.audit_log_clipboard_label), logsText, sensitive = false)

                android.widget.Toast.makeText(
                    this@AuditLogViewerActivity,
                    getString(R.string.audit_log_toast_copied, logs.size),
                    android.widget.Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                Logger.e("AuditLogViewer", "Failed to copy logs", e)
                android.widget.Toast.makeText(
                    this@AuditLogViewerActivity,
                    getString(R.string.audit_log_toast_copy_failed, e.message),
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
                val logs = app.database.auditLogDao().getRecentSummary(1000)

                if (logs.isEmpty()) {
                    resetEmptyView()
                    recyclerView.visibility = View.GONE
                    emptyView.visibility = View.VISIBLE
                    adapter.updateLogs(logs)
                } else {
                    recyclerView.visibility = View.VISIBLE
                    emptyView.visibility = View.GONE
                    adapter.updateLogs(logs)
                }

            } catch (e: Exception) {
                Logger.e("AuditLogViewer", "Failed to load audit logs", e)
                android.widget.Toast.makeText(
                    this@AuditLogViewerActivity,
                    getString(R.string.log_viewer_load_failed, e.message),
                    android.widget.Toast.LENGTH_LONG
                ).show()

                // Distinct from the genuine empty state: error color/copy plus
                // a tap-to-retry affordance instead of silently looking empty.
                recyclerView.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
                emptyView.text = getString(R.string.audit_log_load_error_fmt, e.message ?: "")
                emptyView.setTextColor(
                    androidx.core.content.ContextCompat.getColor(this@AuditLogViewerActivity, R.color.error)
                )
                emptyView.setOnClickListener { loadAuditLogs() }
            }
        }
    }

    /**
     * Restores the empty-state view to its neutral "no audit logs" appearance,
     * undoing any load-error styling from a previous failed attempt.
     */
    private fun resetEmptyView() {
        emptyView.text = getString(R.string.audit_log_viewer_empty_message)
        emptyView.setTextColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.on_surface_variant)
        )
        emptyView.setOnClickListener(null)
    }
    
    /**
     * Show filter dialog
     */
    private fun showFilterDialog() {
        val filterOptions = arrayOf(
            getString(R.string.audit_log_filter_all_events),
            getString(R.string.route_auth_type_hint),
            getString(R.string.prefcat_main_connections),
            getString(R.string.audit_log_filter_name_file_transfers),
            getString(R.string.audit_log_filter_configuration_changes),
            getString(R.string.log_viewer_filter_errors_only)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.log_viewer_filter_dialog_title))
            .setItems(filterOptions) { _, which ->
                applyFilter(which)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    /**
     * Apply filter to logs
     */
    private fun applyFilter(filterIndex: Int) {
        lifecycleScope.launch {
            try {
                val dao = app.database.auditLogDao()
                val (logs, filterName) = when (filterIndex) {
                    0 -> Pair(dao.getRecentSummary(1000), getString(R.string.audit_log_filter_name_all))
                    1 -> Pair(dao.getByEventTypeSummary("AUTH%"), getString(R.string.route_auth_type_hint))
                    2 -> Pair(dao.getByEventTypeSummary("CONNECT%"), getString(R.string.prefcat_main_connections))
                    3 -> Pair(dao.getByEventTypeSummary("SFTP%"), getString(R.string.audit_log_filter_name_file_transfers))
                    4 -> Pair(dao.getByEventTypeSummary("CONFIG%"), getString(R.string.audit_log_filter_name_config_changes))
                    5 -> Pair(dao.getByEventTypeSummary("ERROR%"), getString(R.string.audit_log_filter_name_errors))
                    else -> Pair(dao.getRecentSummary(1000), getString(R.string.audit_log_filter_name_all))
                }

                adapter.updateLogs(logs)

                // Update title to show current filter
                supportActionBar?.title = if (filterIndex == 0) {
                    getString(R.string.audit_log_viewer_title)
                } else {
                    getString(R.string.audit_log_title_with_filter, filterName)
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
                
                // Build RFC-4180 compliant CSV. Every field is quoted and any
                // embedded double-quotes are doubled — otherwise a command or
                // output that contains '"' would terminate the field early and
                // produce an unparseable file.
                fun csvField(value: String?): String {
                    val v = value ?: ""
                    return "\"" + v.replace("\"", "\"\"") + "\""
                }
                val csv = buildString {
                    append("Timestamp,Connection,Session,EventType,Command,Output\n")
                    logs.forEach { log ->
                        append(csvField(log.timestamp.toString())).append(",")
                        append(csvField(log.connectionId)).append(",")
                        append(csvField(log.sessionId)).append(",")
                        append(csvField(log.eventType)).append(",")
                        append(csvField(log.command)).append(",")
                        append(csvField(log.output)).append("\n")
                    }
                }

                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val filename = "audit_logs_$timestamp.csv"

                // Disk write off Main — a few thousand rows can take noticeable
                // time on cheap eMMC.
                withContext(Dispatchers.IO) {
                    java.io.File(getExternalFilesDir(null), filename).writeText(csv)
                }
                
                android.widget.Toast.makeText(
                    this@AuditLogViewerActivity,
                    getString(R.string.audit_log_toast_exported, logs.size, filename),
                    android.widget.Toast.LENGTH_LONG
                ).show()

            } catch (e: Exception) {
                Logger.e("AuditLogViewer", "Failed to export logs", e)
                android.widget.Toast.makeText(
                    this@AuditLogViewerActivity,
                    getString(R.string.log_viewer_export_failed, e.message),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
