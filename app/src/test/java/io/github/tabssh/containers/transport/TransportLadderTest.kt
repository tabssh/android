package io.github.tabssh.containers.transport

import io.github.tabssh.containers.ContainerEngine
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TransportCapabilityDetector.ladderFor] — which tiers are attempted, for
 * which engine, against which endpoint shape. Pure: no SSH session, no host.
 *
 * The rule under test is that a tier an engine cannot use is never attempted
 * and never appears in its failure list, rather than being tried and failing.
 */
class TransportLadderTest {

    @Test
    fun `docker over a unix socket tries streamlocal, then dial-stdio, then cli`() {
        val ladder = TransportCapabilityDetector.ladderFor(
            ContainerEngine.DOCKER,
            ContainerEndpointKind.UNIX
        )

        assertEquals(
            listOf(
                TransportCapabilityDetector.MODE_API_STREAMLOCAL,
                TransportCapabilityDetector.MODE_API_STDIO,
                TransportCapabilityDetector.MODE_CLI_EXEC
            ),
            ladder
        )
    }

    @Test
    fun `podman gets the same ladder as docker`() {
        assertEquals(
            TransportCapabilityDetector.ladderFor(
                ContainerEngine.DOCKER,
                ContainerEndpointKind.UNIX
            ),
            TransportCapabilityDetector.ladderFor(
                ContainerEngine.PODMAN,
                ContainerEndpointKind.UNIX
            )
        )
    }

    @Test
    fun `incus skips the dial-stdio tier entirely`() {
        val ladder = TransportCapabilityDetector.ladderFor(
            ContainerEngine.INCUS,
            ContainerEndpointKind.UNIX
        )

        assertEquals(
            listOf(
                TransportCapabilityDetector.MODE_API_STREAMLOCAL,
                TransportCapabilityDetector.MODE_CLI_EXEC
            ),
            ladder
        )
        assertFalse(TransportCapabilityDetector.MODE_API_STDIO in ladder)
    }

    @Test
    fun `lxd skips the dial-stdio tier entirely`() {
        val ladder = TransportCapabilityDetector.ladderFor(
            ContainerEngine.LXD,
            ContainerEndpointKind.UNIX
        )

        assertFalse(TransportCapabilityDetector.MODE_API_STDIO in ladder)
        assertEquals(TransportCapabilityDetector.MODE_CLI_EXEC, ladder.last())
    }

    @Test
    fun `a tcp endpoint forwards with direct-tcpip, never streamlocal`() {
        val ladder = TransportCapabilityDetector.ladderFor(
            ContainerEngine.DOCKER,
            ContainerEndpointKind.TCP
        )

        assertEquals(
            listOf(
                TransportCapabilityDetector.MODE_API_TCP,
                TransportCapabilityDetector.MODE_CLI_EXEC
            ),
            ladder
        )
    }

    @Test
    fun `a nested ssh endpoint is reachable only through the remote cli`() {
        val ladder = TransportCapabilityDetector.ladderFor(
            ContainerEngine.DOCKER,
            ContainerEndpointKind.SSH
        )

        assertEquals(
            listOf(
                TransportCapabilityDetector.MODE_API_STDIO,
                TransportCapabilityDetector.MODE_CLI_EXEC
            ),
            ladder
        )
    }

    @Test
    fun `every ladder ends at the cli tier`() {
        for (engine in ContainerEngine.entries) {
            for (kind in ContainerEndpointKind.entries) {
                val ladder = TransportCapabilityDetector.ladderFor(engine, kind)
                assertTrue(ladder.isNotEmpty(), "$engine/$kind produced an empty ladder")
                assertEquals(
                    TransportCapabilityDetector.MODE_CLI_EXEC,
                    ladder.last(),
                    "$engine/$kind does not end at the cli tier"
                )
            }
        }
    }

    @Test
    fun `only api tiers are ever pinnable`() {
        assertFalse(
            TransportCapabilityDetector.MODE_CLI_EXEC in TransportCapabilityDetector.PINNABLE_MODES
        )
        assertFalse(
            TransportCapabilityDetector.MODE_AUTO in TransportCapabilityDetector.PINNABLE_MODES
        )
        assertTrue(
            TransportCapabilityDetector.MODE_API_TCP in TransportCapabilityDetector.PINNABLE_MODES
        )
    }
}
