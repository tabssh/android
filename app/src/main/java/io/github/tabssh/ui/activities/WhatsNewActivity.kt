package io.github.tabssh.ui.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
class WhatsNewActivity : TabSSHActivity() {

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
        // Shared app bar, inflated rather than hand-built so this programmatic
        // screen gets the same toolbar styling as every XML-defined screen.
        val appBar = layoutInflater.inflate(R.layout.include_app_bar, root, false)
        val toolbar = appBar.findViewById<Toolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.nav_item_whats_new)
        root.addView(appBar)

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
            this.text = getString(R.string.whats_new_view_full_history_button)
        }
        openBtn.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GH_HISTORY)))
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.whats_new_no_browser_available), Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(openBtn)

        setContentView(root)
        setSupportActionBar(toolbar)

        text.text = try {
            assets.open(ASSET).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to read $ASSET", e)
            getString(R.string.whats_new_asset_missing_fmt, GH_HISTORY)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
}
