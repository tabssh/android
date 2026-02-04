package io.github.tabssh.terminal.gestures

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import io.github.tabssh.terminal.gestures.GestureCommandMapper.GestureType
import io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType
import io.github.tabssh.utils.logging.Logger
import kotlin.math.abs

/**
 * Handles multi-touch gesture detection for terminal multiplexer shortcuts
 * Detects 2-finger and 3-finger swipes, and pinch gestures
 */
class TerminalGestureHandler(
    context: Context,
    private val onGestureDetected: (GestureType) -> Unit
) {
    
    private val scaleGestureDetector: ScaleGestureDetector
    
    // Gesture detection state
    private var pointerCount = 0
    private var initialX = 0f
    private var initialY = 0f
    private var isGestureInProgress = false
    
    // Thresholds
    private val SWIPE_THRESHOLD = 100 // minimum distance for swipe
    private val SWIPE_VELOCITY_THRESHOLD = 100 // minimum velocity
    private val PINCH_THRESHOLD = 0.2f // scale change threshold for pinch
    
    init {
        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                
                if (scaleFactor < 1.0f - PINCH_THRESHOLD) {
                    // Pinch in
                    if (!isGestureInProgress && pointerCount == 2) {
                        isGestureInProgress = true
                        onGestureDetected(GestureType.PINCH_IN)
                        Logger.d("TerminalGestureHandler", "Pinch in detected")
                        return true
                    }
                } else if (scaleFactor > 1.0f + PINCH_THRESHOLD) {
                    // Pinch out
                    if (!isGestureInProgress && pointerCount == 2) {
                        isGestureInProgress = true
                        onGestureDetected(GestureType.PINCH_OUT)
                        Logger.d("TerminalGestureHandler", "Pinch out detected")
                        return true
                    }
                }
                return false
            }
        })
    }
    
    /**
     * Process touch events to detect gestures
     * Returns true if gesture was detected, false otherwise
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        // Always pass to scale detector
        scaleGestureDetector.onTouchEvent(event)
        
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerCount = event.pointerCount
                
                if (pointerCount >= 2) {
                    // Save initial position (average of all pointers)
                    initialX = 0f
                    initialY = 0f
                    for (i in 0 until pointerCount) {
                        initialX += event.getX(i)
                        initialY += event.getY(i)
                    }
                    initialX /= pointerCount
                    initialY /= pointerCount
                    
                    isGestureInProgress = false
                    Logger.d("TerminalGestureHandler", "Multi-touch started: $pointerCount fingers")
                }
            }
            
            MotionEvent.ACTION_MOVE -> {
                if (pointerCount >= 2 && !isGestureInProgress && event.pointerCount == pointerCount) {
                    // Calculate average current position
                    var currentX = 0f
                    var currentY = 0f
                    for (i in 0 until event.pointerCount) {
                        currentX += event.getX(i)
                        currentY += event.getY(i)
                    }
                    currentX /= event.pointerCount
                    currentY /= event.pointerCount
                    
                    // Calculate delta
                    val deltaX = currentX - initialX
                    val deltaY = currentY - initialY
                    
                    // Check if swipe threshold is met
                    if (abs(deltaX) > SWIPE_THRESHOLD || abs(deltaY) > SWIPE_THRESHOLD) {
                        val gestureType = detectSwipeDirection(deltaX, deltaY, pointerCount)
                        if (gestureType != null) {
                            isGestureInProgress = true
                            onGestureDetected(gestureType)
                            Logger.d("TerminalGestureHandler", "Swipe detected: $gestureType")
                            return true
                        }
                    }
                }
            }
            
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (event.pointerCount == 1) {
                    // Last pointer lifted
                    pointerCount = 0
                    isGestureInProgress = false
                } else {
                    pointerCount = event.pointerCount - 1
                }
            }
        }
        
        return false
    }
    
    /**
     * Detect swipe direction from delta values
     */
    private fun detectSwipeDirection(deltaX: Float, deltaY: Float, fingers: Int): GestureType? {
        val absX = abs(deltaX)
        val absY = abs(deltaY)
        
        return when {
            // Horizontal swipe
            absX > absY && absX > SWIPE_THRESHOLD -> {
                if (deltaX > 0) {
                    // Swipe right
                    when (fingers) {
                        2 -> GestureType.TWO_FINGER_SWIPE_RIGHT
                        3 -> GestureType.THREE_FINGER_SWIPE_RIGHT
                        else -> null
                    }
                } else {
                    // Swipe left
                    when (fingers) {
                        2 -> GestureType.TWO_FINGER_SWIPE_LEFT
                        3 -> GestureType.THREE_FINGER_SWIPE_LEFT
                        else -> null
                    }
                }
            }
            
            // Vertical swipe
            absY > absX && absY > SWIPE_THRESHOLD -> {
                if (deltaY > 0) {
                    // Swipe down
                    when (fingers) {
                        2 -> GestureType.TWO_FINGER_SWIPE_DOWN
                        3 -> GestureType.THREE_FINGER_SWIPE_DOWN
                        else -> null
                    }
                } else {
                    // Swipe up
                    when (fingers) {
                        2 -> GestureType.TWO_FINGER_SWIPE_UP
                        3 -> GestureType.THREE_FINGER_SWIPE_UP
                        else -> null
                    }
                }
            }
            
            else -> null
        }
    }
}
