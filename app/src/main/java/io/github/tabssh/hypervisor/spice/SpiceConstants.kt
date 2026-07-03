package io.github.tabssh.hypervisor.spice

/**
 * SPICE protocol constants used by the view and client layers.
 *
 * SPICE's inputs channel talks in PS/2 keyboard scancodes (Set 1, the
 * IBM PC/AT default) rather than X11 keysyms. The button API takes a
 * numeric button ID plus a bitmask of currently-held buttons for the
 * `position` message.
 *
 * References:
 *  - spice-protocol/spice/enums.h — SPICE_MOUSE_BUTTON_*
 *  - PS/2 Set 1 make-code table (IBM PC/AT keyboard reference)
 *
 * Only the make code is stored here. Key release is signalled by
 * OR'ing 0x80 into the byte — the C side does that when [SpiceClient]
 * calls the release path, so callers pass the make code straight
 * through.
 */
object SpiceConstants {
    /* Mouse button IDs (SPICE_MOUSE_BUTTON_*). */
    const val BTN_LEFT = 1
    const val BTN_MIDDLE = 2
    const val BTN_RIGHT = 3
    const val BTN_UP = 4
    const val BTN_DOWN = 5

    /* Bitmask bits for the `state` field of the position message. */
    const val MASK_LEFT = 1 shl 0
    const val MASK_MIDDLE = 1 shl 1
    const val MASK_RIGHT = 1 shl 2

    /*
     * PS/2 Set 1 make codes for keys the view routes explicitly.
     * Extended codes (0xE0-prefixed) are folded into the 16-bit
     * scancode used by libspice — high byte = 0xE0 when set.
     */
    const val SC_ESC = 0x01
    const val SC_BACKSPACE = 0x0E
    const val SC_TAB = 0x0F
    const val SC_ENTER = 0x1C
    const val SC_LEFT_CTRL = 0x1D
    const val SC_LEFT_SHIFT = 0x2A
    const val SC_RIGHT_SHIFT = 0x36
    const val SC_LEFT_ALT = 0x38
    const val SC_SPACE = 0x39
    const val SC_CAPSLOCK = 0x3A
    const val SC_F1 = 0x3B
    const val SC_F2 = 0x3C
    const val SC_F3 = 0x3D
    const val SC_F4 = 0x3E
    const val SC_F5 = 0x3F
    const val SC_F6 = 0x40
    const val SC_F7 = 0x41
    const val SC_F8 = 0x42
    const val SC_F9 = 0x43
    const val SC_F10 = 0x44
    const val SC_F11 = 0x57
    const val SC_F12 = 0x58

    /* Extended (0xE0-prefixed) codes — value is (0xE0 shl 8) | make. */
    const val SC_RIGHT_CTRL = 0xE01D
    const val SC_RIGHT_ALT = 0xE038
    const val SC_LEFT_META = 0xE05B
    const val SC_RIGHT_META = 0xE05C
    const val SC_INSERT = 0xE052
    const val SC_DELETE = 0xE053
    const val SC_HOME = 0xE047
    const val SC_END = 0xE04F
    const val SC_PAGE_UP = 0xE049
    const val SC_PAGE_DOWN = 0xE051
    const val SC_UP = 0xE048
    const val SC_DOWN = 0xE050
    const val SC_LEFT = 0xE04B
    const val SC_RIGHT = 0xE04D
}
