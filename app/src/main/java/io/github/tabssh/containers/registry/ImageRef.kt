package io.github.tabssh.containers.registry

/**
 * Parsed container image reference.
 *
 * Follows the docker/distribution reference grammar closely enough for the
 * update-check flow: the first path component is a registry host only when
 * it contains a `.` or a `:` or is exactly `localhost` — otherwise the whole
 * name is a Docker Hub repository, and bare single-segment Hub names get the
 * implicit `library/` prefix. Tag defaults to `latest`; a `@sha256:…` digest
 * suffix pins the reference and wins over any tag for manifest lookups.
 */
data class ImageRef(
    /** Canonical registry host — always "docker.io" for Hub references. */
    val registryHost: String,
    /** Repository path, e.g. "library/nginx" or "casjaysdev/go". */
    val repository: String,
    /** Tag; "latest" when the reference names neither tag nor digest. */
    val tag: String,
    /** Pinning digest ("sha256:…") when the reference carried one. */
    val digest: String? = null
) {

    /**
     * Host to hit for /v2/ API calls — Docker Hub's API lives on
     * registry-1.docker.io, every other registry serves its own host.
     */
    val apiHost: String
        get() = if (registryHost == DOCKER_HUB_HOST) DOCKER_HUB_API_HOST else registryHost

    /** The /v2/{name}/manifests/{reference} path segment — digest wins over tag. */
    val manifestReference: String
        get() = digest ?: tag

    /** True when this reference points at Docker Hub. */
    val isDockerHub: Boolean
        get() = registryHost == DOCKER_HUB_HOST

    /** Canonical "host/repo" form used to match inspect RepoDigests entries. */
    val canonicalRepository: String
        get() = "$registryHost/$repository"

    companion object {
        const val DOCKER_HUB_HOST = "docker.io"
        const val DOCKER_HUB_API_HOST = "registry-1.docker.io"
        const val DEFAULT_TAG = "latest"

        // Hostnames that all mean Docker Hub and normalize to docker.io.
        private val HUB_ALIASES = setOf(
            "docker.io", "index.docker.io", "registry-1.docker.io", "registry.docker.io"
        )

        // Longest reference string accepted, mirroring the registry name limit.
        private const val MAX_REFERENCE_LENGTH = 512

        // "sha256:" plus exactly 64 lowercase hex characters — nothing else.
        private val DIGEST = Regex("^sha256:[0-9a-f]{64}$")

        /**
         * Canonical form of a configured registry host — every Docker Hub
         * spelling collapses to "docker.io" so a stored credential can be
         * matched against a parsed reference.
         */
        fun normalizeRegistryHost(host: String): String {
            val trimmed = host.trim().lowercase().removeSuffix("/")
            return if (trimmed in HUB_ALIASES) DOCKER_HUB_HOST else trimmed
        }

        /**
         * Whether [value] is a well-formed "sha256:…" content digest. Used to
         * validate digests that arrive from a registry response header or from
         * `docker inspect` output before they are compared or displayed.
         */
        fun isValidDigest(value: String): Boolean = DIGEST.matches(value.trim())

        // Registry host: DNS labels or IPv4 literal, with an optional port.
        private val REGISTRY_HOST =
            Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*(?::[0-9]{1,5})?$")

        // One docker/distribution path component — lowercase, no leading separator.
        private val PATH_COMPONENT = Regex("^[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*$")

        // docker/distribution tag grammar: no leading '.' or '-', max 128 chars.
        private val TAG = Regex("^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$")

        /**
         * Parse [raw] ("nginx", "user/repo:tag", "host:5000/repo@sha256:…")
         * into an [ImageRef]. Returns null for blank or malformed input
         * instead of throwing — callers surface a per-policy error.
         *
         * The grammar is enforced strictly rather than loosely, because every
         * field is interpolated into a registry URL path and into `docker`
         * argv: an unvalidated repository could smuggle `../` path traversal
         * into `/v2/{name}/manifests/…`, and an unvalidated reference starting
         * with `-` would be read by `docker run` as a flag.
         */
        fun parse(raw: String): ImageRef? {
            var rest = raw.trim()
            if (rest.isEmpty() || rest.length > MAX_REFERENCE_LENGTH) return null

            // Digest suffix: everything after the first '@'.
            var digest: String? = null
            val at = rest.indexOf('@')
            if (at >= 0) {
                digest = rest.substring(at + 1)
                rest = rest.substring(0, at)
                if (!DIGEST.matches(digest) || rest.isEmpty()) return null
            }

            // Registry host: first '/'-segment with '.'/':' or "localhost".
            var registry = DOCKER_HUB_HOST
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                val first = rest.substring(0, slash)
                if (first.contains('.') || first.contains(':') || first == "localhost") {
                    if (!REGISTRY_HOST.matches(first)) return null
                    registry = if (first in HUB_ALIASES) DOCKER_HUB_HOST else first
                    rest = rest.substring(slash + 1)
                    if (rest.isEmpty()) return null
                }
            }

            // Tag: a ':' after the last '/' (so a registry port never matches).
            var tag = DEFAULT_TAG
            val lastSlash = rest.lastIndexOf('/')
            val colon = rest.lastIndexOf(':')
            if (colon > lastSlash) {
                tag = rest.substring(colon + 1)
                rest = rest.substring(0, colon)
                if (rest.isEmpty()) return null
            }
            if (!TAG.matches(tag)) return null

            // Every path component must match the reference grammar — this is
            // what rejects "..", empty segments and leading-dash names.
            val components = rest.split('/')
            if (components.any { !PATH_COMPONENT.matches(it) }) return null

            // Bare Hub names get the implicit library/ namespace.
            val repository =
                if (registry == DOCKER_HUB_HOST && components.size == 1) "library/$rest" else rest

            return ImageRef(registry, repository, tag, digest)
        }
    }
}
