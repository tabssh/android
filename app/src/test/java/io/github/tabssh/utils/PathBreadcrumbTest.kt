package io.github.tabssh.utils

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Covers [splitPathBreadcrumb]'s segment-splitting rules for the SFTP local
 * pane's breadcrumb strip (Workstream 3, AI.md PART 2 file-manager-class
 * exception). Pure function — no Android framework dependency — so this
 * runs as a plain JUnit test, not Robolectric.
 */
class PathBreadcrumbTest {

    @Test
    fun `root path splits to a single root segment`() {
        assertEquals(
            listOf(PathBreadcrumbSegment("/", "/")),
            splitPathBreadcrumb("/")
        )
    }

    @Test
    fun `blank path is treated as root`() {
        assertEquals(
            listOf(PathBreadcrumbSegment("/", "/")),
            splitPathBreadcrumb("   ")
        )
    }

    @Test
    fun `nested path splits into one segment per path component`() {
        assertEquals(
            listOf(
                PathBreadcrumbSegment("/", "/"),
                PathBreadcrumbSegment("storage", "/storage"),
                PathBreadcrumbSegment("emulated", "/storage/emulated"),
                PathBreadcrumbSegment("0", "/storage/emulated/0"),
                PathBreadcrumbSegment("Download", "/storage/emulated/0/Download")
            ),
            splitPathBreadcrumb("/storage/emulated/0/Download")
        )
    }

    @Test
    fun `trailing slash does not produce an empty segment`() {
        assertEquals(
            splitPathBreadcrumb("/storage/emulated"),
            splitPathBreadcrumb("/storage/emulated/")
        )
    }

    @Test
    fun `repeated slashes collapse instead of producing empty segments`() {
        assertEquals(
            listOf(
                PathBreadcrumbSegment("/", "/"),
                PathBreadcrumbSegment("a", "/a"),
                PathBreadcrumbSegment("b", "/a/b")
            ),
            splitPathBreadcrumb("//a//b//")
        )
    }
}
