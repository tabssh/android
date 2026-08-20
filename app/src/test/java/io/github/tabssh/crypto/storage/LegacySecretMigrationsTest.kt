package io.github.tabssh.crypto.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the one-time plaintext connection-password migration.
 *
 * The legacy shape stored connection passwords as readable
 * `password_{connectionId}` entries in the default SharedPreferences file.
 * That read path is gone; the migration moves each value into the Keystore and
 * deletes the plaintext key. It must be idempotent and must never lose a
 * password — a failed Keystore write leaves the plaintext key in place so the
 * next launch retries.
 *
 * [SecurePasswordManager] is mocked: its real constructor requires the hardware
 * AndroidKeyStore provider, which does not exist in a local JVM.
 */
@RunWith(RobolectricTestRunner::class)
class LegacySecretMigrationsTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var passwordManager: SecurePasswordManager

    private val prefix = LegacySecretMigrations.LEGACY_CONNECTION_PASSWORD_PREFIX

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("legacy_migration_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        passwordManager = mock()
    }

    private val encrypted get() =
        eq(SecurePasswordManager.StorageLevel.ENCRYPTED)

    private fun stubStore(result: Boolean) = runBlocking {
        whenever(
            passwordManager.storePassword(any(), any(), encrypted)
        ).thenReturn(result)
    }

    @Test
    fun `plaintext passwords move into the keystore and the plaintext keys are deleted`() =
        runBlocking {
            prefs.edit()
                .putString("${prefix}conn-a", "hunter2")
                .putString("${prefix}conn-b", "swordfish")
                .putString("unrelated_key", "keep-me")
                .commit()
            whenever(passwordManager.hasStoredPassword(any())).thenReturn(false)
            stubStore(true)

            val done = LegacySecretMigrations
                .migratePlaintextConnectionPasswords(prefs, passwordManager)

            assertTrue(done)
            verify(passwordManager).storePassword(
                eq("conn-a"),
                eq("hunter2"),
                eq(SecurePasswordManager.StorageLevel.ENCRYPTED)
            )
            verify(passwordManager).storePassword(
                eq("conn-b"),
                eq("swordfish"),
                eq(SecurePasswordManager.StorageLevel.ENCRYPTED)
            )
            assertNull(prefs.getString("${prefix}conn-a", null))
            assertNull(prefs.getString("${prefix}conn-b", null))
            // Only the legacy prefix is touched.
            assertEquals("keep-me", prefs.getString("unrelated_key", null))
        }

    @Test
    fun `a failed keystore write keeps the plaintext key for the next attempt`() = runBlocking {
        prefs.edit().putString("${prefix}conn-a", "hunter2").commit()
        whenever(passwordManager.hasStoredPassword(any())).thenReturn(false)
        stubStore(false)

        val done = LegacySecretMigrations
            .migratePlaintextConnectionPasswords(prefs, passwordManager)

        assertFalse(done, "an incomplete migration must not report done")
        assertEquals("hunter2", prefs.getString("${prefix}conn-a", null))
    }

    @Test
    fun `an existing keystore secret wins and the plaintext echo is dropped`(): Unit = runBlocking {
        prefs.edit().putString("${prefix}conn-a", "stale-echo").commit()
        whenever(passwordManager.hasStoredPassword("conn-a")).thenReturn(true)

        val done = LegacySecretMigrations
            .migratePlaintextConnectionPasswords(prefs, passwordManager)

        assertTrue(done)
        assertNull(prefs.getString("${prefix}conn-a", null))
        // The authoritative Keystore copy must not be overwritten by the echo.
        verify(passwordManager, never())
            .storePassword(any(), any(), encrypted)
    }

    @Test
    fun `running twice is a no-op the second time`() = runBlocking {
        prefs.edit().putString("${prefix}conn-a", "hunter2").commit()
        whenever(passwordManager.hasStoredPassword(any())).thenReturn(false)
        stubStore(true)

        assertTrue(
            LegacySecretMigrations.migratePlaintextConnectionPasswords(prefs, passwordManager)
        )
        reset(passwordManager)

        assertTrue(
            LegacySecretMigrations.migratePlaintextConnectionPasswords(prefs, passwordManager)
        )
        verifyNoInteractions(passwordManager)
    }

    @Test
    fun `an empty legacy value is dropped without a keystore write`(): Unit = runBlocking {
        prefs.edit().putString("${prefix}conn-a", "").commit()

        assertTrue(
            LegacySecretMigrations.migratePlaintextConnectionPasswords(prefs, passwordManager)
        )
        assertNull(prefs.getString("${prefix}conn-a", null))
        verify(passwordManager, never())
            .storePassword(any(), any(), encrypted)
    }
}
