package com.tabssh.utils.helpers

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.regex.Pattern

/**
 * Validation utilities for TabSSH
 * Provides input validation for connections, credentials, and configurations
 */
object ValidationHelper {

    private val EMAIL_PATTERN = Pattern.compile(
        "[a-zA-Z0-9\\+\\.\\_\\%\\-\\+]{1,256}" +
                "\\@" +
                "[a-zA-Z0-9][a-zA-Z0-9\\-]{0,64}" +
                "(" +
                "\\." +
                "[a-zA-Z0-9][a-zA-Z0-9\\-]{0,25}" +
                ")+"
    )

    private val HOSTNAME_PATTERN = Pattern.compile(
        "^([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)*[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?$"
    )

    private val IPV4_PATTERN = Pattern.compile(
        "^(([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.){3}([01]?\\d\\d?|2[0-4]\\d|25[0-5])$"
    )

    private val IPV6_PATTERN = Pattern.compile(
        "^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$"
    )

    /**
     * Validate SSH hostname or IP address
     */
    fun isValidHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false

        return isValidIPv4(host) || isValidIPv6(host) || isValidHostname(host)
    }

    /**
     * Validate IPv4 address
     */
    fun isValidIPv4(ip: String): Boolean {
        return IPV4_PATTERN.matcher(ip).matches()
    }

    /**
     * Validate IPv6 address
     */
    fun isValidIPv6(ip: String): Boolean {
        return try {
            InetAddress.getByName(ip) is java.net.Inet6Address
        } catch (e: UnknownHostException) {
            false
        }
    }

    /**
     * Validate hostname
     */
    fun isValidHostname(hostname: String): Boolean {
        if (hostname.length > 253) return false
        return HOSTNAME_PATTERN.matcher(hostname).matches()
    }

    /**
     * Validate SSH port number
     */
    fun isValidPort(port: Int): Boolean {
        return port in 1..65535
    }

    /**
     * Validate SSH port string
     */
    fun isValidPort(port: String?): Boolean {
        if (port.isNullOrBlank()) return false

        return try {
            val portNum = port.toInt()
            isValidPort(portNum)
        } catch (e: NumberFormatException) {
            false
        }
    }

    /**
     * Validate SSH username
     */
    fun isValidUsername(username: String?): Boolean {
        if (username.isNullOrBlank()) return false

        // SSH username should be 1-32 characters, alphanumeric plus underscore, hyphen, period
        return username.length <= 32 && username.matches(Regex("^[a-zA-Z0-9._-]+$"))
    }

    /**
     * Validate email address
     */
    fun isValidEmail(email: String?): Boolean {
        return !email.isNullOrBlank() && EMAIL_PATTERN.matcher(email).matches()
    }

    /**
     * Validate password strength
     */
    fun validatePasswordStrength(password: String?): PasswordStrength {
        if (password.isNullOrBlank()) return PasswordStrength.EMPTY

        val length = password.length
        var score = 0

        // Length check
        when {
            length >= 12 -> score += 2
            length >= 8 -> score += 1
        }

        // Character variety checks
        if (password.any { it.isUpperCase() }) score++
        if (password.any { it.isLowerCase() }) score++
        if (password.any { it.isDigit() }) score++
        if (password.any { "!@#$%^&*()_+-=[]{}|;:,.<>?".contains(it) }) score++

        return when {
            score >= 6 -> PasswordStrength.STRONG
            score >= 4 -> PasswordStrength.MEDIUM
            score >= 2 -> PasswordStrength.WEAK
            else -> PasswordStrength.VERY_WEAK
        }
    }

    /**
     * Validate SSH connection name
     */
    fun isValidConnectionName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false

        // Connection name should be 1-64 characters, printable ASCII
        return name.length <= 64 && name.all { it.code in 32..126 }
    }

    /**
     * Validate proxy configuration
     */
    fun isValidProxyConfig(host: String?, port: String?, type: String?): Boolean {
        if (type.isNullOrBlank()) return true // No proxy is valid

        if (host.isNullOrBlank() || port.isNullOrBlank()) return false

        return isValidHost(host) && isValidPort(port)
    }

    /**
     * Validate timeout value
     */
    fun isValidTimeout(timeout: String?): Boolean {
        if (timeout.isNullOrBlank()) return false

        return try {
            val timeoutVal = timeout.toInt()
            timeoutVal in 1..300 // 1 second to 5 minutes
        } catch (e: NumberFormatException) {
            false
        }
    }

    /**
     * Validate terminal font size
     */
    fun isValidFontSize(fontSize: String?): Boolean {
        if (fontSize.isNullOrBlank()) return false

        return try {
            val size = fontSize.toFloat()
            size in 8f..32f
        } catch (e: NumberFormatException) {
            false
        }
    }

    /**
     * Get validation error message for host
     */
    fun getHostValidationError(host: String?): String? {
        return when {
            host.isNullOrBlank() -> "Host cannot be empty"
            !isValidHost(host) -> "Invalid hostname or IP address"
            else -> null
        }
    }

    /**
     * Get validation error message for port
     */
    fun getPortValidationError(port: String?): String? {
        return when {
            port.isNullOrBlank() -> "Port cannot be empty"
            !isValidPort(port) -> "Port must be between 1 and 65535"
            else -> null
        }
    }

    /**
     * Get validation error message for username
     */
    fun getUsernameValidationError(username: String?): String? {
        return when {
            username.isNullOrBlank() -> "Username cannot be empty"
            !isValidUsername(username) -> "Username must be 1-32 characters, alphanumeric with . _ - allowed"
            else -> null
        }
    }

    enum class PasswordStrength {
        EMPTY,
        VERY_WEAK,
        WEAK,
        MEDIUM,
        STRONG
    }
}