package io.github.tabssh

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.tabssh.storage.database.TabSSHDatabase
import java.lang.ref.WeakReference
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.crypto.keys.KeyStorage
import io.github.tabssh.ssh.connection.SSHSessionManager
import io.github.tabssh.terminal.emulator.TerminalManager
import io.github.tabssh.themes.definitions.ThemeManager
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.performance.PerformanceManager

/**
 * Main application class for TabSSH
 * Handles application-level initialization and dependency injection
 */
class TabSSHApplication : Application() {

    companion object {
        const val STARTUP_PREFS = "tabssh_startup"
        const val KEY_STARTUP_ERROR = "startup_error"
        const val KEY_LAST_CRASH   = "last_crash"
        const val KEY_CRASH_THREAD = "crash_thread"
        const val KEY_CRASH_TIME   = "crash_time"
    }

    // Core components - initialized lazily
    val database by lazy { TabSSHDatabase.getDatabase(this) }
    val preferencesManager by lazy { PreferenceManager(this) }
    val securePasswordManager by lazy { SecurePasswordManager(this) }
    val keyStorage by lazy { KeyStorage(this) }
    val sshSessionManager by lazy { SSHSessionManager(this) }
    val terminalManager by lazy { TerminalManager(this) }
    val themeManager by lazy { ThemeManager(this) }
    val performanceManager by lazy { PerformanceManager(this) }
    val auditLogManager by lazy { io.github.tabssh.audit.AuditLogManager(this, database, preferencesManager) }
    val tabManager by lazy { io.github.tabssh.ui.tabs.TabManager() }

    // Track the current foreground activity for showing dialogs from background threads
    private var currentActivityRef: WeakReference<Activity>? = null

    /**
     * Get the current foreground Activity.
     * Used for showing dialogs from background threads (like host key verification).
     */
    fun getCurrentActivity(): Activity? = currentActivityRef?.get()

