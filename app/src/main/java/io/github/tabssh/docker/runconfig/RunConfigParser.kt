package io.github.tabssh.docker.runconfig

import java.io.StringReader
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag

/**
 * Strict parser for single-container `run.yml` files → [RunConfig].
 *
 * Built on SnakeYAML's compose API (node graph, not object construction) so
 * every diagnostic can cite the 1-based source line of the offending node.
 * Parsing is strict:
 *  - `image` is required; every other key is optional.
 *  - Unknown keys are rejected with a message pointing at the `extra_args`
 *    escape hatch instead of being silently dropped.
 *  - Duplicate keys are rejected.
 *  - Each key's value shape is validated (scalar vs list vs map).
 *
 * `command` accepts either a YAML list (one argv token per element — the
 * only form that preserves embedded whitespace exactly) or a single string,
 * which is split shell-style (whitespace-separated, single/double quotes
 * honored, no variable expansion).
 */
object RunConfigParser {

    // YAML keys of the run.yml schema, in canonical file order. The writer
    // emits this order; the parser accepts any order.
    internal val SCHEMA_KEYS = listOf(
        "image", "name", "restart", "ports", "volumes", "env", "network",
        "command", "labels", "user", "workdir", "hostname", "privileged",
        "cap_add", "cap_drop", "devices", "tmpfs", "extra_args"
    )

    /**
     * Parse [text] (the contents of a `run.yml`) into a [RunConfig].
     *
     * @throws RunConfigException on any syntax or schema violation, with the
     *   source line woven into the message where known.
     */
    fun parse(text: String): RunConfig {
        val root = composeRoot(text)
        var image: String? = null
        var name: String? = null
        var restart: String? = null
        var ports: List<String> = emptyList()
        var volumes: List<String> = emptyList()
        var env: Map<String, String> = emptyMap()
        var network: String? = null
        var command: List<String> = emptyList()
        var labels: Map<String, String> = emptyMap()
        var user: String? = null
        var workdir: String? = null
        var hostname: String? = null
        var privileged = false
        var capAdd: List<String> = emptyList()
        var capDrop: List<String> = emptyList()
        var devices: List<String> = emptyList()
        var tmpfs: List<String> = emptyList()
        var extraArgs: List<String> = emptyList()

        val seen = HashSet<String>()
        for (tuple in root.value) {
            val keyNode = tuple.keyNode
            if (keyNode !is ScalarNode) {
                fail("mapping keys must be plain strings", keyNode)
            }
            val key = keyNode.value
            if (!seen.add(key)) {
                fail("duplicate key `$key`", keyNode, key)
            }
            val value = tuple.valueNode
            // A key present with an explicit null value (`ports:` with
            // nothing after it) is treated as absent.
            if (value is ScalarNode && value.tag == Tag.NULL) continue
            when (key) {
                "image" -> image = scalar(value, key)
                "name" -> name = scalar(value, key)
                "restart" -> restart = scalar(value, key)
                "ports" -> ports = stringList(value, key)
                "volumes" -> volumes = stringList(value, key)
                "env" -> env = stringMap(value, key)
                "network" -> network = scalar(value, key)
                "command" -> command = commandValue(value)
                "labels" -> labels = stringMap(value, key)
                "user" -> user = scalar(value, key)
                "workdir" -> workdir = scalar(value, key)
                "hostname" -> hostname = scalar(value, key)
                "privileged" -> privileged = boolean(value, key)
                "cap_add" -> capAdd = stringList(value, key)
                "cap_drop" -> capDrop = stringList(value, key)
                "devices" -> devices = stringList(value, key)
                "tmpfs" -> tmpfs = stringList(value, key)
                "extra_args" -> extraArgs = stringList(value, key)
                else -> fail(
                    "unknown key `$key` — not part of the run.yml schema " +
                        "(known keys: ${SCHEMA_KEYS.joinToString(", ")}); " +
                        "to pass a raw `docker run` flag use the `extra_args` list instead",
                    keyNode,
                    key
                )
            }
        }

        val img = image
            ?: throw RunConfigException(
                "run.yml: required key `image` is missing",
                field = "image"
            )
        if (img.isBlank()) {
            throw RunConfigException(
                "run.yml: `image` must not be empty",
                field = "image"
            )
        }
        return RunConfig(
            image = img,
            name = name,
            restart = restart,
            ports = ports,
            volumes = volumes,
            env = env,
            network = network,
            command = command,
            labels = labels,
            user = user,
            workdir = workdir,
            hostname = hostname,
            privileged = privileged,
            capAdd = capAdd,
            capDrop = capDrop,
            devices = devices,
            tmpfs = tmpfs,
            extraArgs = extraArgs
        )
    }

