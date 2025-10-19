package com.tabssh.ssh.config

import com.tabssh.storage.database.entities.ConnectionProfile
import com.tabssh.utils.logging.Logger
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
            "ForwardX11", "ForwardX11Trusted", "RequestTTY"
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

                // Skip empty lines and comments
                if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                    return@forEachLine
                }

                // Split into directive and value
                val parts = trimmedLine.split("\\s+".toRegex(), limit = 2)
                if (parts.isEmpty()) return@forEachLine

                val directive = parts[0]
                val value = if (parts.size > 1) parts[1].trim() else ""

                when (directive.lowercase()) {
                    "host" -> {
                        // Save previous host if exists
                        currentHost?.let { hosts.add(it) }
                        // Start new host
                        currentHost = SSHHost(hostPattern = value)
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

        return ConnectionProfile(
            id = id,
            name = name,
            host = hostname,
            port = host.port,
            username = username,
            authType = authType,
            keyId = host.identityFileStr?.hashCode()?.toString(), // Will need to be resolved separately if identityFileStr is present
            groupId = "imported",
            theme = "dracula",
            createdAt = System.currentTimeMillis(),
            lastConnected = 0,
            connectionCount = 0,
            advancedSettings = advancedSettings
        )
    }

    /**
     * Generate unique connection ID
     */
    private fun generateConnectionId(host: SSHHost): String {
        val hostname = host.hostname ?: host.hostPattern
        val user = host.user ?: "default"
        val port = host.port
        return "$user@$hostname:$port".hashCode().toString()
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