package io.github.tabssh.ui.keyboard

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.widget.LinearLayout
import io.github.tabssh.ui.keyboard.KeyboardLayoutManager
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

    /** Currently latched modifier ("CTL", "ALT", "SFT") or null. FN is handled via row swap. */
    private var currentModifier: String? = null

    /** Whether the FN row swap is currently active. */
    private var fnMode = false

    /** Saved layout to restore when FN is toggled off. */
    private var savedLayout: List<List<KeyboardKey>>? = null

    init {
        orientation = VERTICAL
        // Rows are populated by the hosting activity via setLayout() or
        // resetToDefault() after inflation — no fallback needed here.
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
     *   sw < 600dp  → honour user setting up to [MAX_ROWS]       (phone)
     *   sw ≥ 600dp  → cap at [TABLET_PORTRAIT_MAX_ROWS]          (7" tablet)
     *   sw ≥ 720dp  → cap at [LARGE_TABLET_PORTRAIT_MAX_ROWS]    (10"+ tablet)
     *
     * Landscape:
     *   sw ≥ 720dp  → honour user setting (10"+ tablet has vertical space)
     *   sw < 720dp  → cap at [LANDSCAPE_MAX_ROWS]
     *
     * [config] defaults to the current resource configuration so call sites
     * that have a fresh Configuration (e.g. onConfigurationChanged) can pass
     * it directly and avoid a stale read.
     */
    private fun effectiveRowCount(config: Configuration = resources.configuration): Int {
        val sw = config.smallestScreenWidthDp
        val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

        if (isLandscape) {
            // 10-inch+ tablets have plenty of vertical space in landscape — honour
            // the user's row-count setting without any additional cap.
            // Smaller screens keep the LANDSCAPE_MAX_ROWS ceiling so the terminal
            // area remains usable.
            return if (sw >= 720) portraitRowCount
            else minOf(portraitRowCount, LANDSCAPE_MAX_ROWS)
        }

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
        // Reset transient FN-swap state — a new base layout invalidates the
        // savedLayout snapshot taken when FN was entered. Without this, a
        // restoreFromFn() after a layout change would re-paint the OLD rows.
        fnMode = false
        savedLayout = null
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
            "CTL", "ALT", "SFT" -> {
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
     * Set the visual state of a specific key across all rows.
     * Delegates to [KeyboardRowView.setKeyState] for each row that contains
     * the key. Used to make the PREFIX key green when a multiplexer is active
     * and dimmed/disabled when none is detected.
     *
     * @param keyId       The key's ID (e.g. "PREFIX")
     * @param active      true  → solid green fill (latch armed)
     *                    false → default or accent outline depending on [accentColor]
     * @param enabled     true  → key is clickable
     *                    false → key is dimmed and non-interactive
     * @param accentColor non-zero → coloured outline only (mux detected, not armed);
     *                    0 → default grey outline
     */
    fun setKeyState(keyId: String, active: Boolean, enabled: Boolean = true, accentColor: Int = 0) {
        keyboardRows.forEach { it.setKeyState(keyId, active, enabled, accentColor) }
    }

    /**
     * Update the visible label of a key by ID across all rows.
     * Used by the PREFIX key to show the active prefix notation (e.g. "^B")
     * when a multiplexer is detected, and "PRE" when none is active.
     */
    fun setKeyLabel(keyId: String, label: String) {
        keyboardRows.forEach { it.setKeyLabel(keyId, label) }
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
            KeyboardKey("F1", "F1", "OP", KeyboardKey.KeyCategory.FUNCTION),
            KeyboardKey("F2", "F2", "OQ", KeyboardKey.KeyCategory.FUNCTION),
            KeyboardKey("F3", "F3", "OR", KeyboardKey.KeyCategory.FUNCTION),
            KeyboardKey("F4", "F4", "OS", KeyboardKey.KeyCategory.FUNCTION),
            KeyboardKey("F5", "F5", "[15~", KeyboardKey.KeyCategory.FUNCTION),
            KeyboardKey("F6", "F6", "[17~", KeyboardKey.KeyCategory.FUNCTION),
        )
        val fnRow2 = listOf(
            KeyboardKey("F7", "F7", "[18~", KeyboardKey.KeyCategory.FUNCTION),
            KeyboardKey("F8", "F8", "[19~", KeyboardKey.KeyCategory.FUNCTION),
            KeyboardKey("F9", "F9", "[20~", KeyboardKey.KeyCategory.FUNCTION),
            KeyboardKey("F10", "F10", "[21~", KeyboardKey.KeyCategory.FUNCTION),
            KeyboardKey("F11", "F11", "[23~", KeyboardKey.KeyCategory.FUNCTION),
            KeyboardKey("F12", "F12", "[24~", KeyboardKey.KeyCategory.FUNCTION),
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
     * All keys use flex-weight sizing (widthMultiplier on [KeyboardKey] drives
     * relative width; 2f = double-wide). Fixed-pixel pinning is no longer
     * used — it conflicted with double-wide anchor keys on small screens.
     */
    private fun pinnedCountForRow(@Suppress("UNUSED_PARAMETER") rowIndex: Int): Int = 0

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

    /** Currently latched modifier ("CTL", "ALT", "SFT") or null. */
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
        /** Maximum rows shown in landscape to preserve terminal vertical space.
         *  Set to 3 to match the default portrait row count — the split keyboard
         *  layout saves horizontal space, not vertical, so 3 rows fit cleanly. */
        const val LANDSCAPE_MAX_ROWS = 3
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
        /** Raised from 2 → 3 so a 10-inch tablet shows 3 rows in portrait,
         *  matching the landscape count and the user's configured default. */
        const val LARGE_TABLET_PORTRAIT_MAX_ROWS = 3
        /** Re-exported here for convenience; matches [KeyboardLayoutManager.CURRENT_DEFAULT_LAYOUT_VERSION]. */
        const val CURRENT_DEFAULT_LAYOUT_VERSION = KeyboardLayoutManager.CURRENT_DEFAULT_LAYOUT_VERSION
        private const val TAG = "MultiRowKeyboardView"

        /**
         * Get default key layouts for N rows.
         *
         * Layout — row 1: CTL(2×) TAB(2×) ALT : / ↑ ↓ ← →
         *          row 2: ENT(2×) ESC(2×) HOME END PGUP PGDN FN
         *          row 3: | \ - ~ _ ` $ * < > 📋 (clipboard menu)
         *          row 4: F1-F6
         *          row 5: F7-F12
         *
         * CTL, TAB, ENT, ESC carry widthMultiplier = 1.5f — wider than a standard
         * symbol key for reliable tapping, without excess blank space around the label.
         */
        fun getDefaultRowLayouts(rowCount: Int): List<List<KeyboardKey>> {
            // Shared building blocks reused across all row counts.
            //
            // CTL, TAB, ENT, ESC at 1.5×: larger touch target than a symbol key,
            // tighter than 2× was so the label fills the box without wasted padding.
            val ctl   = KeyboardKey("CTL",   "CTL",  "", KeyboardKey.KeyCategory.MODIFIER, 1.5f)
            val tab   = KeyboardKey("TAB",   "TAB",  "\t", widthMultiplier = 1.5f)
            val ent   = KeyboardKey("ENTER", "ENT",  "\r", widthMultiplier = 1.5f)
            val esc   = KeyboardKey("ESC",   "ESC",  "", widthMultiplier = 1.5f)
            val alt   = KeyboardKey("ALT",   "ALT",  "", KeyboardKey.KeyCategory.MODIFIER)
            val sft   = KeyboardKey("SFT",   "SFT",  "", KeyboardKey.KeyCategory.MODIFIER)
            val fn    = KeyboardKey("FN",    "FN",   "", KeyboardKey.KeyCategory.MODIFIER)
            val up    = KeyboardKey("UP",    "↑",    "[A", KeyboardKey.KeyCategory.ARROW)
            val down  = KeyboardKey("DOWN",  "↓",    "[B", KeyboardKey.KeyCategory.ARROW)
            val left  = KeyboardKey("LEFT",  "←",    "[D", KeyboardKey.KeyCategory.ARROW)
            val right = KeyboardKey("RIGHT", "→",    "[C", KeyboardKey.KeyCategory.ARROW)
            val colon = KeyboardKey("COLON", ":",    ":",          KeyboardKey.KeyCategory.SYMBOL)
            val slash = KeyboardKey("SLASH", "/",    "/",          KeyboardKey.KeyCategory.SYMBOL)
            val home  = KeyboardKey("HOME",  "HOME", "[H")
            val end   = KeyboardKey("END",   "END",  "[F")
            val pgup  = KeyboardKey("PGUP",  "PGUP", "[5~")
            val pgdn  = KeyboardKey("PGDN",  "PGDN", "[6~")
            val clip   = KeyboardKey("CLIPBOARD", "📋", "", KeyboardKey.KeyCategory.ACTION)
            // PREFIX(2×): sends the current multiplexer prefix (C-b / C-a / C-g).
            // Placed at the start of row3 so it sits directly under ENT on row2,
            // matching the user's "pinned left under ENT" spec.
            val prefix = KeyboardKey("PREFIX", "PRE", "", KeyboardKey.KeyCategory.ACTION, 2f)

            // Row 1 (all layouts): CTL(1.5×) TAB(1.5×) ALT SFT : / ↑ ↓ ← →
            val row1 = listOf(ctl, tab, alt, sft, colon, slash, up, down, left, right)

            // Row 2 (layouts ≥ 2): ENT(2×) ESC(2×) HOME END PGUP PGDN FN
            val row2 = listOf(ent, esc, home, end, pgup, pgdn, fn)

            // Row 3 (layouts ≥ 3): PREFIX(2×) then shell/vim symbols + clipboard.
            // PREFIX is 2× wide to match ENT directly above it; the remaining
            // symbols fill the rest of the row at standard width.
            val row3 = listOf(
                prefix,
                KeyboardKey("PIPE",       "|",  "|",  KeyboardKey.KeyCategory.SYMBOL),
                KeyboardKey("BACKSLASH",  "\\", "\\", KeyboardKey.KeyCategory.SYMBOL),
                KeyboardKey("MINUS",      "-",  "-",  KeyboardKey.KeyCategory.SYMBOL),
                KeyboardKey("TILDE",      "~",  "~",  KeyboardKey.KeyCategory.SYMBOL),
                KeyboardKey("UNDERSCORE", "_",  "_",  KeyboardKey.KeyCategory.SYMBOL),
                KeyboardKey("BACKTICK",   "`",  "`",  KeyboardKey.KeyCategory.SYMBOL),
                KeyboardKey("DOLLAR",     "$",  "$",  KeyboardKey.KeyCategory.SYMBOL),
                KeyboardKey("STAR",       "*",  "*",  KeyboardKey.KeyCategory.SYMBOL),
                KeyboardKey("LT",         "<",  "<",  KeyboardKey.KeyCategory.SYMBOL),
                KeyboardKey("GT",         ">",  ">",  KeyboardKey.KeyCategory.SYMBOL),
                clip
            )

            // Row 4 (layouts ≥ 4): F1-F6
            val row4 = listOf(
                KeyboardKey("F1",  "F1",  "OP",   KeyboardKey.KeyCategory.FUNCTION),
                KeyboardKey("F2",  "F2",  "OQ",   KeyboardKey.KeyCategory.FUNCTION),
                KeyboardKey("F3",  "F3",  "OR",   KeyboardKey.KeyCategory.FUNCTION),
                KeyboardKey("F4",  "F4",  "OS",   KeyboardKey.KeyCategory.FUNCTION),
                KeyboardKey("F5",  "F5",  "[15~", KeyboardKey.KeyCategory.FUNCTION),
                KeyboardKey("F6",  "F6",  "[17~", KeyboardKey.KeyCategory.FUNCTION)
            )

            // Row 5 (layout 5 only): F7-F12
            val row5 = listOf(
                KeyboardKey("F7",  "F7",  "[18~", KeyboardKey.KeyCategory.FUNCTION),
                KeyboardKey("F8",  "F8",  "[19~", KeyboardKey.KeyCategory.FUNCTION),
                KeyboardKey("F9",  "F9",  "[20~", KeyboardKey.KeyCategory.FUNCTION),
                KeyboardKey("F10", "F10", "[21~", KeyboardKey.KeyCategory.FUNCTION),
                KeyboardKey("F11", "F11", "[23~", KeyboardKey.KeyCategory.FUNCTION),
                KeyboardKey("F12", "F12", "[24~", KeyboardKey.KeyCategory.FUNCTION)
            )

            return when (rowCount) {
                1    -> listOf(row1)
                2    -> listOf(row1, row2)
                3    -> listOf(row1, row2, row3)
                4    -> listOf(row1, row2, row3, row4)
                5    -> listOf(row1, row2, row3, row4, row5)
                else -> listOf(emptyList())
            }
        }
    }
}
