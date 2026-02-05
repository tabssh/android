package io.github.tabssh.ui.keyboard

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import io.github.tabssh.R
import io.github.tabssh.utils.logging.Logger

/**
 * Custom keyboard bar for SSH terminal
 * Displays customizable keys above the terminal
 */
class CustomKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {
    
    private val keyContainer: LinearLayout
    private var onKeyClickListener: ((KeyboardKey) -> Unit)? = null
    private var onToggleClickListener: (() -> Unit)? = null
    private var currentModifier: String? = null
    private var fnMode = false
    
    init {
        // Inflate layout
        LayoutInflater.from(context).inflate(R.layout.view_custom_keyboard, this, true)
        keyContainer = findViewById(R.id.key_container)
        
        // Configure scroll view
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
    }
    
    /**
     * Set keyboard layout
     */
    fun setLayout(keys: List<KeyboardKey>) {
        keyContainer.removeAllViews()
        
        keys.forEach { key ->
            val button = createKeyButton(key)
            keyContainer.addView(button)
        }
        
        Logger.d("CustomKeyboardView", "Layout set with ${keys.size} keys")
    }
    
    /**
     * Create button for a key
     */
    private fun createKeyButton(key: KeyboardKey): MaterialButton {
        val button = MaterialButton(context, null, R.attr.materialButtonOutlinedStyle)
        
        // Set dimensions
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        params.marginEnd = 8
        button.layoutParams = params
        
        // Set text and style
        button.text = key.label
        button.minWidth = 0
        button.minimumWidth = 0
        button.setPadding(32, 16, 32, 16)
        button.textSize = 12f
        
        // Handle click
        button.setOnClickListener {
            handleKeyClick(key, button)
        }
        
        return button
    }
    
    /**
     * Handle key click
     */
    private fun handleKeyClick(key: KeyboardKey, button: MaterialButton) {
        when (key.category) {
            KeyboardKey.KeyCategory.MODIFIER -> handleModifier(key, button)
            KeyboardKey.KeyCategory.ACTION -> handleAction(key)
            else -> {
                if (fnMode && key.category != KeyboardKey.KeyCategory.FUNCTION) {
                    // FN mode active - show function keys overlay
                    showFunctionKeyOverlay()
                } else {
                    // Send key with current modifier
                    sendKeyWithModifier(key)
                }
            }
        }
    }
    
    /**
     * Handle modifier key (CTL, ALT, FN)
     */
    private fun handleModifier(key: KeyboardKey, button: MaterialButton) {
        when (key.id) {
            "FN" -> {
                fnMode = !fnMode
                if (fnMode) {
                    showFunctionKeyOverlay()
                } else {
                    restoreNormalKeys()
                }
            }
            "CTL", "ALT" -> {
                if (currentModifier == key.id) {
                    // Toggle off
                    currentModifier = null
                } else {
                    // Toggle on
                    currentModifier = key.id
                }
                updateModifierHighlight()
            }
        }
    }
    
    /**
     * Handle action key (Toggle, Paste)
     */
    private fun handleAction(key: KeyboardKey) {
        when (key.id) {
            "TOGGLE" -> onToggleClickListener?.invoke()
            "PASTE" -> {
                // Paste action handled by listener
                onKeyClickListener?.invoke(key)
            }
        }
    }
    
    /**
     * Send key with current modifier
     */
    private fun sendKeyWithModifier(key: KeyboardKey) {
        val modifiedKey = when (currentModifier) {
            "CTL" -> {
                // Ctrl key: convert to control character
                val ctrlSequence = getCtrlSequence(key)
                KeyboardKey(key.id, key.label, ctrlSequence, key.category)
            }
            "ALT" -> {
                // Alt key: prepend ESC
                KeyboardKey(key.id, key.label, "\u001B${key.keySequence}", key.category)
            }
            else -> key
        }
        
        onKeyClickListener?.invoke(modifiedKey)
        
        // Clear modifier after use (sticky modifier)
        if (currentModifier != null) {
            currentModifier = null
            updateModifierHighlight()
        }
    }
    
    /**
     * Get Ctrl sequence for a key
     */
    private fun getCtrlSequence(key: KeyboardKey): String {
        // Ctrl-A to Ctrl-Z
        val char = key.keySequence.firstOrNull()?.uppercaseChar()
        return if (char != null && char in 'A'..'Z') {
            val ctrlCode = char.code - 'A'.code + 1
            ctrlCode.toChar().toString()
        } else {
            key.keySequence
        }
    }
    
    /**
     * Show function key overlay (F1-F12)
     */
    private fun showFunctionKeyOverlay() {
        keyContainer.removeAllViews()
        
        val functionKeys = KeyboardKey.getAllAvailableKeys()
            .filter { it.category == KeyboardKey.KeyCategory.FUNCTION }
        
        functionKeys.forEach { key ->
            val button = createKeyButton(key)
            keyContainer.addView(button)
        }
        
        // Add back button
        val backButton = MaterialButton(context, null, R.attr.materialButtonOutlinedStyle)
        backButton.text = "←"
        backButton.setOnClickListener {
            fnMode = false
            restoreNormalKeys()
        }
        keyContainer.addView(backButton)
    }
    
    /**
     * Restore normal keys layout
     */
    private fun restoreNormalKeys() {
        // This should be called by parent to restore saved layout
        // For now, just log
        Logger.d("CustomKeyboardView", "Restore normal keys requested")
    }
    
    /**
     * Update modifier button highlight
     */
    private fun updateModifierHighlight() {
        // Update button appearance based on active modifier
        for (i in 0 until keyContainer.childCount) {
            val view = keyContainer.getChildAt(i)
            if (view is MaterialButton) {
                val isActive = view.text == currentModifier
                view.alpha = if (isActive) 1.0f else 0.7f
            }
        }
    }
    
    /**
     * Set key click listener
     */
    fun setOnKeyClickListener(listener: (KeyboardKey) -> Unit) {
        this.onKeyClickListener = listener
    }
    
    /**
     * Set toggle click listener
     */
    fun setOnToggleClickListener(listener: () -> Unit) {
        this.onToggleClickListener = listener
    }
}
