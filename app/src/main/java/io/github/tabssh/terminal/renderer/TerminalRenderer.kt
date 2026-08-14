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

    // Scratch paints reused across cells — render() runs per frame, and
    // allocating a Paint per cell caused GC churn on large buffers (audit O1).
    private val cellBgPaint = Paint()
    private val glyphPaint = Paint()

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

        // Re-sync the glyph scratch paint once per frame so external changes
        // to textPaint (font size, antialiasing) are picked up.
        glyphPaint.set(textPaint)

        // Theme palette installed via TerminalBuffer.setColors(), if any.
        val palette = buffer.getColorPalette() ?: defaultColors

        // Draw text
        for (row in 0 until rows) {
            val y = offsetY + (row * cellHeight) - scrollY
            if (y + cellHeight < 0 || y > canvas.height) continue

            val line = buffer.getLine(row) ?: continue

            for (col in 0 until cols) {
                val x = offsetX + (col * cellWidth)
                // A row can be narrower than the grid for one frame if a resize
                // lands between the row lookup and this loop; skip rather than throw.
                val char = line.getOrNull(col) ?: continue

                // Resolve effective foreground colour from the 16-colour palette
                var fgInt = if (char.fgColor in palette.indices) {
                    palette[char.fgColor]
                } else Color.WHITE

                // Resolve effective background; index 0 means the terminal's base background
                var bgInt = when {
                    char.bgColor == 0 -> backgroundPaint.color
                    char.bgColor in palette.indices -> palette[char.bgColor]
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
                    cellBgPaint.color = bgInt
                    canvas.drawRect(x, y, x + cellWidth, y + cellHeight, cellBgPaint)
                }

                // Draw character
                if (char.char != ' ') {
                    glyphPaint.color = fgInt
                    // Reset formatting every cell — the scratch paint carries
                    // state from the previous glyph otherwise.
                    glyphPaint.typeface = if (char.bold) Typeface.DEFAULT_BOLD else textPaint.typeface
                    glyphPaint.isUnderlineText = char.underline

                    canvas.drawText(char.char.toString(), x, y + cellHeight * 0.8f, glyphPaint)
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
