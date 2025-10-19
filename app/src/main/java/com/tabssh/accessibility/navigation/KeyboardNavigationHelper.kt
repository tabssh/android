package com.tabssh.accessibility.navigation

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat

/**
 * Keyboard navigation support for TabSSH
 * Provides full keyboard accessibility for users who cannot use touch input
 */
class KeyboardNavigationHelper {

    private var focusedViewIndex = 0
    private val navigableViews = mutableListOf<View>()

    /**
     * Initialize keyboard navigation for a view hierarchy
     */
    fun setupKeyboardNavigation(rootView: ViewGroup) {
        collectNavigableViews(rootView)
        setupFocusHandling()
    }

    /**
     * Collect all navigable views from the hierarchy
     */
    private fun collectNavigableViews(viewGroup: ViewGroup) {
        navigableViews.clear()

        fun addNavigableView(view: View) {
            if (isNavigable(view)) {
                navigableViews.add(view)
                view.isFocusable = true
                view.isFocusableInTouchMode = true
            }
        }

        fun traverseViews(parent: ViewGroup) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child is ViewGroup) {
                    traverseViews(child)
                } else {
                    addNavigableView(child)
                }
            }
        }

        traverseViews(viewGroup)
    }

    /**
     * Check if a view should be navigable
     */
    private fun isNavigable(view: View): Boolean {
        return view.visibility == View.VISIBLE &&
                view.isEnabled &&
                (view.isClickable || view.isLongClickable || view.isFocusable)
    }

    /**
     * Set up focus handling for keyboard navigation
     */
    private fun setupFocusHandling() {
        navigableViews.forEachIndexed { index, view ->
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    handleKeyNavigation(keyCode, index)
                } else {
                    false
                }
            }

            view.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    focusedViewIndex = index
                    announceCurrentFocus(view)
                }
            }
        }

        // Set initial focus
        if (navigableViews.isNotEmpty()) {
            navigableViews[0].requestFocus()
        }
    }

    /**
     * Handle keyboard navigation events
     */
    private fun handleKeyNavigation(keyCode: Int, currentIndex: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_TAB -> {
                navigateNext()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                navigateNext()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                navigatePrevious()
                true
            }
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                performCurrentAction()
                true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                clearFocus()
                true
            }
            else -> false
        }
    }

    /**
     * Navigate to next focusable view
     */
    private fun navigateNext() {
        if (navigableViews.isEmpty()) return

        val nextIndex = (focusedViewIndex + 1) % navigableViews.size
        navigableViews[nextIndex].requestFocus()
    }

    /**
     * Navigate to previous focusable view
     */
    private fun navigatePrevious() {
        if (navigableViews.isEmpty()) return

        val prevIndex = if (focusedViewIndex > 0) {
            focusedViewIndex - 1
        } else {
            navigableViews.size - 1
        }
        navigableViews[prevIndex].requestFocus()
    }

    /**
     * Perform action on currently focused view
     */
    private fun performCurrentAction() {
        if (focusedViewIndex < navigableViews.size) {
            val currentView = navigableViews[focusedViewIndex]
            if (currentView.isClickable) {
                currentView.performClick()
            } else if (currentView.isLongClickable) {
                currentView.performLongClick()
            }
        }
    }

    /**
     * Clear focus from all views
     */
    private fun clearFocus() {
        navigableViews.forEach { it.clearFocus() }
    }

    /**
     * Announce current focus for screen readers
     */
    private fun announceCurrentFocus(view: View) {
        val description = view.contentDescription
            ?: view.getTag(0x01020001) as? String
            ?: "Focused element"

        view.announceForAccessibility("Focused: $description")
    }

    /**
     * Set up keyboard shortcuts for terminal operations
     */
    fun setupTerminalKeyboardShortcuts(terminalView: View): Map<String, () -> Unit> {
        val shortcuts = mapOf(
            "Ctrl+T" to { /* New tab */ },
            "Ctrl+W" to { /* Close tab */ },
            "Ctrl+Tab" to { /* Next tab */ },
            "Ctrl+Shift+Tab" to { /* Previous tab */ },
            "Ctrl+C" to { /* Copy */ },
            "Ctrl+V" to { /* Paste */ },
            "Ctrl+A" to { /* Select all */ },
            "Ctrl+L" to { /* Clear screen */ },
            "F11" to { /* Toggle fullscreen */ },
            "Alt+Enter" to { /* Toggle fullscreen */ }
        )

        terminalView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                handleTerminalShortcut(keyCode, event)
            } else {
                false
            }
        }

        return shortcuts
    }

    /**
     * Handle terminal-specific keyboard shortcuts
     */
    private fun handleTerminalShortcut(keyCode: Int, event: KeyEvent): Boolean {
        val isCtrl = event.isCtrlPressed
        val isShift = event.isShiftPressed
        val isAlt = event.isAltPressed

        return when {
            isCtrl && keyCode == KeyEvent.KEYCODE_T -> {
                // New tab
                true
            }
            isCtrl && keyCode == KeyEvent.KEYCODE_W -> {
                // Close tab
                true
            }
            isCtrl && keyCode == KeyEvent.KEYCODE_TAB && !isShift -> {
                // Next tab
                true
            }
            isCtrl && keyCode == KeyEvent.KEYCODE_TAB && isShift -> {
                // Previous tab
                true
            }
            isCtrl && keyCode == KeyEvent.KEYCODE_C -> {
                // Copy
                true
            }
            isCtrl && keyCode == KeyEvent.KEYCODE_V -> {
                // Paste
                true
            }
            keyCode == KeyEvent.KEYCODE_F11 -> {
                // Toggle fullscreen
                true
            }
            isAlt && keyCode == KeyEvent.KEYCODE_ENTER -> {
                // Toggle fullscreen
                true
            }
            else -> false
        }
    }

    /**
     * Create focus indicators for better visibility
     */
    fun setupFocusIndicators(views: List<View>) {
        views.forEach { view ->
            view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    // Add visual focus indicator
                    ViewCompat.setBackground(v,
                        v.context.getDrawable(android.R.drawable.edit_text)
                    )
                    v.announceForAccessibility("Focused")
                } else {
                    // Remove focus indicator
                    ViewCompat.setBackground(v, null)
                }
            }
        }
    }

    /**
     * Navigate to specific view by ID
     */
    fun navigateToView(viewId: Int): Boolean {
        val index = navigableViews.indexOfFirst { it.id == viewId }
        return if (index >= 0) {
            navigableViews[index].requestFocus()
            true
        } else {
            false
        }
    }

    /**
     * Get list of keyboard shortcuts for announcement
     */
    fun getAvailableShortcuts(): List<String> {
        return listOf(
            "Tab or Arrow keys to navigate",
            "Enter or Space to activate",
            "Escape to clear focus",
            "Ctrl+T for new tab",
            "Ctrl+W to close tab",
            "Ctrl+Tab to switch tabs",
            "F11 for fullscreen"
        )
    }
}