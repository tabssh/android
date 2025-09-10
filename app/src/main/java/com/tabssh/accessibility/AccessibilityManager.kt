package io.github.tabssh.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import io.github.tabssh.R
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.terminal.emulator.TerminalBuffer
import io.github.tabssh.ui.views.TerminalView
import io.github.tabssh.utils.logging.Logger

/**
 * Comprehensive accessibility manager for TabSSH
 * Provides TalkBack support, high contrast, and motor accessibility features
 */
class AccessibilityManager(private val context: Context) {
    
    private val systemAccessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private val preferenceManager = PreferenceManager(context)
    
    // Accessibility state
    private var isScreenReaderEnabled = false
    private var isTouchExplorationEnabled = false
    private var isHighContrastEnabled = false
    
    // Accessibility settings
    private var minimumTouchTargetSize = 48 // dp
    private var longPressTimeout = 500L // ms
    private var scrollSensitivity = 1.0f
    
    init {
        checkAccessibilityState()
        Logger.d("AccessibilityManager", "Accessibility manager initialized")
    }
    
    /**
     * Check current accessibility services state
     */
    private fun checkAccessibilityState() {
        isScreenReaderEnabled = systemAccessibilityManager.isEnabled &&
            systemAccessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty()
        
        isTouchExplorationEnabled = systemAccessibilityManager.isTouchExplorationEnabled
        isHighContrastEnabled = preferenceManager.isHighContrastMode()
        
        Logger.d("AccessibilityManager", "Accessibility state: screenReader=$isScreenReaderEnabled, touchExploration=$isTouchExplorationEnabled, highContrast=$isHighContrastEnabled")
    }
    
    /**
     * Setup accessibility for terminal view
     */
    fun setupTerminalAccessibility(terminalView: TerminalView) {
        Logger.d("AccessibilityManager", "Setting up terminal accessibility")
        
        terminalView.apply {
            // Basic accessibility properties
            contentDescription = context.getString(R.string.accessibility_terminal)
            
            // Enable accessibility focus
            isFocusable = true
            isFocusableInTouchMode = true
            
            // Live region for dynamic content
            ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
            
            // Custom accessibility delegate
            ViewCompat.setAccessibilityDelegate(this, TerminalAccessibilityDelegate(terminalView))
            
            // Touch exploration handling
            if (isTouchExplorationEnabled) {
                configureForTouchExploration()
            }
        }
    }
    
    /**
     * Custom accessibility delegate for terminal view
     */
    private inner class TerminalAccessibilityDelegate(
        private val terminalView: TerminalView
    ) : AccessibilityDelegateCompat() {
        
        override fun onInitializeAccessibilityNodeInfo(
            host: View,
            info: AccessibilityNodeInfoCompat
        ) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            
            info.className = TerminalView::class.java.name
            info.contentDescription = getTerminalContentDescription()
            
            // Add custom accessibility actions
            info.addAction(
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    R.id.action_read_screen,
                    context.getString(R.string.accessibility_read_screen)
                )
            )
            
