package io.github.tabssh.ui.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.storage.database.entities.PasteRecord
import io.github.tabssh.utils.ClipboardHelper
import io.github.tabssh.utils.paste.PasteDeleteOutcome
import io.github.tabssh.utils.paste.PasteTracker
import io.github.tabssh.utils.tabSSHApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** The pastes this device created, newest first, with Open, Copy link and Delete. */
class MyPastesActivity : TabSSHActivity() {

    private lateinit var listPastes: LinearLayout
    private lateinit var textEmpty: TextView
    private lateinit var tracker: PasteTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_pastes)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setTitle(R.string.my_pastes_title)
        listPastes = findViewById(R.id.list_pastes)
        textEmpty = findViewById(R.id.text_empty)
        tracker = PasteTracker.from(tabSSHApp.database, tabSSHApp.preferencesManager)

        findViewById<MaterialButton>(R.id.button_refresh).setOnClickListener { refreshFromServers(manual = true) }

        val dao = tabSSHApp.database.pasteRecordDao()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.observeAll().collect { render(it) }
            }
        }
        refreshFromServers(manual = false)
    }

    /** Asks every Cairnbin server we hold an owner token for which pastes it still lists. */
    private fun refreshFromServers(manual: Boolean) {
        lifecycleScope.launch {
            val servers = withContext(Dispatchers.IO) {
                val known = tabSSHApp.database.pasteRecordDao().getAllList()
                    .filter { it.deleteKind == PasteRecord.DELETE_OWNER }.map { it.serverUrl }
                val fallback = tabSSHApp.preferencesManager.getPasteFallbackUrl()
                (known + listOfNotNull(fallback, tabSSHApp.preferencesManager.getPasteUrl("cairnbin"))).toSet()
            }
            var confirmed = 0
            for (server in servers) {
                if (tracker.syncOwned(server)) confirmed++
            }
            if (manual) {
                Toast.makeText(this@MyPastesActivity, getString(R.string.my_pastes_refreshed_fmt, confirmed, servers.size), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun render(records: List<PasteRecord>) {
        listPastes.removeAllViews()
        textEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        for (record in records) {
            val row = inflater.inflate(R.layout.item_my_paste, listPastes, false)
            row.findViewById<TextView>(R.id.text_title).text = record.title.ifBlank { record.url }
            row.findViewById<TextView>(R.id.text_subtitle).text = getString(
                R.string.my_pastes_subtitle_fmt, format.format(Date(record.createdAt)), record.serverUrl.substringAfter("://"), record.url
            )
            row.findViewById<MaterialButton>(R.id.button_open).setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(record.url)))
            }
            row.findViewById<MaterialButton>(R.id.button_copy).setOnClickListener {
                ClipboardHelper.copy(this, getString(R.string.report_issue_clipboard_label), record.url, sensitive = false)
                Toast.makeText(this, R.string.report_issue_url_copied_toast, Toast.LENGTH_SHORT).show()
            }
            row.findViewById<MaterialButton>(R.id.button_delete).setOnClickListener { confirmDelete(record) }
            listPastes.addView(row)
        }
    }

    private fun confirmDelete(record: PasteRecord) {
        val server = record.deleteKind != PasteRecord.DELETE_NONE
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.my_pastes_delete_title)
            .setMessage(getString(if (server) R.string.my_pastes_delete_server_fmt else R.string.my_pastes_delete_local_fmt, record.url))
            .setPositiveButton(R.string.delete) { _, _ -> delete(record) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun delete(record: PasteRecord) {
        lifecycleScope.launch {
            when (val outcome = tracker.delete(record)) {
                PasteDeleteOutcome.Deleted -> {
                    tracker.forget(record)
                    toast(R.string.my_pastes_deleted)
                }
                PasteDeleteOutcome.NotFound -> {
                    tracker.forget(record)
                    toast(R.string.my_pastes_not_found)
                }
                PasteDeleteOutcome.Unsupported -> {
                    tracker.forget(record)
                    toast(R.string.my_pastes_removed_from_list)
                }
                PasteDeleteOutcome.TokenRejected -> offerListOnlyRemoval(record, getString(R.string.my_pastes_token_rejected))
                is PasteDeleteOutcome.Failed -> offerListOnlyRemoval(record, outcome.message)
            }
        }
    }

    /** The server copy is untouched; let the user drop just the list entry. */
    private fun offerListOnlyRemoval(record: PasteRecord, reason: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.my_pastes_delete_failed_title)
            .setMessage(getString(R.string.my_pastes_delete_failed_fmt, reason))
            .setPositiveButton(R.string.my_pastes_remove_from_list) { _, _ ->
                lifecycleScope.launch { tracker.forget(record) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
}
