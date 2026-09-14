package io.github.tabssh.sftp

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Regression tests for [SCPClient.scpParentPath] / [SCPClient.scpBaseName].
 *
 * The bug these support: directory upload ran `scp -t -r <remoteDir>` and
 * streamed only the directory's children, never a `D` record for the
 * directory itself. When `<remoteDir>` did not already exist, OpenSSH's sink
 * wrote every top-level `C` record to that path, so uploading a folder of
 * files produced a single FILE containing the last file's bytes — reported as
 * success. The fix targets the PARENT and wraps the contents in a D/E pair,
 * which is what these two helpers compute.
 */
class ScpPathTest {

    @Test
    fun `a nested absolute path splits into parent and name`() {
        assertEquals("/home/user", SCPClient.scpParentPath("/home/user/photos"))
        assertEquals("photos", SCPClient.scpBaseName("/home/user/photos"))
    }

    @Test
    fun `trailing slashes are ignored`() {
        assertEquals("/home/user", SCPClient.scpParentPath("/home/user/photos/"))
        assertEquals("photos", SCPClient.scpBaseName("/home/user/photos/"))
        assertEquals("/home/user", SCPClient.scpParentPath("/home/user/photos///"))
        assertEquals("photos", SCPClient.scpBaseName("/home/user/photos///"))
    }

    @Test
    fun `a top-level directory has the root as its parent`() {
        assertEquals("/", SCPClient.scpParentPath("/photos"))
        assertEquals("photos", SCPClient.scpBaseName("/photos"))
    }

    @Test
    fun `a bare relative name uploads into the working directory`() {
        assertEquals(".", SCPClient.scpParentPath("photos"))
        assertEquals("photos", SCPClient.scpBaseName("photos"))
    }

    @Test
    fun `a relative nested path splits normally`() {
        assertEquals("a/b", SCPClient.scpParentPath("a/b/c"))
        assertEquals("c", SCPClient.scpBaseName("a/b/c"))
    }

    @Test
    fun `the root itself degrades safely`() {
        assertEquals("/", SCPClient.scpParentPath("/"))
        assertEquals("/", SCPClient.scpBaseName("/"))
        assertEquals("/", SCPClient.scpParentPath(""))
        assertEquals("/", SCPClient.scpBaseName(""))
    }

    @Test
    fun `names with spaces and dots are preserved verbatim`() {
        // The name goes into a D record; escaping is the caller's concern,
        // these helpers must not mangle it.
        assertEquals("my photos", SCPClient.scpBaseName("/home/user/my photos"))
        assertEquals("/home/user", SCPClient.scpParentPath("/home/user/my photos"))
        assertEquals(".config", SCPClient.scpBaseName("/home/user/.config"))
    }
}
