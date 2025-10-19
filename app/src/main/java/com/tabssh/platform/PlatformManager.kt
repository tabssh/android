package com.tabssh.platform

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.view.KeyEvent
import com.tabssh.utils.logging.Logger

/**
 * Manages platform-specific optimizations for Android TV and Chromebook
 * Provides adaptive UI and input handling for different form factors
 */
class PlatformManager(private val context: Context) {
    
    private var currentPlatform: Platform = Platform.PHONE
    private var inputMode: InputMode = InputMode.TOUCH
    
    // Platform detection
    init {
        detectPlatform()
        detectInputMode()
        Logger.d("PlatformManager", "Platform: $currentPlatform, Input: $inputMode")
    }
    
    private fun detectPlatform() {
        currentPlatform = when {
            isAndroidTV() -> Platform.ANDROID_TV
            isChromebook() -> Platform.CHROMEBOOK  
            isTablet() -> Platform.TABLET
            else -> Platform.PHONE
        }
    }
    
    private fun detectInputMode() {
        val config = context.resources.configuration
        inputMode = when {
            config.keyboard == Configuration.KEYBOARD_QWERTY -> InputMode.HARDWARE_KEYBOARD
            config.touchscreen == Configuration.TOUCHSCREEN_NOTOUCH -> InputMode.DPAD_ONLY
            currentPlatform == Platform.ANDROID_TV -> InputMode.DPAD_PRIMARY
            else -> InputMode.TOUCH
        }
    }
    
    /**
     * Check if running on Android TV
     */
    private fun isAndroidTV(): Boolean {
        val packageManager = context.packageManager
        return packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
               packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
    
    /**
     * Check if running on Chromebook
     */
    private fun isChromebook(): Boolean {
        return context.packageManager.hasSystemFeature("org.chromium.arc") ||
               Build.DEVICE?.contains("cheets") == true ||
               Build.MANUFACTURER?.equals("chromium", ignoreCase = true) == true
    }
    
    /**
     * Check if device is a tablet
     */
    private fun isTablet(): Boolean {
        val config = context.resources.configuration
        val screenSize = config.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK
        
        return screenSize == Configuration.SCREENLAYOUT_SIZE_LARGE ||
               screenSize == Configuration.SCREENLAYOUT_SIZE_XLARGE ||
               config.smallestScreenWidthDp >= 600
    }
    
    /**
     * Get platform-specific UI configuration
     */
    fun getUIConfiguration(): UIConfiguration {
        return when (currentPlatform) {
            Platform.ANDROID_TV -> getAndroidTVConfiguration()
            Platform.CHROMEBOOK -> getChromebookConfiguration()
            Platform.TABLET -> getTabletConfiguration()
            Platform.PHONE -> getPhoneConfiguration()
        }
    }
    
    private fun getAndroidTVConfiguration(): UIConfiguration {
        return UIConfiguration(
            platform = Platform.ANDROID_TV,
            showFunctionKeys = true,
            useLargerText = true,
            optimizeForDPad = true,
            showTabCloseButtons = false, // Use menu instead
            enableFocusIndicators = true,
            tabBarPosition = TabBarPosition.TOP,
            keyboardHeight = 0, // No soft keyboard
            showToolbar = true,
            enableFullscreen = false, // Keep navigation for TV
            defaultFontSize = 16f, // Larger for TV viewing distance
            terminalPadding = 24, // More padding for TV
            focusIndicatorWidth = 4 // Thicker focus indicators
        )
    }
    
    private fun getChromebookConfiguration(): UIConfiguration {
        return UIConfiguration(
            platform = Platform.CHROMEBOOK,
            showFunctionKeys = true,
            useLargerText = false,
            optimizeForDPad = false,
            showTabCloseButtons = true,
            enableFocusIndicators = true,
            tabBarPosition = TabBarPosition.TOP,
            keyboardHeight = 0, // Hardware keyboard
            showToolbar = true,
            enableFullscreen = true,
            defaultFontSize = 14f,
            terminalPadding = 16,
            focusIndicatorWidth = 2
        )
    }
    
    private fun getTabletConfiguration(): UIConfiguration {
        return UIConfiguration(
            platform = Platform.TABLET,
            showFunctionKeys = true,
            useLargerText = false,
            optimizeForDPad = false,
            showTabCloseButtons = true,
            enableFocusIndicators = false,
            tabBarPosition = TabBarPosition.TOP,
            keyboardHeight = 200,
            showToolbar = true,
            enableFullscreen = true,
            defaultFontSize = 14f,
            terminalPadding = 16,
            focusIndicatorWidth = 2
        )
    }
    
    private fun getPhoneConfiguration(): UIConfiguration {
        return UIConfiguration(
            platform = Platform.PHONE,
            showFunctionKeys = true,
            useLargerText = false,
            optimizeForDPad = false,
            showTabCloseButtons = true,
            enableFocusIndicators = false,
            tabBarPosition = TabBarPosition.TOP,
            keyboardHeight = 200,
            showToolbar = true,
            enableFullscreen = true,
            defaultFontSize = 14f,
            terminalPadding = 12,
            focusIndicatorWidth = 2
        )
    }
    
    /**
     * Handle platform-specific key events
     */
    fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        return when (currentPlatform) {
            Platform.ANDROID_TV -> handleTVKeyEvent(keyCode, event)
            Platform.CHROMEBOOK -> handleChromebookKeyEvent(keyCode, event)
            else -> false // Let normal handling proceed
        }
    }
    
