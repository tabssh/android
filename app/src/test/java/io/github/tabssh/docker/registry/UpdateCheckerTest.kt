package io.github.tabssh.docker.registry

import io.github.tabssh.docker.transport.ComposeInvocation
import io.github.tabssh.docker.transport.ContainerAction
import io.github.tabssh.docker.transport.DockerContainerStats
import io.github.tabssh.docker.transport.DockerContainerSummary
import io.github.tabssh.docker.transport.DockerDiskUsage
import io.github.tabssh.docker.transport.DockerEngineInfo
import io.github.tabssh.docker.transport.DockerImageSummary
import io.github.tabssh.docker.transport.DockerNetworkSummary
import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.docker.transport.DockerTransport
import io.github.tabssh.docker.transport.DockerVersionInfo
import io.github.tabssh.docker.transport.DockerVolumeSummary
import io.github.tabssh.docker.transport.PullProgressEvent
import io.github.tabssh.docker.transport.RemoteDirEntry
import io.github.tabssh.storage.database.dao.ContainerAutoUpdatePolicyDao
import io.github.tabssh.storage.database.dao.RegistryCredentialDao
import io.github.tabssh.storage.database.entities.ContainerAutoUpdatePolicy
import io.github.tabssh.storage.database.entities.RegistryCredential
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Update-check decision logic: the pure [UpdateChecker.decide] table, inspect
 * normalization (API object vs CLI array), RepoDigests matching, and the full
 * [UpdateChecker.checkOne] pass over hand-rolled fakes.
 */
class UpdateCheckerTest {

    private companion object {
        const val OLD = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val NEW = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }

    // ── decide() ─────────────────────────────────────────────────────────────

    @Test
    fun `running digest differs — pending`() {
        assertTrue(UpdateChecker.decide(NEW, OLD, null, null))
    }

    @Test
    fun `running digest matches — up to date`() {
        assertFalse(UpdateChecker.decide(NEW, NEW, OLD, OLD))
    }

    @Test
    fun `no running digest but already pending — stays pending`() {
        assertTrue(UpdateChecker.decide(NEW, null, NEW, NEW))
    }

    @Test
    fun `no running digest — compared against last seen baseline`() {
        assertTrue(UpdateChecker.decide(NEW, null, OLD, null))
        assertFalse(UpdateChecker.decide(NEW, null, NEW, null))
    }

    @Test
    fun `first check with nothing to compare only baselines`() {
        assertFalse(UpdateChecker.decide(NEW, null, null, null))
    }

    // ── normalizeInspect() ───────────────────────────────────────────────────

    @Test
    fun `api inspect object passes through`() {
        val obj = UpdateChecker.normalizeInspect("""{"Id":"abc"}""")!!
        assertEquals("abc", obj.optString("Id"))
    }

    @Test
    fun `cli inspect array unwraps to its first element`() {
        val obj = UpdateChecker.normalizeInspect("""[{"Id":"abc"}]""")!!
        assertEquals("abc", obj.optString("Id"))
    }

    @Test
    fun `garbage inspect output is null`() {
        assertNull(UpdateChecker.normalizeInspect("not json"))
        assertNull(UpdateChecker.normalizeInspect("[]"))
    }

    // ── repoDigestFor() ──────────────────────────────────────────────────────

    @Test
    fun `matches the canonical repository entry`() {
        val ref = ImageRef.parse("nginx:1.27")!!
        val digest = UpdateChecker.repoDigestFor(
            listOf("ghcr.io/other/app@$NEW", "nginx@$OLD"), ref
        )
        assertEquals(OLD, digest)
    }

    @Test
    fun `falls back to a sole entry for retagged images`() {
        val ref = ImageRef.parse("myregistry.local/team/app")!!
        assertEquals(OLD, UpdateChecker.repoDigestFor(listOf("ghcr.io/upstream/app@$OLD"), ref))
    }

    @Test
    fun `ambiguous non-matching entries yield null`() {
        val ref = ImageRef.parse("myregistry.local/team/app")!!
        assertNull(UpdateChecker.repoDigestFor(
            listOf("ghcr.io/a/x@$OLD", "ghcr.io/b/y@$NEW"), ref
        ))
        assertNull(UpdateChecker.repoDigestFor(emptyList(), ref))
    }

    // ── checkOne() over fakes ────────────────────────────────────────────────

    @Test
    fun `newer registry digest flags a pending update`() = runTest {
        val dao = FakePolicyDao(policy())
        val checker = checker(dao, registryDigest = NEW)
        val result = checker.checkOne(policy(), transport(runningDigest = OLD))
        assertEquals(UpdateChecker.Status.UPDATE_AVAILABLE, result.status)
        assertEquals(NEW, result.registryDigest)
        assertEquals(NEW, dao.pendingWrites.single())
        assertEquals(NEW, dao.checkResultDigests.single())
    }

