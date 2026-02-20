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

        // Create notification channels
        io.github.tabssh.utils.NotificationHelper.createNotificationChannels(this)
        
        // Register activity lifecycle callbacks to track foreground activity
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                currentActivityRef = WeakReference(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (currentActivityRef?.get() == activity) {
                    currentActivityRef = null
                }
            }
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Initialize core components
        initializeCoreComponents()
        
        Logger.i("TabSSHApplication", "Application initialized successfully")
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