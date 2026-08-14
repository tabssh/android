package io.github.tabssh.ui.adapters

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.storage.database.entities.HypervisorType

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
        val old = hypervisors
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition].id == newList[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition] == newList[newItemPosition]
        })
        hypervisors = newList
        diff.dispatchUpdatesTo(this)
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
            val context = itemView.context
            textTypeIcon.text = context.getString(
                when (hypervisor.type) {
                    HypervisorType.PROXMOX -> R.string.hypervisor_icon_proxmox
                    HypervisorType.XCPNG -> R.string.hypervisor_icon_xcpng
                    HypervisorType.VMWARE -> R.string.hypervisor_icon_vmware
                    HypervisorType.OCI -> R.string.hypervisor_icon_oci
                    HypervisorType.LIBVIRT -> R.string.hypervisor_icon_libvirt
                }
            )

            textName.text = hypervisor.name

            textType.text = context.getString(
                when (hypervisor.type) {
                    HypervisorType.PROXMOX -> R.string.hypervisor_type_proxmox
                    HypervisorType.XCPNG -> R.string.hypervisor_type_xcpng
                    HypervisorType.VMWARE -> R.string.hypervisor_type_vmware
                    HypervisorType.OCI -> R.string.hypervisor_type_oci
                    HypervisorType.LIBVIRT -> R.string.hypervisor_type_libvirt
                }
            )

            textHost.text = context.getString(
                R.string.hypervisor_endpoint_fmt, hypervisor.host, hypervisor.port
            )

            textLastConnected.visibility = View.VISIBLE
            if (hypervisor.lastConnected > 0) {
                // Localized relative time from the platform formatter, matching
                // DockerHostAdapter — replaces hand-built English-only strings.
                val relativeTime = DateUtils.getRelativeTimeSpanString(
                    hypervisor.lastConnected,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                )
                textLastConnected.text =
                    context.getString(R.string.docker_last_used_fmt, relativeTime)
            } else {
                textLastConnected.text = context.getString(R.string.docker_never_connected)
            }

            // Status indicator (not connected for now)
            viewStatus.setBackgroundResource(R.drawable.status_indicator)
        }
    }
}
