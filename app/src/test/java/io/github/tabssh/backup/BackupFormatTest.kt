package io.github.tabssh.backup

import io.github.tabssh.backup.export.BackupExporter
import io.github.tabssh.sync.encryption.SyncEncryptor
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the backup container primitives in [BackupManager]'s
 * companion: format sniffing, manifest build/parse, and the ZIP writer/reader
 * pair. No Android framework involved — these run as plain JUnit.
 */
class BackupFormatTest {

    private val metadata = BackupManager.BackupMetadata(
        version = BackupManager.BACKUP_VERSION,
        createdAt = 1_700_000_000_000L,
        appVersion = "test 1.0 (1)",
        deviceModel = "unit-test",
        androidVersion = 34,
        itemCounts = mapOf("connections" to 2, "identities" to 1)
    )

    // ── Format sniffing ──────────────────────────────────────────────────────

    @Test
    fun `ZIP magic sniffs as the current archive format`() {
        val head = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
        assertEquals(BackupManager.BackupFileFormat.ZIP, BackupManager.sniffFormat(head))
    }

    @Test
    fun `the SyncEncryptor magic sniffs as a legacy encrypted archive`() {
        val head = "TABSSH_SYNC_V3".toByteArray(Charsets.ISO_8859_1) + ByteArray(16)
        assertEquals(
            BackupManager.BackupFileFormat.LEGACY_ENCRYPTED,
            BackupManager.sniffFormat(head)
        )
    }

    @Test
    fun `a leading brace sniffs as a legacy plaintext JSON archive`() {
        val head = """{"v":3,"metadata":{}}""".toByteArray(Charsets.UTF_8)
        assertEquals(BackupManager.BackupFileFormat.LEGACY_JSON, BackupManager.sniffFormat(head))
    }

    @Test
    fun `junk and empty input sniff as unknown`() {
        assertEquals(
            BackupManager.BackupFileFormat.UNKNOWN,
            BackupManager.sniffFormat("not a backup".toByteArray(Charsets.UTF_8))
        )
        assertEquals(
            BackupManager.BackupFileFormat.UNKNOWN,
            BackupManager.sniffFormat(ByteArray(0))
        )
        // "PK" alone is not enough — the full local-file-header magic is required.
        assertEquals(
            BackupManager.BackupFileFormat.UNKNOWN,
            BackupManager.sniffFormat(byteArrayOf(0x50, 0x4B))
        )
    }

    // ── Manifest ─────────────────────────────────────────────────────────────

    @Test
    fun `manifest round-trips metadata through build and parse`() {
        val json = BackupManager.buildManifest(
            metadata,
            contents = listOf("connections.json", "identities.json"),
            secretsIncluded = false
        )
        val parsed = BackupManager.parseManifest(json)
        assertEquals(metadata.version, parsed.version)
        assertEquals(metadata.createdAt, parsed.createdAt)
        assertEquals(metadata.appVersion, parsed.appVersion)
        assertEquals(metadata.deviceModel, parsed.deviceModel)
        assertEquals(metadata.androidVersion, parsed.androidVersion)
        assertEquals(metadata.itemCounts, parsed.itemCounts)
    }

    @Test
    fun `a malformed manifest is rejected, not parsed best-effort`() {
        assertFails { BackupManager.parseManifest("not json at all") }
        // Structurally valid JSON that is missing the required fields must also fail.
        assertFails { BackupManager.parseManifest("""{"version":4}""") }
    }

    // ── ZIP writer and reader ────────────────────────────────────────────────

    @Test
    fun `archive round-trips manifest and entries byte-for-byte`() {
        val manifestJson = BackupManager.buildManifest(
            metadata,
            contents = listOf("connections.json", "blob.bin"),
            secretsIncluded = false
        )
        val entries = linkedMapOf(
            "connections.json" to """{"v":4,"items":[]}""".toByteArray(Charsets.UTF_8),
            "blob.bin" to byteArrayOf(0, 1, 2, 3, -1, 127)
        )
        val out = ByteArrayOutputStream()
        BackupManager.writeBackupZip(out, manifestJson, entries)
        val archive = out.toByteArray()

        assertEquals(
            BackupManager.BackupFileFormat.ZIP,
            BackupManager.sniffFormat(archive.copyOfRange(0, 4)),
            "the writer must produce a sniffable ZIP container"
        )

        val (readManifest, readEntries) = BackupManager.readBackupZip(ByteArrayInputStream(archive))
        assertEquals(manifestJson, readManifest)
        assertEquals(entries.keys, readEntries.keys)
        entries.forEach { (name, bytes) ->
            assertContentEquals(bytes, readEntries[name], "entry $name changed in transit")
        }
    }

    @Test
    fun `readManifestOnly returns the manifest without needing the data entries`() {
        val manifestJson = BackupManager.buildManifest(metadata, listOf("a.json"), false)
        val out = ByteArrayOutputStream()
        BackupManager.writeBackupZip(
            out,
            manifestJson,
            linkedMapOf("a.json" to "{}".toByteArray(Charsets.UTF_8))
        )
        assertEquals(
            manifestJson,
            BackupManager.readManifestOnly(ByteArrayInputStream(out.toByteArray()))
        )
    }

    @Test
    fun `an archive written without a password carries no secrets entry`() {
        // Mirrors createBackup's rule: without a password the secrets entry is
        // never added to the entry map, so the archive simply does not have one.
        val manifestJson = BackupManager.buildManifest(metadata, listOf("connections.json"), false)
        val out = ByteArrayOutputStream()
        BackupManager.writeBackupZip(
            out,
            manifestJson,
            linkedMapOf("connections.json" to """{"v":4,"items":[]}""".toByteArray(Charsets.UTF_8))
        )
        val (_, entries) = BackupManager.readBackupZip(ByteArrayInputStream(out.toByteArray()))
        assertNull(entries[BackupExporter.FILE_SECRETS])
    }

    @Test
    fun `an encrypted secrets entry is detectable and decrypts back to its plaintext`() {
        // Minimal Argon2 cost so the test stays fast; readers honour the
        // parameters recorded in the ciphertext header regardless.
        val encryptor = SyncEncryptor(
            SyncEncryptor.Argon2Params(memoryKib = 8, iterations = 1, parallelism = 1)
        )
        val secretsJson = """{"v":4,"passwords":{"identity_1":"hunter2"},"ssh_keys":{}}"""
        val encrypted = encryptor.encrypt(secretsJson.toByteArray(Charsets.UTF_8), "pw")

        assertTrue(BackupManager.isSyncEncrypted(encrypted))
        assertFalse(BackupManager.isSyncEncrypted(secretsJson.toByteArray(Charsets.UTF_8)))

        val out = ByteArrayOutputStream()
        BackupManager.writeBackupZip(
            out,
            BackupManager.buildManifest(metadata, listOf(BackupExporter.FILE_SECRETS), true),
            linkedMapOf(BackupExporter.FILE_SECRETS to encrypted)
        )
        val (_, entries) = BackupManager.readBackupZip(ByteArrayInputStream(out.toByteArray()))
        val roundTripped = entries.getValue(BackupExporter.FILE_SECRETS)
        assertEquals(
            secretsJson,
            String(encryptor.decrypt(roundTripped, "pw"), Charsets.UTF_8)
        )
    }
}
