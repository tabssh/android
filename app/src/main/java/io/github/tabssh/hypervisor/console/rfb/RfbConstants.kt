package io.github.tabssh.hypervisor.console.rfb

/** RFB 3.8 protocol constants (RFC 6143). */
object RfbConstants {

    // ── Security types ──────────────────────────────────────────────────────
    const val SECURITY_NONE: Int = 1
    const val SECURITY_VNC_AUTH: Int = 2
    const val SECURITY_VENCRYPT: Int = 19

    // ── VeNCrypt sub-types ───────────────────────────────────────────────────
    const val VENCRYPT_TLS_NONE: Int = 256
    const val VENCRYPT_X509_NONE: Int = 260
    const val VENCRYPT_TLS_VNC: Int = 257
    const val VENCRYPT_X509_VNC: Int = 261
    const val VENCRYPT_TLS_PLAIN: Int = 258
    const val VENCRYPT_X509_PLAIN: Int = 262

    // ── Client → Server message types ───────────────────────────────────────
    const val C2S_SET_PIXEL_FORMAT: Int = 0
    const val C2S_SET_ENCODINGS: Int = 2
    const val C2S_FRAMEBUFFER_UPDATE_REQUEST: Int = 3
    const val C2S_KEY_EVENT: Int = 4
    const val C2S_POINTER_EVENT: Int = 5
    const val C2S_CLIENT_CUT_TEXT: Int = 6

    // ── Server → Client message types ───────────────────────────────────────
    const val S2C_FRAMEBUFFER_UPDATE: Int = 0
    const val S2C_SET_COLOUR_MAP_ENTRIES: Int = 1
    const val S2C_BELL: Int = 2
    const val S2C_SERVER_CUT_TEXT: Int = 3

    // ── Encoding types ───────────────────────────────────────────────────────
    const val ENC_RAW: Int = 0
    const val ENC_COPY_RECT: Int = 1
    const val ENC_RRE: Int = 2
    const val ENC_HEXTILE: Int = 5
    const val ENC_ZLIB: Int = 6
    const val ENC_TIGHT: Int = 7
    const val ENC_ZRLE: Int = 16

    // ── Pseudo-encoding types ────────────────────────────────────────────────
    /** Server can resize the framebuffer at any time. */
    const val ENC_DESKTOP_SIZE: Int = -223
    /** Server sends software cursor separately (client renders it). */
    const val ENC_CURSOR: Int = -239
    /** No more rectangles follow in this update (TigerVNC extension). */
    const val ENC_LAST_RECT: Int = -224

    /** CoRRE encoding (type 4) — compact RRE with 1-byte sub-rect coordinates. */
    const val ENC_CORRE: Int = 4

    /**
     * Fence pseudo-encoding (RFC extension).  Advertised so servers know we
     * can handle ServerFence messages; the payload is consumed and discarded
     * since we do not use synchronous fencing.  Unsigned 0xFFFFFEC8 = -312.
     */
    const val ENC_FENCE: Int = -312

    // ── QEMU extension messages ─────────────────────────────────────────────
    /**
     * Server message type 255: QEMU extended messages.  Sub-type follows as
     * a u16.  Used by QEMU/KVM for LED state, audio, and pointer-mode changes.
     */
    const val S2C_QEMU_EXT: Int = 255
    const val QEMU_EXT_LED_STATE: Int = 0
    const val QEMU_EXT_AUDIO: Int = 1
    const val QEMU_EXT_POINTER_MOTION: Int = 2
    const val QEMU_AUDIO_BEGIN: Int = 0
    const val QEMU_AUDIO_DATA: Int = 1
    const val QEMU_AUDIO_END: Int = 2

    // ── Named client message types ──────────────────────────────────────────
    /** SetDesktopSize — client-initiated resize request (RFB extension §5.4). */
    const val C2S_SET_DESKTOP_SIZE: Int = 251

