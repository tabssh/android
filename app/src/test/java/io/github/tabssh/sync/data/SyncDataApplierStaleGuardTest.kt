package io.github.tabssh.sync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the two pure helpers that guard `SyncDataApplier.applyAll`:
 * the stale-revision check applied to every non-merge-tracked table, and the
 * secret-alias rewrite that follows hypervisor-account id remapping.
 *
 * Both fail against the pre-fix behaviour: the applier had no staleness check
 * at all (remote always won), and secrets were applied under the sender's ids.
 */
class SyncDataApplierStaleGuardTest {

    @Test
    fun `older remote revision is rejected`() {
        assertTrue(SyncDataApplier.remoteIsStale(localModifiedAt = 2_000L, remoteModifiedAt = 1_000L))
    }

    @Test
    fun `newer remote revision is applied`() {
        assertFalse(SyncDataApplier.remoteIsStale(localModifiedAt = 1_000L, remoteModifiedAt = 2_000L))
    }

    @Test
    fun `equal timestamps still apply so a genuine re-push is not skipped`() {
        assertFalse(SyncDataApplier.remoteIsStale(localModifiedAt = 1_000L, remoteModifiedAt = 1_000L))
    }

    @Test
    fun `row absent locally is never stale`() {
        assertFalse(SyncDataApplier.remoteIsStale(localModifiedAt = null, remoteModifiedAt = 0L))
    }

    @Test
    fun `account secret aliases follow the remapped local id`() {
        val secrets = mapOf(
            "hypervisor_account_7" to "pw",
            "oci_private_key_account_7" to "pem",
            "oci_passphrase_account_7" to "phrase"
        )
        val remapped = SyncDataApplier.remapAccountSecretAliases(secrets, mapOf(7L to 42L))
        assertEquals(
            setOf(
                "hypervisor_account_42",
                "oci_private_key_account_42",
                "oci_passphrase_account_42"
            ),
            remapped.keys
        )
        assertEquals("pw", remapped["hypervisor_account_42"])
        assertEquals("pem", remapped["oci_private_key_account_42"])
        assertEquals("phrase", remapped["oci_passphrase_account_42"])
    }

    @Test
    fun `aliases of other families and unmapped ids pass through unchanged`() {
        val secrets = mapOf(
            // Different family — the suffix is a connection UUID, not an account id.
            "conn_pw_9f3a" to "a",
            // Account family, but this account was filtered out by a toggle.
            "hypervisor_account_8" to "b",
            // Account family whose id happens to be identical on both devices.
            "hypervisor_account_5" to "c"
        )
        val remapped = SyncDataApplier.remapAccountSecretAliases(
            secrets,
            mapOf(7L to 42L, 5L to 5L)
        )
        assertEquals(secrets, remapped)
    }

    @Test
    fun `an empty remap leaves every alias untouched`() {
        val secrets = mapOf("hypervisor_account_7" to "pw")
        assertEquals(secrets, SyncDataApplier.remapAccountSecretAliases(secrets, emptyMap()))
    }
}
