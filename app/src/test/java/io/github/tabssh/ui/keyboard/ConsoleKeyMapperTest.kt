package io.github.tabssh.ui.keyboard

import io.github.tabssh.hypervisor.console.rfb.RfbConstants
import io.github.tabssh.hypervisor.spice.SpiceConstants
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [ConsoleKeyMapper] — the named-key id to X11 keysym (RFB) / PS-2
 * scancode (SPICE) lookup tables that drive the custom keyboard bar on
 * graphical console (VNC/SPICE) tabs.
 *
 * Every default-layout key id is covered in both directions: named keys
 * with a real console equivalent must resolve to the exact expected
 * keysym/scancode, and keys with no console equivalent (printable
 * characters, which route through `sendChar` instead, and bar-level
 * actions such as CLIPBOARD/MENU/TOGGLE) must be explicitly absent from
 * both maps so the caller's drop-and-log fallback is exercised, not a
 * silently wrong mapping.
 */
class ConsoleKeyMapperTest {

    private val allKeyIds = KeyboardKey.getAllAvailableKeys().map { it.id }.toSet()

    // Ids with a real console (RFB/SPICE) equivalent — every other default
    // key id must be absent from both maps.
    private val mappedKeyIds = setOf(
        "ESC", "TAB", "ENTER", "BACKSPACE", "DELETE", "INSERT",
        "HOME", "END", "PGUP", "PGDN", "UP", "DOWN", "LEFT", "RIGHT",
        "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12"
    )

    @Test
    fun `RFB keysym table maps every named key to the exact RfbConstants value`() {
        assertEquals(RfbConstants.KEY_ESCAPE, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["ESC"])
        assertEquals(RfbConstants.KEY_TAB, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["TAB"])
        assertEquals(RfbConstants.KEY_RETURN, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["ENTER"])
        assertEquals(RfbConstants.KEY_BACK_SPACE, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["BACKSPACE"])
        assertEquals(RfbConstants.KEY_DELETE, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["DELETE"])
        assertEquals(RfbConstants.KEY_INSERT, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["INSERT"])
        assertEquals(RfbConstants.KEY_HOME, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["HOME"])
        assertEquals(RfbConstants.KEY_END, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["END"])
        assertEquals(RfbConstants.KEY_PAGE_UP, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["PGUP"])
        assertEquals(RfbConstants.KEY_PAGE_DOWN, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["PGDN"])
        assertEquals(RfbConstants.KEY_UP, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["UP"])
        assertEquals(RfbConstants.KEY_DOWN, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["DOWN"])
        assertEquals(RfbConstants.KEY_LEFT, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["LEFT"])
        assertEquals(RfbConstants.KEY_RIGHT, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["RIGHT"])
        assertEquals(RfbConstants.KEY_F1, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["F1"])
        assertEquals(RfbConstants.KEY_F2, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["F2"])
        assertEquals(RfbConstants.KEY_F3, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["F3"])
        assertEquals(RfbConstants.KEY_F4, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["F4"])
        assertEquals(RfbConstants.KEY_F5, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["F5"])
        assertEquals(RfbConstants.KEY_F6, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["F6"])
        assertEquals(RfbConstants.KEY_F7, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["F7"])
        assertEquals(RfbConstants.KEY_F8, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["F8"])
        assertEquals(RfbConstants.KEY_F9, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["F9"])
        assertEquals(RfbConstants.KEY_F10, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["F10"])
        assertEquals(RfbConstants.KEY_F11, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["F11"])
        assertEquals(RfbConstants.KEY_F12, ConsoleKeyMapper.RFB_KEYSYM_BY_ID["F12"])
    }

