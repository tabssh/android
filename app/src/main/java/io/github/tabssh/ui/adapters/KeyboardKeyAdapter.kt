package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.ui.keyboard.KeyboardKey

class KeyboardKeyAdapter(
    private val keys: List<KeyboardKey>,
    private val isRemovable: Boolean = false,
    private val onKeyClick: (KeyboardKey) -> Unit
) : RecyclerView.Adapter<KeyboardKeyAdapter.KeyViewHolder>() {
    
    inner class KeyViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.key_label)
        val description: TextView = view.findViewById(R.id.key_description)
        
        init {
            view.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onKeyClick(keys[position])
                }
            }
            
            if (isRemovable) {
                view.setOnLongClickListener {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onKeyClick(keys[position])
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): KeyViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_keyboard_key, parent, false)
        return KeyViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: KeyViewHolder, position: Int) {
        val key = keys[position]
        holder.label.text = key.label
        holder.description.text = when (key.category) {
            KeyboardKey.KeyCategory.MODIFIER -> "Modifier"
            KeyboardKey.KeyCategory.NAVIGATION -> "Navigation"
            KeyboardKey.KeyCategory.FUNCTION -> "Function"
            KeyboardKey.KeyCategory.SPECIAL -> "Special"
            KeyboardKey.KeyCategory.ACTION -> "Action"
            else -> ""
        }
    }
    
    override fun getItemCount() = keys.size
}
