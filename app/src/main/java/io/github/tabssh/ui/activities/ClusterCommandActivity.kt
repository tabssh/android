package io.github.tabssh.ui.activities
import io.github.tabssh.utils.logging.Logger

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.cluster.ClusterCommandExecutor
import io.github.tabssh.storage.database.entities.ConnectionProfile
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ClusterCommandActivity : AppCompatActivity() {

    private lateinit var app: TabSSHApplication
    private lateinit var executor: ClusterCommandExecutor
    
    private lateinit var connectionList: RecyclerView
    private lateinit var commandInput: EditText
    private lateinit var executeButton: Button
    private lateinit var cancelButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var resultsView: RecyclerView
    
    private val selectedConnections = mutableListOf<ConnectionProfile>()
    private val results = mutableListOf<ClusterCommandExecutor.ExecutionResult>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cluster_command)
        
        app = application as TabSSHApplication
        executor = ClusterCommandExecutor(app)
        
        setupToolbar()
        setupViews()
        loadConnections()
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
        
        connectionList.layoutManager = LinearLayoutManager(this)
        resultsView.layoutManager = LinearLayoutManager(this)
        
        executeButton.setOnClickListener { executeCommand() }
        cancelButton.setOnClickListener { cancelExecution() }
        
        cancelButton.visibility = View.GONE
    }

    private fun loadConnections() {
        lifecycleScope.launch {
            app.database.connectionDao().getAllConnections().collectLatest { connections ->
                val adapter = ConnectionSelectionAdapter(connections) { profile, isSelected ->
                    if (isSelected) {
                        selectedConnections.add(profile)
                    } else {
                        selectedConnections.remove(profile)
                    }
                    updateExecuteButton()
                }
                connectionList.adapter = adapter
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
        val adapter = ClusterResultsAdapter(results)
        resultsView.adapter = adapter
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
        private val connections: List<ConnectionProfile>,
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
            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                onSelectionChanged(connection, isChecked)
            }
        }

        override fun getItemCount() = connections.size
    }

    private class ClusterResultsAdapter(
        private val results: List<ClusterCommandExecutor.ExecutionResult>
    ) : RecyclerView.Adapter<ClusterResultsAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.result_name)
            val status: TextView = view.findViewById(R.id.result_status)
            val output: TextView = view.findViewById(R.id.result_output)
            val time: TextView = view.findViewById(R.id.result_time)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_cluster_result, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val result = results[position]
            holder.name.text = result.profile.name
            holder.status.text = if (result.success) "✅ Success" else "❌ Failed"
            holder.output.text = result.output.ifEmpty { result.error ?: "No output" }
            holder.time.text = "${result.executionTimeMs}ms"
            
            holder.status.setTextColor(
                if (result.success) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
            )
        }

        override fun getItemCount() = results.size
    }
}
