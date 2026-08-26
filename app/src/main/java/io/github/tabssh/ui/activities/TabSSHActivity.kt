package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import io.github.tabssh.BuildConfig
import io.github.tabssh.R
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chrome base class for every TabSSH screen.
 *
 * It owns only the app-wide furniture — the navigation drawer, the toolbar
 * navigation affordance, and the drawer's destinations. Feature screens keep
 * their own logic and their own content layout; they never subclass one
 * another. (AI.md PART 0 non-negotiable #8: activity composition over
 * inheritance — a shared chrome base is the composition seam, not a feature
 * hierarchy.)
 *
 * A screen opts in by extending this class and calling
 * `setSupportActionBar(toolbar)` exactly as before. Everything else —
 * hamburger icon, content description, click handling, drawer back
 * behaviour and drawer navigation — is supplied here so that all screens
 * behave identically.
 */
abstract class TabSSHActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    /**
     * How the navigation drawer may be opened on this screen.
     *
     * FULL — hamburger button plus the standard left-edge swipe.
     * TOOLBAR_ONLY — hamburger button only; the left edge belongs to the
     * screen itself. Session screens (terminal, VNC, SPICE) need the edge
     * for their own gestures, so they are the only users of this mode.
     */
    enum class DrawerMode {
        FULL,
        TOOLBAR_ONLY
    }

    protected open val drawerMode: DrawerMode = DrawerMode.FULL

    /**
     * Which affordance the toolbar's navigation icon offers.
     *
     * DRAWER — the hamburger icon, opens the navigation drawer. Default.
     * UP — a Material back arrow that routes through the same
     * [OnBackPressedDispatcher] as system Back, so an edit/detail screen's
     * up arrow triggers the same unsaved-changes prompt as
     * pressing Back does. `parentActivityName` in the manifest is not used
     * for this — it never fires because this affordance, not the framework
     * default, owns the toolbar's navigation icon.
     */
    enum class NavigationAffordance {
        DRAWER,
        UP
    }

    protected open val navigationAffordance: NavigationAffordance = NavigationAffordance.DRAWER

    private var drawerLayout: DrawerLayout? = null
    private var navigationView: NavigationView? = null
    private var appBarToolbar: Toolbar? = null

    // Enabled only while the drawer is open, so a back press closes the
    // drawer instead of leaving the screen. Registered in onPostCreate so it
    // takes priority over callbacks a subclass adds during onCreate.
    private val drawerBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            closeNavigationDrawer()
        }
    }

    /**
     * True once an editing subclass has recorded a change to a tracked form
     * field. Read by [unsavedChangesBackCallback] and [confirmDiscardIfNeeded]
     * so system Back, the up arrow and a screen's own Cancel button all show
     * the same "Discard changes?" prompt instead of three different ones.
     * A subclass flips this from its own field listeners
     * (TextWatcher / OnCheckedChangeListener / etc.) — the set of fields to
     * watch is screen-specific, so it is not inferred here.
     */
    protected var hasUnsavedChanges: Boolean = false

    // Disabled until a subclass calls enableUnsavedChangesGuard() — screens
    // that never edit anything (trackers, dashboards) must not pay for this.
    private val unsavedChangesBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            confirmDiscardIfNeeded {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun setContentView(layoutResID: Int) {
        setContentView(layoutInflater.inflate(layoutResID, null))
    }

    override fun setContentView(view: View?) {
        setContentView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        val scaffold = layoutInflater.inflate(R.layout.activity_tabssh_scaffold, null) as DrawerLayout
        val container = scaffold.findViewById<FrameLayout>(R.id.tabssh_content)
        if (view != null) {
            val childParams = params ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            container.addView(view, childParams)
        }
        super.setContentView(
            scaffold,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        drawerLayout = scaffold
        navigationView = scaffold.findViewById(R.id.tabssh_nav_view)
        navigationView?.setNavigationItemSelectedListener(this)

        scaffold.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                drawerBackCallback.isEnabled = true
            }

            override fun onDrawerClosed(drawerView: View) {
                drawerBackCallback.isEnabled = false
                applyDrawerMode()
            }
        })
        applyDrawerMode()
    }

    // Cached so the overlay is applied at most once per Activity instance —
    // Resources.Theme.applyStyle() is cumulative, so re-applying on every
    // getTheme() call (it can be called many times while inflating a
    // screen) would stack the overlay's items redundantly.
    private var highContrastOverlayApplied = false

    /**
     * Applies the high-contrast accessibility overlay on top of whichever
     * light/dark theme is already active, instead of switching to a
     * separate fixed-mode theme (AI.md PART 7: "High-contrast mode toggle
     * applied as a palette overlay"). Mirrors the run-time, preference-driven
     * theme switching ThemeManager.applyTheme() already does for custom
     * terminal themes, applied here to the app chrome.
     */
    override fun getTheme(): android.content.res.Resources.Theme {
        val theme = super.getTheme()
        if (!highContrastOverlayApplied) {
            val highContrast = try {
                io.github.tabssh.TabSSHApplication.get().preferencesManager.isHighContrastMode()
            } catch (e: Exception) {
                Logger.w("TabSSHActivity", "Failed to read high-contrast preference: ${e.message}")
                false
            }
            if (highContrast) {
                theme.applyStyle(R.style.ThemeOverlay_TabSSH_HighContrast, true)
            }
            highContrastOverlayApplied = true
        }
        return theme
    }

    /**
     * Records the toolbar so the shared navigation affordance can be
     * re-applied after the screen finishes its own setup.
     */
    override fun setSupportActionBar(toolbar: Toolbar?) {
        super.setSupportActionBar(toolbar)
        appBarToolbar = toolbar
        applyNavigationAffordance()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, drawerBackCallback)
        // Re-applied here because ActionBar.setDisplayHomeAsUpEnabled and
        // per-screen navigation click listeners run during onCreate and would
        // otherwise replace the shared hamburger with a screen-local icon.
        applyNavigationAffordance()
    }

    private fun applyNavigationAffordance() {
        val toolbar = appBarToolbar ?: return
        when (navigationAffordance) {
            NavigationAffordance.DRAWER -> {
                toolbar.setNavigationIcon(R.drawable.ic_menu)
                toolbar.navigationContentDescription = getString(R.string.navigation_drawer_open)
                toolbar.setNavigationOnClickListener { openNavigationDrawer() }
            }
            NavigationAffordance.UP -> {
                toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
                toolbar.navigationContentDescription = getString(R.string.navigation_up_description)
                toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            }
        }
    }

    /**
     * Opts an edit/detail screen into the shared unsaved-changes discard
     * guard. Call once from onCreate, after wiring the field
     * listeners that flip [hasUnsavedChanges]. Has no effect on its own if
     * [hasUnsavedChanges] is never set to true — the guard is a no-op prompt
     * gate, not a tracker.
     */
    protected fun enableUnsavedChangesGuard() {
        unsavedChangesBackCallback.isEnabled = true
        onBackPressedDispatcher.addCallback(this, unsavedChangesBackCallback)
    }

    /**
     * Runs [onProceed] immediately when there is nothing unsaved; otherwise
     * confirms via a discard dialog first. Shared by the back callback, the
     * up arrow (through system Back) and every screen's own Cancel button so
     * the three exit paths never diverge.
     */
    protected fun confirmDiscardIfNeeded(onProceed: () -> Unit) {
        if (!hasUnsavedChanges) {
            onProceed()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.conn_edit_discard_changes_title)
            .setMessage(R.string.conn_edit_discard_changes_message)
            .setPositiveButton(R.string.discard) { _, _ -> onProceed() }
            .setNegativeButton(R.string.conn_edit_keep_editing, null)
            .show()
    }

    /**
     * Recursively saves the text/checked/selection state of every id'd
     * EditText (including AutoCompleteTextView), CompoundButton (Switch,
     * CheckBox, RadioButton, ToggleButton) and Spinner under [root], keyed by
     * its resource id (rotation loses in-progress form data).
     * Views without an `android:id` are skipped — there is no stable key to
     * restore them by, and the framework's own view-hierarchy state
     * restoration already covers everything else (scroll position, spinner
     * dropdown open state, etc.).
     */
    protected fun saveFormState(outState: Bundle, root: View) {
        forEachIdentifiedFormField(root) { view, key ->
            when (view) {
                is android.widget.EditText -> outState.putString(key, view.text?.toString())
                is android.widget.CompoundButton -> outState.putBoolean(key, view.isChecked)
                is android.widget.Spinner -> outState.putInt(key, view.selectedItemPosition)
            }
        }
    }

    /**
     * Restores the state [saveFormState] captured. Call after the screen's
     * own data-driven population (from a database record or intent extras)
     * has finished, so the restored in-progress edits are applied last and
     * are not immediately overwritten by the loaded record.
     */
    protected fun restoreFormState(savedInstanceState: Bundle, root: View) {
        forEachIdentifiedFormField(root) { view, key ->
            if (!savedInstanceState.containsKey(key)) return@forEachIdentifiedFormField
            when (view) {
                is android.widget.EditText -> view.setText(savedInstanceState.getString(key))
                is android.widget.CompoundButton -> view.isChecked = savedInstanceState.getBoolean(key)
                is android.widget.Spinner -> view.setSelection(savedInstanceState.getInt(key))
            }
        }
    }

    private inline fun forEachIdentifiedFormField(root: View, action: (View, String) -> Unit) {
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
            }
            if (view.id == View.NO_ID) continue
            action(view, "formField_${view.id}")
        }
    }

    private fun applyDrawerMode() {
        val lockMode = when (drawerMode) {
            DrawerMode.FULL -> DrawerLayout.LOCK_MODE_UNLOCKED
            DrawerMode.TOOLBAR_ONLY -> DrawerLayout.LOCK_MODE_LOCKED_CLOSED
        }
        drawerLayout?.setDrawerLockMode(lockMode, GravityCompat.START)
    }

    /**
     * Opens the drawer regardless of mode. TOOLBAR_ONLY screens keep the
     * drawer locked while it is closed (so edge swipes reach the session),
     * so the lock is lifted for the duration it is on screen.
     */
    protected fun openNavigationDrawer() {
        val drawer = drawerLayout ?: return
        if (drawerMode == DrawerMode.TOOLBAR_ONLY) {
            drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START)
        }
        drawer.openDrawer(GravityCompat.START)
    }

    protected fun closeNavigationDrawer() {
        drawerLayout?.closeDrawer(GravityCompat.START)
    }

    protected fun isNavigationDrawerOpen(): Boolean =
        drawerLayout?.isDrawerOpen(GravityCompat.START) == true

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        closeNavigationDrawer()
        when (item.itemId) {
            R.id.nav_home -> goHome()
            R.id.nav_quick_connect -> startMain(EXTRA_QUICK_CONNECT)
            R.id.nav_domain_tracker -> startDestination(DomainTrackerActivity::class.java)
            R.id.nav_vps_tracker -> startDestination(VpsTrackerActivity::class.java)
            R.id.nav_snippets -> startDestination(SnippetManagerActivity::class.java)
            R.id.nav_manage_groups -> startDestination(GroupManagementActivity::class.java)
            R.id.nav_port_forwarding -> startDestination(PortForwardingActivity::class.java)
            R.id.nav_cluster_commands -> startDestination(ClusterCommandActivity::class.java)
            R.id.nav_stats -> startDestination(StatsActivity::class.java)
            R.id.nav_multi_dashboard -> startDestination(MultiHostDashboardActivity::class.java)
            R.id.nav_connection_history -> startDestination(ConnectionHistoryActivity::class.java)
            R.id.nav_settings -> startDestination(SettingsActivity::class.java)
            R.id.nav_whats_new -> startDestination(WhatsNewActivity::class.java)
            R.id.nav_copy_app_log -> copyAppLog()
            R.id.nav_copy_debug_logs -> copyDebugLogs()
            R.id.nav_help -> showHelpDialog()
            R.id.nav_about -> showAboutDialog()
            else -> return false
        }
        return true
    }

    /**
     * Navigates to a drawer destination, or does nothing when the user picks
     * the screen they are already on.
     */
    private fun startDestination(target: Class<out AppCompatActivity>) {
        if (this::class.java == target) return
        startActivity(Intent(this, target))
    }

    /**
     * Drawer entries whose UI lives on the home screen route back through it.
     * MainActivity is singleTop, so this reuses the existing instance.
     */
    private fun startMain(action: String) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(EXTRA_NAV_ACTION, action)
            }
        )
    }

    /**
     * "Home" always lands back on the main Frequent/Hosts/Panes/Infra/Auth
     * tab UI, the same way reopening the app does — there was previously no
     * drawer path back to it once you'd drilled into a tracker/manager screen.
     * CLEAR_TOP+SINGLE_TOP reuses the existing MainActivity instance (or
     * starts a fresh one, which restores the last-used tab the same way a
     * cold launch does) and drops any drawer destinations stacked above it.
     */
    private fun goHome() {
        if (this::class.java == MainActivity::class.java) return
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
    }

    protected fun showHelpDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_body)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.action_visit_website) { _, _ ->
                openProjectUrl(PROJECT_URL)
            }
            .show()
    }

    protected fun showAboutDialog() {
        // Version and build metadata come from BuildConfig so devel, daily and
        // beta builds are distinguishable instead of all reporting the same
        // hard-coded version.
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE
        val commit = BuildConfig.GIT_COMMIT_ID
        val flavor = BuildConfig.BUILD_TYPE
        // Native components are cross-compiled per ABI, so report their live
        // availability rather than assuming this build bundles them.
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull()
            ?: getString(R.string.about_abi_unknown)
        val torState = if (io.github.tabssh.protocols.tor.TorNativeClient.isAvailable(this)) {
            getString(R.string.about_component_included)
        } else {
            getString(R.string.about_component_not_bundled)
        }
        val moshState = if (io.github.tabssh.protocols.mosh.MoshNativeClient.isAvailable(this)) {
            getString(R.string.about_component_included)
        } else {
            getString(R.string.about_component_not_bundled)
        }
        val nativeComponents = getString(
            R.string.about_native_components,
            abi,
            io.github.tabssh.protocols.tor.TorNativeClient.TOR_VERSION,
            torState,
            io.github.tabssh.protocols.mosh.MoshNativeClient.MOSH_VERSION,
            moshState,
            io.github.tabssh.protocols.tor.TorNativeClient.OPENSSL_VERSION,
            io.github.tabssh.protocols.tor.TorNativeClient.LIBEVENT_VERSION,
            io.github.tabssh.protocols.tor.TorNativeClient.ZLIB_VERSION,
            BuildConfig.TERMINAL_EMULATOR_VERSION,
            resources.getQuantityString(
                R.plurals.about_fonts_count,
                io.github.tabssh.utils.FontManager.bundledFontCount(),
                io.github.tabssh.utils.FontManager.bundledFontCount()
            )
        )
        val aboutText = getString(
            R.string.about_body,
            versionName,
            versionCode,
            commit,
            flavor,
            nativeComponents
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(aboutText)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.action_github) { _, _ -> openProjectUrl(PROJECT_URL) }
            .setNegativeButton(R.string.action_license) { _, _ -> openProjectUrl(LICENSE_URL) }
            .show()
    }

    private fun openProjectUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }

    /**
     * Copies the sanitized application log, which is safe to share publicly.
     */
    protected fun copyAppLog() {
        // Probe the file directly: getAppLog() returns a placeholder string
        // when nothing has been recorded, and substring-matching that
        // placeholder was unreliable.
        val file = Logger.getAppLogFile()
        val haveRealLogs = file != null && file.exists() && file.length() > 0
        if (!haveRealLogs) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.log_app_title)
                .setMessage(R.string.log_app_empty)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }

        offerLogShareOrCopy(
            title = getString(R.string.log_app_title),
            clipLabel = getString(R.string.log_app_clip_label),
            logs = Logger.getAppLog(),
            logType = "app",
            onClear = {
                Logger.clearAppLog()
                android.widget.Toast.makeText(
                    this,
                    R.string.log_app_cleared,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    /**
     * Copies the developer debug log, which is only captured by debug builds.
     */
    protected fun copyDebugLogs() {
        val file = Logger.getLogFile()
        val haveRealLogs = file != null && file.exists() && file.length() > 0
        if (!haveRealLogs) {
            // The Debug Logging settings category is hidden when DEBUG_LOG is
            // false, so pointing a release-build user at Settings would be a
            // dead end; say what is actually true for their build.
            val message = if (BuildConfig.DEBUG_LOG) {
                getString(R.string.log_debug_empty_debug_build)
            } else {
                getString(R.string.log_debug_empty_release_build)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.log_debug_empty_title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }

        // Logger.getAllLogs() reads several files synchronously and must not
        // run on the main thread.
        lifecycleScope.launch {
            val logs = withContext(Dispatchers.IO) { Logger.getAllLogs() }
            offerLogShareOrCopy(
                title = getString(R.string.log_debug_title),
                clipLabel = getString(R.string.log_debug_clip_label),
                logs = logs,
                logType = "debug",
                onClear = {
                    Logger.clearLogs()
                    android.widget.Toast.makeText(
                        this@TabSSHActivity,
                        R.string.log_debug_cleared,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    /**
     * Shows a captured log and offers Copy, Paste/Issue and Clear.
     *
     * "Paste / Issue" uploads the sanitized log to the configured paste
     * service and opens a pre-filled GitHub issue — no API token needed.
     * The clipboard write is attempted only below a conservative size cap
     * because OEM clipboard services throw TransactionTooLargeException on
     * large payloads and there is no way to recover mid-binder-call.
     *
     * @param logType "debug" or "app" — labels the paste correctly.
     */
    private fun offerLogShareOrCopy(
        title: String,
        clipLabel: String,
        logs: String,
        logType: String,
        onClear: () -> Unit
    ) {
        val openIssueAction: () -> Unit = {
            io.github.tabssh.ui.dialogs.ReportIssueDialog
                .create(logs, logType)
                .show(supportFragmentManager, "report_issue")
        }

        // The real binder limit is roughly 1 MB across all parcels in the
        // call and the clipboard service adds metadata, so 256 KB leaves
        // headroom even on cranky OEM builds.
        val clipboardCapBytes = 256 * 1024
        val logsBytes = logs.toByteArray(Charsets.UTF_8).size

        if (logsBytes > clipboardCapBytes) {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.log_too_large_title, title))
                .setMessage(
                    getString(
                        R.string.log_too_large_message,
                        io.github.tabssh.utils.Format.size(this, logsBytes.toLong())
                    )
                )
                .setPositiveButton(R.string.settings_action_report_issue) { _, _ -> openIssueAction() }
                .setNeutralButton(R.string.action_clear) { _, _ -> onClear() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        val copied = try {
            io.github.tabssh.utils.ClipboardHelper.copy(this, clipLabel, logs, sensitive = false)
            true
        } catch (e: Throwable) {
            Logger.e("TabSSHActivity", "Clipboard write failed for $title ($logsBytes bytes)", e)
            false
        }

        val message = if (copied) {
            getString(R.string.log_copied_message, logs.length)
        } else {
            getString(R.string.log_copy_failed_message)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (copied) getString(R.string.log_copied_title, title) else title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.settings_action_report_issue) { _, _ -> openIssueAction() }
            .setNegativeButton(R.string.action_clear) { _, _ -> onClear() }
            .show()
    }

    companion object {
        /** Names a drawer action that MainActivity should perform on arrival. */
        const val EXTRA_NAV_ACTION = "tabssh_nav_action"

        /** Value of [EXTRA_NAV_ACTION] that opens the Quick Connect dialog. */
        const val EXTRA_QUICK_CONNECT = "quick_connect"

        private const val PROJECT_URL = "https://github.com/tabssh/android"
        private const val LICENSE_URL = "https://github.com/tabssh/android/blob/main/LICENSE.md"
    }
}
