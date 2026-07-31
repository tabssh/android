package io.github.tabssh.terminal.emulator

import android.content.Context
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages multiple terminal emulator instances
 * Provides terminal creation, lifecycle management, and resource cleanup
 */
class TerminalManager(private val context: Context) {
    
    private val terminals = ConcurrentHashMap<String, TerminalEmulator>()
    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Settings
    private var defaultRows = 24
    private var defaultCols = 80
    private var maxScrollback = 10000
    private var defaultTerminalType = "xterm-256color"
    private var defaultEncoding = "UTF-8"
    
    private var isInitialized = false
    
    fun initialize() {
        if (isInitialized) return
        
        Logger.d("TerminalManager", "Initializing terminal manager")
        
        // Load settings from preferences
        loadSettings()
        
        // Start maintenance task
        startMaintenance()
        
        isInitialized = true
        Logger.i("TerminalManager", "Terminal manager initialized")
    }
    
    private fun loadSettings() {
        try {
            // Settings would be loaded from PreferenceManager
            // For now, use defaults
            Logger.d("TerminalManager", "Using default terminal settings")
        } catch (e: Exception) {
            Logger.e("TerminalManager", "Error loading terminal settings", e)
        }
    }
    
    /**
     * Create a new terminal emulator instance
     */
    fun createTerminal(
        terminalId: String,
        rows: Int = defaultRows,
        cols: Int = defaultCols,
        scrollback: Int = maxScrollback
    ): TerminalEmulator {
        
        // Check if terminal already exists
        terminals[terminalId]?.let { existing ->
            Logger.w("TerminalManager", "Terminal $terminalId already exists, returning existing instance")
            return existing
        }
        
        // Create new terminal
        val buffer = TerminalBuffer(rows, cols)
        val terminal = TerminalEmulator(buffer)
        
        terminals[terminalId] = terminal
        
        Logger.i("TerminalManager", "Created terminal $terminalId (${cols}x${rows})")
        return terminal
    }
    
    /**
     * Get an existing terminal by ID
     */
    fun getTerminal(terminalId: String): TerminalEmulator? {
        return terminals[terminalId]
    }
    
    /**
     * Get all terminal IDs
     */
    fun getTerminalIds(): Set<String> {
        return terminals.keys.toSet()
    }
    
    /**
     * Get all active terminals
     */
    fun getActiveTerminals(): List<Pair<String, TerminalEmulator>> {
        return terminals.filter { it.value.isActive.value }.toList()
    }
    
    /**
     * Remove and cleanup a terminal
     */
    fun removeTerminal(terminalId: String) {
        terminals[terminalId]?.let { terminal ->
            Logger.d("TerminalManager", "Removing terminal $terminalId")
            
            // Cleanup terminal resources
            terminal.cleanup()
            
            terminals.remove(terminalId)
            
            Logger.i("TerminalManager", "Removed terminal $terminalId")
        }
    }
    
    /**
     * Resize a terminal
     */
    fun resizeTerminal(terminalId: String, rows: Int, cols: Int) {
        terminals[terminalId]?.let { terminal ->
            terminal.resize(rows, cols)
            Logger.d("TerminalManager", "Resized terminal $terminalId to ${cols}x${rows}")
        }
    }
    
    /**
     * Trim memory usage by cleaning up inactive terminals
     */
    fun trimInactiveTerminals() {
        Logger.d("TerminalManager", "Trimming inactive terminals")
        
        val inactiveTerminals = terminals.filter { !it.value.isActive.value }
        var trimmedCount = 0
        
        inactiveTerminals.forEach { (id, terminal) ->
            // Check if terminal has been inactive for more than 5 minutes
            if (System.currentTimeMillis() - getLastActivityTime(terminal) > 300000) {
                removeTerminal(id)
                trimmedCount++
            }
        }
        
        if (trimmedCount > 0) {
            Logger.i("TerminalManager", "Trimmed $trimmedCount inactive terminals")
        }
        
        // Also trim scrollback for active terminals to save memory
        terminals.values.forEach { terminal ->
            val buffer = terminal.getBuffer()
            if (buffer.getScrollbackSize() > maxScrollback) {
                // Terminal buffer automatically trims scrollback, but we can log it
                Logger.d("TerminalManager", "Terminal has large scrollback: ${buffer.getScrollbackSize()} lines")
            }
        }
    }
    
