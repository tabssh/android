package io.github.tabssh.hypervisor.console.rfb

import android.graphics.BitmapFactory
import io.github.tabssh.utils.logging.Logger
import java.io.DataInputStream
import java.io.EOFException
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Decodes RFB FramebufferUpdate rectangles into ARGB_8888 pixel arrays.
 *
 * Supported encodings (advertised to the server in preference order):
 *   Tight (7)      — TigerVNC default; Fill/Basic/JPEG/PNG sub-types
 *   ZRLE (16)      — zlib-compressed, most efficient, used by Proxmox
 *   ZLIB (6)       — simple zlib-wrapped raw pixels
 *   Hextile (5)    — tile-based, universal server-side fallback
 *   CopyRect (1)   — cheap scroll / blit operations
 *   RRE (2)        — solid-colour region compression
 *   CoRRE (4)      — compact RRE with 1-byte sub-rect coordinates
 *   Raw (0)        — uncompressed baseline, always available
 *
 * Pseudo-encodings decoded inline by RfbClient:
 *   DesktopSize (-223) — framebuffer resize
 *   Cursor (-239)      — software cursor (stored; composited by VncView)
 *   LastRect (-224)    — terminates a FramebufferUpdate early
 *
 * Threading: all decode calls happen on the RfbClient reader thread.
 * Caller is responsible for synchronising access to the framebuffer.
 */
class RfbDecoder(private val fmt: PixelFormat) {

    companion object {
        private const val TAG = "RfbDecoder"
        private const val ZRLE_TILE = 64          // ZRLE tile size in pixels
        private const val ZRLE_BUF = 512 * 1024   // initial inflate buffer

        /**
         * The complete set of encoding values that [decodeRect] can handle.
         * RfbClient uses this to guard calls to [decodeRect] — any encoding
         * not in this set is a pseudo-encoding or vendor extension with zero
         * pixel payload and must NOT be passed to [decodeRect].
         */
        val PIXEL_ENCODINGS: Set<Int> = setOf(
            RfbConstants.ENC_RAW,
            RfbConstants.ENC_COPY_RECT,
            RfbConstants.ENC_RRE,
            RfbConstants.ENC_CORRE,
            RfbConstants.ENC_HEXTILE,
            RfbConstants.ENC_ZLIB,
            RfbConstants.ENC_ZRLE,
            RfbConstants.ENC_TIGHT
        )
    }

    // ── Zlib streams ─────────────────────────────────────────────────────────
    //
    // ZRLE: single continuous stream for the connection lifetime.
    // ZLIB: single continuous stream (separate from ZRLE).
    // Tight: four independent streams (0..3), reset on demand via cc byte.

    private val zrleInflater = Inflater()
    private var zrleBuf = ByteArray(ZRLE_BUF)

    private val zlibInflater = Inflater()

