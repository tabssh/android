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
class DockerNamesValidationTest {

    @Test
    fun `accepts ordinary names`() {
        assertTrue(DockerNames.isValidResourceName("web"))
        assertTrue(DockerNames.isValidResourceName("web_data"))
        assertTrue(DockerNames.isValidResourceName("web-data.1"))
        assertTrue(DockerNames.isValidResourceName("0"))
    }

    @Test
    fun `rejects empty and oversized names`() {
        assertFalse(DockerNames.isValidResourceName(""))
        assertFalse(
            DockerNames.isValidResourceName("a".repeat(DockerNames.MAX_NAME_LENGTH + 1))
        )
        assertTrue(DockerNames.isValidResourceName("a".repeat(DockerNames.MAX_NAME_LENGTH)))
    }

    @Test
    fun `rejects a leading dash that would be read as an option`() {
        assertFalse(DockerNames.isValidResourceName("-rf"))
        assertFalse(DockerNames.isValidResourceName("--force"))
    }

    @Test
    fun `rejects leading punctuation the daemon does not allow`() {
        assertFalse(DockerNames.isValidResourceName("_web"))
        assertFalse(DockerNames.isValidResourceName(".web"))
    }

    @Test
    fun `rejects whitespace and shell metacharacters`() {
        assertFalse(DockerNames.isValidResourceName("web data"))
        assertFalse(DockerNames.isValidResourceName("web;rm -rf /"))
        assertFalse(DockerNames.isValidResourceName("web\$(id)"))
        assertFalse(DockerNames.isValidResourceName("web`id`"))
        assertFalse(DockerNames.isValidResourceName("web|id"))
        assertFalse(DockerNames.isValidResourceName("web\nid"))
    }

    @Test
    fun `rejects control characters and bidi overrides`() {
        assertFalse(DockerNames.isValidResourceName("web\u0007data"))
        assertFalse(DockerNames.isValidResourceName("web\u202Edata"))
    }

    @Test
    fun `rejects non-ascii homoglyphs`() {
        assertFalse(DockerNames.isValidResourceName("w\u0435b"))
        assertFalse(DockerNames.isValidResourceName("日本"))
    }

    @Test
    fun `accepts ordinary driver names`() {
        assertTrue(DockerNames.isValidDriverName("local"))
        assertTrue(DockerNames.isValidDriverName("overlay2"))
        assertTrue(DockerNames.isValidDriverName("my-driver.v1_2"))
    }

    @Test
    fun `rejects empty oversized and option-like driver names`() {
        assertFalse(DockerNames.isValidDriverName(""))
        assertFalse(DockerNames.isValidDriverName("-o"))
        assertFalse(
            DockerNames.isValidDriverName("a".repeat(DockerNames.MAX_DRIVER_LENGTH + 1))
        )
    }

    @Test
    fun `rejects driver names with separators or whitespace`() {
        assertFalse(DockerNames.isValidDriverName("local driver"))
        assertFalse(DockerNames.isValidDriverName("local/driver"))
        assertFalse(DockerNames.isValidDriverName("local=driver"))
    }
}
