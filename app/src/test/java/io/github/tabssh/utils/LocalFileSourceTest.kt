package io.github.tabssh.utils

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [LocalFileSource.Plain]'s snapshot semantics (Workstream 3): every
 * property is captured once at construction time instead of re-read from
 * the underlying [java.io.File] on every access, so a directory's contents
 * changing after the entry was built must never be reflected by an
 * already-constructed [LocalFileSource.Plain] — that's the whole point of
 * snapshotting (fewer syscalls per RecyclerView bind). No Android framework
 * dependency, so this runs as a plain JUnit test, not Robolectric.
 */
class LocalFileSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `plain file snapshot captures name, size and directory flag at construction`() {
        val file = tempFolder.newFile("notes.txt")
        file.writeText("hello")

        val entry = LocalFileSource.Plain(file)

        assertEquals("notes.txt", entry.name)
        assertFalse(entry.isDirectory)
        assertEquals(5L, entry.length)
        assertEquals(file.absolutePath, entry.id)
    }

    @Test
    fun `plain directory snapshot reports zero length regardless of contents`() {
        val dir = tempFolder.newFolder("subdir")
        tempFolder.newFile("subdir/child.txt").writeText("some content")

        val entry = LocalFileSource.Plain(dir)

        assertTrue(entry.isDirectory)
        assertEquals(0L, entry.length)
    }

    @Test
    fun `snapshot does not reflect changes made after construction`() {
        val file = tempFolder.newFile("growing.txt")
        file.writeText("short")

        val entry = LocalFileSource.Plain(file)
        file.writeText("this file grew after the snapshot was taken")

        assertEquals(5L, entry.length)
    }

    @Test
    fun `two entries for the same path are equal and hash the same`() {
        val file = tempFolder.newFile("same.txt")

        val a = LocalFileSource.Plain(file)
        val b = LocalFileSource.Plain(file)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
