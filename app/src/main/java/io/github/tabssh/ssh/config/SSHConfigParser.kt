package io.github.tabssh.ssh.config

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.utils.logging.Logger
import java.io.BufferedReader
import java.io.File
import java.io.StringReader

/**
 * SSH Configuration file parser
 * Parses OpenSSH config file format (~/.ssh/config)
 * Implements specification from SPEC.md section 4.1.2
 */
class SSHConfigParser {

    companion object {
        private val SUPPORTED_DIRECTIVES = setOf(
            "Host", "HostName", "User", "Port", "IdentityFile",
            "ProxyJump", "ProxyCommand", "LocalForward", "RemoteForward",
            "DynamicForward", "Compression", "ServerAliveInterval",
            "ConnectTimeout", "PasswordAuthentication", "PubkeyAuthentication",
            "StrictHostKeyChecking", "UserKnownHostsFile", "LogLevel",
            "PreferredAuthentications", "NumberOfPasswordPrompts",
            "TCPKeepAlive", "ServerAliveCountMax", "ForwardAgent",
            "ForwardX11", "ForwardX11Trusted", "RequestTTY",
            // Issue #37
            "RemoteCommand", "SendEnv"
        )

        private const val DEFAULT_PORT = 22
        private const val DEFAULT_USERNAME = ""
    }

    data class SSHHost(
        var hostPattern: String = "",
        var hostname: String? = null,
        var user: String? = null,
        var port: Int = DEFAULT_PORT,
        var identityFileStr: String? = null,
        var proxyJump: String? = null,
        var proxyCommand: String? = null,
        var compression: Boolean = false,
        var serverAliveInterval: Int = 0,
        var connectTimeout: Int = 15,
        var passwordAuthentication: Boolean = true,
        var pubkeyAuthentication: Boolean = true,
        var strictHostKeyChecking: String = "ask",
        var localForwards: MutableList<String> = mutableListOf(),
        var remoteForwards: MutableList<String> = mutableListOf(),
        var dynamicForwards: MutableList<String> = mutableListOf(),
        var forwardAgent: Boolean = false,
        var forwardX11: Boolean = false,
        var requestTTY: String? = null,
        // Issue #37 — explicit `RemoteCommand` (string) and `SendEnv` (list of names).
        var remoteCommand: String? = null,
        var sendEnv: MutableList<String> = mutableListOf(),
        var groupName: String? = null,
        var additionalOptions: MutableMap<String, String> = mutableMapOf()
    )

    /**
     * Parse SSH config file content
     * @param configContent The content of the SSH config file
     * @return List of ConnectionProfile objects
     */
    fun parseConfig(configContent: String): List<ConnectionProfile> {
        val connections = mutableListOf<ConnectionProfile>()
        val hosts = parseHosts(configContent)

        hosts.forEach { host ->
            if (host.hostPattern != "*" && !host.hostPattern.contains("*")) {
                connections.add(convertToConnectionProfile(host))
            }
        }

        return connections
    }

    /**
     * Parse SSH config file
     * @param configFile The SSH config file
     * @return List of ConnectionProfile objects
     */
    fun parseConfigFile(configFile: File): List<ConnectionProfile> {
        return if (configFile.exists() && configFile.canRead()) {
            parseConfig(configFile.readText())
        } else {
            Logger.w("SSHConfigParser", "Config file not found or not readable: ${configFile.path}")
            emptyList()
        }
    }

