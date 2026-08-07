package io.github.tabssh.ui.adapters

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import io.github.tabssh.R
import io.github.tabssh.storage.database.entities.ForwardType
import io.github.tabssh.storage.database.entities.PortForward

/**
 * RecyclerView adapter for saved port-forward rules.
 *
 * Running state is not part of [PortForward] itself, so it is tracked
 * separately in [runningIds] and refreshed by the host activity whenever a
 * tunnel starts or stops.
 */
class PortForwardAdapter(
    private val onToggle: (PortForward) -> Unit,
    private val onEdit: (PortForward) -> Unit,
    private val onDelete: (PortForward) -> Unit
) : ListAdapter<PortForward, PortForwardAdapter.ForwardViewHolder>(DiffCallback()) {

    // ConnectionProfile.id -> display name, so a saved-connection endpoint can
    // be shown by name rather than an opaque UUID.
    private var connectionNames: Map<String, String> = emptyMap()

    // Ids of forwards with a live tunnel right now.
    private var runningIds: Set<String> = emptySet()

    fun setConnectionNames(names: Map<String, String>) {
        connectionNames = names
        notifyItemRangeChanged(0, itemCount)
    }

    fun setRunningIds(ids: Set<String>) {
        runningIds = ids
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ForwardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_port_forward, parent, false)
        return ForwardViewHolder(view)
    }

    override fun onBindViewHolder(holder: ForwardViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ForwardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val textName: TextView = itemView.findViewById(R.id.text_name)
        private val chipType: Chip = itemView.findViewById(R.id.chip_type)
        private val textSummary: TextView = itemView.findViewById(R.id.text_summary)
        private val textEndpoint: TextView = itemView.findViewById(R.id.text_endpoint)
        private val textStatus: TextView = itemView.findViewById(R.id.text_status)
        private val btnToggle: MaterialButton = itemView.findViewById(R.id.btn_toggle)
        private val btnMore: MaterialButton = itemView.findViewById(R.id.btn_more)

        fun bind(pf: PortForward) {
            val context = itemView.context

            textName.text = pf.name.ifBlank { pf.getSummary() }

            chipType.text = when (pf.forwardType) {
                ForwardType.LOCAL -> context.getString(R.string.port_forward_type_local)
                ForwardType.REMOTE -> context.getString(R.string.port_forward_type_remote)
                ForwardType.DYNAMIC -> context.getString(R.string.port_forward_type_dynamic)
            }

            textSummary.text = pf.getSummary()
            textEndpoint.text = endpointLabel(pf)

            val running = runningIds.contains(pf.id)
            bindStatus(pf, running)

            // Toggle affordance: play when idle, pause when running. Disabled
            // forwards can't run, so the control is greyed out.
            btnToggle.isEnabled = pf.enabled
            if (running) {
                btnToggle.setIconResource(R.drawable.ic_pause)
                btnToggle.contentDescription = context.getString(R.string.port_forward_stop)
            } else {
                btnToggle.setIconResource(R.drawable.ic_play)
                btnToggle.contentDescription = context.getString(R.string.port_forward_start)
            }

            btnToggle.setOnClickListener { onToggle(pf) }
            btnMore.setOnClickListener { showMenu(pf) }
            itemView.setOnClickListener { onEdit(pf) }
        }

        private fun bindStatus(pf: PortForward, running: Boolean) {
            val context = itemView.context
            val (labelRes, colorAttr) = when {
                !pf.enabled -> R.string.port_forward_status_disabled to
                    com.google.android.material.R.attr.colorError
                running -> R.string.port_forward_status_running to
                    com.google.android.material.R.attr.colorPrimary
                else -> R.string.port_forward_status_stopped to
                    android.R.attr.textColorSecondary
            }
            val color = if (colorAttr == android.R.attr.textColorSecondary) {
                MaterialColors.getColor(itemView, android.R.attr.textColorSecondary)
            } else {
                MaterialColors.getColor(itemView, colorAttr)
            }
            textStatus.text = context.getString(labelRes)
            textStatus.setTextColor(color)
            textStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.pf_status_dot, 0, 0, 0
            )
            TextViewCompat.setCompoundDrawableTintList(
                textStatus, ColorStateList.valueOf(color)
            )
        }

        private fun endpointLabel(pf: PortForward): String {
            return if (pf.usesSavedConnection) {
                val name = connectionNames[pf.connectionId] ?: pf.connectionId.orEmpty()
                itemView.context.getString(R.string.port_forward_endpoint_via, name)
            } else {
                val user = pf.sshUsername.orEmpty()
                val host = pf.sshHost.orEmpty()
                val userAtHost = if (user.isNotBlank()) "$user@$host" else host
                itemView.context.getString(
                    R.string.port_forward_endpoint_via, "$userAtHost:${pf.sshPort}"
                )
            }
        }

        private fun showMenu(pf: PortForward) {
            val popup = android.widget.PopupMenu(itemView.context, btnMore)
            popup.menu.add(0, MENU_EDIT, 0, R.string.port_forward_edit)
            popup.menu.add(0, MENU_DELETE, 1, R.string.port_forward_delete)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_EDIT -> { onEdit(pf); true }
                    MENU_DELETE -> { onDelete(pf); true }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<PortForward>() {
        override fun areItemsTheSame(oldItem: PortForward, newItem: PortForward) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: PortForward, newItem: PortForward) =
            oldItem == newItem
    }

    private companion object {
        const val MENU_EDIT = 1
        const val MENU_DELETE = 2
    }
}
