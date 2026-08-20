package io.github.tabssh.containers.runconfig

import org.json.JSONObject

/**
 * Pure planning half of the pull-and-recreate flow (PLAN Phase 5, step 32).
 * Nothing here touches a transport — this object only derives the artifacts
 * a transport needs to execute the plan:
 *
 *  - [createBodyFromInspect] — API tier: the `POST /containers/create`
 *    request body, reusing the old container's `Config` / `HostConfig` /
 *    `NetworkingConfig` verbatim with only the image ref substituted, so
 *    recreation is as lossless as the Engine API allows.
 *  - [runArgvFromInspect] — CLI tier: `docker run` argv via
 *    [RunConfigTranslator], the documented lossier fallback.
 *  - [PLAN] / [RecreateStep] — the ordered step sequence both tiers follow,
 *    with rollback as the recovery path.
 */
object RecreateContainer {

    /** Suffix appended when renaming the old container as the rollback net. */
    const val OLD_NAME_SUFFIX = "_old"

    /**
     * How long [RecreateStep.VERIFY_NEW] watches the new container when the
     * image defines no HEALTHCHECK: if it is still running after this window,
     * the recreate is declared successful. Images with a HEALTHCHECK are
     * verified by health status instead and ignore this timer.
     */
    const val VERIFY_WINDOW_MS = 30_000L

    /**
     * One step of the recreate flow. [PLAN] holds the forward order;
     * [ROLLBACK] runs only when a step after [RENAME_OLD] fails: remove the
     * new container if created, rename `{name}_old` back, restart it.
     */
    enum class RecreateStep {
        /** Pull the new image (registry digest already known to differ). */
        PULL_IMAGE,

        /** Stop the running container. */
        STOP_OLD,

        /** Rename it to `{name}{_old}` so the name frees up and rollback stays possible. */
        RENAME_OLD,

        /** Create + start the replacement under the original name. */
        CREATE_NEW,

        /** Wait for HEALTHCHECK to pass, or for [VERIFY_WINDOW_MS] of uptime. */
        VERIFY_NEW,

        /** Success: remove the `{name}_old` container. */
        REMOVE_OLD,

        /** Failure recovery: remove the new container, restore and restart the old one. */
        ROLLBACK
    }

    /** The forward step sequence; [RecreateStep.ROLLBACK] is not part of it. */
    val PLAN = listOf(
        RecreateStep.PULL_IMAGE,
        RecreateStep.STOP_OLD,
        RecreateStep.RENAME_OLD,
        RecreateStep.CREATE_NEW,
        RecreateStep.VERIFY_NEW,
        RecreateStep.REMOVE_OLD
    )

    /** The rollback name for a container currently named [name]. */
    fun oldName(name: String): String = name + OLD_NAME_SUFFIX

    /**
     * Build the `POST /containers/create` request body that recreates the
     * container described by [inspect] on [newImage].
     *
     * The Engine API's create body is the inspect `Config` object's fields at
     * the top level plus nested `HostConfig` and `NetworkingConfig` — so the
     * old `Config` and `HostConfig` are deep-copied VERBATIM (every field the
     * engine reported, known or not, survives), only `Config.Image` is
     * replaced, and `NetworkingConfig.EndpointsConfig` is rebuilt from
     * `NetworkSettings.Networks`. The engine ignores the runtime-state fields
     * (EndpointID, IPAddress, …) that ride along inside each endpoint object.
     *
     * The container NAME is not part of the body — the transport passes it as
     * the `name` query parameter.
     *
     * @throws RunConfigException when [inspect] has no `Config` object.
     */
    fun createBodyFromInspect(inspect: JSONObject, newImage: String): JSONObject {
        val config = inspect.optJSONObject("Config")
            ?: throw RunConfigException(
                "inspect JSON has no Config object — not a container inspect object?"
            )
        // Deep copy via JSON round trip so mutating the body never bleeds
        // into the caller's inspect object.
        val body = JSONObject(config.toString())
        body.put("Image", newImage)
        inspect.optJSONObject("HostConfig")?.let {
            body.put("HostConfig", JSONObject(it.toString()))
        }
        inspect.optJSONObject("NetworkSettings")?.optJSONObject("Networks")?.let { networks ->
            if (networks.length() > 0) {
                body.put(
                    "NetworkingConfig",
                    JSONObject().put("EndpointsConfig", JSONObject(networks.toString()))
                )
            }
        }
        return body
    }

    /**
     * CLI-tier equivalent of [createBodyFromInspect]: the `docker run` argv
     * (see [RunConfigTranslator.toRunArgv] for token semantics) recreating
     * the container described by [inspect] on [newImage]. Lossier than the
     * API tier by design — only the fields [RunConfigTranslator.fromInspect]
     * models survive.
     */
    fun runArgvFromInspect(inspect: JSONObject, newImage: String): List<String> {
        val config = RunConfigTranslator.fromInspect(inspect).copy(image = newImage)
        return RunConfigTranslator.toRunArgv(config)
    }
}
