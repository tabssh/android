package io.github.tabssh.ui.adapters

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.storage.database.entities.ContainerHost
import io.github.tabssh.ui.utils.ContainerEngineLabels

/**
 * Container host list rows for ContainerHostsFragment.
 * Same DiffUtil update pattern as HypervisorAdapter; [connectionNames]
 * maps ConnectionProfile.id → display name for the linked-connection line.
 *
 * Each row leads with its engine — glyph plus a spelled-out badge — so a list
 * mixing Docker, Incus, Podman and LXC/LXD hosts is readable at a glance.
 */
class ContainerHostAdapter(
    private var hosts: List<ContainerHost> = emptyList()
) : RecyclerView.Adapter<ContainerHostAdapter.ViewHolder>() {

    private var connectionNames: Map<String, String> = emptyMap()
    private var onItemClickListener: ((ContainerHost) -> Unit)? = null
    private var onItemLongClickListener: ((ContainerHost) -> Unit)? = null
    private var onMoreClickListener: ((ContainerHost) -> Unit)? = null

    fun setOnItemClickListener(listener: (ContainerHost) -> Unit) {
        onItemClickListener = listener
    }

    fun setOnItemLongClickListener(listener: (ContainerHost) -> Unit) {
        onItemLongClickListener = listener
    }

    /** Row overflow button — opens the same action sheet as a long-press. */
    fun setOnMoreClickListener(listener: (ContainerHost) -> Unit) {
        onMoreClickListener = listener
    }

    fun updateList(newList: List<ContainerHost>, newConnectionNames: Map<String, String>) {
        val old = hosts
        connectionNames = newConnectionNames
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition].id == newList[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition] == newList[newItemPosition]
        })
        hosts = newList
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_container_host, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(hosts[position])
    }

    override fun getItemCount(): Int = hosts.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textName: TextView = itemView.findViewById(R.id.text_name)
        private val textEngineIcon: TextView = itemView.findViewById(R.id.text_engine_icon)
        private val textEngine: TextView = itemView.findViewById(R.id.text_engine)
        private val textTransportMode: TextView = itemView.findViewById(R.id.text_transport_mode)
        private val textConnection: TextView = itemView.findViewById(R.id.text_connection)
        private val textLastConnected: TextView = itemView.findViewById(R.id.text_last_connected)
        private val buttonMore: ImageButton = itemView.findViewById(R.id.button_more)

        init {
            buttonMore.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onMoreClickListener?.invoke(hosts[position])
                }
            }

            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClickListener?.invoke(hosts[position])
                }
            }

            itemView.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemLongClickListener?.invoke(hosts[position])
                    true
                } else {
                    false
                }
            }
        }

        fun bind(host: ContainerHost) {
            val context = itemView.context
            textName.text = host.name

            val engine = host.engineType()
            textEngineIcon.text = context.getString(ContainerEngineLabels.engineIcon(engine))
            val engineName = context.getString(ContainerEngineLabels.engineName(engine))
            textEngine.text = engineName

            // Transport-tier badge: the configured/detected tier in plain
            // language — this is NOT a live connection status (that's
            // textLastConnected below); an unstarted host reads "Auto-detect"
            // rather than leaking the stored "auto" identifier.
            textTransportMode.text =
                context.getString(ContainerEngineLabels.transportMode(host.transportMode))

            // Linked SSH connection name; custom endpoints show user@host;
            // a host with neither yet reads as auto-detecting its socket.
            val endpointLabel = host.linkedConnectionId
                ?.let { connectionNames[it] }
                ?: host.customHost?.takeIf { it.isNotBlank() }?.let { endpoint ->
                    host.customUsername?.takeIf { it.isNotBlank() }
                        ?.let { "$it@$endpoint" } ?: endpoint
                }
                ?: host.socketCandidates().first()
            textConnection.text = endpointLabel

            // One announcement for the whole row: TalkBack would otherwise read
            // four disconnected fragments and the decorative engine glyph.
            itemView.contentDescription = context.getString(
                R.string.container_host_row_desc, host.name, engineName, endpointLabel
            )

            if (host.lastConnected > 0) {
                // Localized relative time ("5 minutes ago") from the platform
                // formatter — replaces hand-built English-only strings.
                val relativeTime = DateUtils.getRelativeTimeSpanString(
                    host.lastConnected,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                )
                textLastConnected.text =
                    itemView.context.getString(R.string.container_last_used_fmt, relativeTime)
            } else {
                textLastConnected.text =
                    itemView.context.getString(R.string.container_never_connected)
            }
        }
    }
}
