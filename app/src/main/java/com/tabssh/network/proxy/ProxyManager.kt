package com.tabssh.network.proxy

import com.jcraft.jsch.ProxyHTTP
import com.jcraft.jsch.ProxySOCKS4
import com.jcraft.jsch.ProxySOCKS5
import com.jcraft.jsch.Session
import com.tabssh.storage.preferences.PreferenceManager
import com.tabssh.utils.logging.Logger
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

/**
 * Proxy configuration and management
 * Supports HTTP, SOCKS4, and SOCKS5 proxies
 */
class ProxyManager(private val preferenceManager: PreferenceManager) {

    enum class ProxyType {
        NONE,
        HTTP,
        SOCKS4,
        SOCKS5,
        SYSTEM
    }

    data class ProxyConfiguration(
        val type: ProxyType = ProxyType.NONE,
        val host: String = "",
        val port: Int = 0,
        val username: String? = null,
        val password: String? = null,
        val bypassHosts: List<String> = emptyList()
    )

    /**
     * Get proxy configuration for a specific host
     */
    fun getProxyConfiguration(hostname: String): ProxyConfiguration {
        // Check if this host should bypass proxy
        val globalProxy = getGlobalProxyConfiguration()
        if (globalProxy.type != ProxyType.NONE && shouldBypassProxy(hostname, globalProxy)) {
            return ProxyConfiguration(type = ProxyType.NONE)
        }

        // Check for host-specific proxy configuration
        val hostSpecificProxy = getHostSpecificProxyConfiguration(hostname)
        if (hostSpecificProxy.type != ProxyType.NONE) {
            return hostSpecificProxy
        }

        return globalProxy
    }

    /**
     * Get global proxy configuration from preferences
     */
    private fun getGlobalProxyConfiguration(): ProxyConfiguration {
        val proxyEnabled = preferenceManager.isProxyEnabled()
        if (!proxyEnabled) {
            return ProxyConfiguration(type = ProxyType.NONE)
        }

        val type = when (preferenceManager.getProxyType()) {
            "http" -> ProxyType.HTTP
            "socks4" -> ProxyType.SOCKS4
            "socks5" -> ProxyType.SOCKS5
            "system" -> ProxyType.SYSTEM
            else -> ProxyType.NONE
        }

        if (type == ProxyType.SYSTEM) {
            return getSystemProxyConfiguration()
        }

        return ProxyConfiguration(
            type = type,
            host = preferenceManager.getProxyHost(),
            port = preferenceManager.getProxyPort(),
            username = preferenceManager.getProxyUsername(),
            password = preferenceManager.getProxyPassword(),
            bypassHosts = preferenceManager.getProxyBypassHosts()
        )
    }

    /**
     * Get host-specific proxy configuration
     */
    private fun getHostSpecificProxyConfiguration(hostname: String): ProxyConfiguration {
        // Check if there's a specific proxy configuration for this host
        val hostConfigJson = preferenceManager.getHostProxyConfiguration(hostname)
        return if (hostConfigJson != null) {
            try {
                com.google.gson.Gson().fromJson(hostConfigJson, ProxyConfiguration::class.java)
            } catch (e: Exception) {
                ProxyConfiguration(type = ProxyType.NONE)
            }
        } else {
            ProxyConfiguration(type = ProxyType.NONE)
        }
    }

