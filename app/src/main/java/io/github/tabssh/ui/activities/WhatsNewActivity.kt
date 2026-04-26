package io.github.tabssh.ui.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.setPadding
import io.github.tabssh.R
import io.github.tabssh.utils.logging.Logger

/**
 * Wave 3.6 — In-app changelog viewer ("What's new").
 *
 * Reads `assets/whats_new.md` (a hand-curated highlight reel; updated each
 * release) and renders it as plain text. Offers a button to open the full
 * git history on GitHub for users who want everything.
 *
 * No on-upgrade pop — user opens it explicitly from the drawer. (Some apps
 * shove this in your face on first launch after update; we don't.)
 */
class WhatsNewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WhatsNew"
        private const val ASSET = "whats_new.md"
        private const val GH_HISTORY = "https://github.com/tabssh/android/commits/main"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        val toolbar = Toolbar(this).apply {
            title = "What's New"
            setBackgroundResource(R.color.primary_500)
            setTitleTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(toolbar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        val text = TextView(this).apply {
            setPadding(dp(16))
            textSize = 14f
            typeface = android.graphics.Typeface.SANS_SERIF
        }
        scroll.addView(text)
        root.addView(scroll)

        val openBtn = Button(this).apply {
            this.text = "View full git history on GitHub"
        }
        openBtn.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GH_HISTORY)))
            } catch (e: Exception) {
                Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(openBtn)

        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        text.text = try {
            assets.open(ASSET).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to read $ASSET", e)
            "What's New asset is missing.\n\nSee $GH_HISTORY for full history."
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
}
