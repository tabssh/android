package io.github.tabssh.containers.registry

import org.junit.Test
import kotlin.test.assertEquals

class UpdateSettingsResolverTest {

    private fun resolve(
        hostEnabled: Boolean? = null,
        hostAutoApply: Boolean? = null,
        hostInterval: Int? = null,
        stackEnabled: Boolean? = null,
        stackAutoApply: Boolean? = null,
        imageEnabled: Boolean? = null,
        imageAutoApply: Boolean? = null,
        globalEnabled: Boolean = true,
        globalAutoApply: Boolean = false
    ) = UpdateSettingsResolver.resolve(
        globalEnabled, globalAutoApply, 12L,
        hostEnabled, hostAutoApply, hostInterval,
        stackEnabled, stackAutoApply, imageEnabled, imageAutoApply
    )

    @Test
    fun `no overrides inherit the global tier`() {
        val s = resolve()
        assertEquals(Resolved(true, UpdateTier.GLOBAL), s.enabled)
        assertEquals(Resolved(false, UpdateTier.GLOBAL), s.autoApply)
        assertEquals(Resolved(12L, UpdateTier.GLOBAL), s.intervalHours)
    }

    @Test
    fun `host overrides global in both directions`() {
        assertEquals(Resolved(true, UpdateTier.HOST), resolve(globalEnabled = false, hostEnabled = true).enabled)
        assertEquals(Resolved(false, UpdateTier.HOST), resolve(globalEnabled = true, hostEnabled = false).enabled)
        assertEquals(Resolved(true, UpdateTier.HOST), resolve(hostAutoApply = true).autoApply)
    }

    @Test
    fun `image overrides stack overrides host`() {
        val s = resolve(hostAutoApply = true, stackAutoApply = false, imageAutoApply = true)
        assertEquals(Resolved(true, UpdateTier.IMAGE), s.autoApply)
        assertEquals(Resolved(false, UpdateTier.STACK), resolve(hostAutoApply = true, stackAutoApply = false).autoApply)
    }

    @Test
    fun `host interval overrides global and non-positive falls back`() {
        assertEquals(Resolved(6L, UpdateTier.HOST), resolve(hostInterval = 6).intervalHours)
        assertEquals(Resolved(12L, UpdateTier.GLOBAL), resolve(hostInterval = 0).intervalHours)
    }
}
