package io.github.tabssh.containers.runconfig

/**
 * Data model for a single-container `run.yml` config file — the flat,
 * transport-independent schema that mirrors `docker run` flags (PLAN
 * Phase 3, step 17). One file lives per container at
 * `{runConfigBase}/{name}/run.yml` on the Docker host.
 *
 * Every field except [image] is optional. String-shaped fields keep the
 * exact `docker run` value syntax so the translator can pass them through
 * verbatim:
 *  - [ports]:   `host:ctr[/proto]` (optionally `ip:host:ctr[/proto]`)
 *  - [volumes]: `host:ctr[:mode]`
 *  - [devices]: `hostPath[:ctrPath[:permissions]]`
 *  - [tmpfs]:   `path[:opts]`
 *  - [restart]: `no|always|unless-stopped|on-failure[:maxRetries]`
 *
 * [extraArgs] is the escape hatch for any `docker run` flag the schema
 * does not model — tokens are inserted verbatim (already split, one argv
 * token per list element) immediately before the image reference.
 */
data class RunConfig(
    val image: String,
    val name: String? = null,
    val restart: String? = null,
    val ports: List<String> = emptyList(),
    val volumes: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val network: String? = null,
    val command: List<String> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
    val user: String? = null,
    val workdir: String? = null,
    val hostname: String? = null,
    val privileged: Boolean = false,
    val capAdd: List<String> = emptyList(),
    val capDrop: List<String> = emptyList(),
    val devices: List<String> = emptyList(),
    val tmpfs: List<String> = emptyList(),
    val extraArgs: List<String> = emptyList()
)

/**
 * Thrown by [RunConfigParser] for any structural or semantic problem in a
 * `run.yml`. [line] is the 1-based source line of the offending node when
 * known, and [field] the run.yml key involved; both are already woven into
 * [message] so callers can surface it directly.
 */
class RunConfigException(
    message: String,
    val line: Int? = null,
    val field: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)
