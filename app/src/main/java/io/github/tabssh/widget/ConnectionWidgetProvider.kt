package io.github.tabssh.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ui.activities.MainActivity
import io.github.tabssh.ui.activities.TabTerminalActivity
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Widget provider for TabSSH connection widgets
 * Supports 4 sizes: 1x1, 2x1, 4x2, 4x4
 */
open class ConnectionWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Logger.d("Widget", "onUpdate called for ${appWidgetIds.size} widgets")
        
        appWidgetIds.forEach { widgetId ->
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        Logger.d("Widget", "onDeleted called for ${appWidgetIds.size} widgets")
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            appWidgetIds.forEach { widgetId ->
                remove("connection_id_$widgetId")
            }
            apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "io.github.tabssh.widget"

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val connectionId = prefs.getString("connection_id_$widgetId", null)
            
            val widgetInfo = appWidgetManager.getAppWidgetInfo(widgetId) ?: return
            val layoutResId = when {
                widgetInfo.minWidth <= 40 -> R.layout.widget_1x1
                widgetInfo.minWidth <= 110 -> R.layout.widget_2x1
                widgetInfo.minWidth <= 250 -> R.layout.widget_4x2
                else -> R.layout.widget_4x4
            }
            
            if (connectionId != null) {
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val app = context.applicationContext as TabSSHApplication
                        val connection = app.database.connectionDao().getConnectionById(connectionId)
                        
                        if (connection != null) {
                            updateWidgetWithConnection(context, appWidgetManager, widgetId, connection, layoutResId)
                        } else {
                            updateWidgetEmpty(context, appWidgetManager, widgetId, layoutResId)
                        }
                    } catch (e: Exception) {
                        Logger.e("Widget", "Failed to load connection", e)
                        updateWidgetEmpty(context, appWidgetManager, widgetId, layoutResId)
                    }
                }
            } else {
                updateWidgetEmpty(context, appWidgetManager, widgetId, layoutResId)
            }
        }

        private fun updateWidgetWithConnection(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int,
            connection: ConnectionProfile,
            layoutResId: Int
        ) {
            val views = RemoteViews(context.packageName, layoutResId)
            
            when (layoutResId) {
                R.layout.widget_1x1 -> {
                    val icon = if (connection.keyId != null) "🔑" else "🖥️"
                    views.setTextViewText(R.id.widget_icon, icon)
                    views.setOnClickPendingIntent(R.id.widget_root, getConnectIntent(context, connection))
                }
                R.layout.widget_2x1 -> {
                    val icon = if (connection.keyId != null) "🔑" else "🖥️"
                    views.setTextViewText(R.id.widget_icon, icon)
                    views.setTextViewText(R.id.widget_name, connection.name)
                    views.setTextViewText(R.id.widget_info, "${connection.username}@${connection.host}")
                    views.setOnClickPendingIntent(R.id.widget_connect, getConnectIntent(context, connection))
                }
            }
            
            appWidgetManager.updateAppWidget(widgetId, views)
        }

        private fun updateWidgetEmpty(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int,
            layoutResId: Int
        ) {
            val views = RemoteViews(context.packageName, layoutResId)
            
            when (layoutResId) {
                R.layout.widget_1x1 -> {
                    views.setTextViewText(R.id.widget_icon, "📱")
                    views.setOnClickPendingIntent(R.id.widget_root, getMainIntent(context))
                }
                R.layout.widget_2x1 -> {
                    views.setTextViewText(R.id.widget_icon, "📱")
                    views.setTextViewText(R.id.widget_name, "TabSSH")
                    views.setTextViewText(R.id.widget_info, "Tap to configure")
                    views.setOnClickPendingIntent(R.id.widget_connect, getMainIntent(context))
                }
            }
            
            appWidgetManager.updateAppWidget(widgetId, views)
        }

        private fun getConnectIntent(context: Context, connection: ConnectionProfile): PendingIntent {
            val intent = Intent(context, TabTerminalActivity::class.java).apply {
                putExtra("connection_id", connection.id)
                putExtra("auto_connect", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context, connection.id.toInt(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun getMainIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
            return PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun saveConnectionPref(context: Context, widgetId: Int, connectionId: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("connection_id_$widgetId", connectionId).apply()
        }

        fun updateAllWidgets(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ConnectionWidgetProvider::class.java))
            ids.forEach { updateWidget(context, mgr, it) }
        }
    }
}

    // Separate widget providers for different sizes (required by Android)
    class Widget2x1 : ConnectionWidgetProvider()
    class Widget4x2 : ConnectionWidgetProvider()
    class Widget4x4 : ConnectionWidgetProvider()