    override fun onCreate() {
        super.onCreate()

        // Logger and crash handler must be first — before anything can throw.
        Logger.initialize(this, BuildConfig.DEBUG_MODE)
        setupExceptionHandler()

        Logger.d("TabSSHApplication", "Application starting...")

        // Apply the user's saved app theme BEFORE any Activity is created.
        // SettingsActivity's preference change handler only updates the
        // mode for the current process; without this, every cold start
        // ignores the saved value and the user perceives "only dark mode".
        applySavedAppTheme()

        // Create notification channels
        io.github.tabssh.utils.NotificationHelper.createNotificationChannels(this)
        
        // Register activity lifecycle callbacks. Beyond foreground tracking,
        // this is also where we apply screen-level security flags (FLAG_SECURE
        // for screenshot blocking, FLAG_KEEP_SCREEN_ON for screen wake) so
        // the user-facing prefs work uniformly across every Activity without
        // each activity having to remember to call into a helper.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyWindowSecurityFlags(activity)
            }
            override fun onActivityStarted(activity: Activity) {
                maybeRequireUnlock(activity)
            }
            override fun onActivityResumed(activity: Activity) {
                currentActivityRef = WeakReference(activity)
                // Re-apply on resume — the prefs may have changed in
                // SettingsActivity since the activity was created.
                applyWindowSecurityFlags(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (currentActivityRef?.get() == activity) {
                    currentActivityRef = null
                }
            }
            override fun onActivityStopped(activity: Activity) {
                // Track when the app was last visible so the unlock check
                // on next foreground knows how long we were in background.
                val prefs = androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(this@TabSSHApplication)
                prefs.edit().putLong("ui_last_backgrounded_at", System.currentTimeMillis()).apply()
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Initialize core components
        initializeCoreComponents()

        // Single source of truth for host-key verification dialogs. Set on
        // SSHSessionManager so EVERY future connection inherits it
        // (SSHSessionManager.createConnection copies these onto each new
        // SSHConnection at construction time). Looks up the current
        // foreground Activity via currentActivityRef so the dialog renders
        // wherever the user happens to be — terminal, port-forward,
        // multi-host dashboard, SFTP, performance, anything.
        wireGlobalHostKeyCallbacks()

        Logger.i("TabSSHApplication", "Application initialized successfully")
    }

    private fun wireGlobalHostKeyCallbacks() {
        sshSessionManager.newHostKeyCallback = { info ->
            promptHostKey(
                title = "New Host Key",
                message = info.getDisplayMessage(),
                changedHost = false,
            )
        }
        sshSessionManager.hostKeyChangedCallback = { info ->
            promptHostKey(
                title = "⚠️ Host Key CHANGED",
                message = info.getDisplayMessage(),
                changedHost = true,
            )
        }
    }

    /**
     * Block the calling (background) thread on a UI dialog and return the
     * user's decision. SSH connect is on Dispatchers.IO so the latch wait
     * is safe; the dialog itself is shown on the foreground activity via
     * currentActivityRef.
     *
     * Falls back to REJECT_CONNECTION if no foreground activity is
     * available (app backgrounded mid-connect, or first launch racing
     * with an auto-restored multi-host pump).
     */
    private fun promptHostKey(
        title: String,
        message: String,
        changedHost: Boolean,
    ): io.github.tabssh.ssh.connection.HostKeyAction {
        val activity = currentActivityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Logger.w("TabSSHApplication",
                "No foreground activity to show host-key dialog — rejecting (${if (changedHost) "changed" else "new"})")
            return io.github.tabssh.ssh.connection.HostKeyAction.REJECT_CONNECTION
        }
        var userAction = io.github.tabssh.ssh.connection.HostKeyAction.REJECT_CONNECTION
        val latch = java.util.concurrent.CountDownLatch(1)
        activity.runOnUiThread {
            try {
                val builder = androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setCancelable(false)
                    .setOnDismissListener { latch.countDown() }
                if (changedHost) {
                    // MITM warning — make Reject the prominent choice.
                    builder
                        .setNegativeButton("Reject (recommended)") { _, _ ->
                            userAction = io.github.tabssh.ssh.connection.HostKeyAction.REJECT_CONNECTION
                            latch.countDown()
                        }
                        .setPositiveButton("Accept new key & save") { _, _ ->
                            userAction = io.github.tabssh.ssh.connection.HostKeyAction.ACCEPT_NEW_KEY
                            latch.countDown()
                        }
                        .setNeutralButton("Accept once") { _, _ ->
                            userAction = io.github.tabssh.ssh.connection.HostKeyAction.ACCEPT_ONCE
                            latch.countDown()
                        }
                } else {
                    // First-time host — Accept-and-save is the common path.
                    builder
                        .setPositiveButton("Accept & save") { _, _ ->
                            userAction = io.github.tabssh.ssh.connection.HostKeyAction.ACCEPT_NEW_KEY
                            latch.countDown()
                        }
                        .setNeutralButton("Accept once") { _, _ ->
                            userAction = io.github.tabssh.ssh.connection.HostKeyAction.ACCEPT_ONCE
                            latch.countDown()
                        }
                        .setNegativeButton("Reject") { _, _ ->
                            userAction = io.github.tabssh.ssh.connection.HostKeyAction.REJECT_CONNECTION
                            latch.countDown()
                        }
                }
                builder.show()
            } catch (e: Exception) {
                Logger.e("TabSSHApplication", "Failed to show host-key dialog", e)
                latch.countDown()
            }
        }
        try { latch.await() } catch (_: InterruptedException) {}
        return userAction
    }
    
    private fun initializeCoreComponents() {
        // Clear any previous startup errors
        getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE).edit().remove(KEY_STARTUP_ERROR).apply()

