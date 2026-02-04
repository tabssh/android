package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.Snippet
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch

/**
 * Activity for managing command snippets
 * Allows creating, editing, deleting snippets and organizing by category
 */
class SnippetManagerActivity : AppCompatActivity() {

    private lateinit var app: TabSSHApplication
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateLayout: View
    private lateinit var fab: FloatingActionButton
    private lateinit var categoryChipGroup: ChipGroup
    private lateinit var adapter: SnippetAdapter
    private val snippets = mutableListOf<Snippet>()
    private val allSnippets = mutableListOf<Snippet>()
    private var currentCategory: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_snippet_manager)

        app = application as TabSSHApplication

        setupToolbar()
        setupViews()
        loadSnippets()
    }

    private fun setupToolbar() {
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Manage Snippets"
        }
    }

    private fun setupViews() {
        recyclerView = findViewById(R.id.recycler_view_snippets)
        emptyStateLayout = findViewById(R.id.layout_empty_state)
        fab = findViewById(R.id.fab_add_snippet)
        categoryChipGroup = findViewById(R.id.chip_group_categories)

        // Setup RecyclerView
        adapter = SnippetAdapter(
            snippets = snippets,
            onSnippetClick = { snippet -> editSnippet(snippet) },
            onSnippetDelete = { snippet -> deleteSnippet(snippet) },
            onSnippetUse = { snippet -> useSnippet(snippet) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Setup FAB
        fab.setOnClickListener {
            showCreateSnippetDialog()
        }

        // Setup empty state button
        findViewById<View>(R.id.button_create_snippet).setOnClickListener {
            showCreateSnippetDialog()
        }

        // Setup category filter chips
        setupCategoryChips()
    }

    private fun setupCategoryChips() {
        // Add "All" chip
        val allChip = Chip(this).apply {
            text = "All"
            isCheckable = true
            isChecked = true
            setOnClickListener {
                currentCategory = null
                filterByCategory(null)
            }
        }
        categoryChipGroup.addView(allChip)
    }

    private fun updateCategoryChips(categories: List<String>) {
        // Keep "All" chip and add category chips
        val allChip = categoryChipGroup.getChildAt(0) as? Chip
        
        // Remove old category chips (keep "All")
        while (categoryChipGroup.childCount > 1) {
            categoryChipGroup.removeViewAt(1)
        }

        // Add category chips
        categories.sorted().forEach { category ->
            val chip = Chip(this).apply {
                text = category
                isCheckable = true
                setOnClickListener {
                    allChip?.isChecked = false
                    currentCategory = category
                    filterByCategory(category)
                }
            }
            categoryChipGroup.addView(chip)
        }
    }

    private fun filterByCategory(category: String?) {
        snippets.clear()
        if (category == null) {
            snippets.addAll(allSnippets)
        } else {
            snippets.addAll(allSnippets.filter { it.category == category })
        }
        adapter.notifyDataSetChanged()
        updateEmptyState()
    }

    private fun loadSnippets() {
        lifecycleScope.launch {
            try {
                app.database.snippetDao().getAllSnippets().collect { snippetList ->
                    allSnippets.clear()
                    allSnippets.addAll(snippetList)
                    
                    runOnUiThread {
                        filterByCategory(currentCategory)
                        
                        // Update category chips
                        val categories = snippetList.map { it.category }.distinct()
                        updateCategoryChips(categories)
                    }
                    
                    Logger.d("SnippetManagerActivity", "Loaded ${snippetList.size} snippets")
                }
            } catch (e: Exception) {
                Logger.e("SnippetManagerActivity", "Failed to load snippets", e)
                runOnUiThread {
                    Toast.makeText(this@SnippetManagerActivity, "Failed to load snippets", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateEmptyState() {
        if (snippets.isEmpty()) {
            emptyStateLayout.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyStateLayout.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun showCreateSnippetDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_snippet, null)
        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.edit_snippet_name)
        val commandInput = dialogView.findViewById<TextInputEditText>(R.id.edit_snippet_command)
        val descriptionInput = dialogView.findViewById<TextInputEditText>(R.id.edit_snippet_description)
        val categoryInput = dialogView.findViewById<AutoCompleteTextView>(R.id.edit_snippet_category)

        // Setup category autocomplete
        lifecycleScope.launch {
            val categories = app.database.snippetDao().getAllCategories()
            val adapter = ArrayAdapter(this@SnippetManagerActivity, android.R.layout.simple_dropdown_item_1line, categories)
            categoryInput.setAdapter(adapter)
        }

        AlertDialog.Builder(this)
            .setTitle("Create Snippet")
            .setView(dialogView)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text.toString().trim()
                val command = commandInput.text.toString().trim()
                val description = descriptionInput.text.toString().trim()
                val category = categoryInput.text.toString().trim().ifBlank { "General" }

                if (name.isBlank()) {
                    Toast.makeText(this, "Snippet name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (command.isBlank()) {
                    Toast.makeText(this, "Command cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                createSnippet(name, command, description, category)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editSnippet(snippet: Snippet) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_snippet, null)
        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.edit_snippet_name)
        val commandInput = dialogView.findViewById<TextInputEditText>(R.id.edit_snippet_command)
        val descriptionInput = dialogView.findViewById<TextInputEditText>(R.id.edit_snippet_description)
        val categoryInput = dialogView.findViewById<AutoCompleteTextView>(R.id.edit_snippet_category)

        // Pre-fill with existing values
        nameInput.setText(snippet.name)
        commandInput.setText(snippet.command)
        descriptionInput.setText(snippet.description)
        categoryInput.setText(snippet.category)

        // Setup category autocomplete
        lifecycleScope.launch {
            val categories = app.database.snippetDao().getAllCategories()
            val adapter = ArrayAdapter(this@SnippetManagerActivity, android.R.layout.simple_dropdown_item_1line, categories)
            categoryInput.setAdapter(adapter)
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Snippet")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val command = commandInput.text.toString().trim()
                val description = descriptionInput.text.toString().trim()
                val category = categoryInput.text.toString().trim().ifBlank { "General" }

                if (name.isBlank() || command.isBlank()) {
                    Toast.makeText(this, "Name and command cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                updateSnippet(snippet, name, command, description, category)
            }
            .setNeutralButton("Delete") { _, _ ->
                deleteSnippet(snippet)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createSnippet(name: String, command: String, description: String, category: String) {
        lifecycleScope.launch {
            try {
                val snippet = Snippet(
                    name = name,
                    command = command,
                    description = description,
                    category = category,
                    createdAt = System.currentTimeMillis(),
                    modifiedAt = System.currentTimeMillis()
                )

                app.database.snippetDao().insertSnippet(snippet)

                runOnUiThread {
                    Toast.makeText(this@SnippetManagerActivity, "Snippet created", Toast.LENGTH_SHORT).show()
                }

                Logger.i("SnippetManagerActivity", "Created snippet: $name")
            } catch (e: Exception) {
                Logger.e("SnippetManagerActivity", "Failed to create snippet", e)
                runOnUiThread {
                    Toast.makeText(this@SnippetManagerActivity, "Failed to create snippet", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateSnippet(snippet: Snippet, name: String, command: String, description: String, category: String) {
        lifecycleScope.launch {
            try {
                val updatedSnippet = snippet.copy(
                    name = name,
                    command = command,
                    description = description,
                    category = category,
                    modifiedAt = System.currentTimeMillis()
                )

                app.database.snippetDao().updateSnippet(updatedSnippet)

                runOnUiThread {
                    Toast.makeText(this@SnippetManagerActivity, "Snippet updated", Toast.LENGTH_SHORT).show()
                }

                Logger.i("SnippetManagerActivity", "Updated snippet: $name")
            } catch (e: Exception) {
                Logger.e("SnippetManagerActivity", "Failed to update snippet", e)
                runOnUiThread {
                    Toast.makeText(this@SnippetManagerActivity, "Failed to update snippet", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteSnippet(snippet: Snippet) {
        AlertDialog.Builder(this)
            .setTitle("Delete Snippet?")
            .setMessage("Are you sure you want to delete '${snippet.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                performDelete(snippet)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performDelete(snippet: Snippet) {
        lifecycleScope.launch {
            try {
                app.database.snippetDao().deleteSnippet(snippet)

                runOnUiThread {
                    Toast.makeText(this@SnippetManagerActivity, "Snippet deleted", Toast.LENGTH_SHORT).show()
                }

                Logger.i("SnippetManagerActivity", "Deleted snippet: ${snippet.name}")
            } catch (e: Exception) {
                Logger.e("SnippetManagerActivity", "Failed to delete snippet", e)
                runOnUiThread {
                    Toast.makeText(this@SnippetManagerActivity, "Failed to delete snippet", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun useSnippet(snippet: Snippet) {
        // Increment usage count
        lifecycleScope.launch {
            app.database.snippetDao().incrementUsageCount(snippet.id)
        }
        
        // Show toast
        Toast.makeText(this, "Snippet copied: ${snippet.name}", Toast.LENGTH_SHORT).show()
        
        // Copy to clipboard
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Snippet", snippet.command)
        clipboard.setPrimaryClip(clip)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * RecyclerView adapter for snippets
     */
    private class SnippetAdapter(
        private val snippets: List<Snippet>,
        private val onSnippetClick: (Snippet) -> Unit,
        private val onSnippetDelete: (Snippet) -> Unit,
        private val onSnippetUse: (Snippet) -> Unit
    ) : RecyclerView.Adapter<SnippetAdapter.SnippetViewHolder>() {

        class SnippetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val nameText: TextView = itemView.findViewById(R.id.text_snippet_name)
            val commandText: TextView = itemView.findViewById(R.id.text_snippet_command)
            val categoryText: TextView = itemView.findViewById(R.id.text_snippet_category)
            val usageText: TextView = itemView.findViewById(R.id.text_usage_count)
            val favoriteIcon: android.widget.ImageView = itemView.findViewById(R.id.icon_favorite)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SnippetViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_snippet, parent, false)
            return SnippetViewHolder(view)
        }

        override fun onBindViewHolder(holder: SnippetViewHolder, position: Int) {
            val snippet = snippets[position]

            holder.nameText.text = snippet.name
            holder.commandText.text = snippet.command
            holder.categoryText.text = snippet.category

            if (snippet.usageCount > 0) {
                holder.usageText.text = "Used ${snippet.usageCount} times"
                holder.usageText.visibility = View.VISIBLE
            } else {
                holder.usageText.visibility = View.GONE
            }

            // Update favorite icon
            val favoriteIcon = if (snippet.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
            holder.favoriteIcon.setImageResource(favoriteIcon)

            holder.itemView.setOnClickListener {
                onSnippetClick(snippet)
            }

            holder.itemView.setOnLongClickListener {
                onSnippetUse(snippet)
                true
            }

            holder.favoriteIcon.setOnClickListener {
                // Toggle favorite (implement in activity)
                onSnippetClick(snippet)
            }
        }

        override fun getItemCount(): Int = snippets.size
    }
}
