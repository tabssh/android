package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.storage.database.entities.StoredKey

/**
 * RecyclerView adapter for the SSH Keys section of the Identities tab.
 * Each card is tappable — the host fragment shows an options menu on click.
 */
class StoredKeyAdapter(
    private val onKeyClick: (StoredKey) -> Unit
) : ListAdapter<StoredKey, StoredKeyAdapter.VH>(Diff()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ssh_key, parent, false)
        return VH(view, onKeyClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        itemView: View,
        private val onKeyClick: (StoredKey) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val textName: TextView = itemView.findViewById(R.id.text_key_name)
        private val textType: TextView = itemView.findViewById(R.id.text_key_type)
        private val textFingerprint: TextView = itemView.findViewById(R.id.text_key_fingerprint)

        fun bind(key: StoredKey) {
            textName.text = key.name
            textType.text = key.keyType
            textFingerprint.text = key.getShortFingerprint()
            itemView.setOnClickListener { onKeyClick(key) }
        }
    }

    private class Diff : DiffUtil.ItemCallback<StoredKey>() {
        override fun areItemsTheSame(oldItem: StoredKey, newItem: StoredKey) =
            oldItem.keyId == newItem.keyId
        override fun areContentsTheSame(oldItem: StoredKey, newItem: StoredKey) =
            oldItem == newItem
    }
}