    /**
     * Client-initiated resize (RFB 3.8 extension, §5.4).
     * Advertised in SetEncodings so the server knows we support SetDesktopSize
     * messages.  Server acknowledges by sending an ExtendedDesktopSize pseudo-
     * rectangle.  Unsigned value 0xFFFFFECC = -308 as signed int32.
     */
    const val ENC_EXTENDED_DESKTOP_SIZE: Int = -308

    // ── Tight compression control byte top nibble ────────────────────────────
    const val TIGHT_FILL: Int = 0x08
    const val TIGHT_JPEG: Int = 0x09
    const val TIGHT_PNG: Int = 0x0A   // extended Tight (newer servers)

    // ── Tight filter IDs (BasicCompression) ─────────────────────────────────
    const val TIGHT_FILTER_COPY: Int = 0x00
    const val TIGHT_FILTER_PALETTE: Int = 0x01
    const val TIGHT_FILTER_GRADIENT: Int = 0x02

    // Minimum data length to apply zlib compression in Tight BasicCompression
    const val TIGHT_MIN_COMPRESS_SIZE: Int = 12

    // ── Hextile sub-encoding flags ───────────────────────────────────────────
    const val HEXTILE_RAW: Int = 1
    const val HEXTILE_BG_SPECIFIED: Int = 2
    const val HEXTILE_FG_SPECIFIED: Int = 4
    const val HEXTILE_ANY_SUBRECTS: Int = 8
    const val HEXTILE_SUBRECTS_COLOURED: Int = 16

    // ── X11 KeySym values for hardware / on-screen keyboard ─────────────────
    const val KEY_BACK_SPACE: Long = 0xFF08L
    const val KEY_TAB: Long = 0xFF09L
    const val KEY_RETURN: Long = 0xFF0DL
    const val KEY_ESCAPE: Long = 0xFF1BL
    const val KEY_DELETE: Long = 0xFFFFL
    const val KEY_INSERT: Long = 0xFF63L
    const val KEY_HOME: Long = 0xFF50L
    const val KEY_END: Long = 0xFF57L
    const val KEY_PAGE_UP: Long = 0xFF55L
    const val KEY_PAGE_DOWN: Long = 0xFF56L
    const val KEY_LEFT: Long = 0xFF51L
    const val KEY_UP: Long = 0xFF52L
    const val KEY_RIGHT: Long = 0xFF53L
    const val KEY_DOWN: Long = 0xFF54L
    const val KEY_F1: Long = 0xFFBEL
    const val KEY_F2: Long = 0xFFBFL
    const val KEY_F3: Long = 0xFFC0L
    const val KEY_F4: Long = 0xFFC1L
    const val KEY_F5: Long = 0xFFC2L
    const val KEY_F6: Long = 0xFFC3L
    const val KEY_F7: Long = 0xFFC4L
    const val KEY_F8: Long = 0xFFC5L
    const val KEY_F9: Long = 0xFFC6L
    const val KEY_F10: Long = 0xFFC7L
    const val KEY_F11: Long = 0xFFC8L
    const val KEY_F12: Long = 0xFFC9L
    const val KEY_SHIFT_L: Long = 0xFFE1L
    const val KEY_SHIFT_R: Long = 0xFFE2L
    const val KEY_CTRL_L: Long = 0xFFE3L
    const val KEY_CTRL_R: Long = 0xFFE4L
    const val KEY_ALT_L: Long = 0xFFE9L
    const val KEY_ALT_R: Long = 0xFFEAL
    const val KEY_SUPER_L: Long = 0xFFEBL
    const val KEY_SUPER_R: Long = 0xFFECL

    // ── Pointer button mask bits ─────────────────────────────────────────────
    const val BTN_LEFT: Int = 1
    const val BTN_MIDDLE: Int = 2
    const val BTN_RIGHT: Int = 4
    const val BTN_SCROLL_UP: Int = 8
    const val BTN_SCROLL_DOWN: Int = 16
}
