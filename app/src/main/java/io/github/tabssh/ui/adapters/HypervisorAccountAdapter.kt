package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.storage.database.entities.HypervisorAccount

class HypervisorAccountAdapter(
    private val onEdit: (HypervisorAccount) -> Unit,
    private val onDelete: (HypervisorAccount) -> Unit
) : RecyclerView.Adapter<HypervisorAccountAdapter.VH>() {

    private var items: List<HypervisorAccount> = emptyList()

    fun submit(list: List<HypervisorAccount>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_hypervisor_account, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val account = items[position]
        holder.name.text = account.name
        holder.username.text = "Username: ${account.username}"
        if (!account.realm.isNullOrBlank()) {
            holder.realm.visibility = View.VISIBLE
            holder.realm.text = "realm: ${account.realm}"
        } else {
            holder.realm.visibility = View.GONE
        }
        holder.btnEdit.setOnClickListener { onEdit(account) }
        holder.btnDelete.setOnClickListener { onDelete(account) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.text_name)
        val username: TextView = view.findViewById(R.id.text_username)
        val realm: TextView = view.findViewById(R.id.text_realm)
        val btnEdit: ImageButton = view.findViewById(R.id.btn_edit)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
    }
}
