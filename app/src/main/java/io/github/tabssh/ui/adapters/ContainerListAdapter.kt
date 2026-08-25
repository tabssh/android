package io.github.tabssh.ui.adapters

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import io.github.tabssh.R
import io.github.tabssh.ui.utils.ContainerText
import io.github.tabssh.containers.transport.ContainerSummary

/**
 * Container list rows: status dot, name, image, ports, the pending-update
 * badge ([updatePendingNames]), and an always-visible quick-action strip —
 * state-aware start/stop, logs, exec terminal, and an overflow sheet.
 */
class ContainerListAdapter(
    private var containers: List<ContainerSummary> = emptyList()
) : RecyclerView.Adapter<ContainerListAdapter.ViewHolder>() {

    private var pendingUpdateNames: Set<String> = emptySet()
    private var onItemClickListener: ((ContainerSummary) -> Unit)? = null
    private var onPrimaryActionListener: ((ContainerSummary) -> Unit)? = null
    private var onLogsClickListener: ((ContainerSummary) -> Unit)? = null
    private var onTerminalClickListener: ((ContainerSummary) -> Unit)? = null
    private var onMoreClickListener: ((ContainerSummary) -> Unit)? = null

    fun setOnItemClickListener(listener: (ContainerSummary) -> Unit) {
        onItemClickListener = listener
    }

    /** State-aware lifecycle button: start when stopped, stop when running, unpause when paused. */
    fun setOnPrimaryActionListener(listener: (ContainerSummary) -> Unit) {
        onPrimaryActionListener = listener
    }

    fun setOnLogsClickListener(listener: (ContainerSummary) -> Unit) {
        onLogsClickListener = listener
    }

    fun setOnTerminalClickListener(listener: (ContainerSummary) -> Unit) {
        onTerminalClickListener = listener
    }

    /** Overflow button and long-press both open the full action sheet. */
    fun setOnMoreClickListener(listener: (ContainerSummary) -> Unit) {
        onMoreClickListener = listener
    }

    fun updateList(newList: List<ContainerSummary>) {
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
            .inflate(R.layout.item_container, parent, false)
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
        private val buttonPrimary: ImageButton = itemView.findViewById(R.id.button_primary_action)
        private val buttonLogs: ImageButton = itemView.findViewById(R.id.button_logs)
        private val buttonTerminal: ImageButton = itemView.findViewById(R.id.button_terminal)
        private val buttonMore: ImageButton = itemView.findViewById(R.id.button_more)

        init {
            itemView.setOnClickListener {
                current()?.let { onItemClickListener?.invoke(it) }
            }
            itemView.setOnLongClickListener {
                current()?.let { onMoreClickListener?.invoke(it); true } ?: false
            }
            buttonPrimary.setOnClickListener {
                current()?.let { onPrimaryActionListener?.invoke(it) }
            }
            buttonLogs.setOnClickListener {
                current()?.let { onLogsClickListener?.invoke(it) }
            }
            buttonTerminal.setOnClickListener {
                current()?.let { onTerminalClickListener?.invoke(it) }
            }
            buttonMore.setOnClickListener {
                current()?.let { onMoreClickListener?.invoke(it) }
            }
        }

        private fun current(): ContainerSummary? {
            val position = bindingAdapterPosition
            return if (position != RecyclerView.NO_POSITION) containers[position] else null
        }

        fun bind(container: ContainerSummary) {
            val context = itemView.context
            val name = container.names.firstOrNull()?.removePrefix("/")
                ?: container.id.take(12)
            // Name, image, status and ports all originate on the remote daemon
            // — strip control/bidi characters and cap them so a crafted label
            // cannot reorder the row or blow up the layout pass.
            textName.text = ContainerText.display(name)
            textImage.text = ContainerText.display(container.image)
            textStatus.text = ContainerText.display(container.status)

            if (container.ports.isBlank()) {
                textPorts.visibility = View.GONE
            } else {
                textPorts.visibility = View.VISIBLE
                textPorts.text = ContainerText.display(container.ports)
            }

            // Status dot: semantic color resources — green running, amber
            // paused/restarting, red exited/dead, outline grey otherwise.
            val dotColor = when (container.state) {
                "running" -> ContextCompat.getColor(context, R.color.status_success)
                "paused", "restarting" -> ContextCompat.getColor(context, R.color.status_warning)
                "exited", "dead" -> ContextCompat.getColor(context, R.color.status_error)
                else -> MaterialColors.getColor(
                    itemView, com.google.android.material.R.attr.colorOutline
                )
            }
            viewStatus.backgroundTintList = ColorStateList.valueOf(dotColor)

            // Primary lifecycle button follows state; exec only exists while running.
            val running = container.state == "running"
            val paused = container.state == "paused"
            val primaryLabel = when {
                running -> {
                    buttonPrimary.setImageResource(R.drawable.ic_stop)
                    context.getString(R.string.container_action_stop)
                }
                paused -> {
                    buttonPrimary.setImageResource(R.drawable.ic_play)
                    context.getString(R.string.container_action_unpause)
                }
                else -> {
                    buttonPrimary.setImageResource(R.drawable.ic_play)
                    context.getString(R.string.container_action_start)
                }
            }
            buttonTerminal.visibility = if (running) View.VISIBLE else View.GONE

            // TalkBack: scope every action button to its container so rows do
            // not all announce identical "Stop" / "Logs" / "More actions".
            buttonPrimary.contentDescription =
                context.getString(R.string.container_row_action_desc_fmt, primaryLabel, name)
            buttonLogs.contentDescription = context.getString(
                R.string.container_row_action_desc_fmt,
                context.getString(R.string.container_action_logs), name
            )
            buttonTerminal.contentDescription = context.getString(
                R.string.container_row_action_desc_fmt,
                context.getString(R.string.container_action_terminal), name
            )
            buttonMore.contentDescription = context.getString(
                R.string.container_row_action_desc_fmt,
                context.getString(R.string.container_more_actions_desc), name
            )

            textUpdateBadge.visibility =
                if (name in pendingUpdateNames) View.VISIBLE else View.GONE
        }
    }
}
