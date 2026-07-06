package io.github.tabssh.terminal.renderer

import android.graphics.*
import io.github.tabssh.terminal.emulator.TerminalBuffer
import io.github.tabssh.terminal.emulator.TerminalChar

/**
 * Terminal text rendering engine with color and formatting support
 */
class TerminalRenderer(
    private val textPaint: Paint,
    private val backgroundPaint: Paint,
    private val cursorPaint: Paint
) {

    private val defaultColors = intArrayOf(
        Color.BLACK, Color.RED, Color.GREEN, Color.YELLOW,
        Color.BLUE, Color.MAGENTA, Color.CYAN, Color.WHITE,
        Color.GRAY, Color.RED, Color.GREEN, Color.YELLOW,
        Color.BLUE, Color.MAGENTA, Color.CYAN, Color.WHITE
    )

    /**
     * Render terminal buffer to canvas
     */
    fun render(
        canvas: Canvas,
        buffer: TerminalBuffer,
        offsetX: Float,
        offsetY: Float,
        cellWidth: Float,
        cellHeight: Float,
        scrollY: Int
    ) {
        val rows = buffer.getRows()
        val cols = buffer.getCols()

        // Draw background
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), backgroundPaint)

        // Draw text
        for (row in 0 until rows) {
            val y = offsetY + (row * cellHeight) - scrollY
            if (y + cellHeight < 0 || y > canvas.height) continue

            val line = buffer.getLine(row) ?: continue

            for (col in 0 until cols) {
                val x = offsetX + (col * cellWidth)
                val char = line[col]

                // Resolve effective foreground colour from the 16-colour palette
                var fgInt = if (char.fgColor in defaultColors.indices) {
                    defaultColors[char.fgColor]
                } else Color.WHITE

                // Resolve effective background; index 0 means the terminal's base background
                var bgInt = when {
                    char.bgColor == 0 -> backgroundPaint.color
                    char.bgColor in defaultColors.indices -> defaultColors[char.bgColor]
                    else -> Color.BLACK
                }

                // Reverse video (SGR 7): swap foreground and background
                if (char.reverse) {
                    val tempColor = fgInt
                    fgInt = bgInt
                    bgInt = tempColor
                }

                // Draw the cell background whenever it differs from the base background
                // (covers explicit bgColor and reversed cells, including reversed spaces)
                if (bgInt != backgroundPaint.color) {
                    val bgPaint = Paint().apply { color = bgInt }
                    canvas.drawRect(x, y, x + cellWidth, y + cellHeight, bgPaint)
                }

                // Draw character
                if (char.char != ' ') {
                    val charPaint = Paint(textPaint).apply {
                        color = fgInt

                        // Apply formatting
                        if (char.bold) {
                            typeface = Typeface.DEFAULT_BOLD
                        }
                        if (char.underline) {
                            isUnderlineText = true
                        }
                    }

                    canvas.drawText(char.char.toString(), x, y + cellHeight * 0.8f, charPaint)
                }
            }
        }

        // Draw cursor
        val (cursorX, cursorY) = buffer.getCursorPosition()
        val cursorXPos = offsetX + (cursorX * cellWidth)
        val cursorYPos = offsetY + (cursorY * cellHeight) - scrollY

        canvas.drawRect(
            cursorXPos,
            cursorYPos,
            cursorXPos + cellWidth,
            cursorYPos + cellHeight,
            cursorPaint
        )
    }
}
