package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.storage.database.entities.Snippet
import io.github.tabssh.utils.logging.Logger

/**
 * RecyclerView adapter for displaying snippets
 */
class SnippetAdapter(
    private val snippets: MutableList<Snippet>,
    private val onSnippetClick: (Snippet) -> Unit,
    private val onSnippetLongClick: (Snippet) -> Unit,
    private val onFavoriteClick: (Snippet) -> Unit
) : RecyclerView.Adapter<SnippetAdapter.SnippetViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SnippetViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_snippet, parent, false)
        return SnippetViewHolder(view)
    }

    override fun onBindViewHolder(holder: SnippetViewHolder, position: Int) {
        holder.bind(snippets[position])
    }

    override fun getItemCount(): Int = snippets.size

    inner class SnippetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textName: TextView = itemView.findViewById(R.id.text_snippet_name)
        private val textCommand: TextView = itemView.findViewById(R.id.text_snippet_command)
        private val textCategory: TextView = itemView.findViewById(R.id.text_snippet_category)
        private val textUsageCount: TextView = itemView.findViewById(R.id.text_usage_count)
        private val iconFavorite: ImageView = itemView.findViewById(R.id.icon_favorite)
        private val iconVariables: ImageView = itemView.findViewById(R.id.icon_variables)

        fun bind(snippet: Snippet) {
            textName.text = snippet.name
            textCommand.text = snippet.command
            textCategory.text = snippet.category

            // Show usage count if > 0
            if (snippet.usageCount > 0) {
                textUsageCount.text = "Used ${snippet.usageCount} times"
                textUsageCount.visibility = View.VISIBLE
            } else {
                textUsageCount.visibility = View.GONE
            }

            // Show favorite icon
            iconFavorite.setImageResource(
                if (snippet.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
            )

            // Show variables icon if snippet has variables
            iconVariables.visibility = if (snippet.hasVariables()) View.VISIBLE else View.GONE

            // Click listeners
            itemView.setOnClickListener {
                onSnippetClick(snippet)
            }

            itemView.setOnLongClickListener {
                onSnippetLongClick(snippet)
                true
            }

            iconFavorite.setOnClickListener {
                onFavoriteClick(snippet)
            }

            Logger.d("SnippetAdapter", "Bound snippet: ${snippet.name}")
        }
    }
}
