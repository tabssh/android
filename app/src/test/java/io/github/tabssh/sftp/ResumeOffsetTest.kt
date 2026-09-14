package io.github.tabssh.sftp

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Regression tests for [SFTPManager.resumeOffset].
 *
 * The bug these lock down: resume used to be inferred purely from
 * "destination is smaller than source". Every overwrite that grew a file was
 * therefore treated as an interrupted transfer, so the new content's tail was
 * appended after the old content's prefix and the result reported as success.
 * An editor save-back turned remote `AAAA` into `AAAABB` instead of `BBBBBB`.
 *
 * Resume is now opt-in per transfer: a caller must know the destination holds
 * the leading bytes of *this* source before asking for it.
 */
class ResumeOffsetTest {

    @Test
    fun `an overwrite that grows the file starts from zero`() {
        // The editor save-back case: remote AAAA (4), new content BBBBBB (6).
        // Inferring resume here is what corrupted the file.
        assertEquals(
            0L,
            SFTPManager.resumeOffset(
                resumeSupport = true,
                allowResume = false,
                destinationExists = true,
                destinationSize = 4L,
                sourceSize = 6L
            ),
            "a non-resume transfer must overwrite, never append"
        )
    }

    @Test
    fun `a stale cache smaller than the remote file is fully re-downloaded`() {
        // RemoteFileOpener reuses a deterministic cache name. A 100-byte stale
        // copy of a now-400-byte remote file must not be "resumed" into a
        // mixture of old prefix and new tail.
        assertEquals(
            0L,
            SFTPManager.resumeOffset(
                resumeSupport = true,
                allowResume = false,
                destinationExists = true,
                destinationSize = 100L,
                sourceSize = 400L
            )
        )
    }

    @Test
    fun `an explicit resume of a genuine partial transfer skips what is present`() {
        assertEquals(
            100L,
            SFTPManager.resumeOffset(
                resumeSupport = true,
                allowResume = true,
                destinationExists = true,
                destinationSize = 100L,
                sourceSize = 400L
            )
        )
    }

    @Test
    fun `the global kill switch overrides a per-transfer opt-in`() {
        assertEquals(
            0L,
            SFTPManager.resumeOffset(
                resumeSupport = false,
                allowResume = true,
                destinationExists = true,
                destinationSize = 100L,
                sourceSize = 400L
            )
        )
    }

    @Test
    fun `a missing destination has nothing to resume`() {
        assertEquals(
            0L,
            SFTPManager.resumeOffset(
                resumeSupport = true,
                allowResume = true,
                destinationExists = false,
                destinationSize = 0L,
                sourceSize = 400L
            )
        )
    }

    @Test
    fun `an empty destination starts from zero rather than a zero-length resume`() {
        assertEquals(
            0L,
            SFTPManager.resumeOffset(
                resumeSupport = true,
                allowResume = true,
                destinationExists = true,
                destinationSize = 0L,
                sourceSize = 400L
            )
        )
    }

    @Test
    fun `a destination at or beyond the source size restarts the transfer`() {
        // Equal size means there is nothing left to append; a larger
        // destination means it is not a prefix of this source at all.
        for (destinationSize in listOf(400L, 401L, 4000L)) {
            assertEquals(
                0L,
                SFTPManager.resumeOffset(
                    resumeSupport = true,
                    allowResume = true,
                    destinationExists = true,
                    destinationSize = destinationSize,
                    sourceSize = 400L
                ),
                "destination $destinationSize vs source 400 must restart"
            )
        }
    }

    @Test
    fun `a resumed offset never exceeds the source size`() {
        // Guards the invariant the transfer loop depends on: skipFully is
        // called with this offset against a stream of sourceSize bytes.
        val source = 400L
        for (destinationSize in listOf(1L, 2L, 199L, 399L)) {
            val offset = SFTPManager.resumeOffset(
                resumeSupport = true,
                allowResume = true,
                destinationExists = true,
                destinationSize = destinationSize,
                sourceSize = source
            )
            assert(offset in 0 until source) { "offset $offset out of range for source $source" }
        }
    }
}
