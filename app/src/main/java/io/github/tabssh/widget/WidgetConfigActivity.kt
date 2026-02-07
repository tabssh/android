package io.github.tabssh.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ui.adapters.ConnectionAdapter
import kotlinx.coroutines.launch

/**
 * Configuration activity for widgets
 * Allows user to select which connection the widget should display
 */
class WidgetConfigActivity : AppCompatActivity() {

    private lateinit var app: TabSSHApplication
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ConnectionAdapter
    private lateinit var buttonCancel: Button
    
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set result to CANCELED initially
        setResult(RESULT_CANCELED)
        
        // Get widget ID from intent
        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        
        setContentView(R.layout.activity_widget_config)
        
        app = application as TabSSHApplication
        
        setupViews()
        loadConnections()
    }

    private fun setupViews() {
        recyclerView = findViewById(R.id.recycler_connections)
        buttonCancel = findViewById(R.id.button_cancel)
        
        // ConnectionAdapter expects a lambda, not a list
        adapter = ConnectionAdapter { connection ->
            saveConnectionAndFinish(connection)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        
        buttonCancel.setOnClickListener {
            finish()
        }
    }

    private fun loadConnections() {
        lifecycleScope.launch {
            app.database.connectionDao().getAllConnections().collect { list ->
                // ListAdapter uses submitList(), not updateList()
                adapter.submitList(list)
            }
        }
    }

    private fun saveConnectionAndFinish(connection: ConnectionProfile) {
        // Save connection preference (connection ID is a String, not Long)
        ConnectionWidgetProvider.saveConnectionPref(this, widgetId, connection.id)
        
        // Update widget
        val appWidgetManager = AppWidgetManager.getInstance(this)
        ConnectionWidgetProvider.updateWidget(this, appWidgetManager, widgetId)
        
        // Return result
        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }
}
