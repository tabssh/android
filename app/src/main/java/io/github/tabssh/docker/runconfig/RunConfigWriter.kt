package io.github.tabssh.docker.runconfig

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/**
 * Serializes a [RunConfig] back to canonical `run.yml` text.
 *
 * Canonical form:
 *  - keys in schema order ([RunConfigParser.SCHEMA_KEYS]);
 *  - only non-default fields are emitted (`image` always);
 *  - `command` is always written as a list, never a string, so embedded
 *    whitespace survives a round trip exactly;
 *  - block style, 2-space indent, SnakeYAML handles all value quoting.
 *
 * Output is guaranteed to round-trip: `parse(write(config)) == config`.
 */
object RunConfigWriter {

    /** Render [config] as `run.yml` text ending in a single newline. */
    fun write(config: RunConfig): String {
        val doc = LinkedHashMap<String, Any>()
        doc["image"] = config.image
        config.name?.let { doc["name"] = it }
        config.restart?.let { doc["restart"] = it }
        if (config.ports.isNotEmpty()) doc["ports"] = config.ports
        if (config.volumes.isNotEmpty()) doc["volumes"] = config.volumes
        if (config.env.isNotEmpty()) doc["env"] = config.env
        config.network?.let { doc["network"] = it }
        if (config.command.isNotEmpty()) doc["command"] = config.command
        if (config.labels.isNotEmpty()) doc["labels"] = config.labels
        config.user?.let { doc["user"] = it }
        config.workdir?.let { doc["workdir"] = it }
        config.hostname?.let { doc["hostname"] = it }
        if (config.privileged) doc["privileged"] = true
        if (config.capAdd.isNotEmpty()) doc["cap_add"] = config.capAdd
        if (config.capDrop.isNotEmpty()) doc["cap_drop"] = config.capDrop
        if (config.devices.isNotEmpty()) doc["devices"] = config.devices
        if (config.tmpfs.isNotEmpty()) doc["tmpfs"] = config.tmpfs
        if (config.extraArgs.isNotEmpty()) doc["extra_args"] = config.extraArgs

        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 2
            width = 100
        }
        return Yaml(options).dump(doc)
    }
}
