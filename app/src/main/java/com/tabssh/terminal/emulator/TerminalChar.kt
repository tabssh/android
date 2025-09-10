package io.github.tabssh.terminal.emulator

/**
 * Represents a single character in the terminal with its attributes
 */
data class TerminalChar(
    val char: Char,
    val fgColor: Int,
    val bgColor: Int,
    val isBold: Boolean = false,
    val isUnderline: Boolean = false,
    val isReverse: Boolean = false,
    val isBlink: Boolean = false,
    val isItalic: Boolean = false,
    val isStrikethrough: Boolean = false,
    val isDoubleWidth: Boolean = false,
    val isDoubleHeight: Boolean = false
) {
    companion object {
        // ANSI color constants (0-15)
        const val COLOR_BLACK = 0
        const val COLOR_RED = 1
        const val COLOR_GREEN = 2
        const val COLOR_YELLOW = 3
        const val COLOR_BLUE = 4
        const val COLOR_MAGENTA = 5
        const val COLOR_CYAN = 6
        const val COLOR_WHITE = 7
        const val COLOR_BRIGHT_BLACK = 8
        const val COLOR_BRIGHT_RED = 9
        const val COLOR_BRIGHT_GREEN = 10
        const val COLOR_BRIGHT_YELLOW = 11
        const val COLOR_BRIGHT_BLUE = 12
        const val COLOR_BRIGHT_MAGENTA = 13
        const val COLOR_BRIGHT_CYAN = 14
        const val COLOR_BRIGHT_WHITE = 15
        
        // Default color values
        const val DEFAULT_FG = COLOR_WHITE
        const val DEFAULT_BG = COLOR_BLACK
        
        /**
         * Create an empty character (space) with default attributes
         */
        fun empty(): TerminalChar {
            return TerminalChar(' ', DEFAULT_FG, DEFAULT_BG)
        }
        
        /**
         * Create a character with only foreground color
         */
        fun withFg(char: Char, fgColor: Int): TerminalChar {
            return TerminalChar(char, fgColor, DEFAULT_BG)
        }
        
        /**
         * Create a character with foreground and background colors
         */
        fun withColors(char: Char, fgColor: Int, bgColor: Int): TerminalChar {
            return TerminalChar(char, fgColor, bgColor)
        }
    }
    
    /**
     * Check if the character has any text formatting attributes
     */
    fun hasFormatting(): Boolean {
        return isBold || isUnderline || isReverse || isBlink || isItalic || isStrikethrough
    }
    
    /**
     * Check if the character has non-default colors
     */
    fun hasCustomColors(): Boolean {
        return fgColor != DEFAULT_FG || bgColor != DEFAULT_BG
    }
    
    /**
     * Get the effective foreground color (considering reverse video)
     */
    fun getEffectiveFgColor(): Int {
        return if (isReverse) bgColor else fgColor
    }
    
    /**
     * Get the effective background color (considering reverse video)
     */
    fun getEffectiveBgColor(): Int {
        return if (isReverse) fgColor else bgColor
    }
    
    /**
     * Check if this character is printable
     */
    fun isPrintable(): Boolean {
        return char >= ' ' && char != '\u007F' // Exclude DEL
    }
    
    /**
     * Check if this character is whitespace
     */
    fun isWhitespace(): Boolean {
        return char.isWhitespace()
    }
    
    /**
     * Create a copy with modified attributes
     */
    fun withAttributes(
        bold: Boolean = this.isBold,
        underline: Boolean = this.isUnderline,
        reverse: Boolean = this.isReverse,
        blink: Boolean = this.isBlink,
        italic: Boolean = this.isItalic,
        strikethrough: Boolean = this.isStrikethrough
    ): TerminalChar {
        return copy(
            isBold = bold,
            isUnderline = underline,
            isReverse = reverse,
            isBlink = blink,
            isItalic = italic,
            isStrikethrough = strikethrough
        )
    }
    
    /**
     * Create a copy with modified colors
     */
    fun withColors(newFgColor: Int = this.fgColor, newBgColor: Int = this.bgColor): TerminalChar {
        return copy(fgColor = newFgColor, bgColor = newBgColor)
    }
    
    /**
     * Create a copy with a different character but same attributes
     */
    fun withChar(newChar: Char): TerminalChar {
        return copy(char = newChar)
    }
    
    /**
     * Reset all formatting to defaults while keeping the character
     */
    fun resetFormatting(): TerminalChar {
        return copy(
            fgColor = DEFAULT_FG,
            bgColor = DEFAULT_BG,
            isBold = false,
            isUnderline = false,
            isReverse = false,
            isBlink = false,
            isItalic = false,
            isStrikethrough = false,
            isDoubleWidth = false,
            isDoubleHeight = false
        )
    }
    
    /**
     * Convert to a simple character for text operations
     */
    fun toChar(): Char = char
    
    /**
     * Convert to string representation
     */
    override fun toString(): String {
        return if (hasFormatting() || hasCustomColors()) {
            "TerminalChar('$char', fg=$fgColor, bg=$bgColor, bold=$isBold, ul=$isUnderline, rev=$isReverse)"
        } else {
            "TerminalChar('$char')"
        }
    }
    
    /**
     * Check if this character is visually identical to another
     */
    fun isVisuallyIdentical(other: TerminalChar): Boolean {
        return char == other.char &&
                getEffectiveFgColor() == other.getEffectiveFgColor() &&
                getEffectiveBgColor() == other.getEffectiveBgColor() &&
                isBold == other.isBold &&
                isUnderline == other.isUnderline &&
                isItalic == other.isItalic &&
                isStrikethrough == other.isStrikethrough &&
                isBlink == other.isBlink &&
                isDoubleWidth == other.isDoubleWidth &&
                isDoubleHeight == other.isDoubleHeight
    }
    
    /**
     * Get a hash code for visual properties (for caching)
     */
    fun visualHashCode(): Int {
        var result = char.code
        result = 31 * result + getEffectiveFgColor()
        result = 31 * result + getEffectiveBgColor()
        result = 31 * result + if (isBold) 1 else 0
        result = 31 * result + if (isUnderline) 1 else 0
        result = 31 * result + if (isItalic) 1 else 0
        result = 31 * result + if (isStrikethrough) 1 else 0
        result = 31 * result + if (isBlink) 1 else 0
        result = 31 * result + if (isDoubleWidth) 1 else 0
        result = 31 * result + if (isDoubleHeight) 1 else 0
        return result
    }
}