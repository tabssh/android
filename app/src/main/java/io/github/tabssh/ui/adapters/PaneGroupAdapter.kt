package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.storage.database.entities.PaneGroup

class PaneGroupAdapter(
    private val onLaunch: (PaneGroup) -> Unit,
    private val onEdit: (PaneGroup) -> Unit,
    private val onDelete: (PaneGroup) -> Unit
) : RecyclerView.Adapter<PaneGroupAdapter.VH>() {

    private var items: List<PaneGroup> = emptyList()

    fun submit(list: List<PaneGroup>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pane_group, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val group = items[position]
        val context = holder.itemView.context
        holder.name.text = group.name
        holder.description.text = context.getString(
            R.string.pane_group_member_count_fmt, group.resolvedWindows().size
        )
        holder.itemView.setOnClickListener { onLaunch(group) }
        holder.btnEdit.setOnClickListener { onEdit(group) }
        holder.btnDelete.setOnClickListener { onDelete(group) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.text_name)
        val description: TextView = view.findViewById(R.id.text_description)
        val btnEdit: ImageButton = view.findViewById(R.id.btn_edit)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
    }
}
