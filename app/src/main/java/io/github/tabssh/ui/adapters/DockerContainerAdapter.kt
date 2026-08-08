package io.github.tabssh.ui.adapters

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.docker.transport.DockerContainerSummary

/**
 * Container list rows (PLAN.AI.md step 22): status dot, name, image, ports,
 * and the pending-update badge driven by ContainerAutoUpdatePolicy rows whose
 * pendingUpdateDigest is set ([updatePendingNames]).
 */
class DockerContainerAdapter(
    private var containers: List<DockerContainerSummary> = emptyList()
) : RecyclerView.Adapter<DockerContainerAdapter.ViewHolder>() {

    private var pendingUpdateNames: Set<String> = emptySet()
    private var onItemClickListener: ((DockerContainerSummary) -> Unit)? = null
    private var onItemLongClickListener: ((DockerContainerSummary) -> Unit)? = null

    fun setOnItemClickListener(listener: (DockerContainerSummary) -> Unit) {
        onItemClickListener = listener
    }

    fun setOnItemLongClickListener(listener: (DockerContainerSummary) -> Unit) {
        onItemLongClickListener = listener
    }

    fun updateList(newList: List<DockerContainerSummary>) {
        val old = containers
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition].id == newList[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition] == newList[newItemPosition]
        })
        containers = newList
        diff.dispatchUpdatesTo(this)
    }

    /** Container names with a pending update — shows the badge on their rows. */
    fun updatePendingNames(names: Set<String>) {
        if (names == pendingUpdateNames) return
        pendingUpdateNames = names
        notifyItemRangeChanged(0, containers.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_docker_container, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(containers[position])
    }

    override fun getItemCount(): Int = containers.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val viewStatus: View = itemView.findViewById(R.id.view_status)
        private val textName: TextView = itemView.findViewById(R.id.text_name)
        private val textUpdateBadge: TextView = itemView.findViewById(R.id.text_update_badge)
        private val textImage: TextView = itemView.findViewById(R.id.text_image)
        private val textStatus: TextView = itemView.findViewById(R.id.text_status)
        private val textPorts: TextView = itemView.findViewById(R.id.text_ports)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClickListener?.invoke(containers[position])
                }
            }

            itemView.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemLongClickListener?.invoke(containers[position])
                    true
                } else {
                    false
                }
            }
        }

        fun bind(container: DockerContainerSummary) {
            val name = container.names.firstOrNull()?.removePrefix("/")
                ?: container.id.take(12)
            textName.text = name
            textImage.text = container.image
            textStatus.text = container.status

            if (container.ports.isBlank()) {
                textPorts.visibility = View.GONE
            } else {
                textPorts.visibility = View.VISIBLE
                textPorts.text = container.ports
            }

            // Status dot color: green=running, amber=paused/restarting,
            // red=exited/dead, grey=created (CloudInstanceAdapter palette).
            val dotColor = when (container.state) {
                "running" -> 0xFF4CAF50.toInt()
                "paused", "restarting" -> 0xFFFF9800.toInt()
                "exited", "dead" -> 0xFFF44336.toInt()
                else -> 0xFF9E9E9E.toInt()
            }
            viewStatus.backgroundTintList = ColorStateList.valueOf(dotColor)

            textUpdateBadge.visibility =
                if (name in pendingUpdateNames) View.VISIBLE else View.GONE
        }
    }
}
