package io.github.tabssh.terminal.gestures

/**
 * Maps gesture patterns to terminal multiplexer commands (tmux/screen)
 * Provides quick shortcuts for common tmux and screen operations
 */
object GestureCommandMapper {
    
    /**
     * Terminal multiplexer type
     */
    enum class MultiplexerType {
        TMUX,
        SCREEN,
        NONE
    }
    
    /**
     * Gesture pattern types
     */
    enum class GestureType {
        TWO_FINGER_SWIPE_RIGHT,
        TWO_FINGER_SWIPE_LEFT,
        TWO_FINGER_SWIPE_DOWN,
        TWO_FINGER_SWIPE_UP,
        THREE_FINGER_SWIPE_RIGHT,
        THREE_FINGER_SWIPE_LEFT,
        THREE_FINGER_SWIPE_DOWN,
        THREE_FINGER_SWIPE_UP,
        PINCH_IN,
        PINCH_OUT
    }
    
    /**
     * Get the command sequence for a specific gesture and multiplexer
     */
    fun getCommand(gestureType: GestureType, multiplexerType: MultiplexerType): ByteArray? {
        return when (multiplexerType) {
            MultiplexerType.TMUX -> getTmuxCommand(gestureType)
            MultiplexerType.SCREEN -> getScreenCommand(gestureType)
            MultiplexerType.NONE -> null
        }
    }
    
    /**
     * Get tmux command sequence for gesture
     * tmux uses Ctrl+B as prefix
     */
    private fun getTmuxCommand(gestureType: GestureType): ByteArray? {
        val CTRL_B = 0x02.toByte()
        
        return when (gestureType) {
            // Window navigation
            GestureType.TWO_FINGER_SWIPE_RIGHT -> byteArrayOf(CTRL_B, 'n'.code.toByte()) // next window
            GestureType.TWO_FINGER_SWIPE_LEFT -> byteArrayOf(CTRL_B, 'p'.code.toByte()) // previous window
            GestureType.TWO_FINGER_SWIPE_DOWN -> byteArrayOf(CTRL_B, 'c'.code.toByte()) // create window
            GestureType.TWO_FINGER_SWIPE_UP -> byteArrayOf(CTRL_B, 'w'.code.toByte()) // list windows
            
            // Pane operations
            GestureType.THREE_FINGER_SWIPE_RIGHT -> byteArrayOf(CTRL_B, 'o'.code.toByte()) // next pane
            GestureType.THREE_FINGER_SWIPE_LEFT -> byteArrayOf(CTRL_B, ';'.code.toByte()) // last pane
            GestureType.THREE_FINGER_SWIPE_DOWN -> byteArrayOf(CTRL_B, '"'.code.toByte()) // split horizontal
            GestureType.THREE_FINGER_SWIPE_UP -> byteArrayOf(CTRL_B, '%'.code.toByte()) // split vertical
            
            // Zoom/detach
            GestureType.PINCH_IN -> byteArrayOf(CTRL_B, 'z'.code.toByte()) // zoom pane
            GestureType.PINCH_OUT -> byteArrayOf(CTRL_B, 'd'.code.toByte()) // detach
        }
    }
    
    /**
     * Get screen command sequence for gesture
     * screen uses Ctrl+A as prefix
     */
    private fun getScreenCommand(gestureType: GestureType): ByteArray? {
        val CTRL_A = 0x01.toByte()
        
        return when (gestureType) {
            // Window navigation
            GestureType.THREE_FINGER_SWIPE_RIGHT -> byteArrayOf(CTRL_A, 'n'.code.toByte()) // next window
            GestureType.THREE_FINGER_SWIPE_LEFT -> byteArrayOf(CTRL_A, 'p'.code.toByte()) // previous window
            GestureType.THREE_FINGER_SWIPE_DOWN -> byteArrayOf(CTRL_A, 'c'.code.toByte()) // create window
            GestureType.THREE_FINGER_SWIPE_UP -> byteArrayOf(CTRL_A, 'w'.code.toByte()) // list windows
            
            // Split operations
            GestureType.TWO_FINGER_SWIPE_DOWN -> byteArrayOf(CTRL_A, 'S'.code.toByte()) // split horizontal
            GestureType.TWO_FINGER_SWIPE_RIGHT -> byteArrayOf(CTRL_A, '\t'.code.toByte()) // next region
            GestureType.TWO_FINGER_SWIPE_LEFT -> byteArrayOf(CTRL_A, '\t'.code.toByte()) // next region (same)
            
            // Detach
            GestureType.PINCH_OUT -> byteArrayOf(CTRL_A, 'd'.code.toByte()) // detach
            
            // Not mapped
            GestureType.TWO_FINGER_SWIPE_UP,
            GestureType.PINCH_IN -> null
        }
    }
    
    /**
     * Get human-readable description of gesture command
     */
    fun getDescription(gestureType: GestureType, multiplexerType: MultiplexerType): String {
        return when (multiplexerType) {
            MultiplexerType.TMUX -> getTmuxDescription(gestureType)
            MultiplexerType.SCREEN -> getScreenDescription(gestureType)
            MultiplexerType.NONE -> "Gesture disabled"
        }
    }
    
    private fun getTmuxDescription(gestureType: GestureType): String {
        return when (gestureType) {
            GestureType.TWO_FINGER_SWIPE_RIGHT -> "tmux: Next window (Ctrl+B n)"
            GestureType.TWO_FINGER_SWIPE_LEFT -> "tmux: Previous window (Ctrl+B p)"
            GestureType.TWO_FINGER_SWIPE_DOWN -> "tmux: Create window (Ctrl+B c)"
            GestureType.TWO_FINGER_SWIPE_UP -> "tmux: List windows (Ctrl+B w)"
            GestureType.THREE_FINGER_SWIPE_RIGHT -> "tmux: Next pane (Ctrl+B o)"
            GestureType.THREE_FINGER_SWIPE_LEFT -> "tmux: Last pane (Ctrl+B ;)"
            GestureType.THREE_FINGER_SWIPE_DOWN -> "tmux: Split horizontal (Ctrl+B \")"
            GestureType.THREE_FINGER_SWIPE_UP -> "tmux: Split vertical (Ctrl+B %)"
            GestureType.PINCH_IN -> "tmux: Zoom pane (Ctrl+B z)"
            GestureType.PINCH_OUT -> "tmux: Detach (Ctrl+B d)"
        }
    }
    
    private fun getScreenDescription(gestureType: GestureType): String {
        return when (gestureType) {
            GestureType.THREE_FINGER_SWIPE_RIGHT -> "screen: Next window (Ctrl+A n)"
            GestureType.THREE_FINGER_SWIPE_LEFT -> "screen: Previous window (Ctrl+A p)"
            GestureType.THREE_FINGER_SWIPE_DOWN -> "screen: Create window (Ctrl+A c)"
            GestureType.THREE_FINGER_SWIPE_UP -> "screen: List windows (Ctrl+A w)"
            GestureType.TWO_FINGER_SWIPE_DOWN -> "screen: Split horizontal (Ctrl+A S)"
            GestureType.TWO_FINGER_SWIPE_RIGHT -> "screen: Next region (Ctrl+A Tab)"
            GestureType.TWO_FINGER_SWIPE_LEFT -> "screen: Next region (Ctrl+A Tab)"
            GestureType.PINCH_OUT -> "screen: Detach (Ctrl+A d)"
            GestureType.TWO_FINGER_SWIPE_UP,
            GestureType.PINCH_IN -> "Not mapped for screen"
        }
    }
}
