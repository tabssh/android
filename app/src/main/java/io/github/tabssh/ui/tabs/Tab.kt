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
