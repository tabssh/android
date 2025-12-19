package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch

/**
 * Activity for managing SSH keys
 * Allows viewing and deleting stored SSH keys
 */
class KeyManagementActivity : AppCompatActivity() {

    private lateinit var app: TabSSHApplication
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: KeyAdapter
    private val keys = mutableListOf<StoredKey>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_key_management)

        app = application as TabSSHApplication

        // Setup toolbar
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "SSH Key Management"
        }

        // Setup RecyclerView
        recyclerView = findViewById(R.id.recycler_view_keys)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = KeyAdapter(keys) { key -> showKeyDetails(key) }
        recyclerView.adapter = adapter

        // Load keys
        loadKeys()
    }

    private fun loadKeys() {
        lifecycleScope.launch {
            try {
                app.database.keyDao().getAllKeys().collect { loadedKeys ->
                    keys.clear()
                    keys.addAll(loadedKeys)
                    adapter.notifyDataSetChanged()

                    // Show empty state if no keys
                    if (keys.isEmpty()) {
                        findViewById<TextView>(R.id.text_empty_state)?.visibility = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    } else {
                        findViewById<TextView>(R.id.text_empty_state)?.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                Logger.e("KeyManagementActivity", "Failed to load keys", e)
            }
        }
    }

    private fun showKeyDetails(key: StoredKey) {
        AlertDialog.Builder(this)
            .setTitle(key.name)
            .setMessage("""
                Type: ${key.keyType}
                Fingerprint: ${key.fingerprint}
                Created: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(key.createdAt))}
                ${if (!key.comment.isNullOrEmpty()) "Comment: ${key.comment}\n" else ""}
            """.trimIndent())
            .setPositiveButton("OK", null)
            .setNegativeButton("Delete") { _, _ ->
                deleteKey(key)
            }
            .show()
    }

    private fun deleteKey(key: StoredKey) {
        AlertDialog.Builder(this)
            .setTitle("Delete SSH Key")
            .setMessage("Are you sure you want to delete key '${key.name}'?\n\nThis action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        app.database.keyDao().deleteKey(key)
                        loadKeys() // Refresh list
                    } catch (e: Exception) {
                        Logger.e("KeyManagementActivity", "Failed to delete key", e)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Adapter for SSH keys list
     */
    private class KeyAdapter(
        private val keys: List<StoredKey>,
        private val onKeyClick: (StoredKey) -> Unit
    ) : RecyclerView.Adapter<KeyAdapter.KeyViewHolder>() {

        class KeyViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.text_key_name)
            val typeText: TextView = view.findViewById(R.id.text_key_type)
            val fingerprintText: TextView = view.findViewById(R.id.text_key_fingerprint)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): KeyViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_ssh_key, parent, false)
            return KeyViewHolder(view)
        }

        override fun onBindViewHolder(holder: KeyViewHolder, position: Int) {
            val key = keys[position]
            holder.nameText.text = key.name
            holder.typeText.text = key.keyType.uppercase()
            holder.fingerprintText.text = key.fingerprint.take(32) + "..."

            holder.itemView.setOnClickListener {
                onKeyClick(key)
            }
        }

        override fun getItemCount() = keys.size
    }
}