    /**
     * Parse hosts from config content
     */
    private fun parseHosts(configContent: String): List<SSHHost> {
        val hosts = mutableListOf<SSHHost>()
        var currentHost: SSHHost? = null

        BufferedReader(StringReader(configContent)).use { reader ->
            reader.forEachLine { line ->
                val trimmedLine = line.trim()

                // Skip empty lines
                if (trimmedLine.isEmpty()) {
                    return@forEachLine
                }

                // Check if line is a comment (may contain group info)
                if (trimmedLine.startsWith("#")) {
                    return@forEachLine
                }

                // Split into directive and value (and optional inline comment)
                val parts = trimmedLine.split("\\s+".toRegex(), limit = 2)
                if (parts.isEmpty()) return@forEachLine

                val directive = parts[0]
                val valueWithComment = if (parts.size > 1) parts[1].trim() else ""

                // Extract value and inline comment
                val commentIndex = valueWithComment.indexOf("##")
                val value = if (commentIndex > 0) {
                    valueWithComment.substring(0, commentIndex).trim()
                } else {
                    valueWithComment
                }

                // Extract group from inline comment if present (pattern: [group-name])
                val groupMatch = if (commentIndex > 0) {
                    val comment = valueWithComment.substring(commentIndex + 2).trim()
                    Regex("\\[([^\\]]+)\\]").find(comment)
                } else {
                    null
                }

                when (directive.lowercase()) {
                    "host" -> {
                        // Save previous host if exists
                        currentHost?.let { hosts.add(it) }
                        // Start new host
                        currentHost = SSHHost(hostPattern = value)
                        // Extract group name from inline comment if present
                        currentHost?.groupName = groupMatch?.groupValues?.get(1)
                    }

                    "hostname" -> currentHost?.hostname = value
                    "user" -> currentHost?.user = value
                    "port" -> currentHost?.port = value.toIntOrNull() ?: DEFAULT_PORT
                    "identityfile" -> currentHost?.identityFileStr = expandPath(value)
                    "proxyjump" -> currentHost?.proxyJump = value
                    "proxycommand" -> currentHost?.proxyCommand = value
                    "compression" -> currentHost?.compression = value.equals("yes", ignoreCase = true)
                    "serveraliveinterval" -> currentHost?.serverAliveInterval = value.toIntOrNull() ?: 0
                    "connecttimeout" -> currentHost?.connectTimeout = value.toIntOrNull() ?: 15
                    "passwordauthentication" -> currentHost?.passwordAuthentication = value.equals("yes", ignoreCase = true)
                    "pubkeyauthentication" -> currentHost?.pubkeyAuthentication = value.equals("yes", ignoreCase = true)
                    "stricthostkeychecking" -> currentHost?.strictHostKeyChecking = value.lowercase()
                    "localforward" -> currentHost?.localForwards?.add(value)
                    "remoteforward" -> currentHost?.remoteForwards?.add(value)
                    "dynamicforward" -> currentHost?.dynamicForwards?.add(value)
                    "forwardagent" -> currentHost?.forwardAgent = value.equals("yes", ignoreCase = true)
                    "forwardx11" -> currentHost?.forwardX11 = value.equals("yes", ignoreCase = true)
                    "requesttty" -> currentHost?.requestTTY = value

                    // Issue #37 — RemoteCommand: the command to run instead of
                    // a login shell (e.g. SourceForge's `create`). SendEnv:
                    // names of env vars to forward (values come from the
                    // local environment per OpenSSH; we treat them as names
                    // only and let the user populate values via TabSSH's
                    // env_vars field if desired).
                    "remotecommand" -> currentHost?.remoteCommand = value
                    "sendenv" -> {
                        // SendEnv may list multiple names on one line; OpenSSH
                        // also allows multiple SendEnv lines per host.
                        value.split("\\s+".toRegex()).forEach { name ->
                            if (name.isNotBlank()) currentHost?.sendEnv?.add(name)
                        }
                    }

                    else -> {
                        // Store unsupported or custom directives
                        if (currentHost != null && directive.isNotEmpty()) {
                            currentHost?.additionalOptions?.put(directive, value)
                        }
                    }
                }
            }
        }

        // Add the last host if exists
        currentHost?.let { hosts.add(it) }

        // Apply wildcard defaults
        val wildcardHost = hosts.find { it.hostPattern == "*" }
        if (wildcardHost != null) {
            hosts.filter { it.hostPattern != "*" }.forEach { host ->
                applyDefaults(host, wildcardHost)
            }
        }

        return hosts.filter { it.hostPattern != "*" }
    }

