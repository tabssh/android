package io.github.tabssh.ui.tabs

import io.github.tabssh.ssh.connection.ConnectionState
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * One tile (a tiling-window-manager "window") inside a [PanesTab]'s grid.
 * [hostId] is the [io.github.tabssh.storage.database.entities.ConnectableHost]
 * id this window was opened from — the stable key used to re-match a window
 * against `PaneGroup.resolvedWindows()` on relaunch/reattach (NOT
 * `ConnectionProfile.id`, which cloud-instance-sourced hosts don't have). The
 * same [hostId] may appear in more than one window (e.g. the same host open
 * twice with different working directories) — [gridPosition] is what
 * disambiguates windows, not [hostId].
 * [sshTab] is the live connected session backing this tile; null only in
 * the brief window between resolving a member and finishing its connect.
 * [workingDir] is the directory this window's shell was `cd`'d into at
 * connect time (display/debug only — the `cd` itself already ran via the
 * session's postConnectScript, this field is not re-applied on its own).
 * [gridPosition] is this window's index in the grid (0-based, row-major).
 */
data class PaneWindow(
    val hostId: String,
    var sshTab: SSHTab?,
    var customTitle: String? = null,
    var workingDir: String? = null,
    var gridPosition: Int = 0
)

/**
 * Represents one Panes tab — up to 6 tiled SSH sessions sharing a single
 * tab-strip slot. Mirrors [SSHTab]/[ConsoleTab]'s public shape (tabId,
 * StateFlow-based title/isActive, activate()/deactivate()/cleanup()) so
 * [Tab] can treat all variants uniformly.
 *
 * Unlike [ConsoleTab], a Panes tab has no single [io.github.tabssh.ssh.connection.ConnectionState] —
 * each [PaneWindow.sshTab] tracks its own. Focus routing (which window
 * receives keyboard input / the PREFIX key) is tracked here via
 * [focusedPaneIndex] and consulted by `TabTerminalActivity` for
 * `getActiveTerminalView()`/`getActiveInputView()`/`updatePrefixKeyVisual()`.
 */
class PanesTab(
    val groupId: String,
    groupName: String,
    initialEntries: List<PaneWindow>,
    // Consulted only when this tab has exactly 2 windows — "horizontal"
    // (default, side by side) or "vertical" (stacked). See
    // PanesSplitDirection in PanesGridView.kt / PaneGroup.splitDirection.
    val splitDirection: String = "horizontal"
) {

    val tabId: String = UUID.randomUUID().toString()

    private val _entries = MutableStateFlow(initialEntries)
    val entries: StateFlow<List<PaneWindow>> = _entries.asStateFlow()

    private val _title = MutableStateFlow(groupName.ifBlank { "Panes" })
    val title: StateFlow<String> = _title.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _focusedPaneIndex = MutableStateFlow(0)
    val focusedPaneIndex: StateFlow<Int> = _focusedPaneIndex.asStateFlow()

    // Tab position and ordering — same role as SSHTab.tabIndex/VncTab.tabIndex.
    var tabIndex: Int = 0
        internal set

    // tmux synchronize-panes equivalent: when on, every keystroke typed into
    // the focused window's TerminalView is mirrored live (raw bytes, via
    // TermuxBridge.broadcastTargets, the same fan-out Wave 2.7's tab-wide
    // broadcast uses) to every other connected window in this pane group.
    // Replaces the old "Broadcast to All Windows in Pane…" input-box dialog,
    // which composed a whole command before sending instead of mirroring
    // input as it's typed.
    private val _syncInputEnabled = MutableStateFlow(false)
    val syncInputEnabled: StateFlow<Boolean> = _syncInputEnabled.asStateFlow()

    // Windows whose session ended on its own — remote `exit`, `reboot`,
    // `poweroff`, or a dropped link — rather than by the user closing the
    // tile. Keyed by SSHTab.tabId and NOT by grid index: indices shift every
    // time another window closes, and a stale index would park the "session
    // ended" overlay on top of a live pane.
    //
    // A dead window deliberately stays in the grid with its scrollback
    // intact instead of being removed — the user needs to read whatever the
    // shell printed on its way out, and the tile offers Reconnect / Close
    // window from there. Sibling panes are never touched, and every window
    // can legitimately be in this set at once (sync-input `reboot` typed
    // into all of them), which is why nothing here closes the tab.
    private val _disconnectedWindowIds = MutableStateFlow<Set<String>>(emptySet())
    val disconnectedWindowIds: StateFlow<Set<String>> = _disconnectedWindowIds.asStateFlow()

    // Scoped to the tab, not to TabTerminalActivity: a Panes tab can be
    // parked ("Keep Running in Background") and outlive the Activity, and a
    // pane whose session ends while parked must still read as dead when the
    // user comes back. Cancelled in cleanup(), i.e. on real tab removal.
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowWatchers = mutableMapOf<String, Job>()

    init {
        syncWindowWatchers()
    }

    /** Current snapshot of this tab's windows. */
    fun currentEntries(): List<PaneWindow> = _entries.value

    /** Replace the full window list (e.g. after resolving/attaching a window's SSHTab). */
    fun updateEntries(newEntries: List<PaneWindow>) {
        _entries.value = newEntries
        syncWindowWatchers()
        applySyncInputRouting()
    }

    /**
     * Start a connection-state collector for every window that doesn't have
     * one yet, and drop the collectors (and any "disconnected" marker) of
     * sessions that are no longer in the grid.
     *
     * The `hasBeenConnected` latch mirrors [TabManager.createTab]'s per-tab
     * collector: subscribing replays the StateFlow's current value, so the
     * DISCONNECTED a window reports before it has ever connected is the
     * initial state, not a session ending. Unlike that collector this one
     * does NOT close anything — a pane dying is a per-window event here.
     */
    private fun syncWindowWatchers() {
        val live = _entries.value.mapNotNull { it.sshTab?.tabId }.toSet()
        windowWatchers.keys.toList()
            .filterNot { it in live }
            .forEach { windowWatchers.remove(it)?.cancel() }
        _disconnectedWindowIds.value = _disconnectedWindowIds.value.intersect(live)
        _entries.value.forEach { window ->
            val sshTab = window.sshTab ?: return@forEach
            if (windowWatchers.containsKey(sshTab.tabId)) return@forEach
            windowWatchers[sshTab.tabId] = watchScope.launch {
                var hasBeenConnected = false
                sshTab.connectionState.collect { state ->
                    when (state) {
                        ConnectionState.CONNECTED -> {
                            hasBeenConnected = true
                            // A transport that recovers on its own (SSH and
                            // telnet both auto-reconnect) must take its dead
                            // -window overlay back down; otherwise the tile
                            // keeps offering Reconnect over a live session.
                            if (sshTab.tabId in _disconnectedWindowIds.value) {
                                _disconnectedWindowIds.value =
                                    _disconnectedWindowIds.value - sshTab.tabId
                                Logger.i(
                                    "PanesTab",
                                    "Pane session recovered in group $groupId"
                                )
                            }
                        }
                        ConnectionState.DISCONNECTED, ConnectionState.ERROR -> {
                            if (hasBeenConnected) {
                                _disconnectedWindowIds.value =
                                    _disconnectedWindowIds.value + sshTab.tabId
                                Logger.i(
                                    "PanesTab",
                                    "Pane session ended in group $groupId (state=$state)"
                                )
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    /** True when the window at [index] has lost its session and is showing the dead-pane overlay. */
    fun isWindowDisconnected(index: Int): Boolean {
        val tabId = _entries.value.getOrNull(index)?.sshTab?.tabId ?: return false
        return tabId in _disconnectedWindowIds.value
    }

    /**
     * Swap a dead window's session for a freshly dialled one (the tile's
     * Reconnect action), keeping its grid slot, host and working directory.
     *
     * A copy replaces the entry rather than mutating [PaneWindow.sshTab] in
     * place: [entries] is a StateFlow of a list of these, so an in-place
     * mutation compares equal to the previous value and never emits — the
     * grid would keep rendering the dead session's terminal view.
     */
    fun replaceWindowSession(index: Int, newTab: SSHTab) {
        val current = _entries.value
        val window = current.getOrNull(index) ?: return
        window.sshTab?.tabId?.let { oldId ->
            windowWatchers.remove(oldId)?.cancel()
            _disconnectedWindowIds.value = _disconnectedWindowIds.value - oldId
        }
        _entries.value = current.toMutableList().also {
            it[index] = window.copy(sshTab = newTab)
        }
        syncWindowWatchers()
        applySyncInputRouting()
        Logger.i("PanesTab", "Reconnected window $index in group $groupId")
    }

    /**
     * Toggle synchronize-panes style input mirroring for this pane group.
     * Turning it off clears every window's [io.github.tabssh.terminal.TermuxBridge.broadcastTargets]
     * so input goes back to targeting only the focused window.
     */
    fun setSyncInputEnabled(enabled: Boolean) {
        _syncInputEnabled.value = enabled
        applySyncInputRouting()
    }

    /**
     * Re-point the focused window's [io.github.tabssh.terminal.TermuxBridge.broadcastTargets]
     * at every other connected window's output stream, and clear targets
     * everywhere else. Called whenever sync is toggled, focus moves, or the
     * window list changes (open/close/reconnect) so the mirror always tracks
     * "whichever pane is currently driving input".
     */
    private fun applySyncInputRouting() {
        val entries = _entries.value
        val focused = entries.getOrNull(_focusedPaneIndex.value)
        entries.forEach { window ->
            val bridge = window.sshTab?.termuxBridge ?: return@forEach
            bridge.broadcastTargets = if (_syncInputEnabled.value && window === focused) {
                entries.filter { it !== window }.mapNotNull { it.sshTab?.termuxBridge?.peerOutputStream() }
            } else {
                emptyList()
            }
        }
    }

    /** The [PaneWindow] currently focused for keyboard input, if any. */
    fun focusedEntry(): PaneWindow? = _entries.value.getOrNull(_focusedPaneIndex.value)

    /** Move focus to the pane at [index], clamped to the valid entry range. */
    fun setFocusedPane(index: Int) {
        val entries = _entries.value
        if (entries.isEmpty()) return
        val clamped = index.coerceIn(0, entries.lastIndex)
        _focusedPaneIndex.value = clamped
        applySyncInputRouting()
        Logger.d("PanesTab", "Focused pane $clamped in group $groupId")
    }

    /**
     * Disconnect and remove a single window's live session from the grid
     * (the per-pane "close this connection" action — closing the whole
     * Panes tab is a separate action, see `TabTerminalActivity.closeCurrentTab`).
     * No-ops silently on an out-of-range [index]. Remaining windows'
     * [PaneWindow.gridPosition] are renumbered to stay contiguous; focus is
     * re-clamped to the new entry list.
     */
    fun closeWindow(index: Int) {
        val current = _entries.value
        val window = current.getOrNull(index) ?: return
        try {
            window.sshTab?.cleanup()
        } catch (e: Exception) {
            Logger.d("PanesTab", "closeWindow sshTab.cleanup() suppressed: ${e.message}")
        }
        val remaining = current.filterIndexed { i, _ -> i != index }
            .mapIndexed { i, w -> w.also { it.gridPosition = i } }
        _entries.value = remaining
        syncWindowWatchers()
        if (remaining.isNotEmpty()) {
            setFocusedPane(_focusedPaneIndex.value)
        } else {
            applySyncInputRouting()
        }
        Logger.d("PanesTab", "Closed window $index in group $groupId (${remaining.size} remaining)")
    }

    /** Set custom title (user-defined), same contract as SSHTab/VncTab/ConsoleTab. */
    fun setCustomTitle(newTitle: String) {
        if (newTitle.isNotBlank()) {
            _title.value = newTitle
        }
    }

    /** Get display title for tab bar. */
    fun getDisplayTitle(): String = _title.value

    /**
     * Single representative [ConnectionState] for the whole tab, since unlike
     * [SSHTab]/[VncTab]/[ConsoleTab] this tab has no one connection of its
     * own — used anywhere a single state dot/color must summarize all of
     * this tab's windows (e.g. the "OPEN TABS" list in the long-press
     * terminal menu). Worst-state-wins: any window in ERROR reports ERROR,
     * else any window still CONNECTING/AUTHENTICATING reports CONNECTING,
     * else CONNECTED only if every window is CONNECTED, else DISCONNECTED
     * (covers both "no windows yet" and "all windows disconnected").
     */
    fun aggregateConnectionState(): ConnectionState {
        val states = _entries.value.mapNotNull { it.sshTab?.connectionState?.value }
        return when {
            states.isEmpty() -> ConnectionState.DISCONNECTED
            states.any { it == ConnectionState.ERROR } -> ConnectionState.ERROR
            states.any { it == ConnectionState.CONNECTING || it == ConnectionState.AUTHENTICATING } -> ConnectionState.CONNECTING
            states.all { it == ConnectionState.CONNECTED } -> ConnectionState.CONNECTED
            else -> ConnectionState.DISCONNECTED
        }
    }

    /** Activate this tab (mark as current/visible). */
    fun activate() {
        _isActive.value = true
        Logger.d("PanesTab", "Activated panes tab ${getDisplayTitle()}")
    }

    /** Deactivate this tab (mark as background). */
    fun deactivate() {
        _isActive.value = false
        Logger.d("PanesTab", "Deactivated panes tab ${getDisplayTitle()}")
    }

    /**
     * Tear down every pane's live session. Called for "Disconnect All" and
     * for true tab removal — NOT called when the tab is merely parked for
     * "Keep Running in Background" (see `TabManager.parkPanesTab`).
     */
    fun cleanup() {
        Logger.d("PanesTab", "Cleaning up panes tab ${getDisplayTitle()} (${_entries.value.size} panes)")
        _entries.value.forEach { entry ->
            try {
                entry.sshTab?.cleanup()
            } catch (e: Exception) {
                Logger.d("PanesTab", "pane sshTab.cleanup() suppressed: ${e.message}")
            }
        }
        windowWatchers.values.forEach { it.cancel() }
        windowWatchers.clear()
        _disconnectedWindowIds.value = emptySet()
        // Real tab removal — nothing will ever watch this tab's windows
        // again (parking goes through TabManager.parkPanesTab, which does
        // not call cleanup(), so a parked tab keeps its collectors).
        watchScope.cancel()
        _entries.value = emptyList()
        _isActive.value = false
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PanesTab) return false
        return tabId == other.tabId
    }

    override fun hashCode(): Int = tabId.hashCode()

    override fun toString(): String =
        "PanesTab(id=$tabId, groupId=$groupId, panes=${_entries.value.size}, focused=${_focusedPaneIndex.value})"
}
