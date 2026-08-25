package io.github.tabssh.ui.tabs

import io.github.tabssh.ssh.connection.ConnectionState

/**
 * VNC-tab-swipe integration step 2 — unified tab type.
 *
 * Wraps either an [SSHTab] or a [VncTab] so [TabManager] can hold both kinds
 * of session in a single ordered list and [TerminalPagerAdapter] can render
 * either behind one `ViewPager2`. See AI.md §11.7.2 for the design and
 * TODO.AI.md's "VNC-tab-swipe integration" section for the shipping plan —
 * this step only introduces the type; TabManager/TerminalPagerAdapter still
 * only construct [Tab.Ssh] until the later steps land.
 */
sealed class Tab {

    /** Stable identity shared by both variants — same UUID scheme SSH uses. */
    abstract val tabId: String

    data class Ssh(val sshTab: SSHTab) : Tab() {
        override val tabId: String get() = sshTab.tabId
    }

    data class Vnc(val vncTab: VncTab) : Tab() {
        override val tabId: String get() = vncTab.tabId
    }

    data class Console(val consoleTab: ConsoleTab) : Tab() {
        override val tabId: String get() = consoleTab.tabId
    }

    data class Panes(val panesTab: PanesTab) : Tab() {
        override val tabId: String get() = panesTab.tabId
    }
}

/**
 * Tab-bar label shared by all variants — [SSHTab.getShortTitle] for SSH,
 * [VncTab.getDisplayTitle] for VNC, [ConsoleTab.getDisplayTitle] for
 * hypervisor consoles (neither VncTab nor ConsoleTab has a separate short
 * form yet).
 */
fun Tab.shortTitle(): String = when (this) {
    is Tab.Ssh -> sshTab.getShortTitle()
    is Tab.Vnc -> vncTab.getDisplayTitle()
    is Tab.Console -> consoleTab.getDisplayTitle()
    is Tab.Panes -> panesTab.getDisplayTitle()
}

/**
 * Connection-identity label shared by any full-width tab list (the "OPEN
 * TABS" long-press menu, the Active sub-tab's session strip/"see all"
 * dialog). Unlike [shortTitle], SSH never falls back to the remote's OSC
 * 0/2 terminal title here — a shell/mosh-set title (e.g. mosh's own
 * "[mosh]" placeholder before the shell prompt overwrites it) is not the
 * connection, and showing it in a list meant to identify *which host* a
 * tab belongs to is actively misleading. SSH instead shows the saved
 * profile name, or `user@host` when the profile has no name (or the name
 * duplicates it). VNC/Console/Panes have no OSC-title ambiguity, so they
 * keep using their own `getDisplayTitle()`.
 */
fun Tab.connectionDisplayName(): String = when (this) {
    is Tab.Ssh -> {
        val profileName = sshTab.profile.name.trim()
        val user = sshTab.profile.username
        val host = sshTab.profile.host
        val userHost = if (user.isNotBlank() && host.isNotBlank()) "$user@$host" else host
        if (profileName.isNotBlank() && profileName != userHost) profileName else userHost
    }
    is Tab.Vnc -> vncTab.getDisplayTitle()
    is Tab.Console -> consoleTab.getDisplayTitle()
    is Tab.Panes -> panesTab.getDisplayTitle()
}

/**
 * Item 43 — `user@host:port` (or `host:port` where there is no username)
 * connection detail shared by any full-width tab list that needs to show
 * more than [connectionDisplayName]'s identity label, e.g. the Hosts tab's
 * Active sub-tab. Null for [Tab.Panes], which has no single host of its own.
 */
fun Tab.connectionDetail(): String? = when (this) {
    is Tab.Ssh -> {
        val user = sshTab.profile.username
        val host = sshTab.profile.host
        val port = sshTab.profile.port
        val userHost = if (user.isNotBlank()) "$user@$host" else host
        if (port != 22) "$userHost:$port" else userHost
    }
    is Tab.Vnc -> vncTab.vncHost?.let { host ->
        if (host.port != 5900) "${host.host}:${host.port}" else host.host
    }
    is Tab.Console -> {
        val params = consoleTab.connectParams
        val userHost = if (params.username.isNotBlank()) "${params.username}@${params.host}" else params.host
        "$userHost:${params.port}"
    }
    is Tab.Panes -> null
}

/**
 * Single representative [ConnectionState] shared by all variants — lets any
 * unified tab-list UI (e.g. the "OPEN TABS" list in the long-press terminal
 * menu) show a consistent state dot regardless of tab type, instead of only
 * handling [Tab.Ssh] and silently dropping every other variant.
 * [Tab.Panes] has no single connection of its own; see
 * [PanesTab.aggregateConnectionState] for how it's summarized.
 */
fun Tab.connectionState(): ConnectionState = when (this) {
    is Tab.Ssh -> sshTab.connectionState.value
    is Tab.Vnc -> vncTab.connectionState.value
    is Tab.Console -> consoleTab.connectionState.value
    is Tab.Panes -> panesTab.aggregateConnectionState()
}

/**
 * Wall-clock time this tab most recently became CONNECTED, used by the
 * Hosts tab's Active sub-tab to render a connected-since timer. A
 * [Tab.Panes] has no single connection of its own, so it reports the
 * earliest `connectedAt` among its currently-connected panes.
 */
fun Tab.connectedAt(): Long? = when (this) {
    is Tab.Ssh -> sshTab.connectedAt
    is Tab.Vnc -> vncTab.connectedAt
    is Tab.Console -> consoleTab.connectedAt
    is Tab.Panes -> panesTab.currentEntries()
        .mapNotNull { it.sshTab?.takeIf { tab -> tab.connectionState.value == ConnectionState.CONNECTED }?.connectedAt }
        .minOrNull()
}
