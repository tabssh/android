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
        ZELLIJ,
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
     * @param gestureType The gesture that was detected
     * @param multiplexerType The multiplexer type (tmux, screen, zellij, or none)
     * @param customPrefix Optional custom prefix notation (e.g., "C-a", "C-Space")
     * @return Byte sequence to send, or null if not mapped
     */
    fun getCommand(
        gestureType: GestureType, 
        multiplexerType: MultiplexerType,
        customPrefix: String? = null
    ): ByteArray? {
        // If custom prefix specified, use it with multiplexer commands
        val prefix = if (!customPrefix.isNullOrEmpty()) {
            PrefixParser.parse(customPrefix) ?: getDefaultPrefix(multiplexerType)
        } else {
            getDefaultPrefix(multiplexerType)
        }
        
        if (prefix.isEmpty()) {
            return null // NONE multiplexer
        }
        
        return when (multiplexerType) {
            MultiplexerType.TMUX -> getTmuxCommand(gestureType, prefix)
            MultiplexerType.SCREEN -> getScreenCommand(gestureType, prefix)
            MultiplexerType.ZELLIJ -> getZellijCommand(gestureType, prefix)
            MultiplexerType.NONE -> null
        }
    }
    
    /**
     * Get default prefix for multiplexer type
     */
    private fun getDefaultPrefix(multiplexerType: MultiplexerType): ByteArray {
        return when (multiplexerType) {
            MultiplexerType.TMUX -> byteArrayOf(0x02.toByte())    // Ctrl+B
            MultiplexerType.SCREEN -> byteArrayOf(0x01.toByte())  // Ctrl+A
            MultiplexerType.ZELLIJ -> byteArrayOf(0x07.toByte())  // Ctrl+G
            MultiplexerType.NONE -> byteArrayOf()
        }
    }
    
    /**
     * Get tmux command sequence for gesture
     * @param gestureType The gesture detected
     * @param prefix The prefix key(s) to use (default or custom)
     */
    private fun getTmuxCommand(gestureType: GestureType, prefix: ByteArray): ByteArray? {
        return when (gestureType) {
            // Window navigation
            GestureType.TWO_FINGER_SWIPE_RIGHT -> prefix + byteArrayOf('n'.code.toByte()) // next window
            GestureType.TWO_FINGER_SWIPE_LEFT -> prefix + byteArrayOf('p'.code.toByte()) // previous window
            GestureType.TWO_FINGER_SWIPE_DOWN -> prefix + byteArrayOf('c'.code.toByte()) // create window
            GestureType.TWO_FINGER_SWIPE_UP -> prefix + byteArrayOf('w'.code.toByte()) // list windows
            
            // Pane operations
            GestureType.THREE_FINGER_SWIPE_RIGHT -> prefix + byteArrayOf('o'.code.toByte()) // next pane
            GestureType.THREE_FINGER_SWIPE_LEFT -> prefix + byteArrayOf(';'.code.toByte()) // last pane
            GestureType.THREE_FINGER_SWIPE_DOWN -> prefix + byteArrayOf('"'.code.toByte()) // split horizontal
            GestureType.THREE_FINGER_SWIPE_UP -> prefix + byteArrayOf('%'.code.toByte()) // split vertical
            
            // Zoom/detach
            GestureType.PINCH_IN -> prefix + byteArrayOf('z'.code.toByte()) // zoom pane
            GestureType.PINCH_OUT -> prefix + byteArrayOf('d'.code.toByte()) // detach
        }
    }
    
    /**
     * Get screen command sequence for gesture
     * @param gestureType The gesture detected
     * @param prefix The prefix key(s) to use (default or custom)
     */
    private fun getScreenCommand(gestureType: GestureType, prefix: ByteArray): ByteArray? {
        return when (gestureType) {
            // Window navigation
            GestureType.THREE_FINGER_SWIPE_RIGHT -> prefix + byteArrayOf('n'.code.toByte()) // next window
            GestureType.THREE_FINGER_SWIPE_LEFT -> prefix + byteArrayOf('p'.code.toByte()) // previous window
            GestureType.THREE_FINGER_SWIPE_DOWN -> prefix + byteArrayOf('c'.code.toByte()) // create window
            GestureType.THREE_FINGER_SWIPE_UP -> prefix + byteArrayOf('w'.code.toByte()) // list windows
            
            // Split operations
            GestureType.TWO_FINGER_SWIPE_DOWN -> prefix + byteArrayOf('S'.code.toByte()) // split horizontal
            GestureType.TWO_FINGER_SWIPE_RIGHT -> prefix + byteArrayOf('\t'.code.toByte()) // next region
            GestureType.TWO_FINGER_SWIPE_LEFT -> prefix + byteArrayOf('\t'.code.toByte()) // next region (same)
            
            // Detach
            GestureType.PINCH_OUT -> prefix + byteArrayOf('d'.code.toByte()) // detach
            
            // Not mapped
            GestureType.TWO_FINGER_SWIPE_UP,
            GestureType.PINCH_IN -> null
        }
    }
    
    /**
     * Get zellij command sequence for gesture
     * @param gestureType The gesture detected
     * @param prefix The prefix key(s) to use (default or custom)
     */
    private fun getZellijCommand(gestureType: GestureType, prefix: ByteArray): ByteArray? {
        return when (gestureType) {
            // Tab navigation
            GestureType.TWO_FINGER_SWIPE_RIGHT -> prefix + byteArrayOf('n'.code.toByte()) // next tab
            GestureType.TWO_FINGER_SWIPE_LEFT -> prefix + byteArrayOf('p'.code.toByte()) // previous tab
            GestureType.TWO_FINGER_SWIPE_DOWN -> prefix + byteArrayOf('t'.code.toByte()) // new tab
            GestureType.TWO_FINGER_SWIPE_UP -> prefix + byteArrayOf('w'.code.toByte()) // close tab
            
            // Pane operations
            GestureType.THREE_FINGER_SWIPE_RIGHT -> prefix + byteArrayOf('h'.code.toByte()) // next pane
            GestureType.THREE_FINGER_SWIPE_LEFT -> prefix + byteArrayOf('l'.code.toByte()) // previous pane
            GestureType.THREE_FINGER_SWIPE_DOWN -> prefix + byteArrayOf('s'.code.toByte()) // split horizontal
            GestureType.THREE_FINGER_SWIPE_UP -> prefix + byteArrayOf('v'.code.toByte()) // split vertical
            
            // Zoom/quit
            GestureType.PINCH_IN -> prefix + byteArrayOf('z'.code.toByte()) // toggle fullscreen
            GestureType.PINCH_OUT -> prefix + byteArrayOf('q'.code.toByte()) // quit
        }
    }
    
    /**
     * Get human-readable description of gesture command
     */
    fun getDescription(gestureType: GestureType, multiplexerType: MultiplexerType, customPrefix: String? = null): String {
        val prefixDesc = if (!customPrefix.isNullOrEmpty()) {
            PrefixParser.getDescription(customPrefix)
        } else {
            getDefaultPrefixDescription(multiplexerType)
        }
        
        return when (multiplexerType) {
            MultiplexerType.TMUX -> getTmuxDescription(gestureType, prefixDesc)
            MultiplexerType.SCREEN -> getScreenDescription(gestureType, prefixDesc)
            MultiplexerType.ZELLIJ -> getZellijDescription(gestureType, prefixDesc)
            MultiplexerType.NONE -> "Gesture disabled"
        }
    }
    
    private fun getDefaultPrefixDescription(multiplexerType: MultiplexerType): String {
        return when (multiplexerType) {
            MultiplexerType.TMUX -> "Ctrl+B"
            MultiplexerType.SCREEN -> "Ctrl+A"
            MultiplexerType.ZELLIJ -> "Ctrl+G"
            MultiplexerType.NONE -> ""
        }
    }
    
    private fun getTmuxDescription(gestureType: GestureType, prefix: String): String {
        return when (gestureType) {
            GestureType.TWO_FINGER_SWIPE_RIGHT -> "tmux: Next window ($prefix n)"
            GestureType.TWO_FINGER_SWIPE_LEFT -> "tmux: Previous window ($prefix p)"
            GestureType.TWO_FINGER_SWIPE_DOWN -> "tmux: Create window ($prefix c)"
            GestureType.TWO_FINGER_SWIPE_UP -> "tmux: List windows ($prefix w)"
            GestureType.THREE_FINGER_SWIPE_RIGHT -> "tmux: Next pane ($prefix o)"
            GestureType.THREE_FINGER_SWIPE_LEFT -> "tmux: Last pane ($prefix ;)"
            GestureType.THREE_FINGER_SWIPE_DOWN -> "tmux: Split horizontal ($prefix \")"
            GestureType.THREE_FINGER_SWIPE_UP -> "tmux: Split vertical ($prefix %)"
            GestureType.PINCH_IN -> "tmux: Zoom pane ($prefix z)"
            GestureType.PINCH_OUT -> "tmux: Detach ($prefix d)"
        }
    }
    
    private fun getScreenDescription(gestureType: GestureType, prefix: String): String {
        return when (gestureType) {
            GestureType.THREE_FINGER_SWIPE_RIGHT -> "screen: Next window ($prefix n)"
            GestureType.THREE_FINGER_SWIPE_LEFT -> "screen: Previous window ($prefix p)"
            GestureType.THREE_FINGER_SWIPE_DOWN -> "screen: Create window ($prefix c)"
            GestureType.THREE_FINGER_SWIPE_UP -> "screen: List windows ($prefix w)"
            GestureType.TWO_FINGER_SWIPE_DOWN -> "screen: Split horizontal ($prefix S)"
            GestureType.TWO_FINGER_SWIPE_RIGHT -> "screen: Next region ($prefix Tab)"
            GestureType.TWO_FINGER_SWIPE_LEFT -> "screen: Next region ($prefix Tab)"
            GestureType.PINCH_OUT -> "screen: Detach ($prefix d)"
            GestureType.TWO_FINGER_SWIPE_UP,
            GestureType.PINCH_IN -> "Not mapped for screen"
        }
    }
    
    private fun getZellijDescription(gestureType: GestureType, prefix: String): String {
        return when (gestureType) {
            GestureType.TWO_FINGER_SWIPE_RIGHT -> "zellij: Next tab ($prefix n)"
            GestureType.TWO_FINGER_SWIPE_LEFT -> "zellij: Previous tab ($prefix p)"
            GestureType.TWO_FINGER_SWIPE_DOWN -> "zellij: New tab ($prefix t)"
            GestureType.TWO_FINGER_SWIPE_UP -> "zellij: Close tab ($prefix w)"
            GestureType.THREE_FINGER_SWIPE_RIGHT -> "zellij: Next pane ($prefix h)"
            GestureType.THREE_FINGER_SWIPE_LEFT -> "zellij: Previous pane ($prefix l)"
            GestureType.THREE_FINGER_SWIPE_DOWN -> "zellij: Split horizontal ($prefix s)"
            GestureType.THREE_FINGER_SWIPE_UP -> "zellij: Split vertical ($prefix v)"
            GestureType.PINCH_IN -> "zellij: Toggle fullscreen ($prefix z)"
            GestureType.PINCH_OUT -> "zellij: Quit ($prefix q)"
        }
    }
}
