package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.storage.database.entities.HypervisorType
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class HypervisorAdapter(
    private var hypervisors: List<HypervisorProfile> = emptyList()
) : RecyclerView.Adapter<HypervisorAdapter.ViewHolder>() {

    private var onItemClickListener: ((HypervisorProfile) -> Unit)? = null
    private var onItemLongClickListener: ((HypervisorProfile) -> Unit)? = null

    fun setOnItemClickListener(listener: (HypervisorProfile) -> Unit) {
        onItemClickListener = listener
    }

    fun setOnItemLongClickListener(listener: (HypervisorProfile) -> Unit) {
        onItemLongClickListener = listener
    }

    fun updateList(newList: List<HypervisorProfile>) {
        hypervisors = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_hypervisor, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(hypervisors[position])
    }

    override fun getItemCount(): Int = hypervisors.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textTypeIcon: TextView = itemView.findViewById(R.id.text_type_icon)
        private val textName: TextView = itemView.findViewById(R.id.text_name)
        private val textType: TextView = itemView.findViewById(R.id.text_type)
        private val textHost: TextView = itemView.findViewById(R.id.text_host)
        private val textLastConnected: TextView = itemView.findViewById(R.id.text_last_connected)
        private val viewStatus: View = itemView.findViewById(R.id.view_status)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClickListener?.invoke(hypervisors[position])
                }
            }

            itemView.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemLongClickListener?.invoke(hypervisors[position])
                    true
                } else {
                    false
                }
            }
        }

        fun bind(hypervisor: HypervisorProfile) {
            // Set type icon
            textTypeIcon.text = when (hypervisor.type) {
                HypervisorType.PROXMOX -> "🌐"
                HypervisorType.XCPNG -> "☁️"
                HypervisorType.VMWARE -> "📦"
            }

            // Set name
            textName.text = hypervisor.name

            // Set type badge
            textType.text = when (hypervisor.type) {
                HypervisorType.PROXMOX -> "Proxmox"
                HypervisorType.XCPNG -> "XCP-ng"
                HypervisorType.VMWARE -> "VMware"
            }

            // Set host and port
            textHost.text = "${hypervisor.host}:${hypervisor.port}"

            // Set last connected
            if (hypervisor.lastConnected > 0) {
                val relativeTime = getRelativeTime(hypervisor.lastConnected)
                textLastConnected.text = "Last used: $relativeTime"
                textLastConnected.visibility = View.VISIBLE
            } else {
                textLastConnected.text = "Never connected"
                textLastConnected.visibility = View.VISIBLE
            }

            // Status indicator (not connected for now)
            viewStatus.setBackgroundResource(R.drawable.status_indicator)
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
