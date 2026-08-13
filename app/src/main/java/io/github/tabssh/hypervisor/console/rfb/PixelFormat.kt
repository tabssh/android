package io.github.tabssh.hypervisor.console.rfb

import java.io.DataInputStream

/**
 * RFB pixel format negotiated during ServerInit / SetPixelFormat.
 *
 * We always request [PREFERRED] (32bpp, little-endian, RGB-888 packed in
 * the low 24 bits) so that converting server pixels to Android ARGB_8888
 * ints is a trivial bit shift with no per-channel scaling.
 */
data class PixelFormat(
    val bitsPerPixel: Int,
    val depth: Int,
    /** 0 = little-endian, 1 = big-endian */
    val bigEndianFlag: Int,
    /** 1 = true-colour */
    val trueColorFlag: Int,
    val redMax: Int,
    val greenMax: Int,
    val blueMax: Int,
    val redShift: Int,
    val greenShift: Int,
    val blueShift: Int
) {
    val bytesPerPixel: Int get() = bitsPerPixel / 8

    /**
     * Number of bytes in a CPixel (compact pixel used by ZRLE).
     *
     * If 32bpp and depth ≤ 24 the high byte carries no information; ZRLE
     * omits it, giving a 3-byte CPixel. Otherwise CPixel = full pixel.
     */
    val cpixelBytes: Int get() = if (bytesPerPixel == 4 && depth <= 24) 3 else bytesPerPixel

    /**
     * Convert [bytesPerPixel] raw bytes (starting at [offset] in [buf])
     * into an Android ARGB_8888 int: 0xAARRGGBB, A always 0xFF.
     */
    fun toArgb(buf: ByteArray, offset: Int = 0): Int {
        val raw: Long = when (bytesPerPixel) {
            1 -> (buf[offset].toLong() and 0xFF)
            2 -> if (bigEndianFlag != 0) {
                ((buf[offset].toLong() and 0xFF) shl 8) or (buf[offset + 1].toLong() and 0xFF)
            } else {
                ((buf[offset + 1].toLong() and 0xFF) shl 8) or (buf[offset].toLong() and 0xFF)
            }
            4 -> if (bigEndianFlag != 0) {
                ((buf[offset].toLong() and 0xFF) shl 24) or
                ((buf[offset + 1].toLong() and 0xFF) shl 16) or
                ((buf[offset + 2].toLong() and 0xFF) shl 8) or
                (buf[offset + 3].toLong() and 0xFF)
            } else {
                ((buf[offset + 3].toLong() and 0xFF) shl 24) or
                ((buf[offset + 2].toLong() and 0xFF) shl 16) or
                ((buf[offset + 1].toLong() and 0xFF) shl 8) or
                (buf[offset].toLong() and 0xFF)
            }
            // 24bpp is legal RFB (RFC 6143 §7.4 only requires 8/16/32 be
            // supported, it does not forbid 24). Returning 0 here painted every
            // pixel of such a server black.
            3 -> if (bigEndianFlag != 0) {
                ((buf[offset].toLong() and 0xFF) shl 16) or
                ((buf[offset + 1].toLong() and 0xFF) shl 8) or
                (buf[offset + 2].toLong() and 0xFF)
            } else {
                ((buf[offset + 2].toLong() and 0xFF) shl 16) or
                ((buf[offset + 1].toLong() and 0xFF) shl 8) or
                (buf[offset].toLong() and 0xFF)
            }
            else -> 0L
        }
        val r = ((raw ushr redShift) and redMax.toLong()).toInt()
        val g = ((raw ushr greenShift) and greenMax.toLong()).toInt()
        val b = ((raw ushr blueShift) and blueMax.toLong()).toInt()
        // Scale channel if server uses fewer than 8 bits (e.g. redMax=31 → 5-bit).
        // A zero max is only possible from a malformed ServerInit; scaling it
        // would divide by zero, so treat the channel as absent instead.
        val rs = scaleChannel(r, redMax)
        val gs = scaleChannel(g, greenMax)
        val bs = scaleChannel(b, blueMax)
        return (0xFF shl 24) or (rs shl 16) or (gs shl 8) or bs
    }

    /** Scale a [value] whose full-scale is [max] up to 8 bits. */
    private fun scaleChannel(value: Int, max: Int): Int = when {
        max == 255 -> value
        max <= 0 -> 0
        else -> (value * 255 + max / 2) / max
    }

    /**
     * Read a CPixel from [buf] at [offset] and return an ARGB_8888 int.
     *
     * A 3-byte CPixel omits the pixel's most significant byte, which carries
     * no colour information when depth ≤ 24. Which wire position that byte
     * occupies depends on the server's byte order: last for little-endian,
     * first for big-endian. Assuming little-endian unconditionally shifted
     * every channel by 8 bits on a big-endian server.
     */
    fun cpixelToArgb(buf: ByteArray, offset: Int = 0): Int {
        if (cpixelBytes != 3) return toArgb(buf, offset)
        val tmp = ByteArray(4)
        if (bigEndianFlag != 0) {
            tmp[0] = 0
            tmp[1] = buf[offset]
            tmp[2] = buf[offset + 1]
            tmp[3] = buf[offset + 2]
        } else {
            tmp[0] = buf[offset]
            tmp[1] = buf[offset + 1]
            tmp[2] = buf[offset + 2]
            tmp[3] = 0
        }
        return toArgb(tmp, 0)
    }

    companion object {
        /**
         * Our preferred wire format: 32bpp, depth 24, little-endian,
         * true-colour, R=bits[23:16] G=bits[15:8] B=bits[7:0].
         *
         * Conversion to Android ARGB_8888: `0xFF000000 | rawPixel`
         * (since B is at shift 0, G at 8, R at 16 — matching ARGB layout).
         */
        val PREFERRED = PixelFormat(
            bitsPerPixel = 32,
            depth = 24,
            bigEndianFlag = 0,
            trueColorFlag = 1,
            redMax = 255, greenMax = 255, blueMax = 255,
            redShift = 16, greenShift = 8, blueShift = 0
        )

        /** Read the 16-byte pixel-format block from a DataInputStream. */
        fun readFrom(din: DataInputStream): PixelFormat {
            val bpp = din.readUnsignedByte()
            val depth = din.readUnsignedByte()
            val bigEndian = din.readUnsignedByte()
            val trueColor = din.readUnsignedByte()
            val redMax = din.readUnsignedShort()
            val greenMax = din.readUnsignedShort()
            val blueMax = din.readUnsignedShort()
            val redShift = din.readUnsignedByte()
            val greenShift = din.readUnsignedByte()
            val blueShift = din.readUnsignedByte()
            din.skipBytes(3) // padding
            return PixelFormat(bpp, depth, bigEndian, trueColor,
                redMax, greenMax, blueMax, redShift, greenShift, blueShift)
        }

        /** Serialize to the 16-byte SetPixelFormat payload (bytes 4-19). */
        fun PixelFormat.toBytes(): ByteArray {
            val b = ByteArray(16)
            b[0] = bitsPerPixel.toByte()
            b[1] = depth.toByte()
            b[2] = bigEndianFlag.toByte()
            b[3] = trueColorFlag.toByte()
            b[4] = (redMax shr 8).toByte(); b[5] = redMax.toByte()
            b[6] = (greenMax shr 8).toByte(); b[7] = greenMax.toByte()
            b[8] = (blueMax shr 8).toByte(); b[9] = blueMax.toByte()
            b[10] = redShift.toByte()
            b[11] = greenShift.toByte()
            b[12] = blueShift.toByte()
            // b[13..15] = padding (0)
            return b
        }
    }
}
