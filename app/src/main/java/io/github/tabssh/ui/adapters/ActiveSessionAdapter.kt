package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.ssh.connection.ConnectionState
import io.github.tabssh.ui.tabs.SSHTab

/**
 * Issue #165 — adapter for the "Active Sessions" strip in the Connections
 * tab. Backed by a snapshot list of [SSHTab]s; each item shows the tab's
 * current display title (dynamic terminal title if the remote set one,
 * default `user@host:port` otherwise) plus a coloured dot indicating the
 * tab's connection state. Tap → callback with the tab's [SSHTab.tabId]
 * which the fragment uses to launch [TabTerminalActivity] focused on
 * that tab.
 *
 * Disambiguation for tabs that share a default title (multiple tabs to
 * the same host with no OSC title set) is handled by the fragment before
 * passing the items in: it appends `(#N)` based on stable index. So the
 * adapter just renders whatever string the fragment hands it.
 */
class ActiveSessionAdapter(
    private val onTabClick: (tabId: String) -> Unit
) : RecyclerView.Adapter<ActiveSessionAdapter.VH>() {

    data class Row(
        val tabId: String,
        val title: String,
        val state: ConnectionState
    )

    private var items: List<Row> = emptyList()

    fun submit(rows: List<Row>) {
        val old = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = rows.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition].tabId == rows[newItemPosition].tabId
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition] == rows[newItemPosition]
        })
        items = rows
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_active_session, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        holder.title.text = row.title
        holder.dot.setBackgroundResource(dotForState(row.state))
        holder.itemView.setOnClickListener { onTabClick(row.tabId) }
    }

    private fun dotForState(s: ConnectionState): Int = when (s) {
        ConnectionState.CONNECTED -> R.drawable.state_dot_connected
        ConnectionState.CONNECTING,
        ConnectionState.AUTHENTICATING -> R.drawable.state_dot_connecting
        ConnectionState.ERROR -> R.drawable.state_dot_error
        ConnectionState.DISCONNECTED -> R.drawable.state_dot_disconnected
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.text_title)
        val dot: View = view.findViewById(R.id.state_dot)
    }
}
