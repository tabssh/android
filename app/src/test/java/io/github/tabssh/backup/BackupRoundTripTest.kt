package io.github.tabssh.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end backup behaviour over the single supported wire format.
 *
 * Covers both output modes, the plaintext-secrets gate, and the rejection path:
 *  - encrypted (password) → round-trips, and will not restore without it;
 *  - plaintext-with-secrets → refused unless the caller passes the
 *    type-to-confirm acknowledgement, then round-trips;
 *  - an archive that is not the current format → refused with a clear error.
 *
 * Everything runs inside one test method on purpose: [BackupManager] resolves
 * the Room database through its process-wide singleton, so spreading these
 * across methods would hand later methods a handle created under an earlier
 * Robolectric application instance.
 *
 * Keystore-backed secrets are not exercised here — the hardware AndroidKeyStore
 * provider does not exist in a local JVM, so the exporter's secrets section is
 * empty in this environment and is covered by the instrumented suite instead.
 * What this locks in is the container format, the encryption choice, the
 * confirmation gate, and the format gate.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRoundTripTest {

    @Test
    fun `backup modes round trip and non-current formats are refused`(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val manager = BackupManager(context)
        val archive = File(context.cacheDir, "roundtrip.tabssh")
        val uri: Uri = Uri.fromFile(archive)

        // ── Encrypted mode ───────────────────────────────────────────────────
        archive.delete()
        val password = "Correct-Horse-Battery-Staple-9"
        val encrypted = manager.createBackup(uri, encryptBackup = true, password = password)
        assertTrue(encrypted.success, "encrypted create failed: ${encrypted.message}")
        assertEquals(
            "TABSSH_SYNC_V3",
            archive.readBytes().copyOfRange(0, 14).toString(Charsets.ISO_8859_1),
            "encrypted archive must carry the current magic, not readable JSON"
        )
        assertTrue(
            manager.restoreBackup(uri, password = password).success,
            "encrypted archive failed to restore with the correct password"
        )

        val noPassword = manager.restoreBackup(uri, password = null)
        assertFalse(noPassword.success, "encrypted archive restored with no password")
        assertTrue(
            noPassword.message.contains("encrypted"),
            "expected a 'needs password' message, got: ${noPassword.message}"
        )
        assertFalse(
            manager.restoreBackup(uri, password = "not-the-password").success,
            "encrypted archive restored with the wrong password"
        )

        // ── Encryption requested with no password ────────────────────────────
        archive.delete()
        val noPasswordCreate = manager.createBackup(uri, encryptBackup = true, password = null)
        assertFalse(noPasswordCreate.success, "encrypted create succeeded with no password")
        assertFalse(archive.exists(), "a refused export must not write any bytes")

        // ── Plaintext mode, unconfirmed ──────────────────────────────────────
        val unconfirmed = manager.createBackup(uri, encryptBackup = false, password = null)
        assertFalse(unconfirmed.success, "unconfirmed plaintext export was allowed")
        assertTrue(
            unconfirmed.message.contains("confirmation"),
            "expected a confirmation-required message, got: ${unconfirmed.message}"
        )
        assertFalse(archive.exists(), "a refused export must not write any bytes")

        // ── Plaintext mode, confirmed ────────────────────────────────────────
        val plaintext = manager.createBackup(
            outputUri = uri,
            encryptBackup = false,
            password = null,
            plaintextSecretsConfirmed = true
        )
        assertTrue(plaintext.success, "confirmed plaintext create failed: ${plaintext.message}")
        val text = archive.readText(Charsets.UTF_8)
        assertTrue(text.startsWith("{"), "plaintext archive must be readable JSON")
        assertTrue(
            text.contains("\"v\":${BackupManager.BACKUP_VERSION}"),
            "plaintext archive must declare the current version"
        )
        assertTrue(
            manager.restoreBackup(uri).success,
            "confirmed plaintext archive failed to restore"
        )

        // ── Unsupported format ───────────────────────────────────────────────
        archive.writeText(
            """
            {"v":1,"metadata":{"version":1,"createdAt":0,"appVersion":"old",
             "deviceModel":"old","androidVersion":21,"itemCounts":{}},
             "data":{"connections.json":"{\"connections\":[]}"}}
            """.trimIndent(),
            Charsets.UTF_8
        )
        val stale = manager.restoreBackup(uri)
        assertFalse(stale.success, "an older-format archive was accepted")
        assertTrue(
            stale.message.contains("Unsupported backup format"),
            "expected an unsupported-format message, got: ${stale.message}"
        )

        // ── Not a backup at all ──────────────────────────────────────────────
        archive.writeText("this is not a backup", Charsets.UTF_8)
        assertFalse(manager.restoreBackup(uri).success, "a junk file was accepted")

        archive.delete()
    }
}
