package com.tabssh.accessibility.talkback

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * TalkBack and screen reader support for TabSSH
 * Provides enhanced accessibility for terminal content and UI navigation
 */
class TalkBackHelper(private val context: Context) {

    private val accessibilityManager: AccessibilityManager by lazy {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    }

    /**
     * Check if TalkBack or any screen reader is enabled
     */
    fun isScreenReaderEnabled(): Boolean {
        return accessibilityManager.isEnabled &&
                accessibilityManager.isTouchExplorationEnabled
    }

    /**
     * Announce text to screen reader
     */
    fun announceText(view: View, text: String) {
        if (isScreenReaderEnabled()) {
            view.announceForAccessibility(text)
        }
    }

    /**
     * Set up accessibility for terminal view
     */
    fun setupTerminalAccessibility(terminalView: View) {
        ViewCompat.setAccessibilityDelegate(terminalView, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfoCompat
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)

                info.className = "Terminal"
                info.contentDescription = "SSH Terminal"
                info.isScrollable = true
            }
        })

        // Set initial content description
        terminalView.contentDescription = "SSH Terminal - Ready for input"
    }

    /**
     * Update terminal content description with current state
     */
    fun updateTerminalState(terminalView: View, state: TerminalState) {
        val description = when (state) {
            TerminalState.CONNECTING -> "SSH Terminal - Connecting to server"
            TerminalState.CONNECTED -> "SSH Terminal - Connected and ready"
            TerminalState.DISCONNECTED -> "SSH Terminal - Disconnected"
            TerminalState.ERROR -> "SSH Terminal - Connection error"
            TerminalState.READY -> "SSH Terminal - Ready for input"
        }

        terminalView.contentDescription = description
        if (isScreenReaderEnabled()) {
            announceText(terminalView, description)
        }
    }

    /**
     * Announce new terminal output to screen reader
     */
    fun announceTerminalOutput(terminalView: View, output: String) {
        if (isScreenReaderEnabled() && output.isNotBlank()) {
            // Filter out common terminal escape sequences and control characters
            val cleanOutput = output
                .replace(Regex("\\x1B\\[[0-9;]*[a-zA-Z]"), "") // ANSI escape sequences
                .replace(Regex("\\x1B\\].*?\\x07"), "") // OSC sequences
                .trim()

            if (cleanOutput.isNotEmpty()) {
                announceText(terminalView, "Terminal output: $cleanOutput")
            }
        }
    }

    /**
     * Set up accessibility for tab navigation
     */
    fun setupTabAccessibility(tabView: View, tabTitle: String, isActive: Boolean) {
        val description = if (isActive) {
            "Active tab: $tabTitle"
        } else {
            "Tab: $tabTitle"
        }

        tabView.contentDescription = description
        ViewCompat.setAccessibilityDelegate(tabView, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfoCompat
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)

                info.className = "Tab"
                info.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
                if (!isActive) {
                    info.addAction(AccessibilityNodeInfoCompat.ACTION_SELECT)
                }
            }
        })
    }

    /**
     * Announce tab changes
     */
    fun announceTabChange(view: View, oldTab: String?, newTab: String) {
        if (isScreenReaderEnabled()) {
            val message = if (oldTab != null) {
                "Switched from $oldTab to $newTab"
            } else {
                "Opened $newTab"
            }
            announceText(view, message)
        }
    }

    /**
     * Set up accessibility for connection list
     */
    fun setupConnectionAccessibility(connectionView: View, connectionName: String, isConnected: Boolean) {
        val status = if (isConnected) "Connected" else "Not connected"
        val description = "$connectionName - $status"

        connectionView.contentDescription = description
        ViewCompat.setAccessibilityDelegate(connectionView, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfoCompat
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)

                info.className = "Connection"
                info.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
                info.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
            }
        })
    }

    /**
     * Set up accessibility hints for keyboard shortcuts
     */
    fun addKeyboardShortcutHints(view: View, shortcuts: List<String>) {
        if (shortcuts.isNotEmpty()) {
            val hintsText = "Keyboard shortcuts: ${shortcuts.joinToString(", ")}"
            ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.hintText = hintsText
                }
            })
        }
    }

    enum class TerminalState {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        ERROR,
        READY
    }
}
