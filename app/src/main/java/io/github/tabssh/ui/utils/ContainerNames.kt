package io.github.tabssh.ui.utils

/**
 * Validation for user-typed Docker resource identifiers before they are handed
 * to the transport layer as command arguments.
 *
 * The daemon's own grammar for volume and network names is
 * `[a-zA-Z0-9][a-zA-Z0-9_.-]*`; rejecting anything else in the UI keeps
 * shell-significant characters, whitespace, and leading dashes (which the CLI
 * would parse as flags) out of the argument vector regardless of how the
 * transport quotes them.
 */
object ContainerNames {

    /** Daemon-side limit on volume and network names. */
    const val MAX_NAME_LENGTH = 128

    /** Driver names are a short ASCII identifier such as `local` or `overlay`. */
    const val MAX_DRIVER_LENGTH = 64

    /** True when [name] matches the daemon's volume/network name grammar. */
    fun isValidResourceName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return false
        val first = name[0]
        if (!(first in 'a'..'z' || first in 'A'..'Z' || first in '0'..'9')) return false
        return name.all { ch ->
            ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' ||
                ch == '_' || ch == '.' || ch == '-'
        }
    }

    /** True when [driver] is a plausible driver identifier (letters, digits, `_-.`). */
    fun isValidDriverName(driver: String): Boolean {
        if (driver.isEmpty() || driver.length > MAX_DRIVER_LENGTH) return false
        if (driver.startsWith("-")) return false
        return driver.all { ch ->
            ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' ||
                ch == '_' || ch == '.' || ch == '-'
        }
    }
}