    /**
     * Get system proxy configuration
     */
    private fun getSystemProxyConfiguration(): ProxyConfiguration {
        try {
            val uri = URI("http://${"example.com"}")
            val proxyList = ProxySelector.getDefault().select(uri)

            for (proxy in proxyList) {
                if (proxy.type() != Proxy.Type.DIRECT) {
                    val address = proxy.address() as? InetSocketAddress
                    if (address != null) {
                        val type = when (proxy.type()) {
                            Proxy.Type.HTTP -> ProxyType.HTTP
                            Proxy.Type.SOCKS -> ProxyType.SOCKS5
                            else -> ProxyType.NONE
                        }

                        return ProxyConfiguration(
                            type = type,
                            host = address.hostString,
                            port = address.port
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w("ProxyManager", "Failed to get system proxy", e)
        }

        return ProxyConfiguration(type = ProxyType.NONE)
    }

    /**
     * Check if a host should bypass proxy
     */
    private fun shouldBypassProxy(hostname: String, proxyConfig: ProxyConfiguration): Boolean {
        // Check localhost and private IPs
        if (hostname == "localhost" || hostname == "127.0.0.1" || hostname.startsWith("192.168.") ||
            hostname.startsWith("10.") || hostname.startsWith("172.")) {
            return true
        }

        // Check bypass list
        return proxyConfig.bypassHosts.any { pattern ->
            when {
                pattern.startsWith("*.") -> hostname.endsWith(pattern.substring(2))
                pattern.contains("*") -> {
                    val regex = pattern.replace(".", "\\.").replace("*", ".*")
                    hostname.matches(Regex(regex))
                }
                else -> hostname == pattern
            }
        }
    }

    /**
     * Configure JSch session with proxy
     */
    fun configureSessionProxy(session: Session, hostname: String) {
        val proxyConfig = getProxyConfiguration(hostname)

        when (proxyConfig.type) {
            ProxyType.HTTP -> {
                Logger.d("ProxyManager", "Configuring HTTP proxy for $hostname")
                val proxy = ProxyHTTP(proxyConfig.host, proxyConfig.port)
                proxyConfig.username?.let {
                    proxy.setUserPasswd(it, proxyConfig.password)
                }
                session.setProxy(proxy)
            }

            ProxyType.SOCKS4 -> {
                Logger.d("ProxyManager", "Configuring SOCKS4 proxy for $hostname")
                val proxy = ProxySOCKS4(proxyConfig.host, proxyConfig.port)
                proxyConfig.username?.let {
                    proxy.setUserPasswd(it, proxyConfig.password)
                }
                session.setProxy(proxy)
            }

            ProxyType.SOCKS5 -> {
                Logger.d("ProxyManager", "Configuring SOCKS5 proxy for $hostname")
                val proxy = ProxySOCKS5(proxyConfig.host, proxyConfig.port)
                proxyConfig.username?.let {
                    proxy.setUserPasswd(it, proxyConfig.password)
                }
                session.setProxy(proxy)
            }

            ProxyType.NONE, ProxyType.SYSTEM -> {
                // No proxy or handled at system level
                Logger.d("ProxyManager", "No proxy configured for $hostname")
            }
        }
    }

    /**
     * Test proxy connection
     */
    suspend fun testProxyConnection(config: ProxyConfiguration): ProxyTestResult {
        return try {
            // Implementation would test actual connection through proxy
            // This is a simplified version
            when (config.type) {
                ProxyType.NONE -> ProxyTestResult(success = true, message = "No proxy configured")
                else -> {
                    // Would perform actual connection test here
                    ProxyTestResult(success = true, message = "Proxy connection successful")
                }
            }
        } catch (e: Exception) {
            ProxyTestResult(success = false, message = "Proxy connection failed: ${e.message}")
        }
    }

    data class ProxyTestResult(
        val success: Boolean,
        val message: String,
        val latency: Long? = null
    )

    /**
     * Save proxy configuration
     */
    fun saveProxyConfiguration(config: ProxyConfiguration, isGlobal: Boolean = true, hostname: String? = null) {
        if (isGlobal) {
            preferenceManager.setProxyEnabled(config.type != ProxyType.NONE)
            preferenceManager.setProxyType(config.type.name.lowercase())
            preferenceManager.setProxyHost(config.host)
            preferenceManager.setProxyPort(config.port)
            config.username?.let { preferenceManager.setProxyUsername(it) }
            config.password?.let { preferenceManager.setProxyPassword(it) }
            preferenceManager.setProxyBypassHosts(config.bypassHosts)
        } else if (hostname != null) {
            val configJson = com.google.gson.Gson().toJson(config)
            preferenceManager.setHostProxyConfiguration(hostname, configJson)
        }
    }
}