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
     * Highlight the active modifier (called by parent). Pass null to clear.
     */
    fun highlightModifier(activeId: String?) {
        modifierButtons.forEach { (id, btn) ->
            btn.alpha = if (id == activeId) 1.0f else 0.7f
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
        keys.forEachIndexed { index, key ->
            val btn = createKeyButton(key, index < pinnedCount)
            keyContainer.addView(btn)
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

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF666666.toInt()
            strokeWidth = density
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEEEEEE.toInt()
            textAlign = Paint.Align.CENTER
            textSize = 12f * scaledDensity
        }
        private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x33FFFFFF
        }
        private val rectF = RectF()
        private val cornerR = 4f * density

        init {
            isClickable = true
            isFocusable = true
            // Give touch feedback via state-change invalidate below.
        }

        override fun onDraw(canvas: Canvas) {
            val inset = strokePaint.strokeWidth / 2f
            rectF.set(inset, inset, width - inset, height - inset)

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
