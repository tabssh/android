package io.github.tabssh.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.R as MaterialR

/**
 * Minimal sparkline for live container stats (PLAN.AI.md step 24). Keeps a
 * rolling window of samples and draws a filled line chart using the theme's
 * primary color, so it follows dark/light mode without hardcoded colors.
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val MAX_SAMPLES = 60
    }

    private val samples = ArrayDeque<Float>()
    private val linePath = Path()
    private val fillPath = Path()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = resolveThemeColor(MaterialR.attr.colorPrimary)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // 48/255 ≈ 19% alpha baked into the color — lint's Range check
        // misresolves the Kotlin `alpha = 48` Paint setter as a 0..1 float
        color = ColorUtils.setAlphaComponent(
            resolveThemeColor(MaterialR.attr.colorPrimary), 48
        )
    }

    /** Append a sample and redraw; the window slides after MAX_SAMPLES. */
    fun addSample(value: Float) {
        samples.addLast(if (value.isFinite()) value.coerceAtLeast(0f) else 0f)
        while (samples.size > MAX_SAMPLES) {
            samples.removeFirst()
        }
        invalidate()
    }

    /** Drop all samples (e.g. when the stats stream restarts). */
    fun clear() {
        samples.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.size < 2) return

        val w = width.toFloat()
        val h = height.toFloat()
        val max = (samples.max()).coerceAtLeast(1f)
        val stepX = w / (MAX_SAMPLES - 1).toFloat()

        linePath.reset()
        fillPath.reset()
        samples.forEachIndexed { index, sample ->
            val x = index * stepX
            // 4px padding keeps the stroke inside the view bounds at peaks.
            val y = h - (sample / max) * (h - 8f) - 4f
            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo((samples.size - 1) * stepX, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
    }

    /** Resolve a theme color attribute at runtime (no hardcoded colors). */
    private fun resolveThemeColor(attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }
}
