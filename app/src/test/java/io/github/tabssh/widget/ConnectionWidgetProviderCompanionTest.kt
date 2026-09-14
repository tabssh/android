package io.github.tabssh.widget

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Verifies the widget-refresh helpers: every size-variant provider must be
 * enumerated (each registers as its own manifest receiver, so querying only
 * the base component misses widgets placed as the variants) and the info
 * label must keep its user@host shape.
 */
class ConnectionWidgetProviderCompanionTest {

    @Test
    fun `provider list covers the base provider and every size variant`() {
        val expected = setOf(
            ConnectionWidgetProvider::class.java,
            ConnectionWidgetProvider.Widget2x1::class.java,
            ConnectionWidgetProvider.Widget4x2::class.java,
            ConnectionWidgetProvider.Widget4x4::class.java
        )
        assertEquals(expected, ConnectionWidgetProvider.PROVIDER_CLASSES.toSet())
    }

    @Test
    fun `provider list covers every nested provider subclass declared in the class`() {
        // Reflection guard: adding a new size variant without listing it would silently skip its widgets.
        val declaredVariants = ConnectionWidgetProvider::class.java.declaredClasses
            .filter { ConnectionWidgetProvider::class.java.isAssignableFrom(it) }
        assertTrue(declaredVariants.isNotEmpty())
        declaredVariants.forEach { variant ->
            assertTrue(
                ConnectionWidgetProvider.PROVIDER_CLASSES.contains(variant),
                "Nested provider ${variant.simpleName} is missing from PROVIDER_CLASSES"
            )
        }
    }

    @Test
    fun `connection info label is user at host`() {
        assertEquals("root@example.com", ConnectionWidgetProvider.connectionInfoLabel("root", "example.com"))
    }
}
