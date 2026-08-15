package io.github.tabssh.protocols.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit coverage for the mosh-server leak fix: the network-timeout prefix that
 * bounds a detached server's lifetime, and the detached-pid parse used to reap
 * an orphaned server on the fast SSH-fallback path.
 */
class MoshHandoffReapTest {

    @Test
    fun networkTimeout_prependsVariableToDefaultCommand() {
        val out = MoshHandoff.withNetworkTimeout("mosh-server new -l LANG=en_US.UTF-8", 604_800)
        assertEquals("MOSH_SERVER_NETWORK_TMOUT=604800 mosh-server new -l LANG=en_US.UTF-8", out)
    }

    @Test
    fun networkTimeout_prependsToCustomBinaryPath() {
        val out = MoshHandoff.withNetworkTimeout("/usr/local/bin/mosh-server new -l LANG=en_US.UTF-8", 3600)
        assertEquals("MOSH_SERVER_NETWORK_TMOUT=3600 /usr/local/bin/mosh-server new -l LANG=en_US.UTF-8", out)
    }

    @Test
    fun networkTimeout_leftUntouchedWhenAlreadySet() {
        val cmd = "MOSH_SERVER_NETWORK_TMOUT=60 mosh-server new"
        assertEquals(cmd, MoshHandoff.withNetworkTimeout(cmd, 604_800))
    }

    @Test
    fun networkTimeout_disabledWhenNonPositive() {
        val cmd = "mosh-server new"
        assertEquals(cmd, MoshHandoff.withNetworkTimeout(cmd, 0))
        assertEquals(cmd, MoshHandoff.withNetworkTimeout(cmd, -5))
    }

    @Test
    fun parseDetachedPid_extractsPidFromServerOutput() {
        val raw = "MOSH CONNECT 60001 aGVsbG8gd29ybGQ\r\n\r\n" +
            "mosh-server (mosh 1.4.0) [build mosh 1.4.0]\r\n" +
            "[mosh-server detached, pid = 4190739]\r\n"
        assertEquals(4190739, MoshHandoff.parseDetachedPid(raw))
    }

    @Test
    fun parseDetachedPid_toleratesSpacingAndMissingEquals() {
        assertEquals(1243071, MoshHandoff.parseDetachedPid("[mosh-server detached, pid = 1243071]"))
        assertEquals(999, MoshHandoff.parseDetachedPid("mosh-server detached, pid 999"))
    }

    @Test
    fun parseDetachedPid_nullWhenLineAbsent() {
        assertNull(MoshHandoff.parseDetachedPid("MOSH CONNECT 60001 aGVsbG8gd29ybGQ\r\n"))
    }
}
