package io.github.tabssh.hypervisor.oci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for the OCID guard that protects every OCI request path. OCIDs
 * arrive from stored profiles and from the server's own responses, and are
 * interpolated straight into the URL, so anything carrying a path or query
 * separator must be rejected before the request is built.
 */
class OciApiHelpersTest {

    @Test
    fun `requireValidOcid accepts a real instance ocid`() {
        val ocid = "ocid1.instance.oc1.iad.anuwcljtqz4xrpycabcd1234efgh5678"
        assertEquals(ocid, OciApiClient.requireValidOcid(ocid))
    }

    @Test
    fun `requireValidOcid rejects a path separator`() {
        assertThrows(IllegalArgumentException::class.java) {
            OciApiClient.requireValidOcid("ocid1.instance.oc1..abc/../users/me")
        }
    }

    @Test
    fun `requireValidOcid rejects a query separator`() {
        assertThrows(IllegalArgumentException::class.java) {
            OciApiClient.requireValidOcid("ocid1.instance.oc1..abc?action=stop")
        }
    }

    @Test
    fun `requireValidOcid rejects a value with the wrong prefix`() {
        assertThrows(IllegalArgumentException::class.java) {
            OciApiClient.requireValidOcid("instance-1")
        }
    }

    @Test
    fun `requireValidOcid rejects an empty value`() {
        assertThrows(IllegalArgumentException::class.java) {
            OciApiClient.requireValidOcid("")
        }
    }

    @Test
    fun `requireValidOcid rejects an oversized value`() {
        assertThrows(IllegalArgumentException::class.java) {
            OciApiClient.requireValidOcid("ocid1.instance.oc1..".padEnd(300, 'a'))
        }
    }
}