    private fun handleTVKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                // Enter key equivalent
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                // Show context menu
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                // Handle back navigation
                return false // Let activity handle
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                // Could pause/resume transfers
                return true
            }
            else -> return false
        }
    }
    
    private fun handleChromebookKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        // Enhanced keyboard handling for Chromebook
        when {
            event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_T -> {
                // Ctrl+T - New tab
                return true
            }
            event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_W -> {
                // Ctrl+W - Close tab
                return true
            }
            event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_TAB -> {
                // Ctrl+Tab - Switch tabs
                return true
            }
            event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_N -> {
                // Ctrl+N - New connection
                return true
            }
            event.isAltPressed && (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_9) -> {
                // Alt+Number - Switch to tab
                return true
            }
            else -> return false
        }
    }
    
    /**
     * Get platform-specific shortcuts
     */
    fun getPlatformShortcuts(): List<PlatformShortcut> {
        return when (currentPlatform) {
            Platform.ANDROID_TV -> getAndroidTVShortcuts()
            Platform.CHROMEBOOK -> getChromebookShortcuts()
            Platform.TABLET -> getTabletShortcuts()
            Platform.PHONE -> getPhoneShortcuts()
        }
    }
    
    private fun getAndroidTVShortcuts(): List<PlatformShortcut> {
        return listOf(
            PlatformShortcut("D-Pad Center", "Select/Enter"),
            PlatformShortcut("Menu Button", "Show options"),
            PlatformShortcut("D-Pad Left/Right", "Switch tabs"),
            PlatformShortcut("D-Pad Up/Down", "Scroll terminal"),
            PlatformShortcut("Back", "Go back/Close"),
            PlatformShortcut("Play/Pause", "Pause/Resume transfers")
        )
    }
    
    private fun getChromebookShortcuts(): List<PlatformShortcut> {
        return listOf(
            PlatformShortcut("Ctrl+T", "New tab"),
            PlatformShortcut("Ctrl+W", "Close tab"),
            PlatformShortcut("Ctrl+Tab", "Next tab"),
            PlatformShortcut("Ctrl+Shift+Tab", "Previous tab"),
            PlatformShortcut("Ctrl+N", "New connection"),
            PlatformShortcut("Ctrl+1-9", "Switch to tab number"),
            PlatformShortcut("Ctrl+Shift+T", "Reopen closed tab"),
            PlatformShortcut("Alt+Enter", "Toggle fullscreen"),
            PlatformShortcut("F11", "Toggle fullscreen"),
            PlatformShortcut("Ctrl+Plus/Minus", "Zoom in/out"),
            PlatformShortcut("Ctrl+0", "Reset zoom")
        )
    }
    
    private fun getTabletShortcuts(): List<PlatformShortcut> {
        return listOf(
            PlatformShortcut("Two finger tap", "Right click menu"),
            PlatformShortcut("Pinch to zoom", "Adjust font size"),
            PlatformShortcut("Three finger swipe", "Switch tabs"),
            PlatformShortcut("Long press", "Context menu")
        )
    }
    
    private fun getPhoneShortcuts(): List<PlatformShortcut> {
        return listOf(
            PlatformShortcut("Volume Up+Down", "Toggle keyboard"),
            PlatformShortcut("Long press", "Context menu"),
            PlatformShortcut("Swipe left/right", "Switch tabs"),
            PlatformShortcut("Pinch to zoom", "Adjust font size")
        )
    }
    
    /**
     * Apply platform-specific optimizations
     */
    fun applyPlatformOptimizations() {
        when (currentPlatform) {
            Platform.ANDROID_TV -> applyAndroidTVOptimizations()
            Platform.CHROMEBOOK -> applyChromebookOptimizations()
            Platform.TABLET -> applyTabletOptimizations()
            Platform.PHONE -> applyPhoneOptimizations()
        }
    }
    
    private fun applyAndroidTVOptimizations() {
        Logger.d("PlatformManager", "Applying Android TV optimizations")
        
        // TV-specific optimizations
        // - Larger touch targets for D-pad navigation
        // - Focus indicators for navigation
        // - Optimized for 10-foot interface
        // - No soft keyboard
        // - Enhanced focus management
    }
    
    private fun applyChromebookOptimizations() {
        Logger.d("PlatformManager", "Applying Chromebook optimizations")
        
        // Chromebook-specific optimizations
        // - Enhanced keyboard shortcuts
        // - Window management integration
        // - Multi-window support
        // - Hardware keyboard optimization
        // - Desktop-like behavior
    }
    
    private fun applyTabletOptimizations() {
        Logger.d("PlatformManager", "Applying tablet optimizations")
        
        // Tablet-specific optimizations
        // - Larger screen real estate utilization
        // - Multi-panel layouts
        // - Enhanced gesture support
        // - Split-screen compatibility
    }
    
    private fun applyPhoneOptimizations() {
        Logger.d("PlatformManager", "Applying phone optimizations")
        
        // Phone-specific optimizations
        // - Compact UI elements
        // - Gesture-friendly navigation
        // - Thumb-friendly touch targets
        // - Battery optimization priority
    }
    
    // Getters
    fun getCurrentPlatform(): Platform = currentPlatform
    fun getCurrentInputMode(): InputMode = inputMode
    
    fun isPlatformAndroidTV(): Boolean = currentPlatform == Platform.ANDROID_TV
    fun isPlatformChromebook(): Boolean = currentPlatform == Platform.CHROMEBOOK
    fun isPlatformTablet(): Boolean = currentPlatform == Platform.TABLET
    fun isPhone(): Boolean = currentPlatform == Platform.PHONE
    
    fun hasHardwareKeyboard(): Boolean = inputMode == InputMode.HARDWARE_KEYBOARD
    fun isDPadNavigation(): Boolean = inputMode == InputMode.DPAD_ONLY || inputMode == InputMode.DPAD_PRIMARY
}

