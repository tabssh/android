package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.storage.database.entities.VncIdentity

class VncIdentityAdapter(
    private val onEdit: (VncIdentity) -> Unit,
    private val onDelete: (VncIdentity) -> Unit
) : ListAdapter<VncIdentity, VncIdentityAdapter.VH>(DiffCallback()) {

    fun submit(list: List<VncIdentity>) {
        submitList(list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_vnc_identity, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val identity = getItem(position)
        val context = holder.itemView.context
        holder.name.text = identity.name
        if (!identity.description.isNullOrBlank()) {
            holder.description.visibility = View.VISIBLE
            holder.description.text = identity.description
        } else if (!identity.username.isNullOrBlank()) {
            holder.description.visibility = View.VISIBLE
            holder.description.text = context.getString(R.string.hypervisor_account_username_fmt, identity.username)
        } else {
            holder.description.visibility = View.GONE
        }
        holder.btnEdit.setOnClickListener { onEdit(identity) }
        holder.btnDelete.setOnClickListener { onDelete(identity) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.text_name)
        val description: TextView = view.findViewById(R.id.text_description)
        val btnEdit: ImageButton = view.findViewById(R.id.btn_edit)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
    }

    class DiffCallback : DiffUtil.ItemCallback<VncIdentity>() {
        override fun areItemsTheSame(oldItem: VncIdentity, newItem: VncIdentity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: VncIdentity, newItem: VncIdentity): Boolean {
            return oldItem == newItem
        }
    }
}