    @Test
    fun `SPICE scancode table maps every named key to the exact SpiceConstants value`() {
        assertEquals(SpiceConstants.SC_ESC, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["ESC"])
        assertEquals(SpiceConstants.SC_TAB, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["TAB"])
        assertEquals(SpiceConstants.SC_ENTER, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["ENTER"])
        assertEquals(SpiceConstants.SC_BACKSPACE, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["BACKSPACE"])
        assertEquals(SpiceConstants.SC_DELETE, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["DELETE"])
        assertEquals(SpiceConstants.SC_INSERT, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["INSERT"])
        assertEquals(SpiceConstants.SC_HOME, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["HOME"])
        assertEquals(SpiceConstants.SC_END, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["END"])
        assertEquals(SpiceConstants.SC_PAGE_UP, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["PGUP"])
        assertEquals(SpiceConstants.SC_PAGE_DOWN, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["PGDN"])
        assertEquals(SpiceConstants.SC_UP, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["UP"])
        assertEquals(SpiceConstants.SC_DOWN, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["DOWN"])
        assertEquals(SpiceConstants.SC_LEFT, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["LEFT"])
        assertEquals(SpiceConstants.SC_RIGHT, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["RIGHT"])
        assertEquals(SpiceConstants.SC_F1, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["F1"])
        assertEquals(SpiceConstants.SC_F2, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["F2"])
        assertEquals(SpiceConstants.SC_F3, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["F3"])
        assertEquals(SpiceConstants.SC_F4, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["F4"])
        assertEquals(SpiceConstants.SC_F5, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["F5"])
        assertEquals(SpiceConstants.SC_F6, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["F6"])
        assertEquals(SpiceConstants.SC_F7, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["F7"])
        assertEquals(SpiceConstants.SC_F8, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["F8"])
        assertEquals(SpiceConstants.SC_F9, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["F9"])
        assertEquals(SpiceConstants.SC_F10, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["F10"])
        assertEquals(SpiceConstants.SC_F11, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["F11"])
        assertEquals(SpiceConstants.SC_F12, ConsoleKeyMapper.SPICE_SCANCODE_BY_ID["F12"])
    }

    @Test
    fun `modifier tables map CTL ALT SFT to left-side keysym and scancode`() {
        assertEquals(RfbConstants.KEY_CTRL_L, ConsoleKeyMapper.RFB_MODIFIER_KEYSYM["CTL"])
        assertEquals(RfbConstants.KEY_ALT_L, ConsoleKeyMapper.RFB_MODIFIER_KEYSYM["ALT"])
        assertEquals(RfbConstants.KEY_SHIFT_L, ConsoleKeyMapper.RFB_MODIFIER_KEYSYM["SFT"])
        assertEquals(SpiceConstants.SC_LEFT_CTRL, ConsoleKeyMapper.SPICE_MODIFIER_SCANCODE["CTL"])
        assertEquals(SpiceConstants.SC_LEFT_ALT, ConsoleKeyMapper.SPICE_MODIFIER_SCANCODE["ALT"])
        assertEquals(SpiceConstants.SC_LEFT_SHIFT, ConsoleKeyMapper.SPICE_MODIFIER_SCANCODE["SFT"])
    }

    @Test
    fun `every mapped id has both an RFB keysym and a SPICE scancode entry`() {
        mappedKeyIds.forEach { id ->
            assertTrue(
                ConsoleKeyMapper.RFB_KEYSYM_BY_ID.containsKey(id),
                "RFB_KEYSYM_BY_ID is missing named key '$id'"
            )
            assertTrue(
                ConsoleKeyMapper.SPICE_SCANCODE_BY_ID.containsKey(id),
                "SPICE_SCANCODE_BY_ID is missing named key '$id'"
            )
        }
    }

    @Test
    fun `every default-layout key id not in mappedKeyIds is absent from both console maps`() {
        val unmappedIds = allKeyIds - mappedKeyIds
        unmappedIds.forEach { id ->
            assertTrue(
                !ConsoleKeyMapper.RFB_KEYSYM_BY_ID.containsKey(id),
                "RFB_KEYSYM_BY_ID unexpectedly maps '$id' — printable/action keys must route " +
                    "through sendChar or an activity-level action instead"
            )
            assertTrue(
                !ConsoleKeyMapper.SPICE_SCANCODE_BY_ID.containsKey(id),
                "SPICE_SCANCODE_BY_ID unexpectedly maps '$id' — printable/action keys must route " +
                    "through sendChar or an activity-level action instead"
            )
        }
    }

    @Test
    fun `both console maps contain no ids outside the default key palette`() {
        ConsoleKeyMapper.RFB_KEYSYM_BY_ID.keys.forEach { id ->
            assertTrue(id in allKeyIds, "RFB_KEYSYM_BY_ID has stray id '$id' not in KeyboardKey palette")
        }
        ConsoleKeyMapper.SPICE_SCANCODE_BY_ID.keys.forEach { id ->
            assertTrue(id in allKeyIds, "SPICE_SCANCODE_BY_ID has stray id '$id' not in KeyboardKey palette")
        }
    }
}
