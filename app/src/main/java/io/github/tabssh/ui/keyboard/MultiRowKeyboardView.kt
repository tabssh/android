package io.github.tabssh.ui.keyboard

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import io.github.tabssh.utils.logging.Logger

/**
 * Multi-row customizable keyboard for SSH terminal
 * Supports 1-5 rows (default 3), each row is independently scrollable.
 *
 * Owns the sticky CTL/ALT modifier state and FN-row swap so that:
 *  - tapping a modifier reflects on every visible row
 *  - the activity (and thus the terminal) sees a single source of truth via
 *    [setOnModifierChangedListener]
 *  - tapping FN swaps the layout to F1-F12 + Back, restoring on second tap.
 */
class MultiRowKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val keyboardRows = mutableListOf<KeyboardRowView>()
    private var onKeyClickListener: ((KeyboardKey) -> Unit)? = null
    private var onToggleClickListener: (() -> Unit)? = null
    private var onModifierChangedListener: ((String?) -> Unit)? = null
    private var numberOfRows = DEFAULT_ROWS

    /** Currently latched modifier ("CTL", "ALT") or null. FN is handled via row swap. */
    private var currentModifier: String? = null

    /** Whether the FN row swap is currently active. */
    private var fnMode = false

    /** Saved layout to restore when FN is toggled off. */
    private var savedLayout: List<List<KeyboardKey>>? = null

    init {
        orientation = VERTICAL
        setupDefaultLayout()
    }

    /**
     * Set number of keyboard rows (1-5)
     */
    fun setRowCount(count: Int) {
        numberOfRows = count.coerceIn(MIN_ROWS, MAX_ROWS)
        rebuildRows()
    }

    /**
     * Get current number of rows
     */
    fun getRowCount(): Int = numberOfRows

    /**
     * Set layout for all rows
     * Each inner list represents one row of keys
     */
    fun setLayout(rows: List<List<KeyboardKey>>) {
        numberOfRows = rows.size.coerceIn(MIN_ROWS, MAX_ROWS)
        Logger.d(TAG, "setLayout called with ${rows.size} rows")
        rebuildRows()

        rows.take(numberOfRows).forEachIndexed { index, keys ->
            Logger.d(TAG, "Setting row $index with ${keys.size} keys: ${keys.map { it.label }}")
            if (index < keyboardRows.size) {
                keyboardRows[index].setKeys(keys)
            } else {
                Logger.e(TAG, "Row $index doesn't exist in keyboardRows (size=${keyboardRows.size})")
            }
        }

        Logger.d(TAG, "Layout set with $numberOfRows rows, total keys: ${rows.sumOf { it.size }}")
    }

    /**
     * Set layout for a specific row
     */
    fun setRowLayout(rowIndex: Int, keys: List<KeyboardKey>) {
        if (rowIndex in 0 until keyboardRows.size) {
            keyboardRows[rowIndex].setKeys(keys)
        }
    }

    /**
     * Get layout for all rows
     */
    fun getLayout(): List<List<KeyboardKey>> {
        return keyboardRows.map { it.getKeys() }
    }

    /**
     * Setup default 3-row layout
     */
    private fun setupDefaultLayout() {
        rebuildRows()
        applyDefaultKeys()
    }

    /**
     * Rebuild row views based on numberOfRows
     */
    private fun rebuildRows() {
        removeAllViews()
        keyboardRows.clear()

        Logger.d(TAG, "Rebuilding $numberOfRows keyboard rows")

        for (i in 0 until numberOfRows) {
            val row = KeyboardRowView(context)
            row.setOnKeyClickListener { key -> handleRowKey(key) }
            row.setOnToggleClickListener { onToggleClickListener?.invoke() }
            keyboardRows.add(row)
            addView(row)
        }

        applyModifierHighlight()
    }

    /**
     * Apply default keys to rows
     */
    private fun applyDefaultKeys() {
        val defaultLayouts = getDefaultRowLayouts(numberOfRows)
        defaultLayouts.forEachIndexed { index, keys ->
            if (index < keyboardRows.size) {
                keyboardRows[index].setKeys(keys)
            }
        }
        applyModifierHighlight()
    }

    /**
     * Single funnel for every key click coming from any row.
     */
    private fun handleRowKey(key: KeyboardKey) {
        when (key.category) {
            KeyboardKey.KeyCategory.MODIFIER -> handleModifierTap(key)
            else -> {
                // The activity is responsible for the modifier-aware sending.
                // We just emit the raw key plus the current modifier state.
                onKeyClickListener?.invoke(key)
                // CTL/ALT are one-shot — clear after the first non-modifier key.
                if (currentModifier != null) {
                    setCurrentModifier(null)
                }
            }
        }
    }

    /**
     * Handle a modifier key tap. CTL/ALT toggle the sticky state; FN swaps the
     * row layout to expose F1-F12.
     */
    private fun handleModifierTap(key: KeyboardKey) {
        when (key.id) {
            "FN" -> {
                if (fnMode) restoreFromFn() else enterFnMode()
            }
            "CTL", "ALT" -> {
                val newMod = if (currentModifier == key.id) null else key.id
                setCurrentModifier(newMod)
            }
        }
    }

    private fun setCurrentModifier(modifier: String?) {
        if (currentModifier == modifier) return
        currentModifier = modifier
        applyModifierHighlight()
        onModifierChangedListener?.invoke(modifier)
    }

    private fun applyModifierHighlight() {
        keyboardRows.forEach { it.highlightModifier(currentModifier) }
    }

    /**
     * Replace current rows with an F1-F12 + Back layout.
     */
    private fun enterFnMode() {
        if (fnMode) return
        fnMode = true
        savedLayout = getLayout()

        // Use the FN key itself as the back button so a second tap exits via
        // the existing modifier handler (no extra plumbing needed).
        val backKey = KeyboardKey("FN", "← Back", "", KeyboardKey.KeyCategory.MODIFIER)
        val fnRow1 = listOf(
            backKey,
            KeyboardKey("F1", "F1", "\u001BOP", KeyboardKey.KeyCategory.FUNCTION),
            KeyboardKey("F2", "F2", "\u001BOQ", KeyboardKey.KeyCategory.FUNCTION),
            KeyboardKey("F3", "F3", "\u001BOR", KeyboardKey.KeyCategory.FUNCTION),
            KeyboardKey("F4", "F4", "\u001BOS", KeyboardKey.KeyCategory.FUNCTION),
            KeyboardKey("F5", "F5", "\u001B[15~", KeyboardKey.KeyCategory.FUNCTION),
            KeyboardKey("F6", "F6", "\u001B[17~", KeyboardKey.KeyCategory.FUNCTION),
        )
        val fnRow2 = listOf(
            KeyboardKey("F7", "F7", "\u001B[18~", KeyboardKey.KeyCategory.FUNCTION),
            KeyboardKey("F8", "F8", "\u001B[19~", KeyboardKey.KeyCategory.FUNCTION),
            KeyboardKey("F9", "F9", "\u001B[20~", KeyboardKey.KeyCategory.FUNCTION),
            KeyboardKey("F10", "F10", "\u001B[21~", KeyboardKey.KeyCategory.FUNCTION),
            KeyboardKey("F11", "F11", "\u001B[23~", KeyboardKey.KeyCategory.FUNCTION),
            KeyboardKey("F12", "F12", "\u001B[24~", KeyboardKey.KeyCategory.FUNCTION),
        )

        // Show first two rows of F-keys, blank any extras.
        keyboardRows.forEachIndexed { idx, row ->
            row.setKeys(
                when (idx) {
                    0 -> fnRow1
                    1 -> fnRow2
                    else -> emptyList()
                }
            )
        }
    }

    private fun restoreFromFn() {
        if (!fnMode) return
        fnMode = false
        savedLayout?.forEachIndexed { idx, keys ->
            if (idx < keyboardRows.size) keyboardRows[idx].setKeys(keys)
        }
        savedLayout = null
        applyModifierHighlight()
    }

    /**
     * Set key click listener
     */
    fun setOnKeyClickListener(listener: (KeyboardKey) -> Unit) {
        this.onKeyClickListener = listener
    }

    /**
     * Set toggle click listener (system IME show/hide).
     */
    fun setOnToggleClickListener(listener: () -> Unit) {
        this.onToggleClickListener = listener
    }

    /**
     * Subscribe to modifier state changes — payload is "CTL", "ALT" or null.
     */
    fun setOnModifierChangedListener(listener: (String?) -> Unit) {
        this.onModifierChangedListener = listener
    }

    /** Currently latched modifier ("CTL", "ALT") or null. */
    fun getCurrentModifier(): String? = currentModifier

    /** Force-clear the latched modifier (e.g. after the terminal consumes it). */
    fun clearModifier() {
        if (currentModifier != null) setCurrentModifier(null)
    }

    /**
     * Reset to default layout
     */
    fun resetToDefault() {
        fnMode = false
        savedLayout = null
        currentModifier = null
        numberOfRows = DEFAULT_ROWS
        rebuildRows()
        applyDefaultKeys()
        onModifierChangedListener?.invoke(null)
    }

    companion object {
        const val MIN_ROWS = 1
        const val MAX_ROWS = 5
        const val DEFAULT_ROWS = 3
        private const val TAG = "MultiRowKeyboardView"

        /**
         * Get default key layouts for N rows
         */
        fun getDefaultRowLayouts(rowCount: Int): List<List<KeyboardKey>> {
            return when (rowCount) {
                1 -> listOf(
                    // Single row: essential keys only
                    listOf(
                        KeyboardKey("ESC", "ESC", "\u001B"),
                        KeyboardKey("TAB", "TAB", "\t"),
                        KeyboardKey("CTL", "CTL", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("ALT", "ALT", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("UP", "↑", "\u001B[A", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("DOWN", "↓", "\u001B[B", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("LEFT", "←", "\u001B[D", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("RIGHT", "→", "\u001B[C", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("TOGGLE", "⌨", "", KeyboardKey.KeyCategory.ACTION)
                    )
                )
                2 -> listOf(
                    // Row 1: Modifiers and special keys
                    listOf(
                        KeyboardKey("ESC", "ESC", "\u001B"),
                        KeyboardKey("TAB", "TAB", "\t"),
                        KeyboardKey("CTL", "CTL", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("ALT", "ALT", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("FN", "FN", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("TOGGLE", "⌨", "", KeyboardKey.KeyCategory.ACTION)
                    ),
                    // Row 2: Navigation and arrows
                    listOf(
                        KeyboardKey("UP", "↑", "\u001B[A", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("DOWN", "↓", "\u001B[B", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("LEFT", "←", "\u001B[D", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("RIGHT", "→", "\u001B[C", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("HOME", "HOME", "\u001B[H"),
                        KeyboardKey("END", "END", "\u001B[F"),
                        KeyboardKey("PGUP", "PGUP", "\u001B[5~"),
                        KeyboardKey("PGDN", "PGDN", "\u001B[6~")
                    )
                )
                3 -> listOf(
                    // Row 1: Essential keys and modifiers
                    listOf(
                        KeyboardKey("ESC", "ESC", "\u001B"),
                        KeyboardKey("TAB", "TAB", "\t"),
                        KeyboardKey("CTL", "CTL", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("ALT", "ALT", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("FN", "FN", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("ENTER", "ENT", "\n"),
                        KeyboardKey("TOGGLE", "⌨", "", KeyboardKey.KeyCategory.ACTION)
                    ),
                    // Row 2: Navigation keys
                    listOf(
                        KeyboardKey("HOME", "HOME", "\u001B[H"),
                        KeyboardKey("END", "END", "\u001B[F"),
                        KeyboardKey("PGUP", "PGUP", "\u001B[5~"),
                        KeyboardKey("PGDN", "PGDN", "\u001B[6~"),
                        KeyboardKey("UP", "↑", "\u001B[A", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("DOWN", "↓", "\u001B[B", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("LEFT", "←", "\u001B[D", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("RIGHT", "→", "\u001B[C", KeyboardKey.KeyCategory.ARROW)
                    ),
                    // Row 3: Symbols
                    listOf(
                        KeyboardKey("SLASH", "/", "/", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("BACKSLASH", "\\", "\\", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("PIPE", "|", "|", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("MINUS", "-", "-", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("UNDERSCORE", "_", "_", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("TILDE", "~", "~", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("PASTE", "📋", "", KeyboardKey.KeyCategory.ACTION)
                    )
                )
                4 -> listOf(
                    // Row 1: Modifiers
                    listOf(
                        KeyboardKey("ESC", "ESC", "\u001B"),
                        KeyboardKey("TAB", "TAB", "\t"),
                        KeyboardKey("CTL", "CTL", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("ALT", "ALT", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("FN", "FN", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("PASTE", "📋", "", KeyboardKey.KeyCategory.ACTION),
                        KeyboardKey("TOGGLE", "⌨", "", KeyboardKey.KeyCategory.ACTION)
                    ),
                    // Row 2: Navigation
                    listOf(
                        KeyboardKey("UP", "↑", "\u001B[A", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("DOWN", "↓", "\u001B[B", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("LEFT", "←", "\u001B[D", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("RIGHT", "→", "\u001B[C", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("HOME", "HOME", "\u001B[H"),
                        KeyboardKey("END", "END", "\u001B[F"),
                        KeyboardKey("PGUP", "PGUP", "\u001B[5~"),
                        KeyboardKey("PGDN", "PGDN", "\u001B[6~")
                    ),
                    // Row 3: Symbols
                    listOf(
                        KeyboardKey("SLASH", "/", "/", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("BACKSLASH", "\\", "\\", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("PIPE", "|", "|", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("MINUS", "-", "-", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("UNDERSCORE", "_", "_", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("TILDE", "~", "~", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("BACKTICK", "`", "`", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("EQUALS", "=", "=", KeyboardKey.KeyCategory.SYMBOL)
                    ),
                    // Row 4: Function keys F1-F6
                    listOf(
                        KeyboardKey("F1", "F1", "\u001BOP", KeyboardKey.KeyCategory.FUNCTION),
                        KeyboardKey("F2", "F2", "\u001BOQ", KeyboardKey.KeyCategory.FUNCTION),
                        KeyboardKey("F3", "F3", "\u001BOR", KeyboardKey.KeyCategory.FUNCTION),
                        KeyboardKey("F4", "F4", "\u001BOS", KeyboardKey.KeyCategory.FUNCTION),
                        KeyboardKey("F5", "F5", "\u001B[15~", KeyboardKey.KeyCategory.FUNCTION),
                        KeyboardKey("F6", "F6", "\u001B[17~", KeyboardKey.KeyCategory.FUNCTION)
                    )
                )
                5 -> listOf(
                    // Row 1: Modifiers
                    listOf(
                        KeyboardKey("ESC", "ESC", "\u001B"),
                        KeyboardKey("TAB", "TAB", "\t"),
                        KeyboardKey("CTL", "CTL", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("ALT", "ALT", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("FN", "FN", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("PASTE", "📋", "", KeyboardKey.KeyCategory.ACTION),
                        KeyboardKey("TOGGLE", "⌨", "", KeyboardKey.KeyCategory.ACTION)
                    ),
                    // Row 2: Navigation
                    listOf(
                        KeyboardKey("UP", "↑", "\u001B[A", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("DOWN", "↓", "\u001B[B", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("LEFT", "←", "\u001B[D", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("RIGHT", "→", "\u001B[C", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("HOME", "HOME", "\u001B[H"),
                        KeyboardKey("END", "END", "\u001B[F"),
                        KeyboardKey("PGUP", "PGUP", "\u001B[5~"),
                        KeyboardKey("PGDN", "PGDN", "\u001B[6~")
                    ),
                    // Row 3: Symbols
                    listOf(
                        KeyboardKey("SLASH", "/", "/", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("BACKSLASH", "\\", "\\", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("PIPE", "|", "|", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("MINUS", "-", "-", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("UNDERSCORE", "_", "_", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("TILDE", "~", "~", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("BACKTICK", "`", "`", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("EQUALS", "=", "=", KeyboardKey.KeyCategory.SYMBOL)
                    ),
                    // Row 4: Function keys F1-F6
                    listOf(
                        KeyboardKey("F1", "F1", "\u001BOP", KeyboardKey.KeyCategory.FUNCTION),
                        KeyboardKey("F2", "F2", "\u001BOQ", KeyboardKey.KeyCategory.FUNCTION),
                        KeyboardKey("F3", "F3", "\u001BOR", KeyboardKey.KeyCategory.FUNCTION),
                        KeyboardKey("F4", "F4", "\u001BOS", KeyboardKey.KeyCategory.FUNCTION),
                        KeyboardKey("F5", "F5", "\u001B[15~", KeyboardKey.KeyCategory.FUNCTION),
                        KeyboardKey("F6", "F6", "\u001B[17~", KeyboardKey.KeyCategory.FUNCTION)
                    ),
                    // Row 5: Function keys F7-F12
                    listOf(
                        KeyboardKey("F7", "F7", "\u001B[18~", KeyboardKey.KeyCategory.FUNCTION),
                        KeyboardKey("F8", "F8", "\u001B[19~", KeyboardKey.KeyCategory.FUNCTION),
                        KeyboardKey("F9", "F9", "\u001B[20~", KeyboardKey.KeyCategory.FUNCTION),
                        KeyboardKey("F10", "F10", "\u001B[21~", KeyboardKey.KeyCategory.FUNCTION),
                        KeyboardKey("F11", "F11", "\u001B[23~", KeyboardKey.KeyCategory.FUNCTION),
                        KeyboardKey("F12", "F12", "\u001B[24~", KeyboardKey.KeyCategory.FUNCTION)
                    )
                )
                else -> listOf(emptyList())
            }
        }
    }
}
