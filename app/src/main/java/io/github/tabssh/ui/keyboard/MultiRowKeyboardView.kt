package io.github.tabssh.ui.keyboard

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import io.github.tabssh.R
import io.github.tabssh.utils.logging.Logger

/**
 * Multi-row customizable keyboard for SSH terminal
 * Supports 1-5 rows (default 3), each row is independently scrollable and customizable
 */
class MultiRowKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val keyboardRows = mutableListOf<KeyboardRowView>()
    private var onKeyClickListener: ((KeyboardKey) -> Unit)? = null
    private var onToggleClickListener: (() -> Unit)? = null
    private var numberOfRows = DEFAULT_ROWS

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
        rebuildRows()

        rows.take(numberOfRows).forEachIndexed { index, keys ->
            if (index < keyboardRows.size) {
                keyboardRows[index].setKeys(keys)
            }
        }

        Logger.d(TAG, "Layout set with $numberOfRows rows")
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

        for (i in 0 until numberOfRows) {
            val row = KeyboardRowView(context)
            row.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            row.setOnKeyClickListener { key -> onKeyClickListener?.invoke(key) }
            row.setOnToggleClickListener { onToggleClickListener?.invoke() }
            keyboardRows.add(row)
            addView(row)
        }
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
    }

    /**
     * Set key click listener
     */
    fun setOnKeyClickListener(listener: (KeyboardKey) -> Unit) {
        this.onKeyClickListener = listener
        keyboardRows.forEach { it.setOnKeyClickListener(listener) }
    }

    /**
     * Set toggle click listener
     */
    fun setOnToggleClickListener(listener: () -> Unit) {
        this.onToggleClickListener = listener
        keyboardRows.forEach { it.setOnToggleClickListener(listener) }
    }

    /**
     * Reset to default layout
     */
    fun resetToDefault() {
        numberOfRows = DEFAULT_ROWS
        rebuildRows()
        applyDefaultKeys()
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
