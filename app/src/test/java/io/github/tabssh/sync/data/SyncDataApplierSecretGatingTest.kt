package io.github.tabssh.sync.data

import android.content.Context
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.preferences.PreferenceManager
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for SyncDataApplier's private secret-alias-to-toggle gating (F5):
 * a secret alias is only restored from a sync payload when the local sync
 * toggle that owns its category is on. Invoked via reflection since the
 * mapping is intentionally private — it is an implementation detail of
 * applySecrets(), not a public API.
 */
class SyncDataApplierSecretGatingTest {

    private lateinit var context: Context
    private lateinit var database: TabSSHDatabase
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var applier: SyncDataApplier

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        context = Mockito.mock(Context::class.java)
        database = Mockito.mock(TabSSHDatabase::class.java)
        preferenceManager = Mockito.mock(PreferenceManager::class.java)
        applier = SyncDataApplier(context, database, preferenceManager)
    }

    private fun isSecretAliasEnabled(alias: String): Boolean {
        val method = SyncDataApplier::class.java.getDeclaredMethod(
            "isSecretAliasEnabled", String::class.java
        )
        method.isAccessible = true
        return method.invoke(applier, alias) as Boolean
    }

    @Test
    fun `identity secrets are gated by sync_identities`() {
        Mockito.`when`(preferenceManager.isSyncIdentitiesEnabled()).thenReturn(false)
        assertFalse(isSecretAliasEnabled("identity_abc123"))

        Mockito.`when`(preferenceManager.isSyncIdentitiesEnabled()).thenReturn(true)
        assertTrue(isSecretAliasEnabled("identity_abc123"))
    }

    @Test
    fun `connection passwords are gated by sync_connections`() {
        Mockito.`when`(preferenceManager.isSyncConnectionsEnabled()).thenReturn(false)
        assertFalse(isSecretAliasEnabled("conn_pw_abc123"))

        Mockito.`when`(preferenceManager.isSyncConnectionsEnabled()).thenReturn(true)
        assertTrue(isSecretAliasEnabled("conn_pw_abc123"))
    }

    @Test
    fun `key material and key passphrases are both gated by sync_keys`() {
        Mockito.`when`(preferenceManager.isSyncKeysEnabled()).thenReturn(false)
        assertFalse(isSecretAliasEnabled("ssh_key_abc123"))
        assertFalse(isSecretAliasEnabled("key_passphrase_abc123"))

        Mockito.`when`(preferenceManager.isSyncKeysEnabled()).thenReturn(true)
        assertTrue(isSecretAliasEnabled("ssh_key_abc123"))
        assertTrue(isSecretAliasEnabled("key_passphrase_abc123"))
    }

    @Test
    fun `hypervisor account secrets including oci aliases are gated by sync_hypervisor_accounts`() {
        Mockito.`when`(preferenceManager.isSyncHypervisorAccountsEnabled()).thenReturn(false)
        assertFalse(isSecretAliasEnabled("hypervisor_account_abc123"))
        assertFalse(isSecretAliasEnabled("oci_private_key_account_abc123"))
        assertFalse(isSecretAliasEnabled("oci_passphrase_account_abc123"))

        Mockito.`when`(preferenceManager.isSyncHypervisorAccountsEnabled()).thenReturn(true)
        assertTrue(isSecretAliasEnabled("hypervisor_account_abc123"))
        assertTrue(isSecretAliasEnabled("oci_private_key_account_abc123"))
        assertTrue(isSecretAliasEnabled("oci_passphrase_account_abc123"))
    }

    @Test
    fun `hypervisor host secrets are gated by sync_hypervisors`() {
        Mockito.`when`(preferenceManager.isSyncHypervisorsEnabled()).thenReturn(false)
        assertFalse(isSecretAliasEnabled("hypervisor_abc123"))

        Mockito.`when`(preferenceManager.isSyncHypervisorsEnabled()).thenReturn(true)
        assertTrue(isSecretAliasEnabled("hypervisor_abc123"))
    }

    @Test
    fun `vnc identity and host secrets use their own toggles`() {
        Mockito.`when`(preferenceManager.isSyncVncIdentitiesEnabled()).thenReturn(false)
        Mockito.`when`(preferenceManager.isSyncVncHostsEnabled()).thenReturn(true)
        assertFalse(isSecretAliasEnabled("vnc_identity_abc123"))
        assertTrue(isSecretAliasEnabled("vnc_host_abc123"))

        Mockito.`when`(preferenceManager.isSyncVncIdentitiesEnabled()).thenReturn(true)
        Mockito.`when`(preferenceManager.isSyncVncHostsEnabled()).thenReturn(false)
        assertTrue(isSecretAliasEnabled("vnc_identity_abc123"))
        assertFalse(isSecretAliasEnabled("vnc_host_abc123"))
    }

    @Test
    fun `cloud tokens are gated by sync_cloud_accounts`() {
        Mockito.`when`(preferenceManager.isSyncCloudAccountsEnabled()).thenReturn(false)
        assertFalse(isSecretAliasEnabled("cloud_token_abc123"))

        Mockito.`when`(preferenceManager.isSyncCloudAccountsEnabled()).thenReturn(true)
        assertTrue(isSecretAliasEnabled("cloud_token_abc123"))
    }

    @Test
    fun `docker secrets are gated by sync_docker`() {
        Mockito.`when`(preferenceManager.isSyncDockerEnabled()).thenReturn(false)
        assertFalse(isSecretAliasEnabled("docker_host_abc123"))
        assertFalse(isSecretAliasEnabled("registry_credential_abc123"))

        Mockito.`when`(preferenceManager.isSyncDockerEnabled()).thenReturn(true)
        assertTrue(isSecretAliasEnabled("docker_host_abc123"))
        assertTrue(isSecretAliasEnabled("registry_credential_abc123"))
    }

    @Test
    fun `unknown alias prefixes are denied`() {
        assertFalse(isSecretAliasEnabled("some_unrelated_alias"))
        assertFalse(isSecretAliasEnabled(""))
        assertFalse(isSecretAliasEnabled("password_abc123"))
    }
}
