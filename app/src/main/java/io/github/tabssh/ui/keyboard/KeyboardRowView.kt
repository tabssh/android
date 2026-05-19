package io.github.tabssh.ui.keyboard

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import io.github.tabssh.R

/**
 * Single row of the multi-row keyboard
 * Horizontally scrollable row of keys.
 *
 * Modifier (CTL/ALT/FN) state is owned by the parent [MultiRowKeyboardView] so
 * it is shared across all rows AND visible to the rest of the app (the
 * terminal view consults it for IME letters). This view just emits clicks.
 */
class KeyboardRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "KeyboardRowView"
        private const val KEY_PADDING_DP = 12
        private const val KEY_MARGIN_DP = 4
        private const val KEY_TEXT_SIZE_SP = 12f
        /** Fixed width (dp) for pinned anchor keys so they never shift position. */
        private const val KEY_PINNED_WIDTH_DP = 52
    }

    private val keyContainer: LinearLayout
    private var keys: List<KeyboardKey> = emptyList()
    private var pinnedCount = 0
    private var onKeyClickListener: ((KeyboardKey) -> Unit)? = null
    private var onToggleClickListener: (() -> Unit)? = null

    /** Buttons keyed by modifier id (CTL/ALT/FN) so the parent can highlight. */
    private val modifierButtons = mutableMapOf<String, MaterialButton>()

    init {
        // Setup scroll view
        isHorizontalScrollBarEnabled = false
        isFillViewport = true

        // Create key container
        keyContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2))
        }
        addView(keyContainer)

        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, rowHeightPx())
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        layoutParams = layoutParams.also { it.height = rowHeightPx() }
        requestLayout()
    }

    private fun rowHeightPx(): Int =
        resources.getDimensionPixelSize(R.dimen.keyboard_row_height)

    /**
     * Set keys for this row.
     *
     * @param newKeys     The keys to display.
     * @param pinnedCount The first [pinnedCount] keys are given a fixed pixel
     *                    width ([KEY_PINNED_WIDTH_DP]) so they always sit at the
     *                    same left-edge position regardless of how many keys are
     *                    in the row or the physical screen width.  The remaining
     *                    keys share the leftover space equally (weight = 1).
     *                    Pass 0 (default) for uniform flex sizing.
     */
    fun setKeys(newKeys: List<KeyboardKey>, pinnedCount: Int = 0) {
        keys = newKeys
        this.pinnedCount = pinnedCount.coerceIn(0, newKeys.size)
        rebuildKeys()
    }

    /**
     * Get current keys
     */
    fun getKeys(): List<KeyboardKey> = keys.toList()

    /**
     * Highlight the active modifier (called by parent). Pass null to clear.
     */
    fun highlightModifier(activeId: String?) {
        modifierButtons.forEach { (id, button) ->
            button.alpha = if (id == activeId) 1.0f else 0.7f
        }
    }

    /**
     * Rebuild key buttons
     */
    private fun rebuildKeys() {
        keyContainer.removeAllViews()
        modifierButtons.clear()

        keys.forEachIndexed { index, key ->
            val pinned = index < pinnedCount
            val button = createKeyButton(key, pinned)
            keyContainer.addView(button)

            // Track modifier buttons for highlighting
            if (key.category == KeyboardKey.KeyCategory.MODIFIER) {
                modifierButtons[key.id] = button
            }
        }
    }

    /**
     * Create a button for a key.
     *
     * @param pinned When true the button gets a fixed [KEY_PINNED_WIDTH_DP] width
     *               (weight = 0) so it never shifts position.  When false it
     *               shares the remaining row space equally (weight = 1).
     */
    private fun createKeyButton(key: KeyboardKey, pinned: Boolean = false): MaterialButton {
        val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)

        val params = if (pinned) {
            LinearLayout.LayoutParams(dpToPx(KEY_PINNED_WIDTH_DP), LinearLayout.LayoutParams.MATCH_PARENT, 0f)
        } else {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
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
            KeyboardKey.KeyCategory.MODIFIER -> button.alpha = 0.7f
            KeyboardKey.KeyCategory.FUNCTION -> button.alpha = 0.85f
            KeyboardKey.KeyCategory.ACTION -> button.alpha = 0.95f
            else -> button.alpha = 1.0f
        }

        button.setOnClickListener {
            handleKeyClick(key)
        }

        return button
    }

    /**
     * Handle key click
     */
    private fun handleKeyClick(key: KeyboardKey) {
        when (key.category) {
            KeyboardKey.KeyCategory.ACTION -> {
                if (key.id == "TOGGLE") {
                    onToggleClickListener?.invoke()
                } else {
                    onKeyClickListener?.invoke(key)
                }
            }
            else -> {
                // Modifiers, symbols, arrows, function keys, special — all
                // propagate to the parent which decides whether to apply a
                // sticky modifier or interpret as a modifier toggle.
                onKeyClickListener?.invoke(key)
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

    /**
     * Convert dp to pixels
     */
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
