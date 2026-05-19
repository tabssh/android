package io.github.tabssh.ui.keyboard

import android.content.Context
import android.content.res.Configuration
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

    /** User's configured row count (portrait baseline). */
    private var portraitRowCount = DEFAULT_ROWS

    /** Full layout across all configured rows — preserved across orientation changes. */
    private var fullLayout: List<List<KeyboardKey>>? = null

    /** Currently latched modifier ("CTL", "ALT") or null. FN is handled via row swap. */
    private var currentModifier: String? = null

    /** Whether the FN row swap is currently active. */
    private var fnMode = false

    /** Saved layout to restore when FN is toggled off. */
    private var savedLayout: List<List<KeyboardKey>>? = null

    init {
        orientation = VERTICAL
        // Creating MaterialButton instances triggers Material theme resolution via
        // AssetManager JNI on first use — very slow (~200 ms per button on the
        // emulator, ~5 ms on device).  Defer so setContentView / inflate do not
        // block the main thread.  The hosting activity always calls setLayout() or
        // resetToDefault() after inflation, which populates the rows explicitly;
        // this post() is a safety fallback for contexts that inflate the view and
        // rely on the default keys without further configuration.
        post { if (keyboardRows.isEmpty()) setupDefaultLayout() }
    }

    /**
     * Set number of keyboard rows (1-5). This is the portrait baseline; in
     * landscape the count is automatically capped to [LANDSCAPE_MAX_ROWS].
     */
    fun setRowCount(count: Int) {
        portraitRowCount = count.coerceIn(MIN_ROWS, MAX_ROWS)
        numberOfRows = effectiveRowCount()
        rebuildRows()
    }

    /**
     * Get the currently displayed row count (orientation-adjusted).
     */
    fun getRowCount(): Int = numberOfRows

    /**
     * Compute the row count to actually display, considering both orientation
     * and physical screen size. [portraitRowCount] is the user's preferred
     * maximum — this function may return a smaller value on large screens so
     * the keyboard bar stays proportionate to the available terminal area.
     *
     * Screen-size caps (portrait):
     *   sw < 600dp  → honour user setting up to [MAX_ROWS]  (phone)
     *   sw ≥ 600dp  → cap at [TABLET_PORTRAIT_MAX_ROWS]     (7" tablet / large phone)
     *   sw ≥ 720dp  → cap at [LARGE_TABLET_PORTRAIT_MAX_ROWS] (10"+ tablet)
     *
     * Landscape always caps at [LANDSCAPE_MAX_ROWS] regardless of screen size.
     * [config] defaults to the current resource configuration so call sites
     * that have a fresh Configuration (e.g. onConfigurationChanged) can pass
     * it directly and avoid a stale read.
     */
    private fun effectiveRowCount(config: Configuration = resources.configuration): Int {
        val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) return minOf(portraitRowCount, LANDSCAPE_MAX_ROWS)

        val sw = config.smallestScreenWidthDp
        val portraitCap = when {
            sw >= 720 -> LARGE_TABLET_PORTRAIT_MAX_ROWS
            sw >= 600 -> TABLET_PORTRAIT_MAX_ROWS
            else      -> MAX_ROWS
        }
        return minOf(portraitRowCount, portraitCap).coerceAtLeast(MIN_ROWS)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newEffective = effectiveRowCount(newConfig)
        if (newEffective == numberOfRows) return

        numberOfRows = newEffective
        rebuildRows()
        val layout = fullLayout
        if (layout != null) {
            for ((idx, keys) in layout.take(numberOfRows).withIndex()) {
                if (idx < keyboardRows.size) keyboardRows[idx].setKeys(keys, pinnedCountForRow(idx))
            }
        } else {
            applyDefaultKeys()
        }
        applyModifierHighlight()
    }

    /** Public entry point so the hosting Activity can forward its own onConfigurationChanged. */
    fun notifyConfigurationChanged(newConfig: android.content.res.Configuration) {
        onConfigurationChanged(newConfig)
    }

    /**
     * Set layout for all rows. The full layout is remembered so that
     * all rows can be restored when rotating back to portrait even if
     * landscape capped the visible count.
     *
     * If the saved layout has fewer rows than [portraitRowCount] (e.g. the
     * user added a row since the layout was saved), the extra rows are
     * populated with the best-practice defaults rather than left empty.
     * This honours user customisations on existing rows while still giving
     * new rows a useful starting point.
     */
    fun setLayout(rows: List<List<KeyboardKey>>) {
        // Do NOT override portraitRowCount — the Activity already set it via
        // setRowCount() to honour the user's row-count preference. We only
        // clamp if the saved layout is larger than the allowed maximum.
        if (rows.size > portraitRowCount) {
            portraitRowCount = rows.size.coerceIn(MIN_ROWS, MAX_ROWS)
        }
        numberOfRows = effectiveRowCount()
        Logger.d(TAG, "setLayout: ${rows.size} rows supplied, portrait=$portraitRowCount effective=$numberOfRows")
        rebuildRows()

        // Apply saved rows.
        val rowsToApply = rows.take(numberOfRows)
        rowsToApply.forEachIndexed { index, keys ->
            Logger.d(TAG, "Setting row $index with ${keys.size} keys: ${keys.map { it.label }}")
            if (index < keyboardRows.size) {
                keyboardRows[index].setKeys(keys, pinnedCountForRow(index))
            } else {
                Logger.e(TAG, "Row $index doesn't exist in keyboardRows (size=${keyboardRows.size})")
            }
        }

        // If the user has more rows configured than are in the saved layout
        // (e.g. they increased row count after the last save), fill the extra
        // rows with defaults rather than leaving them blank.
        if (rows.size < numberOfRows) {
            val defaultLayouts = getDefaultRowLayouts(numberOfRows)
            for (i in rows.size until numberOfRows) {
                if (i < keyboardRows.size && i < defaultLayouts.size) {
                    Logger.d(TAG, "Filling new row $i with defaults")
                    keyboardRows[i].setKeys(defaultLayouts[i], pinnedCountForRow(i))
                }
            }
            // Extend fullLayout to cover the default-filled rows so orientation
            // changes can restore them correctly.
            fullLayout = rows + defaultLayouts.drop(rows.size).take(numberOfRows - rows.size)
        } else {
            fullLayout = rows.take(portraitRowCount)
        }

        Logger.d(TAG, "Layout set: $numberOfRows rows visible, ${keyboardRows.sumOf { it.getKeys().size }} total keys")
    }

    /**
     * Set layout for a specific row
     */
    fun setRowLayout(rowIndex: Int, keys: List<KeyboardKey>) {
        if (rowIndex in 0 until keyboardRows.size) {
            keyboardRows[rowIndex].setKeys(keys, pinnedCountForRow(rowIndex))
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
                keyboardRows[index].setKeys(keys, pinnedCountForRow(index))
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
            if (idx < keyboardRows.size) keyboardRows[idx].setKeys(keys, pinnedCountForRow(idx))
        }
        savedLayout = null
        applyModifierHighlight()
    }

    /**
     * How many keys to pin (fixed-width) on the left of a given row index.
     * Rows 0 and 1 anchor their first two keys when there are 2+ rows so that
     * ESC+CTL (row 0) and TAB+ENT (row 1) always sit at the same left-edge
     * position regardless of screen size or total key count.
     */
    private fun pinnedCountForRow(rowIndex: Int): Int =
        if (numberOfRows >= 2 && rowIndex <= 1) 2 else 0

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
     * Reset to default layout. Preserves the configured portrait row count;
     * visible rows are still capped in landscape.
     */
    fun resetToDefault() {
        fnMode = false
        savedLayout = null
        fullLayout = null
        currentModifier = null
        if (portraitRowCount == 0) portraitRowCount = DEFAULT_ROWS
        numberOfRows = effectiveRowCount()
        rebuildRows()
        applyDefaultKeys()
        onModifierChangedListener?.invoke(null)
    }

    companion object {
        const val MIN_ROWS = 1
        const val MAX_ROWS = 5
        const val DEFAULT_ROWS = 3
        /** Maximum rows shown in landscape to preserve terminal vertical space. */
        const val LANDSCAPE_MAX_ROWS = 2
        /**
         * Portrait caps for tablet-class screens. The user's setting is always
         * a ceiling — these values only kick in when the screen is large enough
         * that the default row height would eat a disproportionate amount of
         * the terminal area.
         *
         * Threshold          smallestScreenWidthDp    example device
         * 7" / large phone   ≥ 600 dp                 Galaxy Tab A, Pixel Fold
         * 10"+ tablet        ≥ 720 dp                 Galaxy Tab S, iPad-class
         */
        const val TABLET_PORTRAIT_MAX_ROWS = 3
        const val LARGE_TABLET_PORTRAIT_MAX_ROWS = 2
        private const val TAG = "MultiRowKeyboardView"

        /**
         * Get default key layouts for N rows.
         *
         * Layout philosophy — vim/tmux/coding-first:
         *   • ESC + CTL + ALT live on row 1 always (vim escape, tmux
         *     prefix C-b/C-a, shell job control C-c/C-z).
         *   • Arrow keys promoted to row 1 when there's space (most-used
         *     in command-line editing + scrollback navigation).
         *   • `:` `/` symbols promoted to row 1 in the 3+ row layouts —
         *     vim ex-mode + search are first-class.
         *   • Coding/shell symbols (\, |, -, _, ~, `, $, *, <, >) on row 3.
         *   • Page navigation (HOME/END/PGUP/PGDN) lives on row 2.
         *   • The IME-toggle (⌨) was dropped — back-key dismisses the
         *     soft keyboard, no dedicated affordance needed.
         *   • Row 2 of 3+ row layouts ends with SEL (arms drag-select),
         *     PASTE (📋, paste clipboard), and MENU (☰, tabs/sessions
         *     bottom-sheet) — three discrete entry points so the user
         *     never has to dig through nested menus to copy, paste, or
         *     switch tabs.
         */
        fun getDefaultRowLayouts(rowCount: Int): List<List<KeyboardKey>> {
            return when (rowCount) {
                1 -> listOf(
                    listOf(
                        KeyboardKey("ESC", "ESC", "\u001B"),
                        KeyboardKey("CTL", "CTL", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("ALT", "ALT", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("FN", "FN", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("TAB", "TAB", "\t"),
                        KeyboardKey("UP", "↑", "\u001B[A", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("DOWN", "↓", "\u001B[B", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("LEFT", "←", "\u001B[D", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("RIGHT", "→", "\u001B[C", KeyboardKey.KeyCategory.ARROW)
                    )
                )
                2 -> listOf(
                    // Row 1: vim/tmux essentials + arrows
                    listOf(
                        KeyboardKey("ESC", "ESC", "\u001B"),
                        KeyboardKey("CTL", "CTL", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("ALT", "ALT", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("FN", "FN", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("TAB", "TAB", "\t"),
                        KeyboardKey("UP", "↑", "\u001B[A", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("DOWN", "↓", "\u001B[B", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("LEFT", "←", "\u001B[D", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("RIGHT", "→", "\u001B[C", KeyboardKey.KeyCategory.ARROW)
                    ),
                    // Row 2: page navigation + coding symbols
                    listOf(
                        KeyboardKey("HOME", "HOME", "\u001B[H"),
                        KeyboardKey("END", "END", "\u001B[F"),
                        KeyboardKey("PGUP", "PGUP", "\u001B[5~"),
                        KeyboardKey("PGDN", "PGDN", "\u001B[6~"),
                        KeyboardKey("COLON", ":", ":", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("SLASH", "/", "/", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("PIPE", "|", "|", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("TILDE", "~", "~", KeyboardKey.KeyCategory.SYMBOL)
                    )
                )
                3 -> listOf(
                    // Row 1: vim/tmux essentials + arrows. ESC first
                    // (vim escape), `:` and `/` promoted (vim ex/search).
                    listOf(
                        KeyboardKey("ESC", "ESC", "\u001B"),
                        KeyboardKey("CTL", "CTL", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("ALT", "ALT", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("COLON", ":", ":", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("SLASH", "/", "/", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("UP", "↑", "\u001B[A", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("DOWN", "↓", "\u001B[B", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("LEFT", "←", "\u001B[D", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("RIGHT", "→", "\u001B[C", KeyboardKey.KeyCategory.ARROW)
                    ),
                    // Row 2: page navigation + TAB/ENT/PASTE
                    listOf(
                        KeyboardKey("TAB", "TAB", "\t"),
                        KeyboardKey("HOME", "HOME", "\u001B[H"),
                        KeyboardKey("END", "END", "\u001B[F"),
                        KeyboardKey("PGUP", "PGUP", "\u001B[5~"),
                        KeyboardKey("PGDN", "PGDN", "\u001B[6~"),
                        KeyboardKey("FN", "FN", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("ENTER", "ENT", "\r"),
                        KeyboardKey("PASTE", "📋", "", KeyboardKey.KeyCategory.ACTION)
                    ),
                    // Row 3: coding/shell symbols Android keyboard buries
                    // 2 taps deep. Bash + vim flavour: pipe, redirect,
                    // backslash, glob, command sub.
                    listOf(
                        KeyboardKey("PIPE", "|", "|", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("BACKSLASH", "\\", "\\", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("MINUS", "-", "-", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("UNDERSCORE", "_", "_", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("TILDE", "~", "~", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("BACKTICK", "`", "`", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("DOLLAR", "$", "$", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("STAR", "*", "*", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("LT", "<", "<", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("GT", ">", ">", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("SEL", "SEL", "", KeyboardKey.KeyCategory.ACTION),
                    )
                )
                4 -> listOf(
                    // Row 1: vim/tmux essentials + arrows
                    listOf(
                        KeyboardKey("ESC", "ESC", "\u001B"),
                        KeyboardKey("CTL", "CTL", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("ALT", "ALT", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("COLON", ":", ":", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("SLASH", "/", "/", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("UP", "↑", "\u001B[A", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("DOWN", "↓", "\u001B[B", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("LEFT", "←", "\u001B[D", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("RIGHT", "→", "\u001B[C", KeyboardKey.KeyCategory.ARROW)
                    ),
                    // Row 2: navigation
                    listOf(
                        KeyboardKey("TAB", "TAB", "\t"),
                        KeyboardKey("HOME", "HOME", "\u001B[H"),
                        KeyboardKey("END", "END", "\u001B[F"),
                        KeyboardKey("PGUP", "PGUP", "\u001B[5~"),
                        KeyboardKey("PGDN", "PGDN", "\u001B[6~"),
                        KeyboardKey("FN", "FN", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("ENTER", "ENT", "\r"),
                        KeyboardKey("PASTE", "📋", "", KeyboardKey.KeyCategory.ACTION)
                    ),
                    // Row 3: coding symbols
                    listOf(
                        KeyboardKey("PIPE", "|", "|", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("BACKSLASH", "\\", "\\", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("MINUS", "-", "-", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("UNDERSCORE", "_", "_", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("TILDE", "~", "~", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("BACKTICK", "`", "`", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("DOLLAR", "$", "$", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("STAR", "*", "*", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("LT", "<", "<", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("GT", ">", ">", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("SEL", "SEL", "", KeyboardKey.KeyCategory.ACTION),
                    ),
                    // Row 4: F1-F6
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
                    // Row 1: vim/tmux essentials + arrows
                    listOf(
                        KeyboardKey("ESC", "ESC", "\u001B"),
                        KeyboardKey("CTL", "CTL", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("ALT", "ALT", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("COLON", ":", ":", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("SLASH", "/", "/", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("UP", "↑", "\u001B[A", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("DOWN", "↓", "\u001B[B", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("LEFT", "←", "\u001B[D", KeyboardKey.KeyCategory.ARROW),
                        KeyboardKey("RIGHT", "→", "\u001B[C", KeyboardKey.KeyCategory.ARROW)
                    ),
                    // Row 2: navigation
                    listOf(
                        KeyboardKey("TAB", "TAB", "\t"),
                        KeyboardKey("HOME", "HOME", "\u001B[H"),
                        KeyboardKey("END", "END", "\u001B[F"),
                        KeyboardKey("PGUP", "PGUP", "\u001B[5~"),
                        KeyboardKey("PGDN", "PGDN", "\u001B[6~"),
                        KeyboardKey("FN", "FN", "", KeyboardKey.KeyCategory.MODIFIER),
                        KeyboardKey("ENTER", "ENT", "\r"),
                        KeyboardKey("PASTE", "📋", "", KeyboardKey.KeyCategory.ACTION)
                    ),
                    // Row 3: coding symbols
                    listOf(
                        KeyboardKey("PIPE", "|", "|", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("BACKSLASH", "\\", "\\", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("MINUS", "-", "-", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("UNDERSCORE", "_", "_", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("TILDE", "~", "~", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("BACKTICK", "`", "`", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("DOLLAR", "$", "$", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("STAR", "*", "*", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("LT", "<", "<", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("GT", ">", ">", KeyboardKey.KeyCategory.SYMBOL),
                        KeyboardKey("SEL", "SEL", "", KeyboardKey.KeyCategory.ACTION),
                    ),
                    // Row 4: F1-F6
                    listOf(
                        KeyboardKey("F1", "F1", "\u001BOP", KeyboardKey.KeyCategory.FUNCTION),
                        KeyboardKey("F2", "F2", "\u001BOQ", KeyboardKey.KeyCategory.FUNCTION),
                        KeyboardKey("F3", "F3", "\u001BOR", KeyboardKey.KeyCategory.FUNCTION),
                        KeyboardKey("F4", "F4", "\u001BOS", KeyboardKey.KeyCategory.FUNCTION),
                        KeyboardKey("F5", "F5", "\u001B[15~", KeyboardKey.KeyCategory.FUNCTION),
                        KeyboardKey("F6", "F6", "\u001B[17~", KeyboardKey.KeyCategory.FUNCTION)
                    ),
                    // Row 5: F7-F12
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