    /**
     * Apply wildcard defaults to a host
     */
    private fun applyDefaults(host: SSHHost, defaults: SSHHost) {
        if (host.hostname == null) host.hostname = defaults.hostname
        if (host.user == null) host.user = defaults.user
        if (host.port == DEFAULT_PORT && defaults.port != DEFAULT_PORT) host.port = defaults.port
        if (host.identityFileStr == null) host.identityFileStr = defaults.identityFileStr
        if (host.proxyJump == null) host.proxyJump = defaults.proxyJump
        if (host.proxyCommand == null) host.proxyCommand = defaults.proxyCommand
        if (!host.compression && defaults.compression) host.compression = defaults.compression
        if (host.serverAliveInterval == 0) host.serverAliveInterval = defaults.serverAliveInterval
        if (host.connectTimeout == 15) host.connectTimeout = defaults.connectTimeout
        if (!host.forwardAgent && defaults.forwardAgent) host.forwardAgent = defaults.forwardAgent
        if (!host.forwardX11 && defaults.forwardX11) host.forwardX11 = defaults.forwardX11
        // Issue #37 — wildcard inheritance for RemoteCommand / SendEnv.
        if (host.remoteCommand == null) host.remoteCommand = defaults.remoteCommand
        if (host.sendEnv.isEmpty() && defaults.sendEnv.isNotEmpty()) {
            host.sendEnv.addAll(defaults.sendEnv)
        }
    }

    /**
     * Convert SSHHost to ConnectionProfile
     */
    private fun convertToConnectionProfile(host: SSHHost): ConnectionProfile {
        val id = generateConnectionId(host)
        val name = host.hostPattern
        val hostname = host.hostname ?: host.hostPattern
        val username = host.user ?: System.getProperty("user.name") ?: DEFAULT_USERNAME

        // Determine auth type based on configuration
        val authType = when {
            host.identityFileStr != null && host.pubkeyAuthentication -> "publickey"
            host.passwordAuthentication -> "password"
            else -> "keyboard-interactive"
        }

        // Build advanced settings JSON
        val advancedSettings = buildAdvancedSettings(host)

        // Issue #37 — SendEnv lists names; values are taken from the local
        // environment per OpenSSH. TabSSH's `envVars` is "KEY=value" pairs.
        // For imported `SendEnv NAME` we record `NAME=` (empty value, name
        // captured) so the user sees the placeholder and can fill it in
        // later. If `envVars` is already populated from somewhere else we
        // append SendEnv-derived names that aren't already there.
        val mergedEnvVars: String? = if (host.sendEnv.isNotEmpty()) {
            val existing = mutableSetOf<String>()
            val out = StringBuilder()
            host.sendEnv.forEach { name ->
                if (name !in existing) {
                    if (out.isNotEmpty()) out.append('\n')
                    out.append(name).append('=')
                    existing.add(name)
                }
            }
            out.toString()
        } else null

        // ProxyJump from ~/.ssh/config: parse `[user@]host[:port]` into the
        // dedicated proxy columns so SSHConnection.setupJumpHost finds them.
        // Round-trip through advancedSettings JSON is preserved for backward
        // compat with rows imported under earlier code, but the runtime path
        // (SSHConnection / SSHConfigExporter) reads from columns.
        val parsedJump = host.proxyJump?.let { parseProxyJump(it) }

        return ConnectionProfile(
            id = id,
            name = name,
            host = hostname,
            port = host.port,
            username = username,
            authType = authType,
            // keyId: IdentityFile path cannot be resolved to a StoredKey UUID
            // at parse time. The import preview dialog warns the user when
            // identityFileStr is set so they know to import the key manually.
            keyId = null,
            // groupId TRANSITIONAL: carries the raw group NAME here, not a UUID.
            // This MUST only be consumed by importSSHConfigProfiles(), which
            // remaps every name to a real connection_groups UUID before inserting.
            // Never persist a profile from this parser without that remapping step.
            groupId = host.groupName,
            theme = "dracula",
            envVars = mergedEnvVars,
            remoteCommand = host.remoteCommand?.takeIf { it.isNotBlank() },
            // ForwardAgent / ForwardX11 from ~/.ssh/config: these must populate
            // the dedicated entity columns at parse time. Without this they
            // round-trip through `advancedSettings` JSON but the connection
            // layer reads the columns, so imported toggles silently dropped.
            agentForwarding = host.forwardAgent,
            x11Forwarding = host.forwardX11,
            compression = host.compression,
            connectTimeout = host.connectTimeout,
            proxyType = parsedJump?.let { "SSH" },
            proxyHost = parsedJump?.host,
            proxyPort = parsedJump?.port,
            proxyUsername = parsedJump?.user,
            createdAt = System.currentTimeMillis(),
            lastConnected = 0,
            connectionCount = 0,
            advancedSettings = advancedSettings
        )
    }

