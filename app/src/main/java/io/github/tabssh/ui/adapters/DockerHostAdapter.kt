package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.storage.database.entities.DockerHost
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Docker host list rows for DockerHostsFragment (PLAN.AI.md step 19).
 * Same DiffUtil update pattern as HypervisorAdapter; [connectionNames]
 * maps ConnectionProfile.id → display name for the linked-connection line.
 */
class DockerHostAdapter(
    private var hosts: List<DockerHost> = emptyList()
) : RecyclerView.Adapter<DockerHostAdapter.ViewHolder>() {

    private var connectionNames: Map<String, String> = emptyMap()
    private var onItemClickListener: ((DockerHost) -> Unit)? = null
    private var onItemLongClickListener: ((DockerHost) -> Unit)? = null
    private var onMoreClickListener: ((DockerHost) -> Unit)? = null

    fun setOnItemClickListener(listener: (DockerHost) -> Unit) {
        onItemClickListener = listener
    }

    fun setOnItemLongClickListener(listener: (DockerHost) -> Unit) {
        onItemLongClickListener = listener
    }

    /** Row overflow button — opens the same action sheet as a long-press. */
    fun setOnMoreClickListener(listener: (DockerHost) -> Unit) {
        onMoreClickListener = listener
    }

    fun updateList(newList: List<DockerHost>, newConnectionNames: Map<String, String>) {
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
            .inflate(R.layout.item_docker_host, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(hosts[position])
    }

    override fun getItemCount(): Int = hosts.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textName: TextView = itemView.findViewById(R.id.text_name)
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

        fun bind(host: DockerHost) {
            textName.text = host.name

            // Transport-tier badge: the persisted mode, or "auto" pre-detection.
            textTransportMode.text = host.transportMode

            // Linked SSH connection name; custom endpoints show user@host;
            // final fallback is the raw socket path.
            textConnection.text = host.linkedConnectionId
                ?.let { connectionNames[it] }
                ?: host.customHost?.takeIf { it.isNotBlank() }?.let { endpoint ->
                    host.customUsername?.takeIf { it.isNotBlank() }
                        ?.let { "$it@$endpoint" } ?: endpoint
                }
                ?: host.socketPath

            if (host.lastConnected > 0) {
                val relativeTime = getRelativeTime(host.lastConnected)
                textLastConnected.text =
                    itemView.context.getString(R.string.docker_last_used_fmt, relativeTime)
            } else {
                textLastConnected.text =
                    itemView.context.getString(R.string.docker_never_connected)
            }
        }

        private fun getRelativeTime(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp

            return when {
                diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
                diff < TimeUnit.HOURS.toMillis(1) -> {
                    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
                    "$minutes ${if (minutes == 1L) "minute" else "minutes"} ago"
                }
                diff < TimeUnit.DAYS.toMillis(1) -> {
                    val hours = TimeUnit.MILLISECONDS.toHours(diff)
                    "$hours ${if (hours == 1L) "hour" else "hours"} ago"
                }
                diff < TimeUnit.DAYS.toMillis(7) -> {
                    val days = TimeUnit.MILLISECONDS.toDays(diff)
                    "$days ${if (days == 1L) "day" else "days"} ago"
                }
                else -> {
                    val formatter = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                    formatter.format(Date(timestamp))
                }
            }
        }
    }
}
