package io.github.tabssh.hypervisor.console.rfb

import io.github.tabssh.utils.logging.Logger
import java.io.DataInputStream
import java.io.EOFException
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Decodes RFB FramebufferUpdate rectangles into ARGB_8888 pixel arrays.
 *
 * Supported encodings (advertised to the server in preference order):
 *   ZRLE (16)      — zlib-compressed, most efficient, used by Proxmox
 *   Hextile (5)    — tile-based, universal server-side fallback
 *   CopyRect (1)   — cheap scroll / blit operations
 *   RRE (2)        — solid-colour region compression
 *   Raw (0)        — uncompressed baseline, always available
 *
 * Pseudo-encodings decoded inline by RfbClient:
 *   DesktopSize (-223) — framebuffer resize
 *   Cursor (-239)      — software cursor (stored; composited by VncView)
 *
 * Threading: all decode calls happen on the RfbClient reader thread.
 * Caller is responsible for synchronising access to the framebuffer.
 */
class RfbDecoder(private val fmt: PixelFormat) {

    companion object {
        private const val TAG = "RfbDecoder"
        private const val ZRLE_TILE = 64          // ZRLE tile size in pixels
        private const val ZRLE_BUF = 512 * 1024   // initial inflate buffer
    }

    // ZRLE uses a single continuous zlib stream for the lifetime of the
    // connection. The Inflater is reset only if the stream is corrupted.
    private val zrleInflater = Inflater()
    private var zrleBuf = ByteArray(ZRLE_BUF)

    /**
     * Decode one rectangle from [din] into [fb].
     *
     * @param fb      full-framebuffer ARGB_8888 pixel array (row-major)
     * @param fbW     full framebuffer width
     * @param x y w h rectangle coordinates
     * @param encoding encoding type integer
     */
    fun decodeRect(
        din: DataInputStream,
        fb: IntArray,
        fbW: Int,
        x: Int, y: Int, w: Int, h: Int,
        encoding: Int
    ) {
        when (encoding) {
            RfbConstants.ENC_RAW       -> decodeRaw(din, fb, fbW, x, y, w, h)
            RfbConstants.ENC_COPY_RECT -> decodeCopyRect(din, fb, fbW, x, y, w, h)
            RfbConstants.ENC_RRE       -> decodeRre(din, fb, fbW, x, y, w, h)
            RfbConstants.ENC_HEXTILE   -> decodeHextile(din, fb, fbW, x, y, w, h)
            RfbConstants.ENC_ZRLE      -> decodeZrle(din, fb, fbW, x, y, w, h)
            else -> {
                Logger.w(TAG, "Unsupported encoding $encoding for rect $x,$y ${w}×$h — skipping")
                // Best-effort: skip w*h*bpp bytes so the stream stays in sync
                val skip = w.toLong() * h * fmt.bytesPerPixel
                var remaining = skip
                while (remaining > 0) remaining -= din.skip(remaining)
            }
        }
    }

    // ── Raw ─────────────────────────────────────────────────────────────────

    private fun decodeRaw(
        din: DataInputStream, fb: IntArray, fbW: Int,
        x: Int, y: Int, w: Int, h: Int
    ) {
        val pixBuf = ByteArray(w * fmt.bytesPerPixel)
        for (row in 0 until h) {
            din.readFully(pixBuf)
            val base = (y + row) * fbW + x
            for (col in 0 until w) {
                fb[base + col] = fmt.toArgb(pixBuf, col * fmt.bytesPerPixel)
            }
        }
    }

    // ── CopyRect ────────────────────────────────────────────────────────────

    private fun decodeCopyRect(
        din: DataInputStream, fb: IntArray, fbW: Int,
        x: Int, y: Int, w: Int, h: Int
    ) {
        val srcX = din.readUnsignedShort()
        val srcY = din.readUnsignedShort()
        // Copy row by row; handle overlap by choosing direction
        if (srcY < y || (srcY == y && srcX < x)) {
            for (row in h - 1 downTo 0) {
                val src = (srcY + row) * fbW + srcX
                val dst = (y + row) * fbW + x
                fb.copyInto(fb, dst, src, src + w)
            }
        } else {
            for (row in 0 until h) {
                val src = (srcY + row) * fbW + srcX
                val dst = (y + row) * fbW + x
                fb.copyInto(fb, dst, src, src + w)
            }
        }
    }

    // ── RRE ─────────────────────────────────────────────────────────────────