    private data class ParsedJump(val user: String?, val host: String, val port: Int?)

    /**
     * Parse OpenSSH `ProxyJump` value: `[user@]host[:port]`. IPv6 addresses
     * inside brackets aren't supported here — same shape as the existing
     * `setupJumpHost` consumer which uses the literal host string.
     */
    private fun parseProxyJump(spec: String): ParsedJump? {
        val trimmed = spec.trim().takeIf { it.isNotEmpty() } ?: return null
        val atIdx = trimmed.lastIndexOf('@')
        val user: String? = if (atIdx > 0) trimmed.substring(0, atIdx) else null
        val hostAndPort = if (atIdx >= 0) trimmed.substring(atIdx + 1) else trimmed
        if (hostAndPort.isBlank()) return null
        val colonIdx = hostAndPort.lastIndexOf(':')
        return if (colonIdx > 0) {
            val host = hostAndPort.substring(0, colonIdx).trim()
            val port = hostAndPort.substring(colonIdx + 1).trim().toIntOrNull()
            if (host.isEmpty()) null else ParsedJump(user, host, port)
        } else {
            ParsedJump(user, hostAndPort, null)
        }
    }

    /**
     * Generate unique connection ID
     */
    private fun generateConnectionId(@Suppress("UNUSED_PARAMETER") host: SSHHost): String {
        // Always use a UUID. The old hashCode approach produced 32-bit ids that
        // could collide and caused REPLACE-on-conflict to silently overwrite rows.
        return java.util.UUID.randomUUID().toString()
    }

    /**
     * Build advanced settings JSON string
     */
    private fun buildAdvancedSettings(host: SSHHost): String {
        val settings = mutableMapOf<String, Any>()

        if (host.compression) settings["compression"] = true
        if (host.serverAliveInterval > 0) settings["serverAliveInterval"] = host.serverAliveInterval
        if (host.connectTimeout != 15) settings["connectTimeout"] = host.connectTimeout
        if (host.strictHostKeyChecking != "ask") settings["strictHostKeyChecking"] = host.strictHostKeyChecking
        if (host.proxyJump != null) settings["proxyJump"] = host.proxyJump!!
        if (host.proxyCommand != null) settings["proxyCommand"] = host.proxyCommand!!
        if (host.identityFileStr != null) settings["identityFileStr"] = host.identityFileStr!!

        // Port forwarding
        if (host.localForwards.isNotEmpty()) settings["localForwards"] = host.localForwards
        if (host.remoteForwards.isNotEmpty()) settings["remoteForwards"] = host.remoteForwards
        if (host.dynamicForwards.isNotEmpty()) settings["dynamicForwards"] = host.dynamicForwards

        // X11 and agent forwarding
        if (host.forwardAgent) settings["forwardAgent"] = true
        if (host.forwardX11) settings["forwardX11"] = true
        if (host.requestTTY != null) settings["requestTTY"] = host.requestTTY!!

        // Add any additional custom options
        host.additionalOptions.forEach { (key, value) ->
            settings[key] = value
        }

        return if (settings.isNotEmpty()) {
            org.json.JSONObject(settings as Map<String, Any?>).toString()
        } else {
            ""
        }
    }

    /**
     * Expand path with tilde expansion
     */
    private fun expandPath(path: String): String {
        return if (path.startsWith("~")) {
            val home = System.getProperty("user.home") ?: "/home/user"
            path.replaceFirst("~", home)
        } else {
            path
        }
    }

    /**
     * Import SSH config from standard location
     */
    fun importFromDefaultLocation(): List<ConnectionProfile> {
        val home = System.getProperty("user.home") ?: return emptyList()
        val configFile = File("$home/.ssh/config")
        return if (configFile.exists()) {
            parseConfigFile(configFile)
        } else {
            Logger.d("SSHConfigParser", "No SSH config found at default location")
            emptyList()
        }
    }

    /**
     * Validate if a directive is supported
     */
    fun isSupportedDirective(directive: String): Boolean {
        return SUPPORTED_DIRECTIVES.contains(directive)
    }

    /**
     * Get list of all supported directives
     */
    fun getSupportedDirectives(): Set<String> = SUPPORTED_DIRECTIVES
}