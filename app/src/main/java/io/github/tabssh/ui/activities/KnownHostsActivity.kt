package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.storage.database.entities.HostKeyEntry
import io.github.tabssh.sync.tombstone.TombstoneRecorder
import io.github.tabssh.utils.ClipboardHelper
import io.github.tabssh.utils.tabSSHApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Every SSH host key this device has accepted: filter, see the details, delete one. */
class KnownHostsActivity : TabSSHActivity() {

    private lateinit var listHosts: LinearLayout
    private lateinit var textEmpty: TextView
    private lateinit var textSummary: TextView
    private var all: List<HostKeyEntry> = emptyList()
    private var filter: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_known_hosts)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setTitle(R.string.known_hosts_title)
        listHosts = findViewById(R.id.list_hosts)
        textEmpty = findViewById(R.id.text_empty)
        textSummary = findViewById(R.id.text_summary)
        findViewById<TextInputEditText>(R.id.edit_filter).doAfterTextChanged {
            filter = it?.toString()?.trim().orEmpty()
            render()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                tabSSHApp.database.hostKeyDao().getAllHostKeys().collect {
                    all = it
                    render()
                }
            }
        }
    }

    private fun render() {
        val shown = all.filter {
            filter.isEmpty() || it.hostname.contains(filter, ignoreCase = true) ||
                it.fingerprint.contains(filter, ignoreCase = true)
        }
        textSummary.text = resources.getQuantityString(R.plurals.known_hosts_summary, all.size, all.size)
        listHosts.removeAllViews()
        textEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val inflater = LayoutInflater.from(this)
        for (key in shown) {
            val row = inflater.inflate(R.layout.item_known_host, listHosts, false)
            row.findViewById<TextView>(R.id.text_title).text = "${key.hostname}:${key.port}"
            row.findViewById<TextView>(R.id.text_subtitle).text = getString(
                R.string.known_hosts_subtitle_fmt, key.keyType, key.fingerprint, format.format(Date(key.firstSeen))
            )
            row.findViewById<MaterialButton>(R.id.button_details).setOnClickListener { showDetails(key, format) }
            row.findViewById<MaterialButton>(R.id.button_delete).setOnClickListener { confirmDelete(key) }
            listHosts.addView(row)
        }
    }

    private fun showDetails(key: HostKeyEntry, format: DateFormat) {
        MaterialAlertDialogBuilder(this)
            .setTitle("${key.hostname}:${key.port}")
            .setMessage(
                getString(
                    R.string.known_hosts_details_fmt, key.keyType, key.fingerprint,
                    format.format(Date(key.firstSeen)), format.format(Date(key.lastVerified)), key.trustLevel
                )
            )
            .setPositiveButton(R.string.known_hosts_copy_fingerprint) { _, _ ->
                ClipboardHelper.copy(this, getString(R.string.known_hosts_title), key.fingerprint, sensitive = false)
                Toast.makeText(this, R.string.known_hosts_fingerprint_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun confirmDelete(key: HostKeyEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.known_hosts_delete_title)
            .setMessage(getString(R.string.known_hosts_delete_message_fmt, "${key.hostname}:${key.port}"))
            .setPositiveButton(R.string.delete) { _, _ ->
                tabSSHApp.applicationScope.launch(Dispatchers.IO) {
                    tabSSHApp.database.hostKeyDao().deleteHostKeyById(key.id)
                    TombstoneRecorder.record(tabSSHApp, TombstoneRecorder.HOST_KEY, key.id)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