    @Test
    fun `matching digests clear any stale pending flag`() = runTest {
        val dao = FakePolicyDao(policy())
        val checker = checker(dao, registryDigest = OLD)
        val result = checker.checkOne(policy(pending = NEW), transport(runningDigest = OLD))
        assertEquals(UpdateChecker.Status.UP_TO_DATE, result.status)
        assertNull(dao.pendingWrites.single())
    }

    @Test
    fun `locally built image without repo digests baselines on first check`() = runTest {
        val dao = FakePolicyDao(policy())
        val checker = checker(dao, registryDigest = NEW)
        val result = checker.checkOne(policy(), transport(runningDigest = null))
        assertEquals(UpdateChecker.Status.BASELINED, result.status)
        assertNull(dao.pendingWrites.single())
        assertEquals(NEW, dao.checkResultDigests.single())
    }

    @Test
    fun `baselined policy flags when the registry moves`() = runTest {
        val dao = FakePolicyDao(policy())
        val checker = checker(dao, registryDigest = NEW)
        val result = checker.checkOne(
            policy(lastSeen = OLD), transport(runningDigest = null)
        )
        assertEquals(UpdateChecker.Status.UPDATE_AVAILABLE, result.status)
        assertEquals(NEW, dao.pendingWrites.single())
    }

    @Test
    fun `missing container is an error and persists nothing`() = runTest {
        val dao = FakePolicyDao(policy())
        val checker = checker(dao, registryDigest = NEW)
        val gone = object : FakeTransport() {
            override suspend fun inspectContainer(id: String): DockerResult<String> =
                DockerResult.NotFound("Container web not found")
        }
        val result = checker.checkOne(policy(), gone)
        assertEquals(UpdateChecker.Status.ERROR, result.status)
        assertTrue(dao.pendingWrites.isEmpty())
        assertTrue(dao.checkResultDigests.isEmpty())
    }

    @Test
    fun `registry failure is an error and persists nothing`() = runTest {
        val dao = FakePolicyDao(policy())
        val failing = object : RegistryClient() {
            override suspend fun fetchManifestDigest(
                ref: ImageRef,
                credential: RegistryCredential?,
                secret: String?
            ): DockerResult<String> = DockerResult.Error("Registry unreachable")
        }
        val checker = UpdateChecker(dao, FakeCredentialDao(), failing) { null }
        val result = checker.checkOne(policy(), transport(runningDigest = OLD))
        assertEquals(UpdateChecker.Status.ERROR, result.status)
        assertTrue(dao.pendingWrites.isEmpty())
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun policy(
        lastSeen: String? = null,
        pending: String? = null
    ) = ContainerAutoUpdatePolicy(
        id = 1L,
        dockerHostId = 10L,
        containerNameOrStackName = "web",
        scope = "container",
        enabled = true,
        lastDigestSeen = lastSeen,
        pendingUpdateDigest = pending
    )

    private fun checker(dao: FakePolicyDao, registryDigest: String): UpdateChecker {
        val canned = object : RegistryClient() {
            override suspend fun fetchManifestDigest(
                ref: ImageRef,
                credential: RegistryCredential?,
                secret: String?
            ): DockerResult<String> = DockerResult.Success(registryDigest)
        }
        return UpdateChecker(dao, FakeCredentialDao(), canned) { null }
    }

    /** Transport whose container runs nginx:1.27 pulled at [runningDigest]. */
    private fun transport(runningDigest: String?): DockerTransport =
        object : FakeTransport() {
            override suspend fun inspectContainer(id: String): DockerResult<String> =
                DockerResult.Success(
                    """{"Id":"c1","Image":"sha256:imageid","Config":{"Image":"nginx:1.27"}}"""
                )

            override suspend fun inspectImage(ref: String): DockerResult<String> {
                val digests = if (runningDigest == null) "[]" else """["nginx@$runningDigest"]"""
                // CLI shape (array) on purpose — exercises normalizeInspect.
                return DockerResult.Success("""[{"Id":"sha256:imageid","RepoDigests":$digests}]""")
            }
        }
}

/** In-memory policy DAO recording the writes checkOne performs. */
private class FakePolicyDao(
    private var stored: ContainerAutoUpdatePolicy
) : ContainerAutoUpdatePolicyDao {
    val pendingWrites = mutableListOf<String?>()
    val checkResultDigests = mutableListOf<String?>()

    override fun getPoliciesForHost(hostId: Long): Flow<List<ContainerAutoUpdatePolicy>> =
        flowOf(listOf(stored))

    override suspend fun getEnabledList(): List<ContainerAutoUpdatePolicy> =
        listOf(stored).filter { it.enabled }

    override suspend fun getById(id: Long): ContainerAutoUpdatePolicy? =
        stored.takeIf { it.id == id }

    override suspend fun insert(policy: ContainerAutoUpdatePolicy): Long = policy.id

    override suspend fun update(policy: ContainerAutoUpdatePolicy) { stored = policy }

    override suspend fun delete(policy: ContainerAutoUpdatePolicy) {}

    override suspend fun deleteById(id: Long) {}

    override suspend fun deleteForHost(hostId: Long) {}

    override suspend fun updateCheckResult(id: Long, timestamp: Long, digest: String?) {
        checkResultDigests.add(digest)
        stored = stored.copy(lastCheckedAt = timestamp, lastDigestSeen = digest)
    }

    override suspend fun updatePendingUpdateDigest(id: Long, digest: String?) {
        pendingWrites.add(digest)
        stored = stored.copy(pendingUpdateDigest = digest)
    }

    override suspend fun clearRegistryCredentialId(credentialId: Long) {}
}

/** Credential DAO with no stored credentials (anonymous checks). */
private class FakeCredentialDao : RegistryCredentialDao {
    override fun getAllCredentials(): Flow<List<RegistryCredential>> = flowOf(emptyList())
    override suspend fun getAllList(): List<RegistryCredential> = emptyList()
    override suspend fun getById(id: Long): RegistryCredential? = null
    override suspend fun getByRegistryHost(registryHost: String): List<RegistryCredential> = emptyList()
    override suspend fun insert(credential: RegistryCredential): Long = 0
    override suspend fun update(credential: RegistryCredential) {}
    override suspend fun delete(credential: RegistryCredential) {}
    override suspend fun deleteById(id: Long) {}
}

/**
 * DockerTransport base for tests — every operation fails with a plain Error
 * so a test only overrides what its scenario touches.
 */
private open class FakeTransport : DockerTransport {
    private fun <T> unsupported(): DockerResult<T> = DockerResult.Error("not faked")

