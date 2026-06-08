package io.github.tabssh.ui.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import io.github.tabssh.R

/**
 * Single row of the multi-row keyboard.
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
        private const val KEY_MARGIN_DP = 4
        /** Fixed width (dp) for pinned anchor keys so they never shift position. */
        private const val KEY_PINNED_WIDTH_DP = 52
    }

    private val keyContainer: LinearLayout
    private var keys: List<KeyboardKey> = emptyList()
    private var pinnedCount = 0
    private var onKeyClickListener: ((KeyboardKey) -> Unit)? = null
    private var onToggleClickListener: (() -> Unit)? = null

    /** Buttons keyed by modifier id (CTL/ALT/FN) so the parent can highlight. */
    private val modifierButtons = mutableMapOf<String, KeyButton>()

    /** All key buttons by key id — used by [setKeyState] for targeted styling. */
    private val keyButtonMap = mutableMapOf<String, KeyButton>()

    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true

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

    fun getKeys(): List<KeyboardKey> = keys.toList()

    /**
     * Update modifier key visual state. Active modifier gets a solid green
     * fill with dark text so the active state is unmistakable at a glance —
     * a subtle alpha change is easy to miss on a bright screen.
     */
    fun highlightModifier(activeId: String?) {
        modifierButtons.forEach { (id, btn) ->
            val active = id == activeId
            btn.setActive(active)
            btn.alpha = if (active) 1.0f else 0.7f
        }
    }

    /**
     * Set the visual state of a key by ID:
     *  - [active] = true → accent colour (green), full alpha — multiplexer attached
     *  - [active] = false + [enabled] = false → heavily dimmed — no multiplexer
     *  - [active] = false + [enabled] = true → default styling
     * Used by the PREFIX key to reflect live multiplexer detection state.
     */
    fun setKeyState(keyId: String, active: Boolean, enabled: Boolean) {
        val btn = keyButtonMap[keyId] ?: return
        btn.isEnabled = enabled
        // Use the same green-fill active style as modifier keys for consistency.
        btn.setActive(active)
        if (!active) btn.setHighlight(0)   // reset any stale accent
        btn.alpha = when {
            active  -> 1.0f
            enabled -> 0.7f
            else    -> 0.25f   // Very dim: "nothing to send"
        }
    }

    /**
     * Rebuild key buttons synchronously.
     *
     * [KeyButton] extends [View] via the single-argument View(Context) constructor,
     * which does NOT call obtainStyledAttributes / nativeApplyStyle.  On the emulator
     * every other View subclass (AppCompatButton, MaterialButton, TextView…) hits a
     * JNI-backed theme attribute resolution that takes ~200 ms per instance; with 27
     * keys that blocks the main thread for >5 s and triggers both the TabSSH
     * ANR watchdog and the system input-dispatch timeout.
     *
     * KeyButton draws text + an outlined rounded-rect entirely on Canvas, so all 27
     * buttons can be created in a single pass in well under 1 ms total on any device.
     */
    private fun rebuildKeys() {
        keyContainer.removeAllViews()
        modifierButtons.clear()
        keyButtonMap.clear()
        keys.forEachIndexed { index, key ->
            val btn = createKeyButton(key, index < pinnedCount)
            keyContainer.addView(btn)
            keyButtonMap[key.id] = btn
            if (key.category == KeyboardKey.KeyCategory.MODIFIER) {
                modifierButtons[key.id] = btn
            }
        }
    }

    private fun createKeyButton(key: KeyboardKey, pinned: Boolean): KeyButton {
        val btn = KeyButton(context, key.label)

        val params = if (pinned) {
            // Pinned keys use a fixed pixel width scaled by widthMultiplier so
            // a 2× key (CTL/TAB/ENT/ESC) gets twice the anchor width.
            val w = (KEY_PINNED_WIDTH_DP * key.widthMultiplier).toInt()
            LinearLayout.LayoutParams(dpToPx(w), LinearLayout.LayoutParams.MATCH_PARENT, 0f)
        } else {
            // Flex keys: weight = widthMultiplier (1f normal, 2f double-wide).
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, key.widthMultiplier)
        }
        params.marginEnd = dpToPx(KEY_MARGIN_DP)
        btn.layoutParams = params

        when (key.category) {
            KeyboardKey.KeyCategory.MODIFIER -> btn.alpha = 0.7f
            KeyboardKey.KeyCategory.FUNCTION -> btn.alpha = 0.85f
            KeyboardKey.KeyCategory.ACTION   -> btn.alpha = 0.95f
            else                             -> btn.alpha = 1.0f
        }

        btn.setOnClickListener { handleKeyClick(key) }
        return btn
    }

    private fun handleKeyClick(key: KeyboardKey) {
        when {
            key.category == KeyboardKey.KeyCategory.ACTION && key.id == "TOGGLE" ->
                onToggleClickListener?.invoke()
            else -> onKeyClickListener?.invoke(key)
        }
    }

    fun setOnKeyClickListener(listener: (KeyboardKey) -> Unit) {
        this.onKeyClickListener = listener
    }

    fun setOnToggleClickListener(listener: () -> Unit) {
        this.onToggleClickListener = listener
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    // ── Lightweight key button ────────────────────────────────────────────────

    /**
     * Minimal key button drawn entirely on Canvas.
     *
     * Extends View via the single-argument View(Context) constructor, which is
     * the only constructor that does NOT call obtainStyledAttributes.  All other
     * constructors (View(Context, AttributeSet, int) etc.) call nativeApplyStyle
     * regardless of whether attrs or defStyleAttr are null/0 — that JNI call is
     * ~200 ms per instance on the emulator's software renderer.
     *
     * Drawing: rounded-rect outline (1 dp stroke, 4 dp corner) + centred label
     * text.  Pressed state adds a translucent white fill.  Everything is drawn
     * with pre-allocated Paint objects so onDraw never allocates.
     */
    private class KeyButton(context: Context, label: String) : View(context) {

        var label: String = label
            set(v) { field = v; invalidate() }

        private val density = context.resources.displayMetrics.density
        private val scaledDensity = context.resources.displayMetrics.scaledDensity

        // Default stroke/text colors — overridden when a highlight or active state is set.
        private val defaultStrokeColor = 0xFF666666.toInt()
        private val defaultTextColor   = 0xFFEEEEEE.toInt()
        // Active (latched) fill colours: Material Green 500 fill, dark green text.
        private val activeFillColor    = 0xFF4CAF50.toInt()
        private val activeTextColor    = 0xFF1B5E20.toInt()   // dark green — readable on green fill

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = defaultStrokeColor
            strokeWidth = density
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = activeFillColor
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = defaultTextColor
            textAlign = Paint.Align.CENTER
            textSize = 13f * scaledDensity
        }
        private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x33FFFFFF
        }
        private val rectF = RectF()
        private val cornerR = 4f * density
        private var isActive = false   // true = latched modifier / active PREFIX

        init {
            isClickable = true
            isFocusable = true
        }

        /**
         * Set the "latched" state for modifier keys (CTL, ALT, etc.) and the
         * PREFIX key when a multiplexer is active.
         *
         * Active = solid green fill + dark green text.  The contrast satisfies
         * WCAG AA (green on dark-green is ~4.7:1).
         * Inactive = default outline style.
         */
        fun setActive(active: Boolean) {
            isActive = active
            strokePaint.color = if (active) activeFillColor else defaultStrokeColor
            textPaint.color   = if (active) activeTextColor else defaultTextColor
            invalidate()
        }

        /**
         * Apply an accent colour to border and text without filling the background.
         * Used by [KeyboardRowView.setKeyState] for the PREFIX "multiplexer active"
         * visual state when a distinct fill isn't desired (the green outline + text
         * is already distinctive).
         * Pass 0 to reset to the default grey/white palette.
         */
        fun setHighlight(color: Int) {
            if (isActive) return   // active fill state wins
            strokePaint.color = if (color != 0) color else defaultStrokeColor
            textPaint.color   = if (color != 0) color else defaultTextColor
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val inset = strokePaint.strokeWidth / 2f
            rectF.set(inset, inset, width - inset, height - inset)

            // Solid fill for latched modifier / active multiplexer state.
            if (isActive) {
                fillPaint.color = activeFillColor
                canvas.drawRoundRect(rectF, cornerR, cornerR, fillPaint)
            }
            if (isPressed) canvas.drawRoundRect(rectF, cornerR, cornerR, pressPaint)
            canvas.drawRoundRect(rectF, cornerR, cornerR, strokePaint)

            val cx = width / 2f
            val cy = height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(label, cx, cy, textPaint)
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }
    }
}
