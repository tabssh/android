package io.github.tabssh.ui.views

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R

/**
 * Wave 2.6 — VSCode/Termius-style command palette overlay.
 *
 * Pass a list of [Item]s; the dialog shows a search EditText on top and a
 * filtered RecyclerView below. Filter is fuzzy-ish: a query like "set" matches
 * "Settings" as long as the chars appear in order.
 *
 * One reusable component — Ctrl+K (commands) and Ctrl+J (tab switcher) both
 * use it with different item lists. Keeps things tight.
 */
object PaletteDialog {

    data class Item(
        val title: String,
        val subtitle: String? = null,
        val onSelect: () -> Unit
    )

    fun show(context: Context, title: String, items: List<Item>) {
        if (items.isEmpty()) return

        val root = LayoutInflater.from(context).inflate(R.layout.dialog_palette, null)
        val search = root.findViewById<EditText>(R.id.edit_search)
        val rv = root.findViewById<RecyclerView>(R.id.list_palette_items).apply {
            layoutManager = LinearLayoutManager(context)
            // Half the screen height — a proportion of the device, not a
            // fixed dp value, so it stays a runtime calculation rather than
            // a dimens.xml resource.
            layoutParams = layoutParams.apply {
                height = (context.resources.displayMetrics.heightPixels * 0.5).toInt()
            }
        }

        var filtered: List<Item> = items
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .create()

        val adapter = Adapter(filtered) { item ->
            dialog.dismiss()
            item.onSelect.invoke()
        }
        rv.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty().trim()
                filtered = if (q.isEmpty()) items else items.filter { fuzzyMatches(q, it.title) || fuzzyMatches(q, it.subtitle.orEmpty()) }
                adapter.update(filtered)
            }
        })

        dialog.show()
        search.requestFocus()
    }

    /** Subsequence match — chars of query appear in order in target (case-insensitive). */
    private fun fuzzyMatches(query: String, target: String): Boolean {
        if (query.isEmpty()) return true
        val q = query.lowercase()
        val t = target.lowercase()
        var i = 0
        for (c in t) {
            if (c == q[i]) { i++; if (i == q.length) return true }
        }
        return false
    }

    private class Adapter(
        private var items: List<Item>,
        private val onClick: (Item) -> Unit
    ) : RecyclerView.Adapter<Adapter.VH>() {

        fun update(newItems: List<Item>) {
            val old = items
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = old.size
                override fun getNewListSize(): Int = newItems.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    old[oldItemPosition].title == newItems[newItemPosition].title
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    old[oldItemPosition] == newItems[newItemPosition]
            })
            items = newItems
            diff.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_palette_row, parent, false)
            val title = view.findViewById<TextView>(R.id.text_title)
            val subtitle = view.findViewById<TextView>(R.id.text_subtitle)
            return VH(view, title, subtitle)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.subtitle.text = item.subtitle.orEmpty()
            holder.subtitle.visibility = if (item.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View, val title: TextView, val subtitle: TextView) : RecyclerView.ViewHolder(view)
    }
}