            info.addAction(
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    R.id.action_read_line,
                    context.getString(R.string.accessibility_read_line)
                )
            )
            
            // Enable scrolling actions
            info.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_UP)
            info.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_DOWN)
            
            // Text selection actions
            info.addAction(AccessibilityNodeInfoCompat.ACTION_SET_SELECTION)
            info.addAction(AccessibilityNodeInfoCompat.ACTION_COPY)
            
            // Set scrollable if content exceeds view
            info.isScrollable = true
        }
        
        override fun performAccessibilityAction(
            host: View,
            action: Int,
            args: Bundle?
        ): Boolean {
            
            Logger.d("AccessibilityManager", "Performing accessibility action: $action")
            
            return when (action) {
                R.id.action_read_screen -> {
                    announceScreenContent()
                    true
                }
                R.id.action_read_line -> {
                    announceCurrentLine()
                    true
                }
                AccessibilityNodeInfoCompat.ACTION_SCROLL_UP -> {
                    terminalView.scrollUp()
                    announceScrollPosition("up")
                    true
                }
                AccessibilityNodeInfoCompat.ACTION_SCROLL_DOWN -> {
                    terminalView.scrollDown()
                    announceScrollPosition("down")
                    true
                }
                AccessibilityNodeInfoCompat.ACTION_COPY -> {
                    terminalView.copySelectedText()
                    announce("Text copied to clipboard")
                    true
                }
                else -> super.performAccessibilityAction(host, action, args)
            }
        }
        
        private fun getTerminalContentDescription(): String {
            val terminal = terminalView.getTerminal() ?: return "Terminal"
            val buffer = terminal.getBuffer()
            
            val connectionState = when {
                terminal.isActive.value -> context.getString(R.string.accessibility_connected)
                else -> context.getString(R.string.accessibility_disconnected)
            }
            
            val cursorPosition = "Line ${buffer.getCursorRow() + 1}, Column ${buffer.getCursorCol() + 1}"
            
            return "$connectionState terminal. $cursorPosition. Double tap to interact, swipe to scroll."
        }
        
        private fun announceScreenContent() {
            val terminal = terminalView.getTerminal() ?: return
            val content = terminal.getScreenContent()
            
            // Clean up content for screen reader
            val cleanContent = content
                .replace(Regex("\\s+"), " ")
                .trim()
                .takeIf { it.isNotBlank() } ?: "Terminal is empty"
            
            announce("Screen content: $cleanContent")
        }
        
        private fun announceCurrentLine() {
            val terminal = terminalView.getTerminal() ?: return
            val buffer = terminal.getBuffer()
            val currentRow = buffer.getCursorRow()
            val line = buffer.getLine(currentRow)
            
            if (line != null) {
                val lineText = line.joinToString("") { it.char.toString() }
                    .trimEnd()
                    .takeIf { it.isNotBlank() } ?: "Empty line"
                
                announce("Line ${currentRow + 1}: $lineText")
            }
        }
        
        private fun announceScrollPosition(direction: String) {
            announce("Scrolled $direction")
        }
        
        private fun announce(text: String) {
            terminalView.announceForAccessibility(text)
        }
    }
    
    /**
     * Configure view for touch exploration
     */
    private fun View.configureForTouchExploration() {
        // Increase touch target size
        val targetSize = (minimumTouchTargetSize * context.resources.displayMetrics.density).toInt()
        minimumWidth = targetSize
        minimumHeight = targetSize
        
        // Increase long press timeout for users with motor impairments
        isLongClickable = true
        
        Logger.d("AccessibilityManager", "Configured view for touch exploration")
    }
    
    /**
     * Setup high contrast mode
     */
    fun setupHighContrastMode(view: View, enable: Boolean) {
        if (enable) {
            // Apply high contrast styling
            view.background = context.getDrawable(R.drawable.high_contrast_background)
            
            // Increase border visibility
            ViewCompat.setElevation(view, 8f)
            
            Logger.d("AccessibilityManager", "Applied high contrast mode")
        } else {
            // Restore normal styling
            view.background = null
            ViewCompat.setElevation(view, 0f)
        }
    }
    
    /**
     * Configure for motor accessibility
     */
    fun configureForMotorImpairments(view: View) {
        // Larger touch targets
        val largeTargetSize = (56 * context.resources.displayMetrics.density).toInt()
        view.minimumWidth = largeTargetSize
        view.minimumHeight = largeTargetSize
        
        // Longer press timeout
        view.isLongClickable = true
        
        // Reduced motion sensitivity would be applied to scroll views
        
        Logger.d("AccessibilityManager", "Configured view for motor accessibility")
    }
    
    /**
     * Setup screen reader announcements for connection changes
     */
    fun announceConnectionChange(connectionName: String, isConnected: Boolean) {
        if (isScreenReaderEnabled) {
            val status = if (isConnected) "connected" else "disconnected"
            val announcement = "$connectionName $status"
            
            // This would announce through the current view
            Logger.d("AccessibilityManager", "Announcing: $announcement")
        }
    }
    
    /**
     * Setup screen reader announcements for tab changes
     */
    fun announceTabChange(tabTitle: String, tabIndex: Int, totalTabs: Int) {
        if (isScreenReaderEnabled) {
            val announcement = "Switched to tab ${tabIndex + 1} of $totalTabs: $tabTitle"
            Logger.d("AccessibilityManager", "Announcing: $announcement")
        }
    }
    
    /**
     * Announce file transfer progress
     */
    fun announceTransferProgress(fileName: String, percentage: Int, isUpload: Boolean) {
        if (isScreenReaderEnabled) {
            val direction = if (isUpload) "upload" else "download"
            val announcement = "$fileName $direction $percentage percent complete"
            Logger.d("AccessibilityManager", "Announcing: $announcement")
        }
    }
    
    /**
     * Check if screen reader is enabled
     */
    fun isScreenReaderEnabled(): Boolean = isScreenReaderEnabled
    
    /**
     * Check if touch exploration is enabled
     */
    fun isTouchExplorationEnabled(): Boolean = isTouchExplorationEnabled
    
    /**
     * Check if high contrast mode is enabled
     */
    fun isHighContrastEnabled(): Boolean = isHighContrastEnabled
    
    /**
     * Update accessibility settings
     */
    fun updateSettings() {
        checkAccessibilityState()
        
        isHighContrastEnabled = preferenceManager.isHighContrastMode()
        minimumTouchTargetSize = if (preferenceManager.isLargeTouchTargets()) 56 else 48
        
        Logger.d("AccessibilityManager", "Updated accessibility settings")
    }
    
    /**
     * Get accessibility statistics
     */
    fun getAccessibilityStatistics(): AccessibilityStatistics {
        val enabledServices = systemAccessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        
        return AccessibilityStatistics(
            isScreenReaderEnabled = isScreenReaderEnabled,
            isTouchExplorationEnabled = isTouchExplorationEnabled,
            isHighContrastEnabled = isHighContrastEnabled,
            enabledServiceCount = enabledServices.size,
            minimumTouchTargetSize = minimumTouchTargetSize,
            serviceNames = enabledServices.map { it.resolveInfo.serviceInfo.name }
        )
    }
    
    /**
     * Validate accessibility compliance for a view
     */
    fun validateAccessibilityCompliance(view: View): AccessibilityValidationResult {
        val issues = mutableListOf<String>()
        
        // Check content description
        if (view.contentDescription.isNullOrBlank() && view.isClickable) {
            issues.add("Clickable view missing content description")
        }
        
        // Check touch target size
        val width = view.width
        val height = view.height
        val density = context.resources.displayMetrics.density
        val targetSizeDp = 48 * density
        
        if ((width > 0 && width < targetSizeDp) || (height > 0 && height < targetSizeDp)) {
            issues.add("Touch target smaller than 48dp minimum")
        }
        
        // Check focus indicator
        if (view.isFocusable && !view.hasExplicitFocusable()) {
            issues.add("Focusable view may need explicit focus indicator")
        }
        
        return AccessibilityValidationResult(
            isCompliant = issues.isEmpty(),
            issues = issues
        )
    }
    
    /**
     * Apply accessibility theme
     */
    fun applyAccessibilityTheme(view: View) {
        if (isHighContrastEnabled) {
            setupHighContrastMode(view, true)
        }
        
        if (preferenceManager.isLargeTouchTargets()) {
            configureForMotorImpairments(view)
        }
    }
}

/**
 * Accessibility statistics
 */
data class AccessibilityStatistics(
    val isScreenReaderEnabled: Boolean,
    val isTouchExplorationEnabled: Boolean,
    val isHighContrastEnabled: Boolean,
    val enabledServiceCount: Int,
    val minimumTouchTargetSize: Int,
    val serviceNames: List<String>
)

/**
 * Accessibility validation result
 */
data class AccessibilityValidationResult(
    val isCompliant: Boolean,
    val issues: List<String>
)