    private val tightInflaters = Array(4) { Inflater() }

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
            RfbConstants.ENC_CORRE     -> decodeCorre(din, fb, fbW, x, y, w, h)
            RfbConstants.ENC_HEXTILE   -> decodeHextile(din, fb, fbW, x, y, w, h)
            RfbConstants.ENC_ZLIB      -> decodeZlib(din, fb, fbW, x, y, w, h)
            RfbConstants.ENC_ZRLE      -> decodeZrle(din, fb, fbW, x, y, w, h)
            RfbConstants.ENC_TIGHT     -> decodeTight(din, fb, fbW, x, y, w, h)
            else -> {
                // This branch should never be reached: RfbClient guards all
                // calls to decodeRect with the PIXEL_ENCODINGS set. If it is
                // reached, the alternative — skipping w*h*bpp bytes — is
                // catastrophically wrong for pseudo-encodings that carry fake
                // dimensions (e.g. 0x6000 with 16384×8192 → 512 MB skip that
                // blocks the reader thread forever). Fail fast instead.
                throw java.io.IOException(
                    "decodeRect called with unhandled encoding $encoding " +
                    "at $x,$y ${w}×$h — stream state unknown, terminating"
                )
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

    // ── CoRRE ───────────────────────────────────────────────────────────────
    //
    // Compact RRE: like RRE but sub-rect coordinates and dimensions are each
    // a single byte instead of two, limiting tile size to 255×255 pixels.
    // Pixel values use the full pixel format (not CPixel).

    private fun decodeCorre(
        din: DataInputStream, fb: IntArray, fbW: Int,
        x: Int, y: Int, w: Int, h: Int
    ) {
        val numSubRects = din.readInt()
        val bgBuf = ByteArray(fmt.bytesPerPixel)
        din.readFully(bgBuf)
        fillRect(fb, fbW, x, y, w, h, fmt.toArgb(bgBuf))

        val fgBuf = ByteArray(fmt.bytesPerPixel)
        repeat(numSubRects) {
            din.readFully(fgBuf)
            val fg = fmt.toArgb(fgBuf)
            val sx = din.readUnsignedByte()
            val sy = din.readUnsignedByte()
            val sw = din.readUnsignedByte()
            val sh = din.readUnsignedByte()
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

    // ── ZLIB ────────────────────────────────────────────────────────────────
    //
    // Simple continuous zlib stream: 4-byte length + compressed raw pixels.
    // Uses a separate Inflater from ZRLE so the two streams don't interfere.

    private fun decodeZlib(
        din: DataInputStream, fb: IntArray, fbW: Int,
        x: Int, y: Int, w: Int, h: Int
    ) {
        val compLen = din.readInt()
        if (compLen <= 0) return
        val compData = ByteArray(compLen)
        din.readFully(compData)

        zlibInflater.setInput(compData)
        val expectedSize = w * h * fmt.bytesPerPixel
        val plain = ByteArray(expectedSize)
        var totalOut = 0
        try {
            while (totalOut < expectedSize) {
                val n = zlibInflater.inflate(plain, totalOut, expectedSize - totalOut)
                if (n == 0) break
                totalOut += n
            }
        } catch (e: DataFormatException) {
            Logger.w(TAG, "ZLIB inflate error — resetting: ${e.message}")
            zlibInflater.reset()
        }

        for (row in 0 until h) {
            val base = (y + row) * fbW + x
            for (col in 0 until w) {
                fb[base + col] = fmt.toArgb(plain, (row * w + col) * fmt.bytesPerPixel)
            }
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
        val compLen = din.readInt() and 0x7FFFFFFF
        if (compLen <= 0) return

        val compBuf = ByteArray(compLen)
        din.readFully(compBuf)

        zrleInflater.setInput(compBuf, 0, compLen)
        val plain = inflateAll(zrleInflater)
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
                    // 17..127, 129: undefined — solid black to stay in sync
                    else -> fillRect(fb, fbW, tx, ty, tileW, tileH, 0xFF000000.toInt())
                }
                tx += tileW
            }
            ty += tileH
        }
    }

    // ── Tight ────────────────────────────────────────────────────────────────
    //
    // Tight encoding (RFC extension by Const). Compression-control byte:
    //   bits 3..0 — reset flags for each of the 4 zlib streams
    //   bits 7..4 — compression type
    //     0x0..0x3 — BasicCompression, stream index = (type & 3)
    //     0x8       — FillCompression (solid colour)
    //     0x9       — JPEG
    //     0xA       — PNG (extended Tight, TigerVNC ≥ 1.3)
    //
    // BasicCompression reads a FilterID byte:
    //   0 (Copy)     — raw pixels, cpixelBytes each
    //   1 (Palette)  — palette + indexed data (1 bpp if 2 colours, 8 bpp otherwise)
    //   2 (Gradient) — delta-prediction per channel

    private fun decodeTight(
        din: DataInputStream, fb: IntArray, fbW: Int,
        x: Int, y: Int, w: Int, h: Int
    ) {
        val cc = din.readUnsignedByte()

        // Reset requested streams (bits 0..3)
        for (i in 0..3) {
            if (cc and (1 shl i) != 0) tightInflaters[i].reset()
        }

        val compType = cc ushr 4
        val cp = fmt.cpixelBytes

        when (compType) {
            RfbConstants.TIGHT_FILL -> {
                // Single pixel, fill the whole rect
                val pixBuf = ByteArray(cp)
                din.readFully(pixBuf)
                fillRect(fb, fbW, x, y, w, h, fmt.cpixelToArgb(pixBuf))
            }

            RfbConstants.TIGHT_JPEG, RfbConstants.TIGHT_PNG -> {
                // Entire rect as JPEG or PNG image data
                val dataLen = readCompactLen(din)
                val imageData = ByteArray(dataLen)
                din.readFully(imageData)
                val bmp = BitmapFactory.decodeByteArray(imageData, 0, dataLen)
                if (bmp != null) {
                    val pixels = IntArray(w * h)
                    bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                    bmp.recycle()
                    for (row in 0 until h) {
                        pixels.copyInto(fb, (y + row) * fbW + x, row * w, row * w + w)
                    }
                } else {
                    Logger.w(TAG, "Tight JPEG/PNG decode failed for rect $x,$y ${w}×$h")
                }
            }

            else -> {
                // BasicCompression: stream index = compType & 3
                val streamIdx = compType and 0x3
                val filterId = din.readUnsignedByte()

                when (filterId) {
                    RfbConstants.TIGHT_FILTER_COPY -> {
                        val dataSize = w * h * cp
                        val data = readTightData(din, streamIdx, dataSize)
                        var idx = 0
                        for (row in 0 until h) {
                            val base = (y + row) * fbW + x
                            for (col in 0 until w) {
                                fb[base + col] = fmt.cpixelToArgb(data, idx)
                                idx += cp
                            }
                        }
                    }

                    RfbConstants.TIGHT_FILTER_PALETTE -> {
                        val numColors = din.readUnsignedByte() + 1
                        val palette = IntArray(numColors)
                        val pbBuf = ByteArray(cp)
                        for (i in 0 until numColors) {
                            din.readFully(pbBuf)
                            palette[i] = fmt.cpixelToArgb(pbBuf)
                        }
                        // 2 colours → 1 bpp packed; >2 colours → 8 bpp indices
                        val dataSize = if (numColors == 2) ((w + 7) / 8) * h else w * h
                        val data = readTightData(din, streamIdx, dataSize)

                        if (numColors == 2) {
                            var di = 0
                            for (row in 0 until h) {
                                val base = (y + row) * fbW + x
                                var col = 0
                                while (col < w) {
                                    val b = data[di++].toInt() and 0xFF
                                    for (bit in 7 downTo 0) {
                                        if (col >= w) break
                                        fb[base + col++] = palette[(b ushr bit) and 1]
                                    }
                                }
                            }
                        } else {
                            var di = 0
                            for (row in 0 until h) {
                                val base = (y + row) * fbW + x
                                for (col in 0 until w) {
                                    fb[base + col] = palette[data[di++].toInt() and 0xFF]
                                }
                            }
                        }
                    }

                    RfbConstants.TIGHT_FILTER_GRADIENT -> {
                        // Gradient (delta) filter: actual = raw + prediction (per channel)
                        // Prediction = left + above − above_left, clamped [0, 255]
                        val dataSize = w * h * cp
                        val data = readTightData(din, streamIdx, dataSize)

                        val prevRow = ByteArray(w * cp)  // previous decoded row
                        val currRow = ByteArray(w * cp)  // current row being decoded

                        for (row in 0 until h) {
                            val srcOff = row * w * cp
                            val dstBase = (y + row) * fbW + x
                            for (col in 0 until w) {
                                for (c in 0 until cp) {
                                    val raw = data[srcOff + col * cp + c].toInt() and 0xFF
                                    val est = when {
                                        row == 0 && col == 0 -> 0
                                        row == 0 -> currRow[(col - 1) * cp + c].toInt() and 0xFF
                                        col == 0 -> prevRow[c].toInt() and 0xFF
                                        else -> {
                                            val left     = currRow[(col - 1) * cp + c].toInt() and 0xFF
                                            val above    = prevRow[col * cp + c].toInt() and 0xFF
                                            val aboveLeft = prevRow[(col - 1) * cp + c].toInt() and 0xFF
                                            (left + above - aboveLeft).coerceIn(0, 255)
                                        }
                                    }
                                    currRow[col * cp + c] = ((raw + est) and 0xFF).toByte()
                                }
                                fb[dstBase + col] = fmt.cpixelToArgb(currRow, col * cp)
                            }
                            currRow.copyInto(prevRow)
                        }
                    }

                    else -> {
                        Logger.w(TAG, "Unknown Tight filter $filterId for rect $x,$y ${w}×$h")
                    }
                }
            }
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
     * Compact-length integer used by the Tight encoding.
     * Reads 1–3 bytes; MSB of each byte signals "more bytes follow".
     */
    private fun readCompactLen(din: DataInputStream): Int {
        var b = din.readUnsignedByte()
        var len = b and 0x7F
        if (b and 0x80 != 0) {
            b = din.readUnsignedByte()
            len = len or ((b and 0x7F) shl 7)
            if (b and 0x80 != 0) {
                b = din.readUnsignedByte()
                len = len or (b shl 14)
            }
        }
        return len
    }

    /**
     * Read Tight BasicCompression data for [streamIdx].
     * Data is raw (inline) when [dataSize] < TIGHT_MIN_COMPRESS_SIZE, otherwise
     * a compact length followed by zlib-compressed bytes.
     */
    private fun readTightData(din: DataInputStream, streamIdx: Int, dataSize: Int): ByteArray {
        if (dataSize < RfbConstants.TIGHT_MIN_COMPRESS_SIZE) {
            val buf = ByteArray(dataSize)
            din.readFully(buf)
            return buf
        }
        val compLen = readCompactLen(din)
        val compData = ByteArray(compLen)
        din.readFully(compData)
        return inflateTight(streamIdx, compData, dataSize)
    }

    /**
     * Inflate [compData] using Tight stream [streamIdx] into a buffer of exactly
     * [expectedSize] bytes. The stream is persistent (not reset here).
     */
    private fun inflateTight(streamIdx: Int, compData: ByteArray, expectedSize: Int): ByteArray {
        val inflater = tightInflaters[streamIdx]
        inflater.setInput(compData)
        val out = ByteArray(expectedSize)
        var totalOut = 0
        try {
            while (totalOut < expectedSize) {
                val n = inflater.inflate(out, totalOut, expectedSize - totalOut)
                if (n == 0) break
                totalOut += n
            }
        } catch (e: DataFormatException) {
            Logger.w(TAG, "Tight inflate error (stream $streamIdx) — resetting: ${e.message}")
            inflater.reset()
        }
        return out
    }

    /**
     * Inflate all pending input from [inflater] into a fresh byte array.
     * Doubles the output buffer as needed.
     */
    private fun inflateAll(inflater: Inflater): ByteArray {
        var outBuf = ByteArray(maxOf(zrleBuf.size, 4096))
        var totalOut = 0
        try {
            while (true) {
                if (totalOut >= outBuf.size) outBuf = outBuf.copyOf(outBuf.size * 2)
                val n = inflater.inflate(outBuf, totalOut, outBuf.size - totalOut)
                totalOut += n
                // inflate() returns 0 only when needsInput() or finished() —
                // the output buffer has space (expanded above), so 0 means done.
                if (n == 0) break
            }
        } catch (e: DataFormatException) {
            Logger.w(TAG, "ZRLE inflate error — resetting stream: ${e.message}")
            inflater.reset()
        }
        return outBuf.copyOf(totalOut)
    }

    fun reset() {
        zrleInflater.reset()
        zlibInflater.reset()
        for (inflater in tightInflaters) inflater.reset()
    }
}
