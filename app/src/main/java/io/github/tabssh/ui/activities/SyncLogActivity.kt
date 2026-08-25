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
import io.github.tabssh.ui.adapters.SyncLogAdapter
import kotlinx.coroutines.launch
import io.github.tabssh.utils.tabSSHApp

/**
 * Displays the dedicated Sync Log — every sync conflict and how it was
 * resolved (interactive and headless-deferred). Mirrors [AuditLogViewerActivity]
 * but reads from `syncLogDao()` instead of `auditLogDao()`, and never shows
 * raw conflicting field values — only [io.github.tabssh.storage.database.entities.SyncLogEntry.description],
 * a pre-sanitized summary written at conflict-resolution time.
 */
class SyncLogActivity : TabSSHActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: SyncLogAdapter
    private val app by lazy { tabSSHApp }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync_log)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setTitle(R.string.sync_log_title)

        recyclerView = findViewById(R.id.recycler_sync_log)
        emptyView = findViewById(R.id.text_empty_sync_log)

        adapter = SyncLogAdapter(emptyList())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadSyncLog()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.sync_log_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_copy -> {
                copyLogToClipboard()
                true
            }
            R.id.action_refresh -> {
                loadSyncLog()
                true
            }
            R.id.action_clear -> {
                confirmClearLog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadSyncLog() {
        lifecycleScope.launch {
            try {
                val entries = app.database.syncLogDao().getRecent()
                if (entries.isEmpty()) {
                    resetEmptyView()
                    recyclerView.visibility = View.GONE
                    emptyView.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility = View.VISIBLE
                    emptyView.visibility = View.GONE
                    adapter.updateEntries(entries)
                }
            } catch (e: Exception) {
                io.github.tabssh.utils.logging.Logger.e("SyncLogActivity", "Failed to load sync log", e)

                // A DB failure must not crash the coroutine silently — surface a
                // distinct error state (not the same view as a genuine empty log)
                // with a tap-to-retry affordance.
                recyclerView.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
                emptyView.text = getString(R.string.sync_log_load_error_fmt, e.message ?: "")
                emptyView.setTextColor(
                    androidx.core.content.ContextCompat.getColor(this@SyncLogActivity, R.color.error)
                )
                emptyView.setOnClickListener { loadSyncLog() }
            }
        }
    }

    /**
     * Restores the empty-state view to its neutral "no sync activity"
     * appearance, undoing any load-error styling from a previous failed attempt.
     */
    private fun resetEmptyView() {
        emptyView.text = getString(R.string.sync_log_empty)
        emptyView.setTextColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.on_surface_variant)
        )
        emptyView.setOnClickListener(null)
    }

    private fun copyLogToClipboard() {
        lifecycleScope.launch {
            val entries = app.database.syncLogDao().getRecent()
            if (entries.isEmpty()) {
                toast(getString(R.string.sync_log_no_entries_to_copy))
                return@launch
            }

            val text = buildString {
                append("TabSSH Sync Log\n")
                append("=".repeat(60)).append("\n\n")
                entries.forEach { entry ->
                    val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date(entry.timestamp))
                    append("$ts [${entry.resolution}] ${entry.entityType} (${entry.deviceName}): ${entry.description}\n")
                }
            }

            io.github.tabssh.utils.ClipboardHelper.copy(this@SyncLogActivity, getString(R.string.sync_log_title), text, sensitive = false)
            toast(getString(R.string.sync_log_copied, entries.size))
        }
    }

    private fun confirmClearLog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sync_log_clear_title)
            .setMessage(R.string.sync_log_clear_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    app.database.syncLogDao().deleteAll()
                    loadSyncLog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}
