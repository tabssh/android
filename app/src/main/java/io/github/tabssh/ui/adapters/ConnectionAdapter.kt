package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.databinding.ItemConnectionBinding
import io.github.tabssh.storage.database.entities.ConnectionProfile
import java.text.SimpleDateFormat
import java.util.*

/**
 * RecyclerView adapter for displaying SSH connection profiles.
 *
 * Pass [groupNames] (groupId → display name) to show a group badge on each
 * connection card that belongs to a group. The map defaults to empty so all
 * existing usages (widget config, frequent tab, etc.) continue to work without
 * any change.
 */
class ConnectionAdapter(
    private val onConnectionClick: (ConnectionProfile) -> Unit
) : androidx.recyclerview.widget.ListAdapter<ConnectionProfile, ConnectionAdapter.ConnectionViewHolder>(DiffCallback()) {

    // Group id → display name map. When non-empty, cards belonging to a group
    // show a badge (e.g. "• Production"). Set via [updateGroupNames]; defaults
    // to empty so all existing usages keep working without any change.
    private var groupNames: Map<String, String> = emptyMap()

    private var onItemLongClickListener: ((ConnectionProfile) -> Boolean)? = null

    /** Update the group-name map and refresh visible items. */
    fun updateGroupNames(names: Map<String, String>) {
        groupNames = names
        notifyItemRangeChanged(0, itemCount)
    }
    
    fun setOnItemLongClickListener(listener: (ConnectionProfile) -> Boolean) {
        onItemLongClickListener = listener
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
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onConnectionClick(getItem(position))
                }
            }
            
            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemLongClickListener?.invoke(getItem(position)) ?: false
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

                // Wave 3.1 — color tag strip
                if (connection.colorTag != 0) {
                    colorTagStrip.visibility = android.view.View.VISIBLE
                    colorTagStrip.setBackgroundColor(connection.colorTag)
                } else {
                    colorTagStrip.visibility = android.view.View.GONE
                }
                
                // Group badge — shown when connection belongs to a known group
                val groupName = connection.groupId?.let { groupNames[it] }
                if (groupName != null) {
                    textGroupBadge.visibility = android.view.View.VISIBLE
                    textGroupBadge.text = "• $groupName"
                } else {
                    textGroupBadge.visibility = android.view.View.GONE
                }

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
            }
        }
        
        private fun updateStatusIndicator(connection: ConnectionProfile) {
            // Green when SSHSessionManager has a live channel for this
            // profile id, grey otherwise. Pulled off the singleton on
            // the application object — no DI here.
            val app = binding.root.context.applicationContext
                as? io.github.tabssh.TabSSHApplication
            val active = app?.sshSessionManager?.isConnectionActive(connection.id) == true
            binding.indicatorStatus.setBackgroundResource(
                if (active) io.github.tabssh.R.drawable.connection_status_indicator
                else        io.github.tabssh.R.drawable.connection_status_disconnected
            )
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