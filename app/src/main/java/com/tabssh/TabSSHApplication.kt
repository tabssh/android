package com.tabssh

import android.app.Application
import com.tabssh.storage.database.TabSSHDatabase
import com.tabssh.crypto.storage.SecurePasswordManager
import com.tabssh.crypto.keys.KeyStorage
import com.tabssh.ssh.connection.SSHSessionManager
import com.tabssh.terminal.emulator.TerminalManager
import com.tabssh.themes.definitions.ThemeManager
import com.tabssh.storage.preferences.PreferenceManager
import com.tabssh.utils.logging.Logger

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
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize logging
        Logger.initialize(this, BuildConfig.DEBUG_MODE)
        Logger.d("TabSSHApplication", "Application starting...")
        
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
        
        Logger.d("TabSSHApplication", "Core components initialized")
    }
    
    private fun setupExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
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