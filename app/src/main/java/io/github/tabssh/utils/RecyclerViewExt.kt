package io.github.tabssh.utils

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/**
 * Replace the contents of a `MutableList<T>` backing a [RecyclerView.Adapter]
 * with [newItems] and dispatch the minimum set of change notifications via
 * [DiffUtil].
 *
 * Used as a drop-in replacement for the common
 *
 *     items.clear(); items.addAll(newList); adapter.notifyDataSetChanged()
 *
 * pattern. Callers supply two predicates:
 *  - [areItemsTheSame] — usually compares stable IDs (entity primary key,
 *    UUID, vmid, etc.). Defaults to reference equality which is rarely
 *    correct; callers should override.
 *  - [areContentsTheSame] — defaults to structural equality (`==`), which is
 *    correct for data classes and for entities with sensible `equals`.
 */
fun <T> RecyclerView.Adapter<*>.replaceAllWithDiff(
    items: MutableList<T>,
    newItems: List<T>,
    areItemsTheSame: (T, T) -> Boolean = { a, b -> a === b },
    areContentsTheSame: (T, T) -> Boolean = { a, b -> a == b }
) {
    val old = items.toList()
    val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
        override fun getOldListSize(): Int = old.size
        override fun getNewListSize(): Int = newItems.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            areItemsTheSame(old[oldItemPosition], newItems[newItemPosition])
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            areContentsTheSame(old[oldItemPosition], newItems[newItemPosition])
    })
    items.clear()
    items.addAll(newItems)
    diff.dispatchUpdatesTo(this)
}
