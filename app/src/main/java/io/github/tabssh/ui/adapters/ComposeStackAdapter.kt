package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.storage.database.entities.ComposeStack

/**
 * Compose stack list rows (PLAN.AI.md step 22): name, remote path, and the
 * last-known per-service status snapshot (refreshed via composePs).
 */
class ComposeStackAdapter(
    private var stacks: List<ComposeStack> = emptyList()
) : RecyclerView.Adapter<ComposeStackAdapter.ViewHolder>() {

    private var onItemClickListener: ((ComposeStack) -> Unit)? = null
    private var onItemLongClickListener: ((ComposeStack) -> Unit)? = null

    fun setOnItemClickListener(listener: (ComposeStack) -> Unit) {
        onItemClickListener = listener
    }

    fun setOnItemLongClickListener(listener: (ComposeStack) -> Unit) {
        onItemLongClickListener = listener
    }

    fun updateList(newList: List<ComposeStack>) {
        val old = stacks
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition].id == newList[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition] == newList[newItemPosition]
        })
        stacks = newList
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_compose_stack, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(stacks[position])
    }

    override fun getItemCount(): Int = stacks.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textName: TextView = itemView.findViewById(R.id.text_name)
        private val textPath: TextView = itemView.findViewById(R.id.text_path)
        private val textStatus: TextView = itemView.findViewById(R.id.text_status)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClickListener?.invoke(stacks[position])
                }
            }

            itemView.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemLongClickListener?.invoke(stacks[position])
                    true
                } else {
                    false
                }
            }
        }

        fun bind(stack: ComposeStack) {
            textName.text = stack.name
            textPath.text = stack.remotePath
            val status = stack.lastKnownStatus
            if (status.isNullOrBlank()) {
                textStatus.visibility = View.GONE
            } else {
                textStatus.visibility = View.VISIBLE
                textStatus.text = status
            }
        }
    }
}
