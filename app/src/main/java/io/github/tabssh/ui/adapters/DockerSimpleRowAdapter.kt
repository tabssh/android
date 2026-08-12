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
 * Shared three-line card adapter (item_docker_simple.xml) for the image,
 * volume, and network lists — subclasses supply the row key and the
 * title/subtitle/detail mapping.
 */
abstract class DockerSimpleRowAdapter<T> :
    RecyclerView.Adapter<DockerSimpleRowAdapter<T>.ViewHolder>() {

    private var items: List<T> = emptyList()
    private var onItemClickListener: ((T) -> Unit)? = null
    private var onItemLongClickListener: ((T) -> Unit)? = null
    private var onMoreClickListener: ((T) -> Unit)? = null

    /** Stable identity for DiffUtil. */
    protected abstract fun keyOf(item: T): String

    /** Title / subtitle / detail lines for one row (blank detail is hidden). */
    protected abstract fun linesOf(item: T, context: android.content.Context): Triple<String, String, String>

    fun setOnItemClickListener(listener: (T) -> Unit) {
        onItemClickListener = listener
    }

    fun setOnItemLongClickListener(listener: (T) -> Unit) {
        onItemLongClickListener = listener
    }

    fun setOnMoreClickListener(listener: (T) -> Unit) {
        onMoreClickListener = listener
    }

    fun updateList(newList: List<T>) {
        val old = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                keyOf(old[oldItemPosition]) == keyOf(newList[newItemPosition])
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition] == newList[newItemPosition]
        })
        items = newList
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_docker_simple, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textTitle: TextView = itemView.findViewById(R.id.text_title)
        private val textSubtitle: TextView = itemView.findViewById(R.id.text_subtitle)
        private val textDetail: TextView = itemView.findViewById(R.id.text_detail)
        private val buttonMore: ImageButton = itemView.findViewById(R.id.button_more)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClickListener?.invoke(items[position])
                }
            }

            // Visible affordance for the full action sheet; falls back to the
            // row-tap handler when no dedicated more-listener is set.
            buttonMore.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    (onMoreClickListener ?: onItemClickListener)?.invoke(items[position])
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

        fun bind(item: T) {
            val (title, subtitle, detail) = linesOf(item, itemView.context)
            textTitle.text = title
            textSubtitle.text = subtitle
            if (detail.isBlank()) {
                textDetail.visibility = View.GONE
            } else {
                textDetail.visibility = View.VISIBLE
                textDetail.text = detail
            }
        }
    }
}
