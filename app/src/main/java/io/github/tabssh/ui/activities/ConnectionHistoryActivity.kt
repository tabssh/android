package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wave 3.5 — Read-only history view.
 *
 * Shows every connection that's ever been opened (`lastConnected > 0`)
 * sorted most-recent-first. Each row: name, target, time, count. Tap a row
 * to launch `TabTerminalActivity` for that connection.
 *
 * Built from the existing `connections` table — no separate history table,
 * no audit-log dependency, so we don't pay for storage we don't need.
 */
class ConnectionHistoryActivity : AppCompatActivity() {

    private val rowFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TabSSHApplication

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        val toolbar = Toolbar(this).apply {
            title = "Connection History"
            setBackgroundResource(R.color.primary_500)
            setTitleTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(toolbar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8))
        }
        scroll.addView(listContainer)
        root.addView(scroll)

        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        lifecycleScope.launch {
            try {
                val all = app.database.connectionDao().getRecentConnections(500)
                    .filter { it.lastConnected > 0 }
                runOnUiThread {
                    if (all.isEmpty()) {
                        val empty = TextView(this@ConnectionHistoryActivity).apply {
                            text = "No connection history yet."
                            gravity = Gravity.CENTER
                            setPadding(dp(24))
                        }
                        listContainer.addView(empty)
                        return@runOnUiThread
                    }
                    for (c in all) {
                        listContainer.addView(buildRow(c))
                    }
                }
            } catch (e: Exception) {
                Logger.e("ConnectionHistory", "Failed to load history", e)
                Toast.makeText(this@ConnectionHistoryActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildRow(c: io.github.tabssh.storage.database.entities.ConnectionProfile): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12))
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        if (c.colorTag != 0) {
            val strip = View(this).apply {
                val lp = LinearLayout.LayoutParams(dp(4), MATCH)
                lp.rightMargin = dp(8)
                layoutParams = lp
                setBackgroundColor(c.colorTag)
            }
            row.addView(strip)
        }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        text.addView(TextView(this).apply {
            this.text = c.getDisplayName()
            textSize = 15f
        })
        text.addView(TextView(this).apply {
            this.text = "${c.username}@${c.host}:${c.port} · ${rowFormatter.format(Date(c.lastConnected))} · ${c.connectionCount}×"
            textSize = 12f
        })
        row.addView(text)
        row.setOnClickListener {
            startActivity(
                Intent(this, TabTerminalActivity::class.java)
                    .putExtra("connection_id", c.id)
                    .putExtra("auto_connect", true)
            )
        }
        return row
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
