package io.github.tabssh.storage.snippets

import io.github.tabssh.storage.database.entities.Snippet
import java.util.UUID

/**
 * Snippets that ship with the app. They are read-only and cannot be deleted; the commands avoid
 * assuming one distro or OS (a missing tool falls back to another, a path is asked for instead of
 * guessed). Identity is the deterministic id derived from the name.
 */
object BuiltInSnippets {

    // Brace-free on purpose: the snippet engine treats any {text} as a placeholder, so this script must never contain braces.
    private val SYSTEM_UPDATE_COMMAND = listOf(
        "SUDO=\"\"",
        "if [ \"\$(id -u)\" -eq 0 ]; then",
        "  SUDO=\"\"",
        "elif command -v sudo >/dev/null 2>&1; then",
        "  SUDO=\"sudo\"",
        "fi",
        "if command -v apt >/dev/null 2>&1; then",
        "  \$SUDO apt update && \$SUDO apt upgrade -y && \$SUDO apt dist-upgrade -y",
        "elif command -v dnf >/dev/null 2>&1; then",
        "  \$SUDO dnf makecache && \$SUDO dnf upgrade -y",
        "elif command -v yum >/dev/null 2>&1; then",
        "  \$SUDO yum makecache && \$SUDO yum update -y",
        "elif command -v zypper >/dev/null 2>&1; then",
        "  \$SUDO zypper refresh && \$SUDO zypper update -y",
        "elif command -v pacman >/dev/null 2>&1; then",
        "  \$SUDO pacman -Syyu --noconfirm",
        "elif command -v apk >/dev/null 2>&1; then",
        "  \$SUDO apk update && \$SUDO apk upgrade",
        "elif command -v xbps-install >/dev/null 2>&1; then",
        "  \$SUDO xbps-install -Syu",
        "elif command -v pkg >/dev/null 2>&1; then",
        "  \$SUDO pkg update && \$SUDO pkg upgrade -y",
        "else",
        "  echo \"No supported package manager found\" >&2",
        "fi",
        "unset SUDO"
    ).joinToString("\n")

    private fun snippet(name: String, command: String, description: String, category: String, order: Int) =
        Snippet(
            id = UUID.nameUUIDFromBytes("tabssh-default-snippet:$name".toByteArray()).toString(),
            name = name,
            command = command,
            description = description,
            category = category,
            sortOrder = order
        )

    private const val SUDO_PREFIX =
        "S=; [ \"\$(id -u)\" -ne 0 ] && command -v sudo >/dev/null 2>&1 && S=sudo; "

    private val definitions: List<Snippet> = listOf(
        snippet("Disk usage", "df -h", "Show free/used disk space, human-readable", "System", 0),
        snippet("Memory usage", "(free -h || vm_stat || head -n 5 /proc/meminfo) 2>/dev/null", "Show free/used memory with whichever tool the system has", "System", 1),
        snippet("Top processes", "ps aux | sort -nrk 3,3 | head -n 15", "The 15 processes using the most CPU", "System", 2),
        snippet("System info", "uname -a", "Kernel, hostname, and architecture in one line", "System", 3),
        snippet("Uptime", "uptime", "How long the system has been running plus load average", "System", 4),
        snippet("System update", SYSTEM_UPDATE_COMMAND, "Update packages with whichever package manager is installed, using root or sudo as available", "System", 5),
        snippet("Listening ports", "(ss -tulnp || netstat -tulnp || netstat -an) 2>/dev/null", "TCP/UDP sockets currently listening, with whichever tool the system has", "Networking", 0),
        snippet("Ping host", "ping -c 4 {host}", "Send 4 ICMP echo requests to a host", "Networking", 1),
        snippet("Public IP", "curl -s ifconfig.me || wget -qO- ifconfig.me", "Print this machine's public-facing IP address", "Networking", 2),
        snippet("DNS lookup", "(dig +short {host} || nslookup {host} || getent hosts {host}) 2>/dev/null", "Resolve a hostname with whichever resolver tool the system has", "Networking", 3),
        snippet("Tail log", "tail -f {?path}", "Follow a log file as new lines are appended", "Files & Logs", 0),
        snippet("Find large files", "find . -type f -size +100M", "List files over 100MB under the current directory", "Files & Logs", 1),
        snippet("Grep recursive", "grep -rn \"{?pattern}\" .", "Search every file under the current directory for a pattern", "Files & Logs", 2),
        snippet("Disk usage by dir", "du -sk ./*/ | sort -rn", "Show each subdirectory's size in KB, largest first", "Files & Logs", 3),
        snippet("List running containers", "docker ps", "Show currently running Docker containers", "Docker", 0),
        snippet("Container logs", "docker logs -f {?container}", "Follow a container's logs", "Docker", 1),
        snippet("Shell into container", "docker exec -it {?container} sh", "Open an interactive shell inside a running container", "Docker", 2),
        snippet("Prune unused", "docker system prune -f", "Remove stopped containers, dangling images, and unused networks", "Docker", 3),
        snippet("Service status", "systemctl status {?service} || service {?service} status", "Show a service's status (systemd, else SysV/OpenRC)", "Services", 0),
        snippet("Restart service", SUDO_PREFIX + "\$S systemctl restart {?service} || \$S service {?service} restart", "Restart a service (systemd, else SysV/OpenRC), with sudo when needed", "Services", 1),
        snippet("Journal follow", "journalctl -u {?service} -f", "Follow a systemd unit's journal log", "Services", 2)
    )

    /** Fresh copies of every built-in snippet. */
    fun all(): List<Snippet> = definitions

    /** Ids of the built-in snippets. */
    val ids: Set<String> = definitions.map { it.id }.toSet()

    fun isBuiltIn(id: String): Boolean = id in ids
}
