package io.github.tabssh.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the framework-free file:// "Open" round-trip logic in
 * FileOpenPolicy (TODO.AI.md file:// open flow) — size gate, cache file
 * naming, LRU eviction, extension extraction, and change detection.
 */
class FileOpenPolicyTest {

    @Test
    fun `exceedsSizeGate is false at or under the limit`() {
        val limitBytes = 20L * 1024 * 1024
        assertFalse(FileOpenPolicy.exceedsSizeGate(limitBytes, 20))
        assertFalse(FileOpenPolicy.exceedsSizeGate(limitBytes - 1, 20))
    }

    @Test
    fun `exceedsSizeGate is true just over the limit`() {
        val limitBytes = 20L * 1024 * 1024
        assertTrue(FileOpenPolicy.exceedsSizeGate(limitBytes + 1, 20))
    }

    @Test
    fun `cacheFileName is stable for the same remote path`() {
        val a = FileOpenPolicy.cacheFileName("/var/log/syslog")
        val b = FileOpenPolicy.cacheFileName("/var/log/syslog")
        assertEquals(a, b)
    }

    @Test
    fun `cacheFileName differs for different remote paths with the same basename`() {
        val a = FileOpenPolicy.cacheFileName("/etc/nginx/nginx.conf")
        val b = FileOpenPolicy.cacheFileName("/opt/app/nginx.conf")
        assertTrue(a != b)
        assertTrue(a.endsWith("_nginx.conf"))
        assertTrue(b.endsWith("_nginx.conf"))
    }

    @Test
    fun `cacheFileName strips path traversal and separators from a hostile basename`() {
        val name = FileOpenPolicy.cacheFileName("/tmp/../../etc/passwd")
        assertFalse(name.contains('/'))
        assertFalse(name.contains(".."))
    }

    @Test
    fun `extensionOf returns lowercased extension`() {
        assertEquals("txt", FileOpenPolicy.extensionOf("notes.TXT"))
        assertEquals("gz", FileOpenPolicy.extensionOf("archive.tar.gz"))
    }

    @Test
    fun `extensionOf returns null for dotfiles and extensionless names`() {
        assertNull(FileOpenPolicy.extensionOf(".bashrc"))
        assertNull(FileOpenPolicy.extensionOf("README"))
        assertNull(FileOpenPolicy.extensionOf("trailing."))
    }

    @Test
    fun `filesToEvict returns empty when under the cap`() {
        val files = listOf(
            FileOpenPolicy.CachedFileStat("a", 1000, 10),
            FileOpenPolicy.CachedFileStat("b", 2000, 10)
        )
        assertTrue(FileOpenPolicy.filesToEvict(files, capBytes = 1000).isEmpty())
    }

    @Test
    fun `filesToEvict evicts oldest first until under the cap`() {
        val files = listOf(
            FileOpenPolicy.CachedFileStat("oldest", lastModified = 100, length = 40),
            FileOpenPolicy.CachedFileStat("middle", lastModified = 200, length = 40),
            FileOpenPolicy.CachedFileStat("newest", lastModified = 300, length = 40)
        )
        val evicted = FileOpenPolicy.filesToEvict(files, capBytes = 80)
        assertEquals(listOf("oldest"), evicted.map { it.name })
    }

    @Test
    fun `filesToEvict never evicts more than necessary`() {
        val files = listOf(
            FileOpenPolicy.CachedFileStat("a", lastModified = 1, length = 50),
            FileOpenPolicy.CachedFileStat("b", lastModified = 2, length = 50),
            FileOpenPolicy.CachedFileStat("c", lastModified = 3, length = 50)
        )
        val evicted = FileOpenPolicy.filesToEvict(files, capBytes = 100)
        assertEquals(listOf("a"), evicted.map { it.name })
    }

    @Test
    fun `hasFileChanged is false when mtime and size both match`() {
        assertFalse(FileOpenPolicy.hasFileChanged(1000, 500, 1000, 500))
    }

    @Test
    fun `hasFileChanged is true when mtime differs`() {
        assertTrue(FileOpenPolicy.hasFileChanged(1000, 500, 1001, 500))
    }

    @Test
    fun `hasFileChanged is true when size differs`() {
        assertTrue(FileOpenPolicy.hasFileChanged(1000, 500, 1000, 501))
    }
}
