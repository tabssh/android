package io.github.tabssh.docker.registry

import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.docker.transport.DockerTransport
import io.github.tabssh.storage.database.dao.ContainerAutoUpdatePolicyDao
import io.github.tabssh.storage.database.dao.RegistryCredentialDao
import io.github.tabssh.storage.database.entities.ContainerAutoUpdatePolicy
import io.github.tabssh.utils.logging.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-policy registry update check (PLAN.AI.md step 30).
 *
 * For each enabled [ContainerAutoUpdatePolicy] on a host: resolve the digest
 * the running container was pulled from (inspect `RepoDigests`, preferred) or
 * fall back to last-seen bookkeeping, fetch the current registry digest via
 * [RegistryClient], compare, and persist `lastCheckedAt` / `lastDigestSeen` /
 * `pendingUpdateDigest` through the DAO.
 *
 * Secrets are injected through [secretProvider] (backed by the Keystore-based
 * RegistryCredentialStore in production, a plain lambda in tests) so this
 * class never touches Android APIs and never logs a secret.
 */
class UpdateChecker(
    private val policyDao: ContainerAutoUpdatePolicyDao,
    private val credentialDao: RegistryCredentialDao,
    private val registry: RegistryClient,
    private val secretProvider: suspend (Long) -> String?
) {

    /** Outcome class of one policy check. */
    enum class Status {
        /** Registry digest differs from the running/known digest. */
        UPDATE_AVAILABLE,
        /** Registry digest matches — nothing to do. */
        UP_TO_DATE,
        /** First-ever check with no local digest to compare — baselined. */
        BASELINED,
        /** The policy could not be checked (container gone, registry error…). */
        ERROR
    }

    /** Result of one policy check, already persisted when [Status] != ERROR. */
    data class CheckResult(
        val policy: ContainerAutoUpdatePolicy,
        val status: Status,
        /** The digest the registry currently serves (null on ERROR). */
        val registryDigest: String? = null,
        /** The image reference that was checked (null when unresolvable). */
        val imageRef: String? = null,
        val message: String? = null
    )

    companion object {
        private const val TAG = "UpdateChecker"

        /**
         * Pure pending-update decision, unit-testable without I/O:
         *  1. A resolved [runningDigest] is authoritative — pending iff the
         *     registry moved past it.
         *  2. No running digest but an [existingPending] flag — an update was
         *     already known; it stays pending (digest refreshed).
         *  3. No running digest but a [lastDigestSeen] baseline — pending iff
         *     the registry moved past the baseline.
         *  4. Nothing to compare — first check only baselines, never flags.
         */
        fun decide(
            registryDigest: String,
            runningDigest: String?,
            lastDigestSeen: String?,
            existingPending: String?
        ): Boolean = when {
            runningDigest != null -> registryDigest != runningDigest
            existingPending != null -> true
            lastDigestSeen != null -> registryDigest != lastDigestSeen
            else -> false
        }

        /**
         * Normalize inspect output to a single JSONObject: the Engine API
         * returns an object, `docker inspect` returns a one-element ARRAY.
         * Null when [raw] is neither.
         */
        fun normalizeInspect(raw: String): JSONObject? {
            val trimmed = raw.trim()
            return try {
                when {
                    trimmed.startsWith("{") -> JSONObject(trimmed)
                    trimmed.startsWith("[") -> JSONArray(trimmed).optJSONObject(0)
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Pick the RepoDigests entry ("repo@sha256:…") matching [ref] and
         * return its digest part. Repositories are compared canonically via
         * [ImageRef.parse] so "nginx" matches "docker.io/library/nginx@…".
         * Falls back to the sole entry when only one exists.
         */
        fun repoDigestFor(repoDigests: List<String>, ref: ImageRef): String? {
            val matches = repoDigests.mapNotNull { entry ->
                val at = entry.lastIndexOf('@')
                if (at <= 0) return@mapNotNull null
                val repo = ImageRef.parse(entry.substring(0, at)) ?: return@mapNotNull null
                val digest = entry.substring(at + 1)
                if (digest.startsWith("sha256:")) repo to digest else null
            }
            matches.firstOrNull { it.first.canonicalRepository == ref.canonicalRepository }
                ?.let { return it.second }
            // Locally retagged images keep the digest under the original repo
            // name — with exactly one entry, that digest is still the pull.
            return matches.singleOrNull()?.second
        }
    }

    /** Check every enabled policy belonging to [hostId] over [transport]. */
    suspend fun checkAll(hostId: Long, transport: DockerTransport): List<CheckResult> =
        policyDao.getEnabledList()
            .filter { it.dockerHostId == hostId }
            .map { checkOne(it, transport) }

    /**
     * Check one [policy]: inspect the running container for its image ref and
     * pulled digest, fetch the registry digest, decide, persist.
     */
    suspend fun checkOne(
        policy: ContainerAutoUpdatePolicy,
        transport: DockerTransport
    ): CheckResult {
        // 1 — what is actually running.
        val inspect = when (val r = transport.inspectContainer(policy.containerNameOrStackName)) {
            is DockerResult.Success -> normalizeInspect(r.value)
                ?: return error(policy, null, "Unparseable inspect output")
            is DockerResult.NotFound ->
                return error(policy, null, "Container ${policy.containerNameOrStackName} not found")
            else -> return error(policy, null, failureMessage(r))
        }
        val imageRefRaw = inspect.optJSONObject("Config")?.optString("Image").orEmpty()
        if (imageRefRaw.isEmpty()) {
            return error(policy, null, "Container has no Config.Image")
        }
        val ref = ImageRef.parse(imageRefRaw)
            ?: return error(policy, imageRefRaw, "Unparseable image reference")

        // 2 — the digest that image was pulled from (RepoDigests preferred;
        // locally built images have none and fall back to decide()'s
        // lastDigestSeen baseline path).
        val runningDigest = resolveRunningDigest(inspect, ref, transport)

        // 3 — the digest the registry currently serves.
        val credential = policy.registryCredentialId?.let { credentialDao.getById(it) }
        val secret = credential?.takeIf { it.authType != "anonymous" }
            ?.let { secretProvider(it.id) }
        val registryDigest =
            when (val r = registry.fetchManifestDigest(ref, credential, secret)) {
                is DockerResult.Success -> r.value
                else -> return error(policy, imageRefRaw, failureMessage(r))
            }

        // 4 — decide and persist.
        val pending = decide(
            registryDigest, runningDigest, policy.lastDigestSeen, policy.pendingUpdateDigest
        )
        policyDao.updatePendingUpdateDigest(policy.id, if (pending) registryDigest else null)
        policyDao.updateCheckResult(policy.id, System.currentTimeMillis(), registryDigest)

        val status = when {
            pending -> Status.UPDATE_AVAILABLE
            runningDigest == null && policy.lastDigestSeen == null -> Status.BASELINED
            else -> Status.UP_TO_DATE
        }
        Logger.d(TAG, "check for policy ${policy.id} (${policy.containerNameOrStackName}): $status")
        return CheckResult(policy, status, registryDigest, imageRefRaw)
    }

    /**
     * RepoDigests of the running container's image: first from the image the
     * container references by id (exact), then from the tag ref. Null when
     * the image was built locally and has never been pulled or pushed.
     */
    private suspend fun resolveRunningDigest(
        inspect: JSONObject,
        ref: ImageRef,
        transport: DockerTransport
    ): String? {
        val imageId = inspect.optString("Image")
        val target = imageId.ifEmpty { "${ref.canonicalRepository}:${ref.tag}" }
        val imageInspect = when (val r = transport.inspectImage(target)) {
            is DockerResult.Success -> normalizeInspect(r.value) ?: return null
            else -> return null
        }
        val array = imageInspect.optJSONArray("RepoDigests") ?: return null
        val entries = (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotEmpty() } }
        return repoDigestFor(entries, ref)
    }

    /** Build (and log) an ERROR result without touching persisted state. */
    private fun error(
        policy: ContainerAutoUpdatePolicy,
        imageRef: String?,
        message: String
    ): CheckResult {
        Logger.w(TAG, "check failed for policy ${policy.id} (${policy.containerNameOrStackName}): $message")
        return CheckResult(policy, Status.ERROR, imageRef = imageRef, message = message)
    }

    /** Human-readable message for any DockerResult failure. */
    private fun failureMessage(result: DockerResult<*>): String = when (result) {
        is DockerResult.Success -> "unexpected success"
        is DockerResult.PermissionDenied -> result.message
        is DockerResult.NotFound -> result.message
        is DockerResult.TransportUnavailable -> result.message
        is DockerResult.Error -> result.message
    }
}