/**
 * Supported platforms
 */
enum class Platform {
    PHONE,
    TABLET, 
    ANDROID_TV,
    CHROMEBOOK
}

/**
 * Input modes
 */
enum class InputMode {
    TOUCH,              // Touch-only (typical phone/tablet)
    HARDWARE_KEYBOARD,  // Hardware keyboard available
    DPAD_ONLY,          // D-pad only (some TV devices)
    DPAD_PRIMARY        // D-pad primary with touch secondary (Android TV)
}

/**
 * Tab bar positions
 */
enum class TabBarPosition {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT
}

/**
 * Platform-specific UI configuration
 */
data class UIConfiguration(
    val platform: Platform,
    val showFunctionKeys: Boolean,
    val useLargerText: Boolean,
    val optimizeForDPad: Boolean,
    val showTabCloseButtons: Boolean,
    val enableFocusIndicators: Boolean,
    val tabBarPosition: TabBarPosition,
    val keyboardHeight: Int,
    val showToolbar: Boolean,
    val enableFullscreen: Boolean,
    val defaultFontSize: Float,
    val terminalPadding: Int,
    val focusIndicatorWidth: Int
)

/**
 * Platform-specific keyboard shortcuts
 */
data class PlatformShortcut(
    val keys: String,
    val description: String
)