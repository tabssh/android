package io.github.tabssh.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.tabssh.backup.export.BackupExporter
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end backup behaviour over the ZIP wire format.
 *
 * Covers both output modes, the no-credentials guarantee, legacy-format
 * compatibility, and the rejection path:
 *  - password mode → ZIP with an encrypted secrets entry, round-trips with the
 *    password and will not restore without it;
 *  - passwordless mode → ZIP with NO secrets entry at all, restores freely;
 *  - a legacy single-JSON v3 archive → still restores;
 *  - an archive in any other version, or junk bytes → refused with a clear error.
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
 * secrets-only-with-password rule, and the format gate.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRoundTripTest {

    @Test
    fun `backup modes round trip and non-current formats are refused`(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val manager = BackupManager(context)
        val archive = File(context.cacheDir, "roundtrip.tabssh")
        val uri: Uri = Uri.fromFile(archive)

        // ── Password mode ────────────────────────────────────────────────────
        archive.delete()
        val password = "Correct-Horse-Battery-Staple-9"
        val encrypted = manager.createBackup(uri, password = password)
        assertTrue(encrypted.success, "password-mode create failed: ${encrypted.message}")
        assertEquals(
            BackupManager.BackupFileFormat.ZIP,
            BackupManager.sniffFormat(archive.readBytes().copyOfRange(0, 4)),
            "a password-mode archive must be a ZIP container"
        )
        val (manifest, entries) = BackupManager.readBackupZip(
            ByteArrayInputStream(archive.readBytes())
        )
        assertNotNull(manifest, "the archive must carry a manifest entry")
        val secretsEntry = entries[BackupExporter.FILE_SECRETS]
        assertNotNull(secretsEntry, "a password-mode archive must carry a secrets entry")
        assertTrue(
            BackupManager.isSyncEncrypted(secretsEntry),
            "the secrets entry must be SyncEncryptor-encrypted, never plaintext"
        )
        assertTrue(
            manager.restoreBackup(uri, password = password).success,
            "password-mode archive failed to restore with the correct password"
        )

        val noPassword = manager.restoreBackup(uri, password = null)
        assertFalse(noPassword.success, "password-mode archive restored with no password")
        assertTrue(
            noPassword.message.contains("encrypted"),
            "expected a 'needs password' message, got: ${noPassword.message}"
        )
        assertFalse(
            manager.restoreBackup(uri, password = "not-the-password").success,
            "password-mode archive restored with the wrong password"
        )

        // ── Blank password ───────────────────────────────────────────────────
        archive.delete()
        val blank = manager.createBackup(uri, password = "   ")
        assertFalse(blank.success, "a blank password was accepted")
        assertFalse(archive.exists(), "a refused export must not write any bytes")

        // ── Passwordless mode ────────────────────────────────────────────────
        val plain = manager.createBackup(uri, password = null)
        assertTrue(plain.success, "passwordless create failed: ${plain.message}")
        assertEquals(
            BackupManager.BackupFileFormat.ZIP,
            BackupManager.sniffFormat(archive.readBytes().copyOfRange(0, 4)),
            "a passwordless archive must be a ZIP container"
        )
        val (plainManifest, plainEntries) = BackupManager.readBackupZip(
            ByteArrayInputStream(archive.readBytes())
        )
        assertNotNull(plainManifest, "the archive must carry a manifest entry")
        assertNull(
            plainEntries[BackupExporter.FILE_SECRETS],
            "a passwordless archive must contain NO secrets entry in any form"
        )
        assertTrue(
            manager.validateBackup(uri).success,
            "manifest-only validation failed on a freshly written archive"
        )
        assertTrue(
            manager.restoreBackup(uri).success,
            "passwordless archive failed to restore"
        )

        // ── Legacy single-JSON v3 archive ────────────────────────────────────
        archive.writeText(
            """
            {"v":3,"metadata":{"version":3,"createdAt":0,"appVersion":"legacy",
             "deviceModel":"legacy","androidVersion":33,"itemCounts":{}},
             "data":{"connections.json":"{\"v\":3,\"items\":[]}"}}
            """.trimIndent(),
            Charsets.UTF_8
        )
        assertTrue(
            manager.restoreBackup(uri).success,
            "a legacy v3 single-JSON archive must still restore"
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
