package io.github.tabssh.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.ui.activities.MainActivity
import io.github.tabssh.ui.activities.TabTerminalActivity
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Widget provider for quick SSH connection access
 * Allows users to add home screen widgets for their favorite SSH servers
 */
class QuickConnectWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Logger.d("QuickConnectWidget", "onUpdate called for ${appWidgetIds.size} widgets")

        // Update each widget instance
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        Logger.d("QuickConnectWidget", "onDeleted called for ${appWidgetIds.size} widgets")

        // Clean up widget configuration when deleted
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        for (appWidgetId in appWidgetIds) {
            editor.remove(PREF_CONNECTION_ID_PREFIX + appWidgetId)
        }

        editor.apply()
    }

    override fun onEnabled(context: Context) {
        Logger.d("QuickConnectWidget", "First widget instance created")
    }

    override fun onDisabled(context: Context) {
        Logger.d("QuickConnectWidget", "Last widget instance removed")
    }

    companion object {
        private const val PREFS_NAME = "io.github.tabssh.ui.widget.QuickConnectWidgetProvider"
        private const val PREF_CONNECTION_ID_PREFIX = "widget_connection_id_"
        private const val ACTION_CONNECT = "io.github.tabssh.ACTION_WIDGET_CONNECT"

        /**
         * Save widget configuration (connection profile ID)
         */
        fun saveWidgetConfiguration(context: Context, appWidgetId: Int, connectionId: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(PREF_CONNECTION_ID_PREFIX + appWidgetId, connectionId)
                .apply()

            Logger.d("QuickConnectWidget", "Saved widget $appWidgetId config: connectionId=$connectionId")
        }

        /**
         * Load widget configuration (connection profile ID)
         */
        fun loadWidgetConfiguration(context: Context, appWidgetId: Int): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(PREF_CONNECTION_ID_PREFIX + appWidgetId, null)
        }

        /**
         * Update a single widget instance
         */
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            Logger.d("QuickConnectWidget", "Updating widget $appWidgetId")

            // Load widget configuration
            val connectionId = loadWidgetConfiguration(context, appWidgetId)

            if (connectionId == null) {
                Logger.w("QuickConnectWidget", "Widget $appWidgetId has no connection configured")
                updateWidgetWithError(context, appWidgetManager, appWidgetId, "No connection configured")
                return
            }

            // Load connection profile from database
            val app = context.applicationContext as TabSSHApplication

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val profile = app.database.connectionDao().getConnectionById(connectionId)

                    if (profile == null) {
                        Logger.w("QuickConnectWidget", "Connection $connectionId not found")
                        CoroutineScope(Dispatchers.Main).launch {
                            updateWidgetWithError(context, appWidgetManager, appWidgetId, "Connection not found")
                        }
                        return@launch
                    }

                    // Update widget UI on main thread
                    CoroutineScope(Dispatchers.Main).launch {
                        updateWidgetWithConnection(context, appWidgetManager, appWidgetId, profile)
                    }

                } catch (e: Exception) {
                    Logger.e("QuickConnectWidget", "Failed to load connection for widget", e)
                    CoroutineScope(Dispatchers.Main).launch {
                        updateWidgetWithError(context, appWidgetManager, appWidgetId, "Error loading connection")
                    }
                }
            }
        }

        /**
         * Update widget with connection profile data
         */
        private fun updateWidgetWithConnection(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            profile: io.github.tabssh.storage.database.entities.ConnectionProfile
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_connect)

            // Set connection info
            views.setTextViewText(R.id.text_connection_name, profile.getDisplayName())
            views.setTextViewText(
                R.id.text_connection_details,
                "${profile.username}@${profile.host}:${profile.port}"
            )

            // Create intent to launch TabTerminalActivity with this connection
            val connectIntent = Intent(context, TabTerminalActivity::class.java).apply {
                action = ACTION_CONNECT
                putExtra("connection_id", profile.id)
                putExtra("widget_id", appWidgetId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId, // Use widget ID as request code for uniqueness
                connectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(R.id.button_connect, pendingIntent)

            // Update widget
            appWidgetManager.updateAppWidget(appWidgetId, views)

            Logger.d("QuickConnectWidget", "Widget $appWidgetId updated with connection: ${profile.getDisplayName()}")
        }

        /**
         * Update widget with error message
         */
        private fun updateWidgetWithError(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            errorMessage: String
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_connect)

            // Set error info
            views.setTextViewText(R.id.text_connection_name, "Error")
            views.setTextViewText(R.id.text_connection_details, errorMessage)

            // Create intent to open main activity
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(R.id.button_connect, pendingIntent)

            // Update widget
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
