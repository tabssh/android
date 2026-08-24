package io.github.tabssh.ui.tabs

import io.github.tabssh.ssh.connection.ConnectionState
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** Current snapshot of this tab's windows. */
    fun currentEntries(): List<PaneWindow> = _entries.value

    /** Replace the full window list (e.g. after resolving/attaching a window's SSHTab). */
    fun updateEntries(newEntries: List<PaneWindow>) {
        _entries.value = newEntries
    }

    /** The [PaneWindow] currently focused for keyboard input, if any. */
    fun focusedEntry(): PaneWindow? = _entries.value.getOrNull(_focusedPaneIndex.value)

    /** Move focus to the pane at [index], clamped to the valid entry range. */
    fun setFocusedPane(index: Int) {
        val entries = _entries.value
        if (entries.isEmpty()) return
        val clamped = index.coerceIn(0, entries.lastIndex)
        _focusedPaneIndex.value = clamped
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
        if (remaining.isNotEmpty()) {
            setFocusedPane(_focusedPaneIndex.value)
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
