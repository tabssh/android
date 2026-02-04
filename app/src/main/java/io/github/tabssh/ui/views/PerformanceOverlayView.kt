package io.github.tabssh.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import io.github.tabssh.R
import io.github.tabssh.utils.performance.PerformanceMetrics
import kotlin.math.roundToInt

/**
 * Floating overlay view to display real-time performance metrics
 * Shows CPU, Memory, Battery, and Network stats
 * Draggable and dismissible
 */
class PerformanceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val cardView: CardView
    private val textCpu: TextView
    private val textMemory: TextView
    private val textBattery: TextView
    private val textNetwork: TextView

    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false

    init {
        // Inflate layout
        LayoutInflater.from(context).inflate(R.layout.layout_performance_overlay, this, true)

        // Find views
        cardView = findViewById(R.id.card_performance_overlay)
        textCpu = findViewById(R.id.text_cpu)
        textMemory = findViewById(R.id.text_memory)
        textBattery = findViewById(R.id.text_battery)
        textNetwork = findViewById(R.id.text_network)

        // Setup dismiss on long press
        cardView.setOnLongClickListener {
            visibility = GONE
            true
        }

        // Initial position (top-right corner)
        val layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            setMargins(0, 100, 16, 0)
        }
        setLayoutParams(layoutParams)
    }

    /**
     * Update displayed metrics
     */
    fun updateMetrics(metrics: PerformanceMetrics) {
        textCpu.text = "CPU: ${metrics.cpuUsagePercent}%"
        textMemory.text = "MEM: ${metrics.memoryUsedMB}MB"
        
        val batteryIcon = if (metrics.isCharging) "⚡" else "🔋"
        textBattery.text = "$batteryIcon ${metrics.batteryLevel}%"
        
        val networkKBs = ((metrics.networkBytesReceived + metrics.networkBytesSent) / 1024.0).roundToInt()
        textNetwork.text = "NET: ${networkKBs}KB/s"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - lastX
                val deltaY = event.rawY - lastY

                // Detect drag threshold
                if (!isDragging && (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10)) {
                    isDragging = true
                }

                if (isDragging) {
                    // Update position
                    translationX += deltaX
                    translationY += deltaY

                    lastX = event.rawX
                    lastY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) {
                    // Treat as click - do nothing
                }
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