    private fun decodeRre(
        din: DataInputStream, fb: IntArray, fbW: Int,
        x: Int, y: Int, w: Int, h: Int
    ) {
        val numSubRects = din.readInt()
        val bgBuf = ByteArray(fmt.bytesPerPixel)
        din.readFully(bgBuf)
        val bg = fmt.toArgb(bgBuf)
        fillRect(fb, fbW, x, y, w, h, bg)

        val fgBuf = ByteArray(fmt.bytesPerPixel)
        repeat(numSubRects) {
            din.readFully(fgBuf)
            val fg = fmt.toArgb(fgBuf)
            val sx = din.readUnsignedShort()
            val sy = din.readUnsignedShort()
            val sw = din.readUnsignedShort()
            val sh = din.readUnsignedShort()
            fillRect(fb, fbW, x + sx, y + sy, sw, sh, fg)
        }
    }

    // ── Hextile ─────────────────────────────────────────────────────────────

    private fun decodeHextile(
        din: DataInputStream, fb: IntArray, fbW: Int,
        x: Int, y: Int, w: Int, h: Int
    ) {
        var bg = 0
        var fg = 0
        val fgBuf = ByteArray(fmt.bytesPerPixel)
        val bgBuf = ByteArray(fmt.bytesPerPixel)

        var ty = y
        while (ty < y + h) {
            val tileH = minOf(16, y + h - ty)
            var tx = x
            while (tx < x + w) {
                val tileW = minOf(16, x + w - tx)
                val sub = din.readUnsignedByte()

                if (sub and RfbConstants.HEXTILE_RAW != 0) {
                    decodeRaw(din, fb, fbW, tx, ty, tileW, tileH)
                    tx += tileW; continue
                }
                if (sub and RfbConstants.HEXTILE_BG_SPECIFIED != 0) {
                    din.readFully(bgBuf); bg = fmt.toArgb(bgBuf)
                }
                fillRect(fb, fbW, tx, ty, tileW, tileH, bg)

                if (sub and RfbConstants.HEXTILE_FG_SPECIFIED != 0) {
                    din.readFully(fgBuf); fg = fmt.toArgb(fgBuf)
                }
                if (sub and RfbConstants.HEXTILE_ANY_SUBRECTS != 0) {
                    val nSubs = din.readUnsignedByte()
                    repeat(nSubs) {
                        val coloured = sub and RfbConstants.HEXTILE_SUBRECTS_COLOURED != 0
                        if (coloured) { din.readFully(fgBuf); fg = fmt.toArgb(fgBuf) }
                        val xy = din.readUnsignedByte()
                        val wh = din.readUnsignedByte()
                        val sx = tx + (xy shr 4)
                        val sy = ty + (xy and 0x0F)
                        val sw = (wh shr 4) + 1
                        val sh = (wh and 0x0F) + 1
                        fillRect(fb, fbW, sx, sy, sw, sh, fg)
                    }
                }
                tx += tileW
            }
            ty += tileH
        }
    }

    // ── ZRLE ────────────────────────────────────────────────────────────────
    //
    // The zlib stream is continuous across all ZRLE rectangles for the life
    // of the connection. Each ZRLE rect begins with a 4-byte compressed
    // length; we inflate that many bytes and feed the result tile by tile.