    /**
     * Split a `command:` string into argv tokens shell-style: whitespace
     * separates tokens; single and double quotes group; a backslash escapes
     * the next character outside single quotes. No expansion of any kind.
     */
    internal fun splitCommandString(raw: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        // Tracks whether the current token has begun, so `''` yields an
        // explicit empty token instead of being dropped.
        var started = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\'' -> {
                    started = true
                    val end = raw.indexOf('\'', i + 1)
                    if (end < 0) {
                        throw RunConfigException(
                            "run.yml: `command` string has an unterminated single quote",
                            field = "command"
                        )
                    }
                    current.append(raw, i + 1, end)
                    i = end
                }
                c == '"' -> {
                    started = true
                    i++
                    var closed = false
                    while (i < raw.length) {
                        val d = raw[i]
                        if (d == '\\' && i + 1 < raw.length) {
                            current.append(raw[i + 1])
                            i++
                        } else if (d == '"') {
                            closed = true
                            break
                        } else {
                            current.append(d)
                        }
                        i++
                    }
                    if (!closed) {
                        throw RunConfigException(
                            "run.yml: `command` string has an unterminated double quote",
                            field = "command"
                        )
                    }
                }
                c == '\\' && i + 1 < raw.length -> {
                    started = true
                    current.append(raw[i + 1])
                    i++
                }
                c.isWhitespace() -> {
                    if (started) {
                        tokens.add(current.toString())
                        current.setLength(0)
                        started = false
                    }
                }
                else -> {
                    started = true
                    current.append(c)
                }
            }
            i++
        }
        if (started) tokens.add(current.toString())
        return tokens
    }

    // ── Node helpers ─────────────────────────────────────────────────────────

    private fun composeRoot(text: String): MappingNode {
        val root: Node? = try {
            Yaml().compose(StringReader(text))
        } catch (e: MarkedYAMLException) {
            val line = e.problemMark?.line?.plus(1)
            throw RunConfigException(
                "run.yml${line?.let { " line $it" } ?: ""}: invalid YAML — ${e.problem}",
                line = line,
                cause = e
            )
        } catch (e: YAMLException) {
            throw RunConfigException("run.yml: invalid YAML — ${e.message}", cause = e)
        }
        if (root == null) {
            throw RunConfigException("run.yml is empty — at minimum `image:` is required")
        }
        if (root !is MappingNode) {
            fail("top level must be a mapping of run.yml keys", root)
        }
        return root
    }

    private fun scalar(node: Node, field: String): String {
        if (node !is ScalarNode || node.tag == Tag.NULL) {
            fail("`$field` must be a single string value", node, field)
        }
        return node.value
    }

    private fun stringList(node: Node, field: String): List<String> {
        if (node !is SequenceNode) {
            fail("`$field` must be a list of strings (one `- item` per line)", node, field)
        }
        return node.value.map { item ->
            if (item !is ScalarNode || item.tag == Tag.NULL) {
                fail("`$field` entries must be plain strings", item, field)
            }
            item.value
        }
    }

    private fun stringMap(node: Node, field: String): Map<String, String> {
        if (node !is MappingNode) {
            fail("`$field` must be a mapping of `KEY: value` pairs", node, field)
        }
        val out = LinkedHashMap<String, String>()
        for (tuple in node.value) {
            val k = tuple.keyNode
            if (k !is ScalarNode) {
                fail("`$field` keys must be plain strings", k, field)
            }
            val v = tuple.valueNode
            if (v !is ScalarNode) {
                fail("`$field`.${k.value} must be a single string value", v, field)
            }
            // An explicit null value ("KEY:") becomes the empty string, which
            // `docker run -e KEY=` also treats as set-but-empty.
            out[k.value] = if (v.tag == Tag.NULL) "" else v.value
        }
        return out
    }

    private fun boolean(node: Node, field: String): Boolean {
        if (node is ScalarNode && node.tag == Tag.BOOL) {
            return when (node.value.lowercase()) {
                "true", "yes", "on" -> true
                "false", "no", "off" -> false
                else -> fail("`$field` must be true or false", node, field)
            }
        }
        fail("`$field` must be true or false", node, field)
    }

    private fun commandValue(node: Node): List<String> = when (node) {
        is SequenceNode -> stringList(node, "command")
        is ScalarNode -> splitCommandString(node.value)
        else -> fail("`command` must be a string or a list of strings", node, "command")
    }

    // Throws a RunConfigException citing the node's 1-based source line.
    // Declared to return Nothing so call sites can use it in expressions.
    private fun fail(message: String, node: Node, field: String? = null): Nothing {
        val line = node.startMark?.line?.plus(1)
        throw RunConfigException(
            "run.yml${line?.let { " line $it" } ?: ""}: $message",
            line = line,
            field = field
        )
    }
}
