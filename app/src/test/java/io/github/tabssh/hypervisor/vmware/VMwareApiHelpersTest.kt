package io.github.tabssh.hypervisor.vmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for VMwareApiClient's pure helpers: the session-token acceptance
 * rule, the managed-object-reference guard that protects the REST path, the
 * `/rest` vs `/api` envelope difference, and error-text sanitization.
 */
class VMwareApiHelpersTest {

    @Test
    fun `parseSessionId accepts a quoted opaque token`() {
        assertEquals("a1b2c3d4", VMwareApiClient.parseSessionId("\"a1b2c3d4\""))
    }

    @Test
    fun `parseSessionId accepts an unquoted token`() {
        assertEquals("a1b2c3d4", VMwareApiClient.parseSessionId("a1b2c3d4\n"))
    }

    @Test
    fun `parseSessionId rejects a token carrying a header injection`() {
        // Would otherwise be echoed into the vmware-api-session-id header.
        assertNull(VMwareApiClient.parseSessionId("\"tok\r\nX-Evil: 1\""))
    }

    @Test
    fun `parseSessionId rejects empty null and oversized input`() {
        assertNull(VMwareApiClient.parseSessionId(null))
        assertNull(VMwareApiClient.parseSessionId("\"\""))
        assertNull(VMwareApiClient.parseSessionId("z".repeat(VMwareApiClient.MAX_SESSION_ID_LEN + 1)))
    }

    @Test
    fun `requireValidMoRef accepts a normal reference`() {
        assertEquals("vm-1042", VMwareApiClient.requireValidMoRef("vm-1042"))
    }

    @Test
    fun `requireValidMoRef rejects path traversal and separators`() {
        assertThrows(IllegalArgumentException::class.java) {
            VMwareApiClient.requireValidMoRef("../session")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VMwareApiClient.requireValidMoRef("vm-1/power")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VMwareApiClient.requireValidMoRef("vm-1?action=stop")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VMwareApiClient.requireValidMoRef("")
        }
    }

    @Test
    fun `unwrapValueArray accepts the legacy rest envelope`() {
        val arr = VMwareApiClient.unwrapValueArray("{\"value\":[{\"vm\":\"vm-1\"}]}")
        assertEquals(1, arr.length())
        assertEquals("vm-1", arr.getJSONObject(0).getString("vm"))
    }

    @Test
    fun `unwrapValueArray accepts the bare api array`() {
        val arr = VMwareApiClient.unwrapValueArray("  [{\"vm\":\"vm-2\"}] ")
        assertEquals(1, arr.length())
        assertEquals("vm-2", arr.getJSONObject(0).getString("vm"))
    }

    @Test
    fun `unwrapValueObject accepts both envelope shapes`() {
        assertEquals(
            2,
            VMwareApiClient.unwrapValueObject("{\"value\":{\"cpu\":{\"count\":2}}}")
                .getJSONObject("cpu").getInt("count")
        )
        assertEquals(
            4,
            VMwareApiClient.unwrapValueObject("{\"cpu\":{\"count\":4}}")
                .getJSONObject("cpu").getInt("count")
        )
    }

    @Test
    fun `sanitizeServerText flattens control characters and bounds length`() {
        assertEquals("fault a b", VMwareApiClient.sanitizeServerText("fault\ta\nb"))
        val long = "q".repeat(VMwareApiClient.MAX_ERROR_TEXT_LEN + 5)
        val result = VMwareApiClient.sanitizeServerText(long)
        assertEquals(VMwareApiClient.MAX_ERROR_TEXT_LEN + 1, result.length)
        assertTrue(result.endsWith("…"))
        assertEquals("", VMwareApiClient.sanitizeServerText(null))
    }
}
