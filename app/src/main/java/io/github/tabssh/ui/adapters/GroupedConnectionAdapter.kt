package io.github.tabssh.ui.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ui.models.ConnectionListItem
import io.github.tabssh.utils.logging.Logger

/**
 * RecyclerView adapter for displaying grouped connections
 * Supports group headers, ungrouped header, and individual connections
 */
class GroupedConnectionAdapter(
    val items: MutableList<ConnectionListItem>,
    private val onConnectionClick: (ConnectionProfile) -> Unit,
    private val onConnectionLongClick: (ConnectionProfile) -> Unit,
    private val onConnectionEdit: (ConnectionProfile) -> Unit,
    private val onConnectionDelete: (ConnectionProfile) -> Unit,
    private val onGroupClick: (ConnectionListItem.GroupHeader) -> Unit,
    private val onGroupLongClick: (ConnectionListItem.GroupHeader) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_GROUP_HEADER = 0
        private const val VIEW_TYPE_CONNECTION = 1
        private const val VIEW_TYPE_UNGROUPED_HEADER = 2
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ConnectionListItem.GroupHeader -> VIEW_TYPE_GROUP_HEADER
            is ConnectionListItem.Connection -> VIEW_TYPE_CONNECTION
            is ConnectionListItem.UngroupedHeader -> VIEW_TYPE_UNGROUPED_HEADER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_GROUP_HEADER -> {
                val view = inflater.inflate(R.layout.item_connection_group, parent, false)
                GroupHeaderViewHolder(view)
            }
            VIEW_TYPE_UNGROUPED_HEADER -> {
                val view = inflater.inflate(R.layout.item_connection_group, parent, false)
                UngroupedHeaderViewHolder(view)
            }
            VIEW_TYPE_CONNECTION -> {
                val view = inflater.inflate(R.layout.item_connection, parent, false)
                ConnectionViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ConnectionListItem.GroupHeader -> {
                (holder as GroupHeaderViewHolder).bind(item)
            }
            is ConnectionListItem.UngroupedHeader -> {
                (holder as UngroupedHeaderViewHolder).bind(item)
            }
            is ConnectionListItem.Connection -> {
                (holder as ConnectionViewHolder).bind(item.profile, item.indentLevel)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    /**
     * ViewHolder for group header
     */
    inner class GroupHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textGroupName: TextView = itemView.findViewById(R.id.text_group_name)
        private val iconExpand: ImageView = itemView.findViewById(R.id.icon_expand)
        private val viewGroupColor: View? = itemView.findViewById(R.id.view_group_color)

        fun bind(item: ConnectionListItem.GroupHeader) {
            textGroupName.text = item.getDisplayText()

            // Set expand/collapse icon
            iconExpand.setImageResource(
                if (item.isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )

            // Set group color if available
            item.group.color?.let { colorHex ->
                try {
                    viewGroupColor?.setBackgroundColor(Color.parseColor(colorHex))
                    viewGroupColor?.visibility = View.VISIBLE
                } catch (e: Exception) {
                    viewGroupColor?.visibility = View.GONE
                }
            } ?: run {
                viewGroupColor?.visibility = View.GONE
            }

            // Click to expand/collapse
            itemView.setOnClickListener {
                onGroupClick(item)
            }

            // Long click for group management
            itemView.setOnLongClickListener {
                onGroupLongClick(item)
                true
            }

            Logger.d("GroupedConnectionAdapter", "Bound group header: ${item.group.name} (${if (item.isExpanded) "expanded" else "collapsed"})")
        }
    }

    /**
     * ViewHolder for ungrouped header
     */
    inner class UngroupedHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textGroupName: TextView = itemView.findViewById(R.id.text_group_name)
        private val iconExpand: ImageView = itemView.findViewById(R.id.icon_expand)
        private val viewGroupColor: View? = itemView.findViewById(R.id.view_group_color)

        fun bind(item: ConnectionListItem.UngroupedHeader) {
            textGroupName.text = item.getDisplayText()

            // Set expand/collapse icon
            iconExpand.setImageResource(
                if (item.isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )

            // No color for ungrouped
            viewGroupColor?.visibility = View.GONE

            // Click to expand/collapse
            itemView.setOnClickListener {
                // Toggle ungrouped expansion (handled by MainActivity)
                Logger.d("GroupedConnectionAdapter", "Clicked ungrouped header")
            }

            Logger.d("GroupedConnectionAdapter", "Bound ungrouped header (${if (item.isExpanded) "expanded" else "collapsed"})")
        }
    }

    /**
     * ViewHolder for connection item (reuses existing item_connection layout)
     */
    inner class ConnectionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textName: TextView = itemView.findViewById(R.id.text_connection_name)
        private val textDetails: TextView = itemView.findViewById(R.id.text_connection_details)
        private val textCount: TextView = itemView.findViewById(R.id.text_connection_count)

        fun bind(profile: ConnectionProfile, indentLevel: Int) {
            textName.text = profile.name
            textDetails.text = "${profile.username}@${profile.host}:${profile.port}"

            // Show connection count if > 0
            if (profile.connectionCount > 0) {
                textCount.text = "Connected ${profile.connectionCount} times"
                textCount.visibility = View.VISIBLE
            } else {
                textCount.visibility = View.GONE
            }

            // Apply indent for grouped items
            val indentPx = indentLevel * 32 * itemView.resources.displayMetrics.density.toInt()
            itemView.setPadding(indentPx, itemView.paddingTop, itemView.paddingRight, itemView.paddingBottom)

            // Click listeners
            itemView.setOnClickListener {
                onConnectionClick(profile)
            }

            itemView.setOnLongClickListener {
                onConnectionLongClick(profile)
                true
            }

            Logger.d("GroupedConnectionAdapter", "Bound connection: ${profile.getDisplayName()} (indent: $indentLevel)")
        }
    }
}
