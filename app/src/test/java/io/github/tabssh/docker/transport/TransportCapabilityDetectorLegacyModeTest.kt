package io.github.tabssh.docker.transport

import io.github.tabssh.storage.database.dao.DockerHostDao
import io.github.tabssh.storage.database.entities.DockerHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Unrecognised stored-mode migration — any [DockerHost.transportMode] value
 * that is not one of the currently-supported tiers ("auto", "api_streamlocal",
 * "api_stdio", "cli_exec") must be treated as "auto" and run full detection
 * rather than being handed to [TransportCapabilityDetector] as a real pin.
 * This is a general rule, not a name-specific migration: no removed tier
 * name is special-cased, so any stale or corrupt value falls through the
 * same "not a known tier" check.
 */
class TransportCapabilityDetectorLegacyModeTest {

    /** Records every persisted transportMode; every other member is unused by the detector. */
    private class RecordingDockerHostDao : DockerHostDao {
        val persistedModes = mutableListOf<String>()

        override fun getAllHosts(): Flow<List<DockerHost>> = throw NotImplementedError()
        override suspend fun getAllList(): List<DockerHost> = throw NotImplementedError()
        override suspend fun getById(id: Long): DockerHost? = throw NotImplementedError()
        override suspend fun insert(host: DockerHost): Long = throw NotImplementedError()
        override suspend fun update(host: DockerHost) {
            persistedModes += host.transportMode
        }
        override suspend fun delete(host: DockerHost) = throw NotImplementedError()
        override suspend fun deleteById(id: Long) = throw NotImplementedError()
        override suspend fun updateLastConnected(id: Long, timestamp: Long) = throw NotImplementedError()
        override suspend fun updateLastUpdateCheck(id: Long, timestamp: Long) = throw NotImplementedError()
        override suspend fun clearLinkedConnectionId(connectionId: String) = throw NotImplementedError()
    }

    private fun host(mode: String): DockerHost =
        DockerHost(id = 1L, name = "test", transportMode = mode)

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

        assertTrue(result is DockerResult.TransportUnavailable)
        val detail = (result as DockerResult.TransportUnavailable).detail.orEmpty()
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

        assertTrue(result is DockerResult.TransportUnavailable)
        val detail = (result as DockerResult.TransportUnavailable).detail.orEmpty()
        assertTrue("Unknown transport mode" !in detail)
    }

    @Test
    fun `stored auto also runs full detection`() = runTest {
        val dao = RecordingDockerHostDao()
        val detector = TransportCapabilityDetector(dao)

        val result = detector.detect(host("auto"), noSessionRunner())

        assertTrue(result is DockerResult.TransportUnavailable)
    }
}
