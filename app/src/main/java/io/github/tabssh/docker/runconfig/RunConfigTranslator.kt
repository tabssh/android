package io.github.tabssh.docker.runconfig

import org.json.JSONObject

/**
 * Translates between [RunConfig] and the two live representations used by
 * the transports (PLAN Phase 3, step 17):
 *
 *  - [toRunArgv]: `RunConfig` → `docker run` argv token list for the
 *    CLI-exec tier.
 *  - [fromInspect]: a live `docker inspect` JSON object (single container)
 *    → best-effort `RunConfig` for the recreate flow's CLI fallback and for
 *    "import running container as run.yml".
 *
 * The reverse direction is inherently lossy in one documented way: the
 * engine merges image-baked defaults (ENV, LABEL, CMD, USER, WORKDIR from
 * the Dockerfile) into `Config.*` at create time and does not record which
 * values came from the user. [fromInspect] therefore returns the FULL
 * effective env/labels/command — re-running the result is behaviorally
 * identical, but the run.yml is more verbose than what the user originally
 * wrote.
 */
object RunConfigTranslator {

    /**
     * Build the `docker run` argv for [config] as a token list — one execve
     * argument per element, in canonical order: options first (`-d` and, when
     * a name is set, `--name` always lead), then [RunConfig.extraArgs]
     * verbatim, then the image reference, then the command tokens.
     *
     * The list does NOT include the `docker` binary itself — the CLI
     * transport prepends its per-host docker path. Values are raw tokens;
     * shell quoting (if the transport goes through a shell) is the
     * transport's responsibility.
     */
    fun toRunArgv(config: RunConfig): List<String> {
        val argv = ArrayList<String>()
        argv += "run"
        argv += "-d"
        config.name?.let { argv += listOf("--name", it) }
        config.restart?.let { argv += listOf("--restart", it) }
        for (p in config.ports) argv += listOf("-p", p)
        for (v in config.volumes) argv += listOf("-v", v)
        for ((k, v) in config.env) argv += listOf("-e", "$k=$v")
        config.network?.let { argv += listOf("--network", it) }
        for ((k, v) in config.labels) argv += listOf("--label", "$k=$v")
        config.user?.let { argv += listOf("--user", it) }
        config.workdir?.let { argv += listOf("--workdir", it) }
        config.hostname?.let { argv += listOf("--hostname", it) }
        if (config.privileged) argv += "--privileged"
        for (c in config.capAdd) argv += listOf("--cap-add", c)
        for (c in config.capDrop) argv += listOf("--cap-drop", c)
        for (d in config.devices) argv += listOf("--device", d)
        for (t in config.tmpfs) argv += listOf("--tmpfs", t)
        argv += config.extraArgs
        argv += config.image
        argv += config.command
        return argv
    }