    override suspend fun listContainers(all: Boolean): DockerResult<List<DockerContainerSummary>> = unsupported()
    override suspend fun inspectContainer(id: String): DockerResult<String> = unsupported()
    override suspend fun containerAction(id: String, action: ContainerAction): DockerResult<Unit> = unsupported()
    override suspend fun renameContainer(id: String, newName: String): DockerResult<Unit> = unsupported()
    override suspend fun removeContainer(id: String, force: Boolean): DockerResult<Unit> = unsupported()
    override suspend fun createAndStartContainer(
        name: String,
        createBody: JSONObject,
        runArgv: List<String>
    ): DockerResult<Unit> = unsupported()
    override fun streamLogs(id: String, tail: Int?): Flow<String> = emptyFlow()
    override fun streamStats(id: String): Flow<DockerContainerStats> = emptyFlow()
    override suspend fun listImages(): DockerResult<List<DockerImageSummary>> = unsupported()
    override suspend fun inspectImage(ref: String): DockerResult<String> = unsupported()
    override fun pullImage(ref: String): Flow<PullProgressEvent> = emptyFlow()
    override suspend fun removeImage(ref: String, force: Boolean): DockerResult<Unit> = unsupported()
    override suspend fun pruneImages(): DockerResult<Unit> = unsupported()
    override suspend fun listVolumes(): DockerResult<List<DockerVolumeSummary>> = unsupported()
    override suspend fun inspectVolume(name: String): DockerResult<String> = unsupported()
    override suspend fun createVolume(name: String, driver: String?): DockerResult<Unit> = unsupported()
    override suspend fun removeVolume(name: String, force: Boolean): DockerResult<Unit> = unsupported()
    override suspend fun pruneVolumes(): DockerResult<Unit> = unsupported()
    override suspend fun listNetworks(): DockerResult<List<DockerNetworkSummary>> = unsupported()
    override suspend fun inspectNetwork(id: String): DockerResult<String> = unsupported()
    override suspend fun createNetwork(name: String, driver: String?): DockerResult<Unit> = unsupported()
    override suspend fun removeNetwork(id: String): DockerResult<Unit> = unsupported()
    override suspend fun pruneNetworks(): DockerResult<Unit> = unsupported()
    override suspend fun engineInfo(): DockerResult<DockerEngineInfo> = unsupported()
    override suspend fun engineVersion(): DockerResult<DockerVersionInfo> = unsupported()
    override suspend fun diskUsage(): DockerResult<DockerDiskUsage> = unsupported()
    override suspend fun composeUp(stackDir: String): DockerResult<String> = unsupported()
    override suspend fun composeDown(stackDir: String): DockerResult<String> = unsupported()
    override suspend fun composePull(stackDir: String): DockerResult<String> = unsupported()
    override suspend fun composeRestart(stackDir: String): DockerResult<String> = unsupported()
    override suspend fun composePs(stackDir: String): DockerResult<String> = unsupported()
    override suspend fun detectComposeInvocation(): DockerResult<ComposeInvocation> = unsupported()
    override suspend fun readRemoteFile(path: String): DockerResult<String> = unsupported()
    override suspend fun writeRemoteFile(path: String, content: String): DockerResult<Unit> = unsupported()
    override suspend fun listRemoteDir(path: String): DockerResult<List<RemoteDirEntry>> = unsupported()
    override suspend fun expandRemotePath(path: String): DockerResult<String> = unsupported()
    override suspend fun close() {}
}
