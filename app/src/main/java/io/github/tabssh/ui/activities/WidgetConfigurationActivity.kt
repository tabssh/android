package io.github.tabssh.ui.activities

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ui.widget.QuickConnectWidgetProvider
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Configuration activity for Quick Connect widget
 * Allows user to select which connection profile to use for the widget
 */
class WidgetConfigurationActivity : AppCompatActivity() {

    private lateinit var app: TabSSHApplication
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedConnectionId: String? = null

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: WidgetConnectionAdapter
    private lateinit var buttonSave: MaterialButton
    private lateinit var buttonCancel: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_configuration)

        app = application as TabSSHApplication

        // Set result to CANCELED initially
        setResult(RESULT_CANCELED)

        // Get widget ID from intent
        appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )

        // If widget ID is invalid, finish immediately
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Logger.e("WidgetConfigActivity", "Invalid widget ID")
            finish()
            return
        }

        Logger.d("WidgetConfigActivity", "Configuring widget $appWidgetId")

        // Initialize views
        recyclerView = findViewById(R.id.recycler_connections)
        buttonSave = findViewById(R.id.button_save)
        buttonCancel = findViewById(R.id.button_cancel)

        // Setup RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = WidgetConnectionAdapter { connection ->
            selectedConnectionId = connection.id
            buttonSave.isEnabled = true
            Logger.d("WidgetConfigActivity", "Selected connection: ${connection.getDisplayName()}")
        }
        recyclerView.adapter = adapter

        // Load connections
        loadConnections()

        // Setup buttons
        buttonSave.isEnabled = false
        buttonSave.setOnClickListener {
            saveWidgetConfiguration()
        }

        buttonCancel.setOnClickListener {
            finish()
        }

        supportActionBar?.title = "Configure Quick Connect Widget"
    }

    private fun loadConnections() {
        lifecycleScope.launch {
            try {
                // Collect connections from Flow
                app.database.connectionDao().getAllConnections().collect { connections ->
                    if (connections.isEmpty()) {
                        Logger.w("WidgetConfigActivity", "No connections available")
                        showEmptyState()
                    } else {
                        adapter.submitList(connections)
                        Logger.d("WidgetConfigActivity", "Loaded ${connections.size} connections")
                    }
                }
            } catch (e: Exception) {
                Logger.e("WidgetConfigActivity", "Failed to load connections", e)
            }
        }
    }

    private fun showEmptyState() {
        // Show message that no connections exist
        findViewById<TextView>(R.id.text_empty_state)?.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
    }

    private fun saveWidgetConfiguration() {
        val selectedId = selectedConnectionId
        if (selectedId == null) {
            Logger.w("WidgetConfigActivity", "No connection selected")
            return
        }

        Logger.d("WidgetConfigActivity", "Saving widget configuration: widgetId=$appWidgetId, connectionId=$selectedId")

        // Save widget configuration
        QuickConnectWidgetProvider.saveWidgetConfiguration(this, appWidgetId, selectedId)

        // Update the widget
        val appWidgetManager = AppWidgetManager.getInstance(this)
        QuickConnectWidgetProvider.updateWidget(this, appWidgetManager, appWidgetId)

        // Return success result
        val resultValue = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        setResult(RESULT_OK, resultValue)

        Logger.d("WidgetConfigActivity", "Widget configuration saved successfully")
        finish()
    }

    /**
     * RecyclerView adapter for connection selection
     */
    private class WidgetConnectionAdapter(
        private val onConnectionSelected: (ConnectionProfile) -> Unit
    ) : RecyclerView.Adapter<WidgetConnectionAdapter.ConnectionViewHolder>() {

        private var connections: List<ConnectionProfile> = emptyList()
        private var selectedPosition: Int = -1

        fun submitList(newConnections: List<ConnectionProfile>) {
            connections = newConnections
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConnectionViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_widget_connection, parent, false)
            return ConnectionViewHolder(view)
        }

        override fun onBindViewHolder(holder: ConnectionViewHolder, position: Int) {
            val connection = connections[position]
            holder.bind(connection, position == selectedPosition) {
                val oldPosition = selectedPosition
                selectedPosition = position
                notifyItemChanged(oldPosition)
                notifyItemChanged(selectedPosition)
                onConnectionSelected(connection)
            }
        }

        override fun getItemCount(): Int = connections.size

        class ConnectionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val textName: TextView = itemView.findViewById(R.id.text_connection_name)
            private val textDetails: TextView = itemView.findViewById(R.id.text_connection_details)
            private val radioButton: View = itemView.findViewById(R.id.radio_selected)

            fun bind(
                connection: ConnectionProfile,
                isSelected: Boolean,
                onClick: () -> Unit
            ) {
                textName.text = connection.getDisplayName()
                textDetails.text = "${connection.username}@${connection.host}:${connection.port}"
                radioButton.visibility = if (isSelected) View.VISIBLE else View.GONE

                itemView.setOnClickListener {
                    onClick()
                }
            }
        }
    }
}
