package io.github.tabssh.ui.views

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

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

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (context.resources.displayMetrics.density * 8).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val search = EditText(context).apply {
            hint = "Type to filter…"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        root.addView(search)

        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (context.resources.displayMetrics.heightPixels * 0.5).toInt()
            )
        }
        root.addView(rv)

        var filtered: List<Item> = items
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(root)
            .setNegativeButton("Cancel", null)
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
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (parent.resources.displayMetrics.density * 12).toInt()
                setPadding(pad, pad, pad, pad)
                isClickable = true
                isFocusable = true
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            val title = TextView(parent.context).apply {
                textSize = 15f
                setTextColor(0xFF222222.toInt())
            }
            val subtitle = TextView(parent.context).apply {
                textSize = 12f
                setTextColor(0xFF666666.toInt())
            }
            container.addView(title)
            container.addView(subtitle)
            return VH(container, title, subtitle)
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
