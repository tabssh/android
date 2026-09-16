package io.github.tabssh.sync.observer

import java.io.File
import org.junit.Test
import kotlin.test.fail

/**
 * Static guard for the "sync-on-change trigger covers the whole sync surface"
 * constraint (AI.md PART 10): every table [io.github.tabssh.sync.data.SyncDataCollector]
 * reads must appear in [io.github.tabssh.sync.observer.DatabaseChangeObserver]'s
 * `SYNCED_TABLES`, or an edit to that table never invalidates an observed table,
 * never debounces, and never enqueues `SyncWorker` — the data then only travels
 * on the periodic job or a manual sync, which reads to the user as "it does not
 * sync". Domain/VpsHost regressed exactly this way when they joined the sync
 * surface without the observer list being updated.
 *
 * `DatabaseChangeObserver` is a WorkManager/Room collaborator with no unit-test
 * seam, so this is a source scan in the same shape as `SyncLoggerSourceScanTest`:
 * pull the DAO accessors out of the collector, resolve each to the tables its
 * DAO queries, and require at least one of them to be observed.
 */
class DatabaseChangeObserverCoverageTest {

    /**
     * Sync's own bookkeeping tables. They are written by the sync engine itself,
     * so observing them would make every sync schedule another one.
     */
    private val syncInternalDaos = setOf("syncShadowDao", "syncTombstoneDao")

    private val daoAccessorPattern = Regex("""database\.([a-zA-Z]+Dao)\(\)""")

    private val tableReferencePattern = Regex(
        """(?:FROM|INTO|UPDATE)\s+([a-z][a-z0-9_]*)""",
        RegexOption.IGNORE_CASE
    )

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "app/src/main/java/io/github/tabssh/sync").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        if (File("src/main/java/io/github/tabssh/sync").isDirectory) return File(".").absoluteFile
        fail("Could not locate project root from user.dir=${System.getProperty("user.dir")}")
    }

    private fun sourceFile(root: File, relative: String): File {
        val direct = File(root, "app/src/main/java/io/github/tabssh/$relative")
        if (direct.isFile) return direct
        val fallback = File(root, "src/main/java/io/github/tabssh/$relative")
        if (fallback.isFile) return fallback
        fail("Missing source file: $relative")
    }

    private fun observedTables(root: File): Set<String> {
        val source = sourceFile(root, "sync/observer/DatabaseChangeObserver.kt").readText()
        val block = Regex("""SYNCED_TABLES\s*=\s*arrayOf\((.*?)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(source)
            ?.groupValues
            ?.get(1)
            ?: fail("Could not find SYNCED_TABLES in DatabaseChangeObserver.kt")
        return Regex(""""([a-z][a-z0-9_]*)"""").findAll(block).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `every table the sync collector reads is observed for sync-on-change`() {
        val root = projectRoot()
        val observed = observedTables(root)
        if (observed.isEmpty()) fail("SYNCED_TABLES parsed as empty — the scan pattern is stale")

        val collector = sourceFile(root, "sync/data/SyncDataCollector.kt").readText()
        val daos = daoAccessorPattern.findAll(collector)
            .map { it.groupValues[1] }
            .filterNot { it in syncInternalDaos }
            .toSortedSet()
        if (daos.isEmpty()) fail("No DAO accessors found in SyncDataCollector.kt — the scan pattern is stale")

        val unobserved = daos.filter { dao ->
            val daoSource = sourceFile(
                root,
                "storage/database/dao/${dao.replaceFirstChar { it.uppercase() }}.kt"
            ).readText()
            val tables = tableReferencePattern.findAll(daoSource)
                .map { it.groupValues[1].lowercase() }
                .toSet()
            tables.none { it in observed }
        }

        if (unobserved.isNotEmpty()) {
            fail(
                "SyncDataCollector reads ${unobserved.joinToString(", ")} but none of " +
                    "their tables appear in DatabaseChangeObserver.SYNCED_TABLES — " +
                    "edits there will never trigger sync-on-change"
            )
        }
    }
}
