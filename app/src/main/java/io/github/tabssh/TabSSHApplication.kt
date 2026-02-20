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
        
        // Initialize logging
        Logger.initialize(this, BuildConfig.DEBUG_MODE)
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
        
        // Set up uncaught exception handler
        setupExceptionHandler()
        
        Logger.i("TabSSHApplication", "Application initialized successfully")
    }
    
    private fun initializeCoreComponents() {
        // Initialize preferences first as other components may depend on them
        preferencesManager.initialize()
        
        // Initialize theme system
        themeManager.initialize()
        
        // Initialize security components
        securePasswordManager.initialize()
        keyStorage.initialize()
        
        // Initialize SSH components
        sshSessionManager.initialize()
        
        // Initialize terminal system
        terminalManager.initialize()
        
        // Initialize performance monitoring
        performanceManager.initialize()
        
        Logger.d("TabSSHApplication", "Core components initialized")
    }
    
    private fun setupExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Write crash synchronously — async executor won't flush before process dies
            Logger.writeCrashSync(thread, throwable)
            Logger.e("TabSSHApplication", "Uncaught exception in thread ${thread.name}", throwable)
            
            // Clean up sensitive data on crash
            try {
                securePasswordManager.clearSensitiveDataOnCrash()
                sshSessionManager.closeAllConnections()
            } catch (e: Exception) {
                Logger.e("TabSSHApplication", "Error during crash cleanup", e)
            }
            
            // Call original handler
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