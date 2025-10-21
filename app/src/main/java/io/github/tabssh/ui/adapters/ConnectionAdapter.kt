package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.databinding.ItemConnectionBinding
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.utils.logging.Logger
import java.text.SimpleDateFormat
import java.util.*

/**
 * RecyclerView adapter for displaying SSH connection profiles
 */
class ConnectionAdapter(
    private val onConnectionClick: (ConnectionProfile) -> Unit
) : androidx.recyclerview.widget.ListAdapter<ConnectionProfile, ConnectionAdapter.ConnectionViewHolder>(DiffCallback()) {

    // Alternative constructor for more detailed event handling
    constructor(
        connections: List<ConnectionProfile>,
        onConnectionClick: (ConnectionProfile) -> Unit,
        onConnectionLongClick: (ConnectionProfile) -> Unit,
        onConnectionEdit: (ConnectionProfile) -> Unit,
        onConnectionDelete: (ConnectionProfile) -> Unit
    ) : this(onConnectionClick) {
        submitList(connections)
    }
    
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConnectionViewHolder {
        val binding = ItemConnectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ConnectionViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: ConnectionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    
    inner class ConnectionViewHolder(
        private val binding: ItemConnectionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onConnectionClick(getItem(position))
                }
            }
        }
        
        fun bind(connection: ConnectionProfile) {
            binding.apply {
                // Connection name and details
                textConnectionName.text = connection.name.takeIf { it.isNotBlank() } 
                    ?: connection.getDisplayName()
                
                textConnectionDetails.text = buildString {
                    append("${connection.username}@${connection.host}")
                    if (connection.port != 22) {
                        append(":${connection.port}")
                    }

                    // Add auth type indicator
                    append(" • ${getAuthTypeDisplay(connection.getAuthTypeEnum())}")
                }

                // Show connection count if > 0
                if (connection.connectionCount > 0) {
                    textConnectionCount.visibility = android.view.View.VISIBLE
                    textConnectionCount.text = "Connected ${connection.connectionCount} times"
                } else {
                    textConnectionCount.visibility = android.view.View.GONE
                }
                
                // Connection icon based on type/status
                iconConnection.setImageResource(getConnectionIcon(connection))
                
                // Status indicator
                updateStatusIndicator(connection)
                
                // Accessibility
                root.contentDescription = buildString {
                    append("Connection ${connection.getDisplayName()}")
                    append(". ${connection.username} at ${connection.host}")
                    if (connection.port != 22) {
                        append(" port ${connection.port}")
                    }
                    append(". Authentication: ${getAuthTypeDisplay(connection.getAuthTypeEnum())}")
                    if (connection.lastConnected > 0) {
                        append(". Last connected ${dateFormat.format(Date(connection.lastConnected))}")
                    }
                }
            }
        }
        
        private fun getConnectionIcon(connection: ConnectionProfile): Int {
            return when {
                connection.host.contains("prod") || connection.host.contains("live") -> 
                    io.github.tabssh.R.drawable.ic_computer_secure
                connection.host == "localhost" || connection.host.startsWith("192.168") || connection.host.startsWith("10.") ->
                    io.github.tabssh.R.drawable.ic_computer_local
                else -> io.github.tabssh.R.drawable.ic_computer
            }
        }
        
        private fun getAuthTypeDisplay(authType: io.github.tabssh.ssh.auth.AuthType): String {
            return when (authType) {
                io.github.tabssh.ssh.auth.AuthType.PASSWORD -> "Password"
                io.github.tabssh.ssh.auth.AuthType.PUBLIC_KEY -> "SSH Key"
                io.github.tabssh.ssh.auth.AuthType.KEYBOARD_INTERACTIVE -> "2FA"
                io.github.tabssh.ssh.auth.AuthType.GSSAPI -> "GSSAPI"
            }
        }
        
        private fun updateStatusIndicator(connection: ConnectionProfile) {
            // This would check if connection is currently active
            // For now, show disconnected state
            binding.indicatorStatus.setBackgroundResource(io.github.tabssh.R.drawable.connection_status_disconnected)
            
            // In a real implementation, this would:
            // 1. Check SSHSessionManager for active connections
            // 2. Update indicator color based on connection state
            // 3. Show connecting/connected/error states
        }
    }

    class DiffCallback : androidx.recyclerview.widget.DiffUtil.ItemCallback<ConnectionProfile>() {
        override fun areItemsTheSame(oldItem: ConnectionProfile, newItem: ConnectionProfile): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ConnectionProfile, newItem: ConnectionProfile): Boolean {
            return oldItem == newItem
        }
    }
}