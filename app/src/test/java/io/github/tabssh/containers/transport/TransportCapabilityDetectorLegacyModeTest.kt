package io.github.tabssh.containers.transport

import io.github.tabssh.storage.database.dao.ContainerHostDao
import io.github.tabssh.storage.database.entities.ContainerHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Unrecognised stored-mode migration — any [ContainerHost.transportMode] value
 * that is not one of the currently-supported tiers ("auto", "api_streamlocal",
 * "api_tcp", "api_stdio", "cli_exec") must be treated as "auto" and run full
 * detection rather than being handed to [TransportCapabilityDetector] as a
 * real pin. This is a general rule, not a name-specific migration: no removed
 * tier name is special-cased, so any stale or corrupt value falls through the
 * same "not a known tier" check.
 *
 * A real tier name that is not in THIS host's ladder — a `tcp://`-only tier
 * stored against a unix-socket host — falls through the same way, so an
 * engine or endpoint change can never leave a host pinned to a tier it can no
 * longer use.
 */
class TransportCapabilityDetectorLegacyModeTest {

    /** Records every persisted transportMode; every other member is unused by the detector. */
    private class RecordingDockerHostDao : ContainerHostDao {
        val persistedModes = mutableListOf<String>()

        override fun getAllHosts(): Flow<List<ContainerHost>> = throw NotImplementedError()
        override suspend fun getAllList(): List<ContainerHost> = throw NotImplementedError()
        override suspend fun getById(id: Long): ContainerHost? = throw NotImplementedError()
        override suspend fun insert(host: ContainerHost): Long = throw NotImplementedError()
        override suspend fun update(host: ContainerHost) {
            persistedModes += host.transportMode
        }
        override suspend fun delete(host: ContainerHost) = throw NotImplementedError()
        override suspend fun deleteById(id: Long) = throw NotImplementedError()
        override suspend fun updateLastConnected(id: Long, timestamp: Long) = throw NotImplementedError()
        override suspend fun updateLastUpdateCheck(id: Long, timestamp: Long) = throw NotImplementedError()
        override suspend fun clearLinkedConnectionId(connectionId: String) = throw NotImplementedError()
    }

    private fun host(mode: String): ContainerHost =
        ContainerHost(id = 1L, name = "test", transportMode = mode)

    /** No live SSH session — every exec throws TransportUnavailableException, failing every tier. */
    private fun noSessionRunner(): SshExecRunner = SshExecRunner { null }

    @Test
    fun `unrecognised stored mode runs full detection instead of a fast path`() = runTest {
        val dao = RecordingDockerHostDao()
        val detector = TransportCapabilityDetector(dao)

        // A stored mode that has never been valid — no removed tier name is
        // special-cased, this is any corrupt/unknown string. With no live
        // SSH session every tier fails, so a fast-pathed pin would surface
        // as "Unknown transport mode" from openTier(); full detection
        // instead surfaces the ladder's own TransportUnavailable.
        val result = detector.detect(host("some_unknown_mode"), noSessionRunner())

        assertTrue(result is ContainerResult.TransportUnavailable)
        val detail = (result as ContainerResult.TransportUnavailable).detail.orEmpty()
        assertTrue(
            "Unknown transport mode" !in detail,
            "expected the socket/cli tier failures from a full run, not an unknown-mode error: $detail"
        )
    }

    @Test
    fun `another unrecognised value also runs full detection, not a name-specific list`() = runTest {
        val dao = RecordingDockerHostDao()
        val detector = TransportCapabilityDetector(dao)

        val result = detector.detect(host("totally-corrupt-value"), noSessionRunner())

        assertTrue(result is ContainerResult.TransportUnavailable)
        val detail = (result as ContainerResult.TransportUnavailable).detail.orEmpty()
        assertTrue("Unknown transport mode" !in detail)
    }

    @Test
    fun `a pinned tier outside this host's ladder runs full detection`() = runTest {
        val dao = RecordingDockerHostDao()
        val detector = TransportCapabilityDetector(dao)

        // api_tcp is a real, pinnable tier, but it exists only for a tcp://
        // endpoint — this host has a unix socket, so the pin is ignored and
        // the tier is never attempted.
        val result = detector.detect(
            host(TransportCapabilityDetector.MODE_API_TCP),
            noSessionRunner()
        )

        assertTrue(result is ContainerResult.TransportUnavailable)
        val detail = (result as ContainerResult.TransportUnavailable).detail.orEmpty()
        assertTrue(
            TransportCapabilityDetector.MODE_API_TCP !in detail,
            "the TCP tier was attempted against a unix socket: $detail"
        )
    }

    @Test
    fun `stored auto also runs full detection`() = runTest {
        val dao = RecordingDockerHostDao()
        val detector = TransportCapabilityDetector(dao)

        val result = detector.detect(host("auto"), noSessionRunner())

        assertTrue(result is ContainerResult.TransportUnavailable)
    }
}
