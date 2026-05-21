package io.github.tabssh

import android.app.Activity
import android.app.Application
import android.os.Bundle
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
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
import kotlinx.coroutines.launch

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
        const val KEY_LAST_LOGGED_COMMIT = "last_logged_commit"

        // Intent extras used to pass crash data directly to CrashReportActivity.
        // Preferred over SharedPreferences because they are in-process and not
        // subject to disk-write races; SharedPreferences is kept as a fallback.
        const val EXTRA_CRASH_TRACE  = "extra_crash_trace"
        const val EXTRA_CRASH_THREAD = "extra_crash_thread"
        const val EXTRA_CRASH_TIME   = "extra_crash_time"

        // Lightweight Application singleton — set in onCreate, used by
        // helpers that don't have a Context (notably SSHTab when it
        // builds the multiplexer auto-launch command from
        // gesture_multiplexer_type). Intentionally not exposed via
        // public API; treat as a last-resort accessor.
        @Volatile private var INSTANCE: TabSSHApplication? = null
        fun get(): TabSSHApplication = INSTANCE
            ?: error("TabSSHApplication.get() called before onCreate()")
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
    /** App-wide network state observer. Single instance so every connection
     *  type (SSH, VNC, Telnet) shares one [ConnectivityManager] callback. */
    val networkDetector by lazy { io.github.tabssh.network.detection.NetworkDetector(this) }

    // ANR watchdog — single instance, only running when debug logging is
    // active. Public start/stop so the Settings → Logging toggle can flip
    // it in lockstep with `Logger.forceEnableDebugMode` /
    // `Logger.disableDebugMode`.
    private val anrWatchdog by lazy { io.github.tabssh.utils.diagnostics.AnrWatchdog() }
    fun startAnrWatchdog() = anrWatchdog.start()
    fun stopAnrWatchdog() = anrWatchdog.stop()

    /**
     * Issue #36 — application-wide background coroutine scope, used by
     * `onCreate` to push slow init off the main thread. SupervisorJob
     * because one component failing shouldn't cancel the others; the
     * `tryInit` wrapper inside `initializeCoreComponents` already isolates
     * exceptions per component.
     *
     * No need to ever cancel this scope — it dies with the process.
     */
    val applicationScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()
    )

    // Track the current foreground activity for showing dialogs from background threads
    private var currentActivityRef: WeakReference<Activity>? = null
    private var foregroundActivityCount = 0

    /**
     * Get the current foreground Activity.
     * Used for showing dialogs from background threads (like host key verification).
     */
    fun getCurrentActivity(): Activity? = currentActivityRef?.get()

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this

        // Android ships a stripped "BC" provider that is missing KeyFactory/RSA
        // and other algorithms. Replace it with the full external BouncyCastle
        // before any crypto code runs so every subsequent BC lookup is correct.
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        // Logger init policy:
        //   - debug builds (BuildConfig.DEBUG_MODE = true) auto-enable
        //     debug-file logging — these are dev / daily / CI APKs and the
        //     extra log spam is exactly what makes them useful for testing.
        //   - release / fdroidRelease builds default OFF; users opt in via
        //     Settings → Logging → "Enable Debug Logging" (pref key
        //     `debug_logging_enabled`).
        // The app log (sanitized for public sharing) is always on regardless
        // — it's safe and cheap and powers the Copy App Log menu item.
        val defaultPrefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
        val savedDebug = defaultPrefs.getBoolean("debug_logging_enabled", false)
        val debugLoggingActive = BuildConfig.DEBUG_MODE || savedDebug
        // Sync the toggle pref to match the actual active state. On debug
        // builds DEBUG_MODE forces logging on regardless of what the user
        // last set; without this sync the Settings → Logging toggle shows
        // "Off" even though the logger is running — misleading.
        if (BuildConfig.DEBUG_MODE && !savedDebug) {
            defaultPrefs.edit().putBoolean("debug_logging_enabled", true).apply()
        }
        Logger.initialize(this, debugLoggingActive)
        setupExceptionHandler()

        // ANR watchdog tracks debug mode — when debug logging is on we
        // catch main-thread freezes and write the captured stack trace
        // to the debug log so it shows up in Copy Debug Logs.
        if (debugLoggingActive) startAnrWatchdog()

        // Restore the user's keystroke-byte-logging opt-in (default off).
        // Static flag on TermuxBridge — gated separately from debug logging
        // because users who enable debug logs for protocol triage don't
        // necessarily want their typed sudo/ssh passwords in those logs.
        io.github.tabssh.terminal.TermuxBridge.logKeystrokeBytes =
            preferencesManager.getBoolean("log_keystroke_bytes", false)

        // Stamp the source-of-truth commit into the log exactly once per
        // commit-id change (install / update). Lets users grepping their
        // own log — or pasting it into a bug report — figure out which
        // build the symptoms came from without having to ask the version
        // string. Falls through silently when commit_id is the same as
        // last launch so we don't spam the log on every startup.
        val prefs = getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE)
        val lastLoggedCommit = prefs.getString(KEY_LAST_LOGGED_COMMIT, null)
        if (lastLoggedCommit != BuildConfig.GIT_COMMIT_ID) {
            val marker = "## apk built from: ${BuildConfig.GIT_COMMIT_ID} ##"
            Logger.i("TabSSHApplication", marker)
            Logger.d("TabSSHApplication", marker)
            prefs.edit().putString(KEY_LAST_LOGGED_COMMIT, BuildConfig.GIT_COMMIT_ID).apply()
        }

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
                foregroundActivityCount++
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
                foregroundActivityCount--
                // Only stamp the background time when the ENTIRE app goes to
                // background (no activities left in the Started state). Stamping
                // on every individual activity stop would reset the clock on
                // normal in-app transitions (A starts B → A.onStop fires while
                // B is already started) and make the timeout never accumulate.
                if (foregroundActivityCount == 0) {
                    val prefs = androidx.preference.PreferenceManager
                        .getDefaultSharedPreferences(this@TabSSHApplication)
                    prefs.edit().putLong("ui_last_backgrounded_at", System.currentTimeMillis()).apply()
                    // Clear SESSION_ONLY in-memory passwords when the whole app
                    // goes to background. ENCRYPTED/BIOMETRIC-level passwords
                    // are not touched — only the volatile sessionPasswords map.
                    securePasswordManager.clearSensitiveData()
                    // Also zero per-connection credential caches on active SSH
                    // sessions. JSch keeps its own reference to the password for
                    // the live session; these cached copies only exist to service
                    // UserInfo callbacks. Clearing here prevents them surviving a
                    // biometric-lock event or a process cache into the background.
                    sshSessionManager.clearCachedCredentials()
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Issue #36 — Move slow init off the main thread.
        //
        // The lazy delegates below (`securePasswordManager`, `themeManager`,
        // `keyStorage`, `sshSessionManager`, ...) do non-trivial work in
        // their constructors — `SecurePasswordManager` for example does an
        // eager `getSharedPreferences("tabssh_secure_storage", ...)` plus
        // `KeyStore.getInstance(...).load(null)` at field-init time. Doing
        // this on the main thread caused ANRs on update for users with
        // large saved-credentials sets. Touching the lazies from a
        // background coroutine first means Kotlin's `lazy { }` runs the
        // constructor on the background thread; later main-thread accesses
        // block on the lock but the actual I/O is already done.
        //
        // No regression if the main thread races and beats the scope: the
        // lazy fires on whichever thread hits it first, same as before.
        applicationScope.launch {
            initializeCoreComponents()
            // Single source of truth for host-key verification dialogs. Set
            // on SSHSessionManager so EVERY future connection inherits it
            // (SSHSessionManager.createConnection copies these onto each new
            // SSHConnection at construction time). Looks up the current
            // foreground Activity via currentActivityRef so the dialog
            // renders wherever the user happens to be — terminal,
            // port-forward, multi-host dashboard, SFTP, performance.
            wireGlobalHostKeyCallbacks()
            wireGlobalNotifications()
            // Re-register periodic sync work on every cold start. WorkManager's
            // DB survives process death but can be wiped by reinstall or system
            // maintenance. Re-registering is idempotent when
            // ExistingPeriodicWorkPolicy.KEEP is used.
            val syncEnabled = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this@TabSSHApplication)
                .getBoolean("sync_enabled", false)
            if (syncEnabled) {
                io.github.tabssh.sync.worker.SyncWorkScheduler(this@TabSSHApplication)
                    .schedulePeriodicSync()
            }
            Logger.i("TabSSHApplication", "Application initialized successfully (background)")
        }
    }

    private fun wireGlobalNotifications() {
        // Per-host connect/disconnect notifications are handled entirely by
        // SSHConnectionService via renderHostNotification(). Posting a
        // second showDisconnected() here produced a duplicate notification
        // for the same host — the legacy aggregate ID 2001 shadowing the
        // per-host silent channel notification. Nothing to do here now.
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
        // Issue #158 — pre-open the Room DB on the background scope so the
        // first Flow subscription from ConnectionsFragment doesn't pay for
        // open + migrations on the main thread. Touching writableDatabase
        // forces SupportSQLiteOpenHelper to run the migration chain now.
        tryInit("Database")     { database.openHelper.writableDatabase }

        // Schedule background host availability checks via WorkManager.
        // Uses ExistingPeriodicWorkPolicy.KEEP — idempotent, safe to call on
        // every cold start. The master "monitoring_enabled" pref is checked
        // inside the worker itself so we don't need to conditionalize here.
        tryInit("HostMonitor") {
            io.github.tabssh.background.HostAvailabilityWorker.schedule(this)
        }

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
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
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
                    val trace     = android.util.Log.getStackTraceString(throwable)
                    val crashTime = System.currentTimeMillis()

                    // Primary path: pass data directly via Intent extras (in-process,
                    // no disk I/O, no race between write and read).
                    // Secondary path: SharedPreferences on disk as a fallback for the
                    // case where Android restarts CrashReportActivity in a new process.
                    getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_LAST_CRASH,   trace)
                        .putString(KEY_CRASH_THREAD, thread.name)
                        .putLong(KEY_CRASH_TIME,     crashTime)
                        .commit() // synchronous; ignore return value — Intent extras are primary

                    startActivity(
                        android.content.Intent(
                            this,
                            io.github.tabssh.ui.activities.CrashReportActivity::class.java
                        ).apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                            putExtra(EXTRA_CRASH_TRACE,  trace)
                            putExtra(EXTRA_CRASH_THREAD, thread.name)
                            putExtra(EXTRA_CRASH_TIME,   crashTime)
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