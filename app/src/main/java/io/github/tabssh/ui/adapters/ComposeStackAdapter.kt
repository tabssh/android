package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R

/**
 * Compose stack list rows (PLAN.AI.md step 22): name, remote path, and the
 * last-known per-service status snapshot (refreshed via composePs) for
 * Room-tracked stacks; discovered-but-untracked stacks (TODO.AI.md § D) show
 * their `docker compose ls` config file path and an "External" badge.
 */
class ComposeStackAdapter(
    private var items: List<StackListItem> = emptyList()
) : RecyclerView.Adapter<ComposeStackAdapter.ViewHolder>() {

    private var onItemClickListener: ((StackListItem) -> Unit)? = null
    private var onItemLongClickListener: ((StackListItem) -> Unit)? = null
    private var onMoreClickListener: ((StackListItem) -> Unit)? = null

    fun setOnItemClickListener(listener: (StackListItem) -> Unit) {
        onItemClickListener = listener
    }

    fun setOnItemLongClickListener(listener: (StackListItem) -> Unit) {
        onItemLongClickListener = listener
    }

    /** Row overflow button — opens the same action sheet as a long-press. */
    fun setOnMoreClickListener(listener: (StackListItem) -> Unit) {
        onMoreClickListener = listener
    }

    fun updateList(newList: List<StackListItem>) {
        val old = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition].listKey == newList[newItemPosition].listKey
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition] == newList[newItemPosition]
        })
        items = newList
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_compose_stack, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textName: TextView = itemView.findViewById(R.id.text_name)
        private val textPath: TextView = itemView.findViewById(R.id.text_path)
        private val textStatus: TextView = itemView.findViewById(R.id.text_status)
        private val buttonMore: ImageButton = itemView.findViewById(R.id.button_more)

        init {
            buttonMore.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onMoreClickListener?.invoke(items[position])
                }
            }

            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClickListener?.invoke(items[position])
                }
            }

            itemView.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemLongClickListener?.invoke(items[position])
                    true
                } else {
                    false
                }
            }
        }

        fun bind(item: StackListItem) {
            textName.text = item.name
            textPath.text = item.statusLine
            val status = when (item) {
                is StackListItem.Tracked -> item.stack.lastKnownStatus
                is StackListItem.External -> itemView.context.getString(
                    R.string.docker_stack_external_status, item.entry.status
                )
            }
            if (status.isNullOrBlank()) {
                textStatus.visibility = View.GONE
            } else {
                textStatus.visibility = View.VISIBLE
                textStatus.text = status
            }
        }
    }
}
