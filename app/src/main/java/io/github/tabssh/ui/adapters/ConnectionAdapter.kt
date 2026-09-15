package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.databinding.ItemConnectionBinding
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.utils.Format

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

    // Multi-select rendering state — the owning fragment tracks WHICH ids are
    // selected; the adapter only needs it to draw the checked overlay per row.
    private var selectionMode = false
    private var selectedIds: Set<String> = emptySet()

    /** Update the group-name map and refresh visible items. */
    fun updateGroupNames(names: Map<String, String>) {
        groupNames = names
        notifyItemRangeChanged(0, itemCount)
    }

    /** Enable/disable selection mode and set the currently selected ids. */
    fun setSelection(active: Boolean, ids: Set<String>) {
        selectionMode = active
        selectedIds = ids.toSet()
        notifyItemRangeChanged(0, itemCount)
    }
    
    fun setOnItemLongClickListener(listener: (ConnectionProfile) -> Boolean) {
        onItemLongClickListener = listener
    }

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
                // Selection mode — MaterialCardView's checkable state draws the
                // stock checked overlay, making the selection visible per row.
                (root as? com.google.android.material.card.MaterialCardView)?.let { card ->
                    card.isCheckable = selectionMode
                    card.isChecked = selectionMode && connection.id in selectedIds
                }

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

                // Show connection count + last-connected relative time if > 0.
                // Reuses this single existing TextView rather than adding a
                // new layout view for the relative-time subtitle.
                if (connection.connectionCount > 0) {
                    textConnectionCount.visibility = android.view.View.VISIBLE
                    val ctx = binding.root.context
                    textConnectionCount.text = buildString {
                        append(Format.connectedTimes(ctx, connection.connectionCount))
                        if (connection.lastConnected > 0) {
                            append(" • ")
                            append(Format.pastTimestamp(ctx, connection.lastConnected))
                        }
                    }
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
                    textGroupBadge.text = binding.root.context.getString(
                        io.github.tabssh.R.string.connection_group_badge_fmt, groupName
                    )
                } else {
                    textGroupBadge.visibility = android.view.View.GONE
                }

                // Status indicator
                updateStatusIndicator(connection)

                // Accessibility — each sentence is its own translated string so
                // no locale ever sees stitched-together English fragments.
                val ctx = binding.root.context
                val sentences = mutableListOf<String>()
                sentences += if (connection.port != 22) {
                    ctx.getString(
                        io.github.tabssh.R.string.connection_a11y_port_fmt,
                        connection.getDisplayName(), connection.username,
                        connection.host, connection.port
                    )
                } else {
                    ctx.getString(
                        io.github.tabssh.R.string.connection_a11y_fmt,
                        connection.getDisplayName(), connection.username, connection.host
                    )
                }
                sentences += ctx.getString(
                    io.github.tabssh.R.string.connection_a11y_auth_fmt,
                    getAuthTypeDisplay(connection.getAuthTypeEnum())
                )
                if (connection.lastConnected > 0) {
                    sentences += ctx.getString(
                        io.github.tabssh.R.string.connection_a11y_last_connected_fmt,
                        Format.pastTimestamp(ctx, connection.lastConnected)
                    )
                }
                root.contentDescription = sentences.joinToString(". ")
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
            val ctx = binding.root.context
            return when (authType) {
                io.github.tabssh.ssh.auth.AuthType.PASSWORD ->
                    ctx.getString(io.github.tabssh.R.string.auth_type_password)
                io.github.tabssh.ssh.auth.AuthType.PUBLIC_KEY ->
                    ctx.getString(io.github.tabssh.R.string.identity_auth_type_ssh_key)
                io.github.tabssh.ssh.auth.AuthType.KEYBOARD_INTERACTIVE ->
                    ctx.getString(io.github.tabssh.R.string.auth_type_2fa)
            }
        }
        
        private fun updateStatusIndicator(connection: ConnectionProfile) {
            // Green when the profile has a live session (pooled SSH connection
            // OR a connected tab — the tab check covers mosh, whose bootstrap
            // SSH connection is gone after the handshake), grey otherwise.
            // Pulled off the singleton on the application object — no DI here.
            val app = binding.root.context.applicationContext
                as? io.github.tabssh.TabSSHApplication
            val active = app?.hasLiveSessionFor(connection.id) == true
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