    /**
     * Best-effort reverse translation of a single container's
     * `docker inspect` JSON object into a [RunConfig].
     *
     * Field sources: `Config.Image`, `Config.Env`, `Config.Labels`,
     * `Config.Cmd`, `Config.User`, `Config.WorkingDir`, `Config.Hostname`,
     * `HostConfig.PortBindings`, `HostConfig.Binds`,
     * `HostConfig.RestartPolicy`, `HostConfig.NetworkMode`,
     * `HostConfig.CapAdd`/`CapDrop`, `HostConfig.Devices`,
     * `HostConfig.Tmpfs`, `HostConfig.Privileged`, and the container `Name`.
     *
     * Known lossiness (see class KDoc): env/labels/command come back as the
     * FULL effective values including image-baked defaults. `hostname` is
     * only kept when it differs from the short container id, since the
     * engine auto-assigns the id as hostname when none was requested.
     *
     * @throws RunConfigException when the JSON has no usable `Config.Image`.
     */
    fun fromInspect(inspect: JSONObject): RunConfig {
        val config = inspect.optJSONObject("Config") ?: JSONObject()
        val hostConfig = inspect.optJSONObject("HostConfig") ?: JSONObject()

        val image = config.optString("Image")
        if (image.isBlank()) {
            throw RunConfigException(
                "inspect JSON has no Config.Image — not a container inspect object?",
                field = "image"
            )
        }

        val env = LinkedHashMap<String, String>()
        config.optJSONArray("Env")?.let { arr ->
            for (i in 0 until arr.length()) {
                val entry = arr.optString(i)
                val eq = entry.indexOf('=')
                if (eq >= 0) env[entry.substring(0, eq)] = entry.substring(eq + 1)
                else if (entry.isNotEmpty()) env[entry] = ""
            }
        }

        val labels = LinkedHashMap<String, String>()
        config.optJSONObject("Labels")?.let { obj ->
            for (key in obj.keys()) labels[key] = obj.optString(key)
        }

        val ports = ArrayList<String>()
        hostConfig.optJSONObject("PortBindings")?.let { bindings ->
            for (portKey in bindings.keys()) {
                // Key form: "80/tcp". The default protocol (tcp) is dropped
                // from the run.yml entry; udp/sctp are kept as a suffix.
                val slash = portKey.indexOf('/')
                val ctrPort = if (slash >= 0) portKey.substring(0, slash) else portKey
                val proto = if (slash >= 0) portKey.substring(slash + 1) else "tcp"
                val suffix = if (proto == "tcp") "" else "/$proto"
                val list = bindings.optJSONArray(portKey) ?: continue
                for (i in 0 until list.length()) {
                    val binding = list.optJSONObject(i) ?: continue
                    val hostIp = binding.optString("HostIp")
                    val hostPort = binding.optString("HostPort")
                    val base = if (hostPort.isEmpty()) ctrPort else "$hostPort:$ctrPort"
                    // 0.0.0.0 / :: are the implicit any-address defaults —
                    // only an explicit bind address is worth preserving.
                    val entry =
                        if (hostIp.isEmpty() || hostIp == "0.0.0.0" || hostIp == "::") base
                        else "$hostIp:$base"
                    ports += entry + suffix
                }
            }
        }

        val volumes = jsonStringList(hostConfig, "Binds")

        val restart = hostConfig.optJSONObject("RestartPolicy")?.let { policy ->
            val policyName = policy.optString("Name")
            val retries = policy.optInt("MaximumRetryCount", 0)
            when {
                policyName.isEmpty() || policyName == "no" -> null
                policyName == "on-failure" && retries > 0 -> "on-failure:$retries"
                else -> policyName
            }
        }

        val networkMode = hostConfig.optString("NetworkMode")
        val network = if (networkMode.isEmpty() || networkMode == "default") null else networkMode

        val devices = ArrayList<String>()
        hostConfig.optJSONArray("Devices")?.let { arr ->
            for (i in 0 until arr.length()) {
                val dev = arr.optJSONObject(i) ?: continue
                val host = dev.optString("PathOnHost")
                val ctr = dev.optString("PathInContainer")
                val perms = dev.optString("CgroupPermissions")
                // "rwm" is the docker default permission set — omit it.
                val permSuffix = if (perms.isEmpty() || perms == "rwm") "" else ":$perms"
                if (host.isNotEmpty()) {
                    devices += if (ctr.isEmpty() || ctr == host) host + permSuffix
                    else "$host:$ctr$permSuffix"
                }
            }
        }

        val tmpfs = ArrayList<String>()
        hostConfig.optJSONObject("Tmpfs")?.let { obj ->
            for (path in obj.keys()) {
                val opts = obj.optString(path)
                tmpfs += if (opts.isEmpty()) path else "$path:$opts"
            }
        }

        val rawName = inspect.optString("Name").removePrefix("/")
        val shortId = inspect.optString("Id").take(12)
        val hostname = config.optString("Hostname")

        return RunConfig(
            image = image,
            name = rawName.ifEmpty { null },
            restart = restart,
            ports = ports,
            volumes = volumes,
            env = env,
            network = network,
            command = jsonStringList(config, "Cmd"),
            labels = labels,
            user = config.optString("User").ifEmpty { null },
            workdir = config.optString("WorkingDir").ifEmpty { null },
            hostname = if (hostname.isEmpty() || hostname == shortId) null else hostname,
            privileged = hostConfig.optBoolean("Privileged", false),
            capAdd = jsonStringList(hostConfig, "CapAdd"),
            capDrop = jsonStringList(hostConfig, "CapDrop"),
            devices = devices,
            tmpfs = tmpfs
        )
    }

    // Read an optional JSON array of strings; JSON null and a missing key
    // both mean an empty list (docker emits null for unset array fields).
    private fun jsonStringList(obj: JSONObject, key: String): List<String> {
        val arr = obj.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }
    }
}
