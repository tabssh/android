package io.github.tabssh.ui.tabs

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
 * [VncBackgroundSessionStore] — RfbClient connect/reconnect wiring is added
 * when entry points are consolidated onto `TabTerminalActivity` (step 6);
 * per AI.md §11.7.2, no new persistence layer is introduced here.
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
    @Volatile
    var rfbClient: RfbClient? = null

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
