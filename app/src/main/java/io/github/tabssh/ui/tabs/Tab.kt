package io.github.tabssh.ui.tabs

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
}

/**
 * Tab-bar label shared by both variants — [SSHTab.getShortTitle] for SSH,
 * [VncTab.getDisplayTitle] for VNC (VncTab has no separate short form yet).
 */
fun Tab.shortTitle(): String = when (this) {
    is Tab.Ssh -> sshTab.getShortTitle()
    is Tab.Vnc -> vncTab.getDisplayTitle()
}
