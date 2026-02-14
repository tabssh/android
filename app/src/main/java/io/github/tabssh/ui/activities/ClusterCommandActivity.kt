package io.github.tabssh.ui.activities
import io.github.tabssh.utils.logging.Logger

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.widget.*
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.cluster.ClusterCommandExecutor
import io.github.tabssh.storage.database.entities.ConnectionProfile
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ClusterCommandActivity : AppCompatActivity() {

    private lateinit var app: TabSSHApplication
    private lateinit var executor: ClusterCommandExecutor
    
    private lateinit var connectionList: RecyclerView
    private lateinit var commandInput: EditText
    private lateinit var executeButton: Button
    private lateinit var cancelButton: Button
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var progressText: TextView
    private lateinit var resultsView: RecyclerView
    private lateinit var emptyStateLayout: View
    private lateinit var addConnectionButton: Button
    private lateinit var searchConnections: EditText
    private lateinit var selectAllButton: Button
    private lateinit var selectNoneButton: Button
    private lateinit var selectedCountText: TextView
    private lateinit var commandHistoryChips: ChipGroup
    private lateinit var fromSnippetButton: Button
    private lateinit var prefs: SharedPreferences

    private val selectedConnections = mutableListOf<ConnectionProfile>()
    private val commandHistory = mutableListOf<String>()
    private val results = mutableListOf<ClusterCommandExecutor.ExecutionResult>()
    private var allConnections = listOf<ConnectionProfile>()
    private var connectionAdapter: ConnectionSelectionAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cluster_command)
        
        app = application as TabSSHApplication
        executor = ClusterCommandExecutor(app)
        prefs = getSharedPreferences("cluster_commands", Context.MODE_PRIVATE)

        setupToolbar()
        setupViews()
        loadConnections()
        loadCommandHistory()
        observeProgress()

        Logger.d("ClusterCommandActivity", "Activity created")
    }

    private fun setupToolbar() {
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Cluster Commands"
    }

    private fun setupViews() {
        connectionList = findViewById(R.id.connection_list)
        commandInput = findViewById(R.id.command_input)
        executeButton = findViewById(R.id.execute_button)
        cancelButton = findViewById(R.id.cancel_button)
        progressBar = findViewById(R.id.progress_bar)
        progressText = findViewById(R.id.progress_text)
        resultsView = findViewById(R.id.results_view)
        emptyStateLayout = findViewById(R.id.layout_empty_state)
        addConnectionButton = findViewById(R.id.button_add_connection)
        searchConnections = findViewById(R.id.search_connections)
        selectAllButton = findViewById(R.id.button_select_all)
        selectNoneButton = findViewById(R.id.button_select_none)
        selectedCountText = findViewById(R.id.selected_count)
        commandHistoryChips = findViewById(R.id.command_history_chips)
        fromSnippetButton = findViewById(R.id.button_from_snippet)

        connectionList.layoutManager = LinearLayoutManager(this)
        resultsView.layoutManager = LinearLayoutManager(this)

        executeButton.setOnClickListener { executeCommand() }
        cancelButton.setOnClickListener { cancelExecution() }
        addConnectionButton.setOnClickListener {
            startActivity(Intent(this, ConnectionEditActivity::class.java))
        }

        // Search/filter connections
        searchConnections.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterConnections(s?.toString() ?: "")
            }
        })

        // Select all button
        selectAllButton.setOnClickListener {
            connectionAdapter?.selectAll()
            updateSelectedCount()
        }

        // Select none button
        selectNoneButton.setOnClickListener {
            connectionAdapter?.selectNone()
            selectedConnections.clear()
            updateSelectedCount()
        }

        // From Snippet button
        fromSnippetButton.setOnClickListener {
            showSnippetPicker()
        }

        cancelButton.visibility = View.GONE
    }

    private fun loadCommandHistory() {
        val historySet = prefs.getStringSet("command_history", emptySet()) ?: emptySet()
        commandHistory.clear()
        commandHistory.addAll(historySet.take(10)) // Keep last 10 commands
        updateHistoryChips()
    }

    private fun saveCommandToHistory(command: String) {
        if (command.isBlank()) return

        // Add to beginning, remove duplicates
        commandHistory.remove(command)
        commandHistory.add(0, command)

        // Keep only last 10
        while (commandHistory.size > 10) {
            commandHistory.removeLast()
        }

        // Save to SharedPreferences
        prefs.edit().putStringSet("command_history", commandHistory.toSet()).apply()
        updateHistoryChips()
    }

    private fun updateHistoryChips() {
        commandHistoryChips.removeAllViews()

        for (command in commandHistory.take(5)) { // Show only 5 most recent
            val chip = Chip(this).apply {
                text = if (command.length > 20) command.take(20) + "..." else command
                isClickable = true
                isCheckable = false
                setOnClickListener {
                    commandInput.setText(command)
                }
            }
            commandHistoryChips.addView(chip)
        }
    }

    private fun showSnippetPicker() {
        lifecycleScope.launch {
            val snippets = app.database.snippetDao().getAllSnippets().first()

            if (snippets.isEmpty()) {
                Toast.makeText(this@ClusterCommandActivity, "No snippets available", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val snippetNames = snippets.map { it.name }.toTypedArray()

            runOnUiThread {
                androidx.appcompat.app.AlertDialog.Builder(this@ClusterCommandActivity)
                    .setTitle("Select Snippet")
                    .setItems(snippetNames) { _, which ->
                        val selectedSnippet = snippets[which]
                        commandInput.setText(selectedSnippet.command)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun filterConnections(query: String) {
        val filtered = if (query.isEmpty()) {
            allConnections
        } else {
            allConnections.filter { connection ->
                connection.name.contains(query, ignoreCase = true) ||
                connection.host.contains(query, ignoreCase = true) ||
                connection.username.contains(query, ignoreCase = true)
            }
        }
        connectionAdapter?.updateConnections(filtered)
    }

    private fun updateSelectedCount() {
        val count = selectedConnections.size
        selectedCountText.text = "$count selected"
        updateExecuteButton()
    }

    private fun loadConnections() {
        lifecycleScope.launch {
            app.database.connectionDao().getAllConnections().collectLatest { connections ->
                allConnections = connections
                if (connections.isEmpty()) {
                    emptyStateLayout.visibility = View.VISIBLE
                    connectionList.visibility = View.GONE
                } else {
                    emptyStateLayout.visibility = View.GONE
                    connectionList.visibility = View.VISIBLE
                    connectionAdapter = ConnectionSelectionAdapter(
                        connections,
                        selectedConnections
                    ) { profile, isSelected ->
                        if (isSelected) {
                            if (!selectedConnections.contains(profile)) {
                                selectedConnections.add(profile)
                            }
                        } else {
                            selectedConnections.remove(profile)
                        }
                        updateSelectedCount()
                    }
                    connectionList.adapter = connectionAdapter
                    updateSelectedCount()
                }
            }
        }
    }

    private fun observeProgress() {
        lifecycleScope.launch {
            executor.progress.collectLatest { progress ->
                runOnUiThread {
                    progressBar.max = progress.total
                    progressBar.progress = progress.completed
                    
                    progressText.text = getString(
                        R.string.cluster_progress,
                        progress.completed,
                        progress.total,
                        progress.succeeded,
                        progress.failed
                    )
                    
                    if (progress.completed == progress.total && progress.total > 0) {
                        executeButton.visibility = View.VISIBLE
                        cancelButton.visibility = View.GONE
                        showResults(progress.results)
                    }
                }
            }
        }
    }

    private fun executeCommand() {
        val command = commandInput.text.toString().trim()
        
        if (command.isEmpty()) {
            Toast.makeText(this, "Enter a command", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (selectedConnections.isEmpty()) {
            Toast.makeText(this, "Select at least one connection", Toast.LENGTH_SHORT).show()
            return
        }
        
        executeButton.visibility = View.GONE
        cancelButton.visibility = View.VISIBLE
        results.clear()

        // Save command to history
        saveCommandToHistory(command)

        lifecycleScope.launch {
            try {
                val timeoutMs = app.preferencesManager.getLong("cluster_timeout", 60000L)
                val maxConcurrent = app.preferencesManager.getInt("cluster_max_concurrent", 10)
                
                val executionResults = executor.executeOnCluster(
                    selectedConnections,
                    command,
                    timeoutMs,
                    maxConcurrent
                )
                
                results.addAll(executionResults)
                
                Toast.makeText(
                    this@ClusterCommandActivity,
                    "Execution complete",
                    Toast.LENGTH_SHORT
                ).show()
                
            } catch (e: Exception) {
                Logger.e("ClusterCommandActivity", "Execution failed", e)
                Toast.makeText(
                    this@ClusterCommandActivity,
                    "Execution failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun cancelExecution() {
        executor.cancelAll()
        executeButton.visibility = View.VISIBLE
        cancelButton.visibility = View.GONE
        Toast.makeText(this, "Execution cancelled", Toast.LENGTH_SHORT).show()
    }

    private fun showResults(results: List<ClusterCommandExecutor.ExecutionResult>) {
        val adapter = ClusterResultsAdapter(results) { output ->
            copyToClipboard(output)
        }
        resultsView.adapter = adapter
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Cluster Command Output", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Output copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun updateExecuteButton() {
        executeButton.isEnabled = selectedConnections.isNotEmpty()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // Adapter classes

    private class ConnectionSelectionAdapter(
        private var connections: List<ConnectionProfile>,
        private val selectedSet: MutableList<ConnectionProfile>,
        private val onSelectionChanged: (ConnectionProfile, Boolean) -> Unit
    ) : RecyclerView.Adapter<ConnectionSelectionAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkbox: CheckBox = view.findViewById(R.id.connection_checkbox)
            val name: TextView = view.findViewById(R.id.connection_name)
            val host: TextView = view.findViewById(R.id.connection_host)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_cluster_connection, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val connection = connections[position]
            holder.name.text = connection.name
            holder.host.text = "${connection.username}@${connection.host}:${connection.port}"

            // Remove listener to prevent triggering during setChecked
            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = selectedSet.contains(connection)
            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                onSelectionChanged(connection, isChecked)
            }

            // Allow clicking anywhere on the item to toggle
            holder.itemView.setOnClickListener {
                holder.checkbox.isChecked = !holder.checkbox.isChecked
            }
        }

        override fun getItemCount() = connections.size

        fun updateConnections(newConnections: List<ConnectionProfile>) {
            connections = newConnections
            notifyDataSetChanged()
        }

        fun selectAll() {
            selectedSet.clear()
            selectedSet.addAll(connections)
            notifyDataSetChanged()
        }

        fun selectNone() {
            selectedSet.clear()
            notifyDataSetChanged()
        }
    }

    private class ClusterResultsAdapter(
        private val results: List<ClusterCommandExecutor.ExecutionResult>,
        private val onCopyOutput: (String) -> Unit
    ) : RecyclerView.Adapter<ClusterResultsAdapter.ViewHolder>() {

        // Track expanded state for each item
        private val expandedItems = mutableSetOf<Int>()

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.result_name)
            val status: TextView = view.findViewById(R.id.result_status)
            val output: TextView = view.findViewById(R.id.result_output)
            val time: TextView = view.findViewById(R.id.result_time)
            val copyButton: ImageButton = view.findViewById(R.id.button_copy_output)
            val toggleButton: ImageButton = view.findViewById(R.id.button_toggle_output)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_cluster_result, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val result = results[position]
            val outputText = result.output.ifEmpty { result.error ?: "No output" }

            holder.name.text = result.profile.name
            holder.status.text = if (result.success) "✅ Success" else "❌ Failed"
            holder.output.text = outputText
            holder.time.text = "${result.executionTimeMs}ms"

            holder.status.setTextColor(
                if (result.success) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
            )

            // Handle expanded state
            val isExpanded = expandedItems.contains(position)
            holder.output.visibility = if (isExpanded) View.VISIBLE else View.GONE
            holder.toggleButton.setImageResource(
                if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )

            // Toggle button click
            holder.toggleButton.setOnClickListener {
                if (expandedItems.contains(position)) {
                    expandedItems.remove(position)
                } else {
                    expandedItems.add(position)
                }
                notifyItemChanged(position)
            }

            // Copy button click
            holder.copyButton.setOnClickListener {
                onCopyOutput(outputText)
            }

            // Click on card header to toggle
            holder.itemView.setOnClickListener {
                if (expandedItems.contains(position)) {
                    expandedItems.remove(position)
                } else {
                    expandedItems.add(position)
                }
                notifyItemChanged(position)
            }
        }

        override fun getItemCount() = results.size
    }
}
