package io.github.tabssh.ui.utils

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the volume/network/container name grammar.
 *
 * These values are typed by the user and handed to the transport as `docker`
 * command arguments. A leading dash would be parsed as an option and a name
 * containing whitespace or shell metacharacters would split into extra
 * arguments, so the UI must reject them before the transport sees them.
 */
class ContainerNamesValidationTest {

    @Test
    fun `accepts ordinary names`() {
        assertTrue(ContainerNames.isValidResourceName("web"))
        assertTrue(ContainerNames.isValidResourceName("web_data"))
        assertTrue(ContainerNames.isValidResourceName("web-data.1"))
        assertTrue(ContainerNames.isValidResourceName("0"))
    }

    @Test
    fun `rejects empty and oversized names`() {
        assertFalse(ContainerNames.isValidResourceName(""))
        assertFalse(
            ContainerNames.isValidResourceName("a".repeat(ContainerNames.MAX_NAME_LENGTH + 1))
        )
        assertTrue(ContainerNames.isValidResourceName("a".repeat(ContainerNames.MAX_NAME_LENGTH)))
    }

    @Test
    fun `rejects a leading dash that would be read as an option`() {
        assertFalse(ContainerNames.isValidResourceName("-rf"))
        assertFalse(ContainerNames.isValidResourceName("--force"))
    }

    @Test
    fun `rejects leading punctuation the daemon does not allow`() {
        assertFalse(ContainerNames.isValidResourceName("_web"))
        assertFalse(ContainerNames.isValidResourceName(".web"))
    }

    @Test
    fun `rejects whitespace and shell metacharacters`() {
        assertFalse(ContainerNames.isValidResourceName("web data"))
        assertFalse(ContainerNames.isValidResourceName("web;rm -rf /"))
        assertFalse(ContainerNames.isValidResourceName("web\$(id)"))
        assertFalse(ContainerNames.isValidResourceName("web`id`"))
        assertFalse(ContainerNames.isValidResourceName("web|id"))
        assertFalse(ContainerNames.isValidResourceName("web\nid"))
    }

    @Test
    fun `rejects control characters and bidi overrides`() {
        assertFalse(ContainerNames.isValidResourceName("web\u0007data"))
        assertFalse(ContainerNames.isValidResourceName("web\u202Edata"))
    }

    @Test
    fun `rejects non-ascii homoglyphs`() {
        assertFalse(ContainerNames.isValidResourceName("w\u0435b"))
        assertFalse(ContainerNames.isValidResourceName("日本"))
    }

    @Test
    fun `accepts ordinary driver names`() {
        assertTrue(ContainerNames.isValidDriverName("local"))
        assertTrue(ContainerNames.isValidDriverName("overlay2"))
        assertTrue(ContainerNames.isValidDriverName("my-driver.v1_2"))
    }

    @Test
    fun `rejects empty oversized and option-like driver names`() {
        assertFalse(ContainerNames.isValidDriverName(""))
        assertFalse(ContainerNames.isValidDriverName("-o"))
        assertFalse(
            ContainerNames.isValidDriverName("a".repeat(ContainerNames.MAX_DRIVER_LENGTH + 1))
        )
    }

    @Test
    fun `rejects driver names with separators or whitespace`() {
        assertFalse(ContainerNames.isValidDriverName("local driver"))
        assertFalse(ContainerNames.isValidDriverName("local/driver"))
        assertFalse(ContainerNames.isValidDriverName("local=driver"))
    }
}
