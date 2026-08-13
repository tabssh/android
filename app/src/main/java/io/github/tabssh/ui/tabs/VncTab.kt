package io.github.tabssh.ui.tabs

import io.github.tabssh.hypervisor.console.ConsoleDisconnectReason
import io.github.tabssh.hypervisor.console.rfb.RfbClient
import io.github.tabssh.hypervisor.vnc.VncBackgroundSessionStore
import io.github.tabssh.ssh.connection.ConnectionState
import io.github.tabssh.storage.database.entities.VncHost
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Represents a single VNC tab: either a persisted [VncHost] (VNC-tab-swipe
 * integration step 2, see AI.md §11.7.2 / TODO.AI.md) or an ephemeral
 * hypervisor console that has no `vnc_hosts` row of its own.
 *
 * Mirrors [SSHTab]'s public shape (tabId/title/connectionState/isActive as
 * `StateFlow`s, `activate()`/`deactivate()`/`cleanup()`) so [Tab] can treat
 * both variants uniformly once `TabManager`/`TerminalPagerAdapter` are
 * rewritten to be sealed-type aware (steps 3–4). This step only wires the
 * tab's identity and its handle into the existing
 * [VncBackgroundSessionStore]; per AI.md §11.7.2, no new persistence layer
 * is introduced here.
 *
 * Reconnect after a non-clean drop is MANUAL only, by design: no
 * auto-retry/backoff. `TabTerminalActivity.handleConsoleTabDisconnected`
 * observes [connectionState], and for an ERROR-reason drop shows a dialog
 * with a tap-to-reconnect action (`TabTerminalActivity.reconnectVncTab`)
 * that re-dials with this tab's original [vncHost] credentials and swaps
 * the result onto [rfbClient]. Only persisted-[VncHost] tabs are offered
 * reconnect — ephemeral hypervisor console tabs have no host row to re-dial
 * from and get Close/Keep instead, with the last framebuffer frame left on
 * screen so the user can still read it.
 */
class VncTab(
    val vncHost: VncHost?,
    private val ephemeralDisplayName: String? = null
) {
    init {
        require(vncHost != null || ephemeralDisplayName != null) {
            "VncTab requires either a persisted VncHost or an ephemeral display name"
        }
    }

    val tabId: String = UUID.randomUUID().toString()

    /**
     * Key used to park/reclaim this tab's live [RfbClient] in
     * [VncBackgroundSessionStore]. A direct VncHost connection reuses the
     * host's own id — the same key VMConsoleActivity's `EXTRA_VNC_HOST_ID`
     * path already parks/reattaches under — so a tab and a not-yet-retired
     * VMConsoleActivity instance for the same host resolve to the same
     * parked session instead of colliding. An ephemeral hypervisor console
     * (no persisted VncHost row) has no natural shared key, so it uses this
     * tab's own id instead.
     */
    val storeKey: String = vncHost?.id ?: tabId

    // @Volatile: written from the connect/background-park coroutines (IO),
    // read from Main when wiring/unwiring this tab's VncView.
    // The setter wires the client's session-end hook at attach time so every
    // existing attach site (VncHostsActivity, MainActivity, manager activities)
    // gets close-policy coverage without changing — and so a disconnect still
    // reaches the tab while its page is recycled (view listeners are unbound).
    @Volatile
    var rfbClient: RfbClient? = null
        set(value) {
            field = value
            if (value != null) {
                // Fresh client — clear the previous end-of-session verdict.
                lastDisconnectReason = null
                lastDisconnectMessage = null
                value.onSessionEnded = { reason, detail ->
                    lastDisconnectReason = reason
                    lastDisconnectMessage = detail
                    setConnectionState(ConnectionState.DISCONNECTED)
                }
            }
        }

    /**
     * Optional transport teardown hook, mirroring `ConsoleTab.onCleanup`.
     *
     * [RfbClient] owns only the streams it was handed — it cannot close the
     * thing that produced them. Anything that wraps the RFB stream in a
     * separate resource (a JSch `direct-tcpip` channel on the libvirt path, an
     * SSH port forward) sets this so [cleanup] releases it. Invoked exactly
     * once, after the client is stopped, and cleared afterwards.
     */
    @Volatile
    var onCleanup: (() -> Unit)? = null

    // Why the last NON-user-initiated session end happened; null until the
    // first such end and after each successful (re)connect. Read by
    // TabTerminalActivity's close-policy gate: CLEAN → auto-close, ERROR →
    // reconnect dialog. @Volatile: written from the RFB reader thread.
    @Volatile
    var lastDisconnectReason: ConsoleDisconnectReason? = null

    // Human-readable detail accompanying lastDisconnectReason, for the dialog body.
    @Volatile
    var lastDisconnectMessage: String? = null

    private val _title = MutableStateFlow(ephemeralDisplayName ?: vncHost?.name ?: "VNC")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    // Tab position and ordering — same role as SSHTab.tabIndex.
    var tabIndex: Int = 0
        internal set

    /** Set by the connect/reconnect path once wired (step 6). */
    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    /** Set custom title (user-defined), same contract as SSHTab.setCustomTitle. */
    fun setCustomTitle(newTitle: String) {
        if (newTitle.isNotBlank()) {
            _title.value = newTitle
            Logger.d("VncTab", "Set custom title for tab: $newTitle")
        }
    }

    /** Activate this tab (mark as current/visible). */
    fun activate() {
        _isActive.value = true
        Logger.d("VncTab", "Activated VNC tab ${getDisplayTitle()}")
    }

    /** Deactivate this tab (mark as background). */
    fun deactivate() {
        _isActive.value = false
        Logger.d("VncTab", "Deactivated VNC tab ${getDisplayTitle()}")
    }

    /** Get display title for tab bar. */
    fun getDisplayTitle(): String = _title.value

    /** True once this tab's session is parked in [VncBackgroundSessionStore]. */
    fun isParked(): Boolean = VncBackgroundSessionStore.contains(storeKey)

    /**
     * Tear down this tab's session. If a live [RfbClient] is still directly
     * attached to this tab object (not already handed off to
     * [VncBackgroundSessionStore] for a background park), stop it directly;
     * either way, discard whatever is parked under [storeKey] so nothing
     * outlives the tab once it's closed.
     */
    fun cleanup() {
        Logger.d("VncTab", "Cleaning up VNC tab ${getDisplayTitle()}")
        rfbClient?.let { client ->
            try { client.stop() } catch (e: Exception) {
                Logger.d("VncTab", "rfbClient.stop() suppressed: ${e.message}")
            }
        }
        rfbClient = null
        VncBackgroundSessionStore.discard(storeKey)
        try {
            onCleanup?.invoke()
        } catch (e: Exception) {
            Logger.d("VncTab", "onCleanup hook suppressed: ${e.message}")
        }
        onCleanup = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VncTab) return false
        return tabId == other.tabId
    }

    override fun hashCode(): Int = tabId.hashCode()

    override fun toString(): String =
        "VncTab(id=$tabId, host=${vncHost?.name ?: ephemeralDisplayName}, state=${_connectionState.value})"
}
