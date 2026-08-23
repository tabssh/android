package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.ssh.connection.ConnectionState

/**
 * Issue #165 follow-up — vertical, unclipped list for the "See all" dialog
 * launched from the Active Sessions strip. Same row data as
 * [ActiveSessionAdapter] plus a type subtitle (SSH/VNC/Console/Panes) so
 * sessions that share a display label are still distinguishable once every
 * active session is shown at once, not just the ones that fit on screen.
 */
class AllActiveSessionsAdapter(
    private val onTabClick: (tabId: String) -> Unit
) : RecyclerView.Adapter<AllActiveSessionsAdapter.VH>() {

    data class Row(
        val tabId: String,
        val title: String,
        val subtitle: String,
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
            .inflate(R.layout.item_active_session_full, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        holder.title.text = row.title
        holder.subtitle.text = row.subtitle
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
        val subtitle: TextView = view.findViewById(R.id.text_subtitle)
        val dot: View = view.findViewById(R.id.state_dot)
    }
}
