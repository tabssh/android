package io.github.tabssh.docker.transport

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Legacy pinned-mode migration decision — the removed "api_socat" bridge
 * tier must fall through to full auto-detection instead of being treated
 * as a still-valid pin ([TransportCapabilityDetector.isAutoOrLegacy]).
 */
class TransportCapabilityDetectorLegacyModeTest {

    @Test
    fun `auto is treated as auto`() {
        assertTrue(TransportCapabilityDetector.isAutoOrLegacy("auto"))
    }

    @Test
    fun `legacy api_socat pin is treated as auto`() {
        assertTrue(TransportCapabilityDetector.isAutoOrLegacy("api_socat"))
    }

    @Test
    fun `pinned streamlocal mode is not treated as auto`() {
        assertFalse(TransportCapabilityDetector.isAutoOrLegacy("api_streamlocal"))
    }

    @Test
    fun `pinned dial-stdio mode is not treated as auto`() {
        assertFalse(TransportCapabilityDetector.isAutoOrLegacy("api_stdio"))
    }

    @Test
    fun `pinned cli_exec mode is not treated as auto`() {
        assertFalse(TransportCapabilityDetector.isAutoOrLegacy("cli_exec"))
    }
}