    private fun decodeZrle(
        din: DataInputStream, fb: IntArray, fbW: Int,
        x: Int, y: Int, w: Int, h: Int
    ) {
        val compLen = din.readInt() and 0xFFFFFFFFL.toInt() // treat as unsigned
        if (compLen <= 0) return

        val compBuf = ByteArray(compLen)
        din.readFully(compBuf)

        // Inflate the full compressed chunk.
        zrleInflater.setInput(compBuf, 0, compLen)
        val plain = inflateAll(compLen)
        val src = java.io.ByteArrayInputStream(plain)

        val cp = fmt.cpixelBytes
        val cpBuf = ByteArray(cp)

        var ty = y
        while (ty < y + h) {
            val tileH = minOf(ZRLE_TILE, y + h - ty)
            var tx = x
            while (tx < x + w) {
                val tileW = minOf(ZRLE_TILE, x + w - tx)
                val sub = src.read()
                if (sub < 0) throw EOFException("ZRLE stream exhausted")

                when {
                    sub == 0 -> {
                        // Raw tile: CPixel per pixel
                        val rawBuf = ByteArray(tileW * tileH * cp)
                        src.read(rawBuf)
                        var idx = 0
                        for (row in 0 until tileH) {
                            val base = (ty + row) * fbW + tx
                            for (col in 0 until tileW) {
                                fb[base + col] = fmt.cpixelToArgb(rawBuf, idx)
                                idx += cp
                            }
                        }
                    }
                    sub == 1 -> {
                        // Solid tile: single CPixel
                        src.read(cpBuf)
                        fillRect(fb, fbW, tx, ty, tileW, tileH, fmt.cpixelToArgb(cpBuf))
                    }
                    sub in 2..16 -> {
                        // Packed palette
                        val paletteSize = sub
                        val palette = IntArray(paletteSize)
                        val pb = ByteArray(cp)
                        for (i in 0 until paletteSize) { src.read(pb); palette[i] = fmt.cpixelToArgb(pb) }
                        val bitsPerIdx = when {
                            paletteSize <= 2 -> 1
                            paletteSize <= 4 -> 2
                            else -> 4
                        }
                        val mask = (1 shl bitsPerIdx) - 1
                        for (row in 0 until tileH) {
                            val base = (ty + row) * fbW + tx
                            var col = 0
                            while (col < tileW) {
                                val b = src.read()
                                var shift = 8 - bitsPerIdx
                                while (shift >= 0 && col < tileW) {
                                    fb[base + col] = palette[(b ushr shift) and mask]
                                    col++
                                    shift -= bitsPerIdx
                                }
                            }
                        }
                    }
                    sub == 128 -> {
                        // Plain RLE
                        var pixelsLeft = tileW * tileH
                        while (pixelsLeft > 0) {
                            src.read(cpBuf)
                            val argb = fmt.cpixelToArgb(cpBuf)
                            var runLen = 1
                            var b: Int
                            do { b = src.read(); runLen += b } while (b == 255)
                            val pixels = minOf(runLen, pixelsLeft)
                            writeRunToFb(fb, fbW, tx, ty, tileW, tileH,
                                tileW * tileH - pixelsLeft, argb, pixels)
                            pixelsLeft -= pixels
                        }
                    }
                    sub in 130..255 -> {
                        // Palette RLE
                        val paletteSize = sub - 128
                        val palette = IntArray(paletteSize)
                        val pb = ByteArray(cp)
                        for (i in 0 until paletteSize) { src.read(pb); palette[i] = fmt.cpixelToArgb(pb) }
                        var pixelsLeft = tileW * tileH
                        while (pixelsLeft > 0) {
                            val idxByte = src.read()
                            val argb = palette[idxByte and 0x7F]
                            val runLen = if (idxByte and 0x80 != 0) {
                                var r = 1; var b: Int
                                do { b = src.read(); r += b } while (b == 255)
                                r
                            } else 1
                            val pixels = minOf(runLen, pixelsLeft)
                            writeRunToFb(fb, fbW, tx, ty, tileW, tileH,
                                tileW * tileH - pixelsLeft, argb, pixels)
                            pixelsLeft -= pixels
                        }
                    }
                    // 17-127, 129: undefined — treat as solid black to stay in sync
                    else -> fillRect(fb, fbW, tx, ty, tileW, tileH, 0xFF000000.toInt())
                }
                tx += tileW
            }
            ty += tileH
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun fillRect(fb: IntArray, fbW: Int, x: Int, y: Int, w: Int, h: Int, color: Int) {
        for (row in 0 until h) {
            val base = (y + row) * fbW + x
            fb.fill(color, base, base + w)
        }
    }

    /**
     * Write [count] pixels of [argb] into [fb] starting at linear pixel
     * offset [startOff] within the tile (tile-local, not framebuffer-local).
     */
    private fun writeRunToFb(
        fb: IntArray, fbW: Int,
        tileX: Int, tileY: Int, tileW: Int, tileH: Int,
        startOff: Int, argb: Int, count: Int
    ) {
        var off = startOff
        val endOff = minOf(startOff + count, tileW * tileH)
        while (off < endOff) {
            val row = off / tileW
            val col = off % tileW
            if (row >= tileH) break
            fb[(tileY + row) * fbW + (tileX + col)] = argb
            off++
        }
    }

    /**
     * Inflate all pending input from [zrleInflater] into a fresh byte array.
     * Doubles the output buffer as needed. Resets the inflater on corruption.
     */
    private fun inflateAll(compLen: Int): ByteArray {
        // Estimate: compressed data expands roughly 4-10×
        var outBuf = ByteArray(maxOf(zrleBuf.size, compLen * 8))
        var totalOut = 0
        try {
            while (!zrleInflater.needsInput()) {
                if (totalOut >= outBuf.size) outBuf = outBuf.copyOf(outBuf.size * 2)
                val n = zrleInflater.inflate(outBuf, totalOut, outBuf.size - totalOut)
                if (n == 0) break
                totalOut += n
            }
        } catch (e: DataFormatException) {
            Logger.w(TAG, "ZRLE inflate error — resetting stream: ${e.message}")
            zrleInflater.reset()
        }
        return if (totalOut == outBuf.size) outBuf else outBuf.copyOf(totalOut)
    }

    fun reset() {
        zrleInflater.reset()
    }
}
