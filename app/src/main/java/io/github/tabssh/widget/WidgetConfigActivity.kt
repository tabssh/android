package io.github.tabssh.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ui.activities.ConnectionEditActivity
import io.github.tabssh.ui.adapters.ConnectionAdapter
import kotlinx.coroutines.launch
import io.github.tabssh.utils.tabSSHApp

/**
 * Configuration activity for widgets
 * Allows user to select which connection the widget should display
 */
class WidgetConfigActivity : AppCompatActivity() {

    private lateinit var app: TabSSHApplication
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ConnectionAdapter
    private lateinit var buttonCancel: Button
    private lateinit var emptyState: LinearLayout

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
        
        app = tabSSHApp
        
        setupViews()
        loadConnections()
    }

    private fun setupViews() {
        recyclerView = findViewById(R.id.recycler_connections)
        buttonCancel = findViewById(R.id.button_cancel)
        emptyState = findViewById(R.id.layout_empty_state)

        // ConnectionAdapter expects a lambda, not a list
        adapter = ConnectionAdapter { connection ->
            saveConnectionAndFinish(connection)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        buttonCancel.setOnClickListener {
            finish()
        }

        findViewById<MaterialButton>(R.id.button_add_connection).setOnClickListener {
            startActivity(ConnectionEditActivity.createIntent(this))
        }
    }

    private fun loadConnections() {
        lifecycleScope.launch {
            app.database.connectionDao().getAllConnections().collect { list ->
                // A user with no saved connections must see an actionable
                // empty state, not a blank RecyclerView with nothing to pick.
                if (list.isEmpty()) {
                    recyclerView.visibility = View.GONE
                    emptyState.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility = View.VISIBLE
                    emptyState.visibility = View.GONE
                }
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