        fun tryInit(name: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                val msg = "$name: ${e::class.simpleName}: ${e.message}"
                Logger.e("TabSSHApplication", "Failed to initialize $name", e)
                // Persist error so MainActivity can surface it on-screen without ADB
                val prefs = getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE)
                val existing = prefs.getString(KEY_STARTUP_ERROR, "")
                prefs.edit().putString(
                    KEY_STARTUP_ERROR,
                    if (existing.isNullOrEmpty()) msg else "$existing\n$msg"
                ).apply()
            }
        }

        tryInit("Preferences")  { preferencesManager.initialize() }
        tryInit("Themes")       { themeManager.initialize() }
        tryInit("Passwords")    { securePasswordManager.initialize() }
        tryInit("KeyStorage")   { keyStorage.initialize() }
        tryInit("SSHSession")   { sshSessionManager.initialize() }
        tryInit("Terminal")     { terminalManager.initialize() }
        tryInit("Performance")  { performanceManager.initialize() }

        Logger.d("TabSSHApplication", "Core components initialized")
    }
    
    /**
     * Background-lock guard — invoked from `onActivityStarted` for every
     * Activity. If the user has `security_auto_lock_background` on AND
     * the app was backgrounded for longer than `security_auto_lock_timeout`
     * seconds AND a PIN is configured, redirect to the PIN-verify screen.
     *
     * Skipped for the PinLockActivity itself (would loop) and skipped if
     * we're tracking the very first launch (no prior background timestamp).
     */
    private fun maybeRequireUnlock(activity: Activity) {
        try {
            val prefs = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this)
            if (!prefs.getBoolean("security_auto_lock_background", false)) return
            if (!prefs.getBoolean("app_lock_enabled", false)) return
            if (activity::class.java.simpleName == "PinLockActivity") return

            val backgroundedAt = prefs.getLong("ui_last_backgrounded_at", 0L)
            if (backgroundedAt == 0L) return  // first launch in this process
            val timeoutSec = (prefs.getString("security_auto_lock_timeout", "300") ?: "300")
                .toIntOrNull() ?: 300
            val elapsed = (System.currentTimeMillis() - backgroundedAt) / 1000
            if (elapsed < timeoutSec) return

            Logger.i("TabSSHApplication",
                "Auto-lock triggered after ${elapsed}s background (limit ${timeoutSec}s)")
            // Reset the timestamp so we don't re-trigger immediately if the
            // PIN screen itself moves through the lifecycle hooks.
            prefs.edit().putLong("ui_last_backgrounded_at", 0L).apply()

            val intent = io.github.tabssh.ui.activities.PinLockActivity
                .verifyIntent(activity)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            activity.startActivity(intent)
        } catch (e: Exception) {
            Logger.w("TabSSHApplication", "maybeRequireUnlock failed: ${e.message}")
        }
    }

    private fun applyWindowSecurityFlags(activity: Activity) {
        try {
            val prefs = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this)
            val window = activity.window ?: return
            // Block screenshots / screen recording when the user opts in.
            if (prefs.getBoolean("security_prevent_screenshots", false)) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }

            // Keep the screen on globally when the pref says so. Per-activity
            // overrides (e.g. terminal-only) still apply on top of this.
            if (prefs.getBoolean("keep_screen_on", false)) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } catch (e: Exception) {
            Logger.w("TabSSHApplication", "applyWindowSecurityFlags failed: ${e.message}")
        }
    }

    private fun applySavedAppTheme() {
        // Read directly via PreferenceManager — same key as SettingsActivity
        // (preferences_general.xml: `android:key="app_theme"`). Mode values:
        //   "light"  → MODE_NIGHT_NO
        //   "dark"   → MODE_NIGHT_YES
        //   "system" → MODE_NIGHT_FOLLOW_SYSTEM (Android 10+) or AUTO_BATTERY
        try {
            val prefs = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this)
            val theme = prefs.getString("app_theme", "system") ?: "system"
            val mode = when (theme) {
                "light"  -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                "dark"   -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else     -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
            Logger.d("TabSSHApplication", "Applied saved theme: $theme (mode=$mode)")
        } catch (e: Exception) {
            Logger.w("TabSSHApplication", "Failed to apply saved theme: ${e.message}")
        }
    }

    private fun setupExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Write synchronously before anything else
            Logger.writeCrashSync(thread, throwable)
            Logger.e("TabSSHApplication", "Uncaught exception in thread ${thread.name}", throwable)

            if (BuildConfig.DEBUG_MODE) {
                try {
                    getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_LAST_CRASH, android.util.Log.getStackTraceString(throwable))
                        .putString(KEY_CRASH_THREAD, thread.name)
                        .putLong(KEY_CRASH_TIME, System.currentTimeMillis())
                        .commit() // must be synchronous

                    startActivity(
                        android.content.Intent(
                            this,
                            io.github.tabssh.ui.activities.CrashReportActivity::class.java
                        ).apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                    )
                } catch (e: Exception) {
                    // Crash reporter itself failed — fall through to default handler below
                    defaultHandler?.uncaughtException(thread, throwable)
                }
                // Do NOT call defaultHandler in debug mode.
                // Keeping the process alive lets the crash screen stay on screen
                // so the developer can read the trace and tap Copy/Share.
                return@setDefaultUncaughtExceptionHandler
            }

            // Release build: clean up and let Android handle it normally
            try {
                securePasswordManager.clearSensitiveDataOnCrash()
                sshSessionManager.closeAllConnections()
            } catch (_: Exception) {}

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
    
    override fun onTerminate() {
        Logger.d("TabSSHApplication", "Application terminating...")
        
        // Close all SSH connections
        sshSessionManager.closeAllConnections()
        
        // Clear sensitive data from memory
        securePasswordManager.clearSensitiveData()
        
        super.onTerminate()
    }
    
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        
        when (level) {
            TRIM_MEMORY_BACKGROUND,
            TRIM_MEMORY_MODERATE,
            TRIM_MEMORY_COMPLETE -> {
                // App is in background, trim memory usage
                terminalManager.trimInactiveTerminals()
                themeManager.clearCache()
                Logger.d("TabSSHApplication", "Memory trimmed due to level $level")
            }
        }
    }
}