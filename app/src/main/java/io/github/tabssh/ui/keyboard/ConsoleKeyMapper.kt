package io.github.tabssh.ui.keyboard

import io.github.tabssh.hypervisor.console.rfb.RfbConstants
import io.github.tabssh.hypervisor.spice.SpiceConstants

/**
 * Maps custom-keyboard-bar [KeyboardKey] ids to the X11 keysyms (RFB) and
 * PS/2 scancodes (SPICE) needed to drive a graphical console session.
 *
 * Only named keys with a real console equivalent are listed — printable
 * single-character keys (symbols, letters) are sent through the active
 * view's `sendChar` instead, which already resolves char → keysym/scancode.
 * A key id absent from the relevant map has no console equivalent and must
 * be dropped by the caller after a debug log, never crash.
 */
object ConsoleKeyMapper {

    /** Named-key id → X11 keysym, sent via [io.github.tabssh.ui.views.VncView.sendKey]. */
    val RFB_KEYSYM_BY_ID: Map<String, Long> = mapOf(
        "ESC" to RfbConstants.KEY_ESCAPE,
        "TAB" to RfbConstants.KEY_TAB,
        "ENTER" to RfbConstants.KEY_RETURN,
        "BACKSPACE" to RfbConstants.KEY_BACK_SPACE,
        "DELETE" to RfbConstants.KEY_DELETE,
        "INSERT" to RfbConstants.KEY_INSERT,
        "HOME" to RfbConstants.KEY_HOME,
        "END" to RfbConstants.KEY_END,
        "PGUP" to RfbConstants.KEY_PAGE_UP,
        "PGDN" to RfbConstants.KEY_PAGE_DOWN,
        "UP" to RfbConstants.KEY_UP,
        "DOWN" to RfbConstants.KEY_DOWN,
        "LEFT" to RfbConstants.KEY_LEFT,
        "RIGHT" to RfbConstants.KEY_RIGHT,
        "F1" to RfbConstants.KEY_F1,
        "F2" to RfbConstants.KEY_F2,
        "F3" to RfbConstants.KEY_F3,
        "F4" to RfbConstants.KEY_F4,
        "F5" to RfbConstants.KEY_F5,
        "F6" to RfbConstants.KEY_F6,
        "F7" to RfbConstants.KEY_F7,
        "F8" to RfbConstants.KEY_F8,
        "F9" to RfbConstants.KEY_F9,
        "F10" to RfbConstants.KEY_F10,
        "F11" to RfbConstants.KEY_F11,
        "F12" to RfbConstants.KEY_F12
    )

    /** Named-key id → PS/2 scancode, sent via [io.github.tabssh.ui.views.SpiceView.sendScancode]. */
    val SPICE_SCANCODE_BY_ID: Map<String, Int> = mapOf(
        "ESC" to SpiceConstants.SC_ESC,
        "TAB" to SpiceConstants.SC_TAB,
        "ENTER" to SpiceConstants.SC_ENTER,
        "BACKSPACE" to SpiceConstants.SC_BACKSPACE,
        "DELETE" to SpiceConstants.SC_DELETE,
        "INSERT" to SpiceConstants.SC_INSERT,
        "HOME" to SpiceConstants.SC_HOME,
        "END" to SpiceConstants.SC_END,
        "PGUP" to SpiceConstants.SC_PAGE_UP,
        "PGDN" to SpiceConstants.SC_PAGE_DOWN,
        "UP" to SpiceConstants.SC_UP,
        "DOWN" to SpiceConstants.SC_DOWN,
        "LEFT" to SpiceConstants.SC_LEFT,
        "RIGHT" to SpiceConstants.SC_RIGHT,
        "F1" to SpiceConstants.SC_F1,
        "F2" to SpiceConstants.SC_F2,
        "F3" to SpiceConstants.SC_F3,
        "F4" to SpiceConstants.SC_F4,
        "F5" to SpiceConstants.SC_F5,
        "F6" to SpiceConstants.SC_F6,
        "F7" to SpiceConstants.SC_F7,
        "F8" to SpiceConstants.SC_F8,
        "F9" to SpiceConstants.SC_F9,
        "F10" to SpiceConstants.SC_F10,
        "F11" to SpiceConstants.SC_F11,
        "F12" to SpiceConstants.SC_F12
    )

    /** Latched-modifier bar id (CTL/ALT/SFT) → X11 keysym, for bracketing the next console key. */
    val RFB_MODIFIER_KEYSYM: Map<String, Long> = mapOf(
        "CTL" to RfbConstants.KEY_CTRL_L,
        "ALT" to RfbConstants.KEY_ALT_L,
        "SFT" to RfbConstants.KEY_SHIFT_L
    )

    /** Latched-modifier bar id (CTL/ALT/SFT) → PS/2 scancode, for bracketing the next console key. */
    val SPICE_MODIFIER_SCANCODE: Map<String, Int> = mapOf(
        "CTL" to SpiceConstants.SC_LEFT_CTRL,
        "ALT" to SpiceConstants.SC_LEFT_ALT,
        "SFT" to SpiceConstants.SC_LEFT_SHIFT
    )
}
