package io.github.tabssh.ui.keyboard

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import io.github.tabssh.utils.logging.Logger

/**
 * Single row of the multi-row keyboard
 * Horizontally scrollable row of keys
 */
class KeyboardRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "KeyboardRowView"
        private const val ROW_HEIGHT_DP = 42
        private const val KEY_PADDING_DP = 12
        private const val KEY_MARGIN_DP = 4
        private const val KEY_TEXT_SIZE_SP = 12f
    }

    private val keyContainer: LinearLayout
    private var keys: List<KeyboardKey> = emptyList()
    private var onKeyClickListener: ((KeyboardKey) -> Unit)? = null
    private var onToggleClickListener: (() -> Unit)? = null

    // Modifier state (shared across all rows via parent)
    private var currentModifier: String? = null
    private val modifierButtons = mutableMapOf<String, MaterialButton>()

    init {
        // Setup scroll view
        isHorizontalScrollBarEnabled = false
        isFillViewport = false

        // Create key container
        keyContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2))
        }
        addView(keyContainer)

        // Set row height
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dpToPx(ROW_HEIGHT_DP))
    }

    /**
     * Set keys for this row
     */
    fun setKeys(newKeys: List<KeyboardKey>) {
        keys = newKeys
        rebuildKeys()
    }

    /**
     * Get current keys
     */
    fun getKeys(): List<KeyboardKey> = keys.toList()

    /**
     * Rebuild key buttons
     */
    private fun rebuildKeys() {
        keyContainer.removeAllViews()
        modifierButtons.clear()

        keys.forEach { key ->
            val button = createKeyButton(key)
            keyContainer.addView(button)

            // Track modifier buttons for highlighting
            if (key.category == KeyboardKey.KeyCategory.MODIFIER) {
                modifierButtons[key.id] = button
            }
        }
    }

    /**
     * Create a button for a key
     */
    private fun createKeyButton(key: KeyboardKey): MaterialButton {
        val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        params.marginEnd = dpToPx(KEY_MARGIN_DP)
        button.layoutParams = params

        button.text = key.label
        button.minWidth = 0
        button.minimumWidth = 0
        button.minHeight = 0
        button.minimumHeight = 0
        button.setPadding(dpToPx(KEY_PADDING_DP), 0, dpToPx(KEY_PADDING_DP), 0)
        button.textSize = KEY_TEXT_SIZE_SP
        button.insetTop = 0
        button.insetBottom = 0

        // Color coding by category
        when (key.category) {
            KeyboardKey.KeyCategory.MODIFIER -> {
                button.alpha = 0.9f
            }
            KeyboardKey.KeyCategory.FUNCTION -> {
                button.alpha = 0.85f
            }
            KeyboardKey.KeyCategory.ACTION -> {
                button.alpha = 0.95f
            }
            else -> {
                button.alpha = 1.0f
            }
        }

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
            else -> sendKeyWithModifier(key)
        }
    }

    /**
     * Handle modifier key press
     */
    private fun handleModifier(key: KeyboardKey, button: MaterialButton) {
        if (key.id == "FN") {
            // FN is special - handled by parent
            onKeyClickListener?.invoke(key)
            return
        }

        // Toggle modifier
        if (currentModifier == key.id) {
            currentModifier = null
        } else {
            currentModifier = key.id
        }
        updateModifierHighlight()
    }

    /**
     * Handle action key press
     */
    private fun handleAction(key: KeyboardKey) {
        when (key.id) {
            "TOGGLE" -> onToggleClickListener?.invoke()
            else -> onKeyClickListener?.invoke(key)
        }
    }

    /**
     * Send key with current modifier applied
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

        // Clear modifier after use (sticky modifier behavior)
        if (currentModifier != null) {
            currentModifier = null
            updateModifierHighlight()
        }
    }

    /**
     * Get Ctrl sequence for a key
     */
    private fun getCtrlSequence(key: KeyboardKey): String {
        val char = key.keySequence.firstOrNull()?.uppercaseChar()
        return if (char != null && char in 'A'..'Z') {
            val ctrlCode = char.code - 'A'.code + 1
            ctrlCode.toChar().toString()
        } else {
            key.keySequence
        }
    }

    /**
     * Update modifier button visual state
     */
    private fun updateModifierHighlight() {
        modifierButtons.forEach { (id, button) ->
            val isActive = id == currentModifier
            button.alpha = if (isActive) 1.0f else 0.7f
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

    /**
     * Convert dp to pixels
     */
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
