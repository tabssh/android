package io.github.tabssh.containers.transport

import io.github.tabssh.containers.ContainerEngine
import io.github.tabssh.containers.EngineCapability
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [EngineCapabilityGate] — the transport-layer backstop that refuses a request
 * for a concept the engine does not have before any command is built or any
 * socket is dialled.
 */
class EngineCapabilityGateTest {

    @Test
    fun `a supported capability is not rejected`() {
        val gate = EngineCapabilityGate(ContainerEngine.DOCKER)

        assertTrue(gate.supports(EngineCapability.COMPOSE_STACKS))
        assertNull(gate.reject(EngineCapability.COMPOSE_STACKS))
        gate.require(EngineCapability.COMPOSE_STACKS)
    }

    @Test
    fun `compose on incus is refused with a typed transport failure`() {
        val gate = EngineCapabilityGate(ContainerEngine.INCUS)

        assertFalse(gate.supports(EngineCapability.COMPOSE_STACKS))
        val refusal = assertNotNull(gate.reject(EngineCapability.COMPOSE_STACKS))
        assertTrue(refusal is ContainerResult.TransportUnavailable)
        assertEquals(ContainerTransportMessages.CAPABILITY_UNSUPPORTED, refusal.message)
        assertEquals(
            ContainerTransportMessages.capabilityDetail(
                ContainerEngine.INCUS,
                EngineCapability.COMPOSE_STACKS
            ),
            refusal.detail
        )
    }

    @Test
    fun `disk usage on lxd is refused`() {
        val gate = EngineCapabilityGate(ContainerEngine.LXD)

        assertNotNull(gate.reject(EngineCapability.DISK_USAGE))
    }

    @Test
    fun `snapshots on docker are refused`() {
        val gate = EngineCapabilityGate(ContainerEngine.DOCKER)

        assertNotNull(gate.reject(EngineCapability.SNAPSHOTS))
    }

    @Test
    fun `require throws the typed exception for streaming callers`() {
        val gate = EngineCapabilityGate(ContainerEngine.INCUS)

        val thrown = assertFailsWith<CapabilityUnsupportedException> {
            gate.require(EngineCapability.DISK_USAGE)
        }

        assertEquals(ContainerEngine.INCUS, thrown.engine)
        assertEquals(EngineCapability.DISK_USAGE, thrown.capability)
        assertEquals(ContainerTransportMessages.CAPABILITY_UNSUPPORTED, thrown.message)
    }

    @Test
    fun `every engine keeps the capabilities the transport always uses`() {
        val universal = listOf(
            EngineCapability.CONTAINERS,
            EngineCapability.IMAGES,
            EngineCapability.VOLUMES,
            EngineCapability.NETWORKS,
            EngineCapability.EXEC,
            EngineCapability.LOGS,
            EngineCapability.STATS
        )

        for (engine in ContainerEngine.entries) {
            val gate = EngineCapabilityGate(engine)
            for (capability in universal) {
                assertNull(
                    gate.reject(capability),
                    "${engine.id} refused $capability, which every engine has"
                )
            }
        }
    }
}