    private fun getLastActivityTime(terminal: TerminalEmulator): Long {
        // This would track last activity time if implemented
        // For now, return current time to prevent premature cleanup
        return System.currentTimeMillis()
    }
    
    private fun startMaintenance() {
        managerScope.launch {
            while (isActive) {
                try {
                    // Perform maintenance every 5 minutes
                    delay(300000)
                    
                    performMaintenance()
                    
                } catch (e: Exception) {
                    Logger.e("TerminalManager", "Error in maintenance task", e)
                    delay(60000) // Wait 1 minute on error
                }
            }
        }
    }
    
    private suspend fun performMaintenance() {
        Logger.d("TerminalManager", "Performing terminal maintenance")
        
        // Rough memory estimate: 16 bytes per cell across buffer and scrollback
        var memoryUsageBytes = 0L
        terminals.values.forEach { terminal ->
            val buffer = terminal.getBuffer()
            memoryUsageBytes += (buffer.getRows() + buffer.getScrollbackSize()).toLong() * buffer.getCols() * 16
        }
        val activeTerminals = terminals.values.count { it.isActive.value }
        Logger.d("TerminalManager", "Terminal stats: ${terminals.size} total, $activeTerminals active, ${memoryUsageBytes / 1024}KB memory")

        // Clean up inactive terminals if memory usage is high
        if (memoryUsageBytes > 50 * 1024 * 1024) { // 50MB threshold
            Logger.w("TerminalManager", "High memory usage (${memoryUsageBytes / 1024 / 1024}MB), trimming terminals")
            trimInactiveTerminals()
        }
        
        // Cleanup terminals that have been inactive for too long
        val currentTime = System.currentTimeMillis()
        val inactiveThreshold = 30 * 60 * 1000L // 30 minutes
        
        terminals.entries.removeAll { (id, terminal) ->
            if (!terminal.isActive.value && currentTime - getLastActivityTime(terminal) > inactiveThreshold) {
                Logger.i("TerminalManager", "Removing long-inactive terminal: $id")
                terminal.cleanup()
                true
            } else {
                false
            }
        }
    }
    
    /**
     * Apply settings to all terminals
     */
    fun applySettings() {
        Logger.d("TerminalManager", "Applying settings to all terminals")
        
        loadSettings()
        
        // Apply settings to existing terminals
        terminals.values.forEach { terminal ->
            terminal.setTerminalType(defaultTerminalType)
            terminal.setEncoding(defaultEncoding)
        }
    }
    
    /**
     * Set default terminal size
     */
    fun setDefaultSize(rows: Int, cols: Int) {
        defaultRows = rows.coerceIn(1, 100)
        defaultCols = cols.coerceIn(1, 200)
        Logger.d("TerminalManager", "Set default terminal size to ${defaultCols}x${defaultRows}")
    }
    
    fun getDefaultSize(): Pair<Int, Int> = Pair(defaultRows, defaultCols)
    
    /**
     * Set maximum scrollback lines
     */
    fun setMaxScrollback(lines: Int) {
        maxScrollback = lines.coerceIn(100, 50000)
        Logger.d("TerminalManager", "Set max scrollback to $maxScrollback lines")
    }
    
    fun getMaxScrollback(): Int = maxScrollback
    
    /**
     * Set default terminal type
     */
    fun setDefaultTerminalType(type: String) {
        defaultTerminalType = type
        Logger.d("TerminalManager", "Set default terminal type to $type")
    }
    
    fun getDefaultTerminalType(): String = defaultTerminalType
    
    /**
     * Cleanup all terminals and resources
     */
    fun cleanup() {
        Logger.d("TerminalManager", "Cleaning up terminal manager")
        
        // Cleanup all terminals
        terminals.values.forEach { terminal ->
            try {
                terminal.cleanup()
            } catch (e: Exception) {
                Logger.e("TerminalManager", "Error cleaning up terminal", e)
            }
        }
        
        terminals.clear()
        managerScope.cancel()
        
        Logger.i("TerminalManager", "Terminal manager cleanup complete")
    }
}
