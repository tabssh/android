package io.github.tabssh.backup.validation

import io.github.tabssh.backup.BackupManager
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Format-gate tests for [BackupValidator].
 *
 * There is exactly one backup wire version. These lock in the rejection path:
 * an archive written in any other version — older or newer — must fail
 * validation with an "unsupported backup format" error rather than be parsed
 * best-effort against a legacy shape that no longer exists.
 */
class BackupValidatorTest {

    private val validator = BackupValidator()

    private fun metadata(version: Int) = BackupManager.BackupMetadata(
        version = version,
        createdAt = 0L,
        appVersion = "test",
        deviceModel = "test",
        androidVersion = 34,
        itemCounts = emptyMap()
    )

    private val validConnections = """
        {"v":${BackupManager.BACKUP_VERSION},"items":[
          {"id":"c1","name":"box","host":"example.test","port":22,
           "username":"root","authType":"PASSWORD"}
        ]}
    """.trimIndent()

    @Test
    fun `current version validates`() {
        val result = validator.validateBackup(
            mapOf("connections.json" to validConnections),
            metadata(BackupManager.BACKUP_VERSION)
        )
        assertTrue(result.isValid, "errors: ${result.errors}")
    }

    @Test
    fun `an older version is rejected as an unsupported format`() {
        val result = validator.validateBackup(
            mapOf("connections.json" to validConnections),
            metadata(BackupManager.BACKUP_VERSION - 1)
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Unsupported backup format") })
    }

    @Test
    fun `a newer version is rejected as an unsupported format`() {
        val result = validator.validateBackup(
            mapOf("connections.json" to validConnections),
            metadata(BackupManager.BACKUP_VERSION + 1)
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Unsupported backup format") })
    }

    @Test
    fun `missing metadata is rejected`() {
        val result = validator.validateBackup(emptyMap(), null)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Missing backup metadata") })
    }

    @Test
    fun `the removed v1 plural-key entity shape no longer parses`() {
        // Pre-change archives wrapped rows under the table-plural key instead
        // of "items". That read path is gone, so the file is simply invalid.
        val legacyShape = """{"connections":[{"id":"c1","name":"box"}]}"""
        val result = validator.validateBackup(
            mapOf("connections.json" to legacyShape),
            metadata(BackupManager.BACKUP_VERSION)
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Missing items array") })
    }

    @Test
    fun `an unknown authType is a warning not an error`() {
        val data = """
            {"v":${BackupManager.BACKUP_VERSION},"items":[
              {"id":"c1","name":"box","host":"example.test","port":22,
               "username":"root","authType":"publickey"}
            ]}
        """.trimIndent()
        val result = validator.validateBackup(
            mapOf("connections.json" to data),
            metadata(BackupManager.BACKUP_VERSION)
        )
        assertTrue(result.isValid, "errors: ${result.errors}")
        assertTrue(result.warnings.any { it.contains("unknown authType") })
    }

    @Test
    fun `an encrypted archive is detected by its magic header`() {
        val encrypted = "TABSSH_SYNC_V3".toByteArray(Charsets.ISO_8859_1) + ByteArray(64)
        assertTrue(validator.isBackupEncrypted(encrypted))
        assertFalse(validator.isBackupEncrypted("""{"v":3}""".toByteArray(Charsets.UTF_8)))
        assertFalse(
            validator.isBackupEncrypted(
                "TABSSH_SYNC_V2".toByteArray(Charsets.ISO_8859_1) + ByteArray(64)
            )
        )
    }
}
