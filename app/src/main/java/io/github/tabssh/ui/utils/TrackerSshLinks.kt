package io.github.tabssh.ui.utils

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.storage.database.entities.ConnectionProfile

/**
 * The "SSH" row on a Domain or VPS tracker entry: the saved connections that belong to it (see
 * HostLinkResolver). Tapping the row connects, or asks which one when there are several.
 */
object TrackerSshLinks {

    fun addRow(container: ViewGroup, connections: List<ConnectionProfile>) {
        if (connections.isEmpty()) return
        val context = container.context
        val row = LayoutInflater.from(context).inflate(R.layout.item_tracker_detail_row, container, false)
        row.findViewById<TextView>(R.id.text_detail_label).setText(R.string.tracker_col_ssh)
        row.findViewById<TextView>(R.id.text_detail_value).text =
            context.getString(R.string.tracker_ssh_row_fmt, connections.joinToString(", ") { it.getDisplayName() })
        row.setOnClickListener {
            if (connections.size == 1) {
                ConnectionLauncher.launch(context, connections.single())
            } else {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.tracker_ssh_choose_title)
                    .setItems(connections.map { it.getDisplayName() }.toTypedArray()) { _, which ->
                        ConnectionLauncher.launch(context, connections[which])
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        container.addView(row)
    }
}
