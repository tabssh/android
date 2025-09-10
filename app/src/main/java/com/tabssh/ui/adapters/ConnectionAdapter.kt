package com.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tabssh.databinding.ItemConnectionBinding
import com.tabssh.storage.database.entities.ConnectionProfile
import com.tabssh.utils.logging.Logger
import java.text.SimpleDateFormat
import java.util.*

/**
 * RecyclerView adapter for displaying SSH connection profiles
 */
class ConnectionAdapter(
    private val connections: List<ConnectionProfile>,
    private val onConnectionClick: (ConnectionProfile) -> Unit,
    private val onConnectionLongClick: (ConnectionProfile) -> Unit,
    private val onConnectionEdit: (ConnectionProfile) -> Unit,
    private val onConnectionDelete: (ConnectionProfile) -> Unit
) : RecyclerView.Adapter<ConnectionAdapter.ConnectionViewHolder>() {
    
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConnectionViewHolder {
        val binding = ItemConnectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ConnectionViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: ConnectionViewHolder, position: Int) {
        val connection = connections[position]
        holder.bind(connection)
    }
    
    override fun getItemCount(): Int = connections.size
    
    inner class ConnectionViewHolder(
        private val binding: ItemConnectionBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        init {
            // Set up click listeners
            binding.root.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onConnectionClick(connections[position])
                }
            }
            
            binding.root.setOnLongClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onConnectionLongClick(connections[position])
                    true
                } else {
                    false
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
                    
                    // Add last connected info
                    if (connection.lastConnected > 0) {
                        append(" • Last: ${dateFormat.format(Date(connection.lastConnected))}")
                    }
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
                    com.tabssh.R.drawable.ic_computer_secure
                connection.host == "localhost" || connection.host.startsWith("192.168") || connection.host.startsWith("10.") ->
                    com.tabssh.R.drawable.ic_computer_local
                else -> com.tabssh.R.drawable.ic_computer
            }
        }
        
        private fun getAuthTypeDisplay(authType: com.tabssh.ssh.auth.AuthType): String {
            return when (authType) {
                com.tabssh.ssh.auth.AuthType.PASSWORD -> "Password"
                com.tabssh.ssh.auth.AuthType.PUBLIC_KEY -> "SSH Key"
                com.tabssh.ssh.auth.AuthType.KEYBOARD_INTERACTIVE -> "2FA"
                com.tabssh.ssh.auth.AuthType.GSSAPI -> "GSSAPI"
            }
        }
        
        private fun updateStatusIndicator(connection: ConnectionProfile) {
            // This would check if connection is currently active
            // For now, show disconnected state
            binding.indicatorStatus.setBackgroundResource(com.tabssh.R.drawable.connection_status_disconnected)
            
            // In a real implementation, this would:
            // 1. Check SSHSessionManager for active connections
            // 2. Update indicator color based on connection state
            // 3. Show connecting/connected/error states
        }
    }
}