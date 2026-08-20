package io.github.tabssh.sync

import java.io.File
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Static guard for the "conflict text never reaches Logger under the sync
 * source tree"
 * constraint: conflict detail (a field's local/remote/base value, or a
 * conflict's human-readable description) must be recorded only through
 * [io.github.tabssh.sync.log.SyncLogManager] into the dedicated Sync Log
 * table, never via [io.github.tabssh.utils.logging.Logger]. There is no
 * Logger-mocking pattern in this codebase, so this is a source scan rather
 * than a runtime verification — it fails the build if either regresses:
 * (1) a `Conflict`-field member access appears on the same line as a
 * `Logger.*` call anywhere under the sync tree, or (2) any of the three files
 * that own conflict resolution/logging (`ConflictResolver`,
 * `SyncMergeCoordinator`, `SyncLogManager`) starts importing `Logger` again.
 */
class SyncLoggerSourceScanTest {

    private val conflictFieldAccessPattern = Regex(
        """conflict\.(description|localValue|remoteValue|baseValue|field)\b"""
    )

    /** Sensitive owners that must never depend on Logger at all. */
    private val forbiddenLoggerImportFiles = listOf(
        "merge/ConflictResolver.kt",
        "merge/SyncMergeCoordinator.kt",
        "log/SyncLogManager.kt"
    )

    private fun findSyncSourceDir(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val candidate = File(dir, "app/src/main/java/io/github/tabssh/sync")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        val direct = File("src/main/java/io/github/tabssh/sync")
        if (direct.isDirectory) return direct
        fail("Could not locate sync/ source directory from user.dir=${System.getProperty("user.dir")}")
    }

    @Test
    fun `no Logger call anywhere in sync source references a conflict field value`() {
        val syncDir = findSyncSourceDir()
        val violations = mutableListOf<String>()

        syncDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (line.contains("Logger.") && conflictFieldAccessPattern.containsMatchIn(line)) {
                    violations.add("${file.name}:${index + 1}: $line")
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Conflict field value logged via Logger in sync/**:\n${violations.joinToString("\n")}"
        )
    }

    @Test
    fun `conflict resolution and sync-log owners never import Logger`() {
        val syncDir = findSyncSourceDir()
        val violations = mutableListOf<String>()

        for (relativePath in forbiddenLoggerImportFiles) {
            val file = File(syncDir, relativePath)
            assertTrue(file.isFile, "Expected $relativePath to exist under sync/")
            if (file.readText().contains("import io.github.tabssh.utils.logging.Logger")) {
                violations.add(relativePath)
            }
        }

        assertTrue(
            violations.isEmpty(),
            "These files must not import Logger — conflict detail belongs in SyncLogManager only: $violations"
        )
    }
}
