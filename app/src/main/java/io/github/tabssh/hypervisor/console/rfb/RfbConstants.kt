package io.github.tabssh.hypervisor.console.rfb

/**
 * RFB 3.8 protocol constants (RFC 6143 + vendor extensions).
 *
 * Sources:
 *   RFC 6143 — https://www.rfc-editor.org/rfc/rfc6143
 *   rfbproto/rfbproto — https://github.com/rfbproto/rfbproto/blob/master/rfbproto.rst
 *   IANA RFB assignments — https://www.iana.org/assignments/rfb/rfb.xhtml
 *   QEMU ui/vnc.h — https://github.com/qemu/qemu/blob/master/ui/vnc.h
 *   TigerVNC CMsgReader — https://github.com/TigerVNC/tigervnc
 *   LibVNCServer rfb/rfbproto.h — https://libvnc.github.io
 *   noVNC rfb.js — https://github.com/novnc/noVNC
 *   UltraVNC rfb/rfbproto.h — https://github.com/veyon/ultravnc
 */
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
    /** SetDesktopSize — client-initiated resize request (RFB extension §5.4). */
    const val C2S_SET_DESKTOP_SIZE: Int = 251

    /**
     * EnableContinuousUpdates — client opts into continuous-update mode for a
     * region. Sent after the server advertises [ENC_CONTINUOUS_UPDATES]; the
     * server then streams FramebufferUpdates for the named region without
     * requiring a FramebufferUpdateRequest between each one (TigerVNC §1.4.7).
     * Payload: U8 enable-flag, U16 x, U16 y, U16 width, U16 height.
     */
    const val C2S_ENABLE_CONTINUOUS_UPDATES: Int = 150

    // ── Server → Client message types ───────────────────────────────────────
    const val S2C_FRAMEBUFFER_UPDATE: Int = 0
    const val S2C_SET_COLOUR_MAP_ENTRIES: Int = 1
    const val S2C_BELL: Int = 2
    const val S2C_SERVER_CUT_TEXT: Int = 3

    /**
     * UltraVNC ResizeFrameBuffer (type 4 / 0x04).
     * Payload: 1 byte padding + U16 width + U16 height = 5 bytes.
     */
    const val S2C_ULTRAVNC_RESIZE: Int = 4

    /**
     * EndOfContinuousUpdates (type 150 / 0x96) — TigerVNC extension.
     * Signals the server is no longer sending continuous updates.
     * Zero payload.
     */
    const val S2C_END_OF_CONTINUOUS_UPDATES: Int = 150

    /**
     * ServerFence (type 248 / 0xF8) — TigerVNC/rfbproto extension.
     * Payload: 3 bytes padding + U32 flags + U8 length + length bytes data.
     * QEMU/Proxmox sends this before the first FramebufferUpdate; the client
     * must echo it back or the update queue stays blocked (black screen).
     */
    const val S2C_FENCE: Int = 248

    /**
     * ClientFence message type (248, same wire byte as ServerFence).
     * Client echoes back the server's flags (minus Request bit) to unblock.
     */
    const val C2S_CLIENT_FENCE: Int = 248

    /**
     * xvp Server Message (type 250 / 0xFA).
     * Payload: 1 byte padding + U8 version + U8 message-code = 3 bytes.
     * Used by xvp VNC extension for power control (shutdown/reboot/reset).
     */
    const val S2C_XVP: Int = 250

    /**
     * gii Server Message (type 253 / 0xFD) — General Input Interface.
     * Payload: U8 endian/subtype + EU16 length + length bytes payload.
     */
    const val S2C_GII: Int = 253

    /**
     * QEMU Server Message (type 255 / 0xFF).
     * Payload: U8 subtype + subtype-specific data.
     * Only subtype 1 (Audio) is defined for server-to-client.
     */
    const val S2C_QEMU_EXT: Int = 255

    // ── Encoding types (pixel data) ───────────────────────────────────────────
    const val ENC_RAW: Int = 0
    const val ENC_COPY_RECT: Int = 1
    const val ENC_RRE: Int = 2
    /** CoRRE (type 4) — compact RRE with 1-byte sub-rect coordinates. */
    const val ENC_CORRE: Int = 4
    const val ENC_HEXTILE: Int = 5
    const val ENC_ZLIB: Int = 6
    const val ENC_TIGHT: Int = 7
    const val ENC_ZRLE: Int = 16

    // ── Pseudo-encoding types ─────────────────────────────────────────────────
    // Negative values are pseudo-encodings per RFB spec convention.
    // They appear as rectangle encoding fields in FramebufferUpdate but carry
    // metadata or capability signals, not pixel data.

    /**
     * DesktopSize (-223 / 0xFFFFFF21).
     * No payload. Width and height in the rectangle header are the new
     * framebuffer dimensions.
     */
    const val ENC_DESKTOP_SIZE: Int = -223

    /**
     * LastRect (-224 / 0xFFFFFF20).
     * No payload. Signals no more rectangles follow in this update.
     */
    const val ENC_LAST_RECT: Int = -224

    /**
     * PointerPos (-232 / 0xFFFFFF18).
     * No payload. Rectangle x/y carry the current pointer position.
     */
    const val ENC_POINTER_POS: Int = -232

    /**
     * Cursor / RichCursor (-239 / 0xFFFFFF11).
     * Payload: w×h×Bpp bytes (cursor pixels) + ⌈w/8⌉×h bytes (1-bit mask).
     * Hotspot in rectangle x/y. Client renders the cursor locally.
     */
    const val ENC_CURSOR: Int = -239

    /**
     * XCursor (-240 / 0xFFFFFF10).
     * Payload: 6-byte color header (2×RGB) + 2×⌈w/8⌉×h bytes (AND+XOR masks).
     * Obsolete 1-bit cursor format; hotspot in rectangle x/y.
     */
    const val ENC_XCURSOR: Int = -240

    // CompressionLevel pseudo-encodings (-247 to -256 / 0xFFFFFF09 to 0xFFFFFF00).
    // No payload. Range encodes hint: -256=level0 … -247=level9.
    const val ENC_COMPRESS_LEVEL_0: Int = -256  // lowest compression
    const val ENC_COMPRESS_LEVEL_9: Int = -247  // highest compression

    // QualityLevel pseudo-encodings (-23 to -32 / 0xFFFFFFE9 to 0xFFFFFFE0).
    // No payload. Range encodes hint: -32=level0 … -23=level9.
    const val ENC_QUALITY_LEVEL_0: Int = -32    // lowest quality
    const val ENC_QUALITY_LEVEL_9: Int = -23    // highest quality

    /**
     * QEMU LED State pseudo-encoding (-261 / 0xFFFFFEFB).
     * Payload: 1 byte U8 with LED bitmask.
     *   bit 0 = ScrollLock, bit 1 = NumLock, bit 2 = CapsLock
     * Delivered inside a FramebufferUpdate as a 1×1 pseudo-rectangle.
     * This is the correct mechanism (NOT a top-level message type 0xB9).
     */
    const val ENC_LED_STATE: Int = -261

    /**
     * DesktopName (-307 / 0xFFFFFEB5).
     * Payload: U32 name-length + UTF-8 name bytes.
     */
    const val ENC_DESKTOP_NAME: Int = -307

    /**
     * ExtendedDesktopSize (-308 / 0xFFFFFECC) — standard RFB extension.
     * Payload: U8 numScreens + 3 bytes padding + 16 bytes per screen.
     * Each screen: U32 id + U16 x + U16 y + U16 w + U16 h + U32 flags.
     * Rectangle x = reason (0=server, 1=admin, 2=client).
     * Rectangle y = status (0=OK, 1=prohibited, 2=no-resources, 3=bad-layout).
     * Rectangle w/h = new framebuffer dimensions when status=0.
     *
     * @see ENC_QEMU_EXTENDED_DESKTOP_SIZE for QEMU's non-standard alias.
     */
    const val ENC_EXTENDED_DESKTOP_SIZE: Int = -308

    /**
     * QEMU-internal ExtendedDesktopSize alias (-52 / 0xFFFFFFCC).
     * QEMU defines VNC_ENCODING_DESKTOP_RESIZE_EXT = 0xFFFFFFCC (-52) rather
     * than the IANA-registered -308 (0xFFFFFECC). Wire format is identical to
     * ENC_EXTENDED_DESKTOP_SIZE. QEMU sends this encoding regardless of which
     * value the client advertised; we must handle both.
     */
    const val ENC_QEMU_EXTENDED_DESKTOP_SIZE: Int = -52

    /**
     * Fence pseudo-encoding (-312 / 0xFFFFFEC8) — capability advertisement.
     * No payload in pseudo-encoding advertisement rectangle.
     * Enables ServerFence (type 248) / ClientFence (type 248) messages.
     * When sent as an inline Fence inside FramebufferUpdate:
     * payload = U32 flags + U8 length + length bytes data.
     */
    const val ENC_FENCE: Int = -312

    /**
     * ContinuousUpdates capability advertisement (-313 / 0xFFFFFEC7).
     * No payload. Enables EnableContinuousUpdates (client 150) /
     * EndOfContinuousUpdates (server 150).
     */
    const val ENC_CONTINUOUS_UPDATES: Int = -313

    /**
     * CursorWithAlpha (-314 / 0xFFFFFEC6).
     * Payload: 4×w×h bytes (pre-multiplied RGBA). Hotspot in rectangle x/y.
     */
    const val ENC_CURSOR_WITH_ALPHA: Int = -314

    // ── Tight compression control byte top nibble ────────────────────────────
    const val TIGHT_FILL: Int = 0x08
    const val TIGHT_JPEG: Int = 0x09
    const val TIGHT_PNG: Int = 0x0A   // extended Tight (TightPng)

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

    // ── QEMU extension messages ─────────────────────────────────────────────
    /**
     * QEMU Audio server sub-message (type 255, sub-type 1 / 0x01).
     * This is the only defined server-to-client QEMU sub-type.
     * Sub-types 0 and 2 are client-to-server only (extended key event /
     * pointer motion) and will never be sent by the server.
     */
    const val QEMU_EXT_AUDIO: Int = 1

    /**
     * QEMU Audio operation codes (U16 field following sub-type byte).
     * Correct mapping per noVNC rfb.js and QEMU ui/vnc.c:
     *   0 = stream end   (no extra bytes)
     *   1 = stream begin (6 bytes: U8 format + U8 nchannels + U32 freq)
     *   2 = audio data   (U32 length + length bytes of PCM data)
     */
    const val QEMU_AUDIO_END: Int = 0
    const val QEMU_AUDIO_BEGIN: Int = 1
    const val QEMU_AUDIO_DATA: Int = 2

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
