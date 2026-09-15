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
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.storage.database.entities.Identity

/**
 * RecyclerView adapter for Identity list
 */
class IdentityAdapter(
    private val onEdit: (Identity) -> Unit,
    private val onDelete: (Identity) -> Unit
) : ListAdapter<Identity, IdentityAdapter.IdentityViewHolder>(IdentityDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IdentityViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_identity, parent, false)
        return IdentityViewHolder(view, onEdit, onDelete)
    }

    override fun onBindViewHolder(holder: IdentityViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class IdentityViewHolder(
        itemView: View,
        private val onEdit: (Identity) -> Unit,
        private val onDelete: (Identity) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val textName: TextView = itemView.findViewById(R.id.text_name)
        private val textUsername: TextView = itemView.findViewById(R.id.text_username)
        private val textAuthType: TextView = itemView.findViewById(R.id.text_auth_type)
        private val textDescription: TextView = itemView.findViewById(R.id.text_description)
        private val btnEdit: ImageButton = itemView.findViewById(R.id.btn_edit)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete)
        
        fun bind(identity: Identity) {
            textName.text = identity.name
            textUsername.text = itemView.context.getString(
                R.string.hypervisor_account_username_fmt, identity.username
            )
            textAuthType.text = when (identity.authType) {
                AuthType.PASSWORD ->
                    itemView.context.getString(R.string.auth_type_password)
                AuthType.PUBLIC_KEY ->
                    itemView.context.getString(R.string.identity_auth_type_ssh_key)
                AuthType.KEYBOARD_INTERACTIVE ->
                    itemView.context.getString(R.string.identity_auth_type_keyboard_interactive)
            }
            
            if (identity.description.isNullOrBlank()) {
                textDescription.visibility = View.GONE
            } else {
                textDescription.visibility = View.VISIBLE
                textDescription.text = identity.description
            }
            
            itemView.setOnClickListener { onEdit(identity) }
            btnEdit.setOnClickListener { onEdit(identity) }
            btnDelete.setOnClickListener { onDelete(identity) }
        }
    }

    class IdentityDiffCallback : DiffUtil.ItemCallback<Identity>() {
        override fun areItemsTheSame(oldItem: Identity, newItem: Identity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Identity, newItem: Identity) = oldItem == newItem
    }
}
