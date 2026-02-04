package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.ssh.forwarding.Tunnel
import io.github.tabssh.ssh.forwarding.TunnelState
import io.github.tabssh.ssh.forwarding.TunnelType

/**
 * Adapter for displaying SSH tunnels in RecyclerView
 */
class TunnelAdapter(
    private val onStart: (Tunnel) -> Unit,
    private val onStop: (Tunnel) -> Unit,
    private val onDelete: (Tunnel) -> Unit
) : ListAdapter<Tunnel, TunnelAdapter.TunnelViewHolder>(TunnelDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TunnelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tunnel, parent, false)
        return TunnelViewHolder(view)
    }

    override fun onBindViewHolder(holder: TunnelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TunnelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconType: ImageView = itemView.findViewById(R.id.icon_tunnel_type)
        private val textType: TextView = itemView.findViewById(R.id.text_tunnel_type)
        private val textDetails: TextView = itemView.findViewById(R.id.text_tunnel_details)
        private val textStatus: TextView = itemView.findViewById(R.id.text_tunnel_status)
        private val btnToggle: ImageButton = itemView.findViewById(R.id.btn_toggle_tunnel)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete_tunnel)

        fun bind(tunnel: Tunnel) {
            // Set tunnel type icon and label
            when (tunnel.type) {
                TunnelType.LOCAL_FORWARD -> {
                    iconType.setImageResource(R.drawable.ic_upload)
                    textType.text = "Local Forward (-L)"
                    textDetails.text = "${tunnel.localPort} → ${tunnel.remoteHost}:${tunnel.remotePort}"
                }
                TunnelType.REMOTE_FORWARD -> {
                    iconType.setImageResource(R.drawable.ic_download)
                    textType.text = "Remote Forward (-R)"
                    textDetails.text = "${tunnel.remotePort} → ${tunnel.localHost}:${tunnel.localPort}"
                }
                TunnelType.DYNAMIC_FORWARD -> {
                    iconType.setImageResource(R.drawable.ic_interface)
                    textType.text = "Dynamic/SOCKS (-D)"
                    textDetails.text = "SOCKS proxy on port ${tunnel.localPort}"
                }
            }

            // Set status based on tunnel.state
            val statusText = when (tunnel.state) {
                TunnelState.ACTIVE -> "🟢 Active"
                TunnelState.CONNECTING -> "🟡 Connecting..."
                TunnelState.STOPPED -> "⚪ Stopped"
                TunnelState.ERROR -> "🔴 Error${tunnel.lastError?.let { ": $it" } ?: ""}"
            }
            textStatus.text = statusText

            // Toggle button
            val isActive = tunnel.state == TunnelState.ACTIVE
            if (isActive) {
                btnToggle.setImageResource(R.drawable.ic_close)
                btnToggle.setOnClickListener { onStop(tunnel) }
            } else {
                btnToggle.setImageResource(R.drawable.ic_add)
                btnToggle.setOnClickListener { onStart(tunnel) }
            }

            // Delete button
            btnDelete.setOnClickListener { onDelete(tunnel) }
        }
    }

    class TunnelDiffCallback : DiffUtil.ItemCallback<Tunnel>() {
        override fun areItemsTheSame(oldItem: Tunnel, newItem: Tunnel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Tunnel, newItem: Tunnel): Boolean {
            return oldItem.state == newItem.state &&
                   oldItem.lastError == newItem.lastError &&
                   oldItem.activeConnections == newItem.activeConnections
        }
    }
}
