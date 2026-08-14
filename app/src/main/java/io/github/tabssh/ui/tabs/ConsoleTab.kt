package io.github.tabssh.ui.tabs

import io.github.tabssh.hypervisor.console.ConsoleDisconnectReason
import io.github.tabssh.hypervisor.console.HypervisorConsoleManager
import io.github.tabssh.hypervisor.console.rfb.RfbClient
import io.github.tabssh.hypervisor.spice.SpiceClient
import io.github.tabssh.hypervisor.vnc.VncBackgroundSessionStore
import io.github.tabssh.ssh.connection.ConnectionState
import io.github.tabssh.terminal.TermuxBridge
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Hypervisor console type a [ConsoleTab] connects to — the three
 * `VMConsoleActivity.TYPE_*` string constants replaced with a real enum
 * ahead of `VMConsoleActivity`'s retirement (TODO.AI.md step 6f). VMware is
 * intentionally excluded: `VMConsoleActivity` never implemented it (serial
 * console via RFB "not yet implemented"; VMware VMs use the SSH path
 * instead), so there is nothing to port. LIBVIRT was added for the
 * SSH-tunneled SPICE console path (`LibvirtManagerActivity.openConsole`) —
 * libvirt VNC consoles still use ephemeral [VncTab]s.
 */
enum class HypervisorConsoleType { PROXMOX, XCPNG, XEN_ORCHESTRA, LIBVIRT }

/**
 * Which renderer a [ConsoleTab] is currently driving: TEXT (serial console
 * via TermuxBridge), RFB (VNC framebuffer via [RfbClient]), or SPICE
 * (native SPICE session via [SpiceClient]). TEXT→RFB is the existing
 * one-way Proxmox mid-session flip; SPICE is decided at connect time and
 * never changes afterward.
 */
enum class ConsoleDisplayMode { TEXT, RFB, SPICE }

/**
 * Plain-value connect parameters a [ConsoleTab] needs to (re)establish a
 * [HypervisorConsoleManager] session, gathered by the caller (e.g.
 * `ProxmoxManagerActivity.openConsole()`) before any live session object
 * exists — mirrors [VncTab]'s deferred-connect shape. [vmNode]/[vmType]/
 * [realm] are Proxmox-only; [vmRef] is XCP-ng/Xen Orchestra-only (the VM
 * uuid, same value as [vmId] for those two types today, per
 * `XCPngManagerActivity.openVMConsole()`).
 */
data class ConsoleConnectParams(
    val type: HypervisorConsoleType,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val verifySsl: Boolean,
    val pinnedCertSha256: String?,
    val vmId: String,
    val vmName: String,
    val vmNode: String? = null,
    val vmType: String? = null,
    val realm: String? = null,
    val vmRef: String? = null
) {
    /**
     * The compiler-generated `toString()` prints every property, so any log
     * line, crash report or debugger dump that touched this object exposed the
     * hypervisor password in clear text. Mask it with the project's "xxxxx"
     * convention and keep the rest for diagnostics.
     */
    override fun toString(): String =
        "ConsoleConnectParams(type=$type, host=$host, port=$port, username=$username, " +
            "password=${if (password.isEmpty()) "<none>" else "xxxxx"}, verifySsl=$verifySsl, " +
            "pinnedCertSha256=${if (pinnedCertSha256 == null) "null" else "set"}, " +
            "vmId=$vmId, vmName=$vmName, vmNode=$vmNode, vmType=$vmType, realm=$realm, vmRef=$vmRef)"
}

/**
 * Represents a single hypervisor-console tab (Proxmox/XCP-ng/Xen
 * Orchestra). VNC-tab-swipe integration step 6a, see AI.md §11.7.2 /
 * TODO.AI.md. Unlike [VncTab] — always graphical — a console session
 * resolves to either text (serial, [TermuxBridge]-driven) or graphical
 * (VNC, [RfbClient]-driven) at connect time, and Proxmox can flip
 * text→graphical mid-session (`HypervisorConsoleManager`'s
 * `onSwitchToGraphical` fallback). That flip is one-way, matching
 * `VMConsoleActivity`'s existing behavior — there is no graphical→text path
 * anywhere in this codebase.
 *
 * Mirrors [SSHTab]/[VncTab]'s public shape (tabId/title/connectionState/
 * isActive as `StateFlow`s, `activate()`/`deactivate()`/`cleanup()`) so
 * [Tab] can treat all three variants uniformly. This step only wires
 * identity and the deferred connect params; live `HypervisorConsoleManager`
 * wiring is added when entry points are consolidated onto
 * `TabTerminalActivity` (steps 6c/6e).
 */
class ConsoleTab(val connectParams: ConsoleConnectParams) {

    val tabId: String = UUID.randomUUID().toString()

    /**
     * No persisted DB row backs a hypervisor console the way [VncHost] backs
     * a [VncTab] (TODO.AI.md step 6b decides whether that changes), so this
     * tab always uses its own id as its [VncBackgroundSessionStore] key
     * (step 6e wires graphical-mode console sessions into the same
     * background-parking path VNC tabs use — see [isParked]/[TabManager]).
     */
    val storeKey: String = tabId

    /** Live manager for this tab's session, once connected (steps 6c/6e). */
    @Volatile
    var consoleManager: HypervisorConsoleManager? = null

    /** Non-null once wired for text-mode rendering (steps 6c/6e). */
    @Volatile
    var termuxBridge: TermuxBridge? = null

    /** Non-null once the session has switched to graphical mode. */
    @Volatile
    var rfbClient: RfbClient? = null

    /** Non-null once a SPICE session was assigned via [markSpice]. */
    @Volatile
    var spiceClient: SpiceClient? = null

    // Set true by the first bind that calls SpiceClient.start(); rebinds must
    // not start() again — a SpiceClient is single-shot (see SpiceClient docs).
    private val spiceStartRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Optional extra teardown hook run once during [cleanup] — e.g. the
     * libvirt SPICE path removes its SSH local port forward here.
     */
    @Volatile
    var onCleanup: (() -> Unit)? = null

    // Why the last NON-user-initiated session end happened; null until the
    // first such end and cleared by markGraphical/markSpice on (re)attach.
    // Read by TabTerminalActivity's close-policy gate: CLEAN → auto-close,
    // ERROR → reconnect dialog. @Volatile: written from client worker threads.
    @Volatile
    var lastDisconnectReason: ConsoleDisconnectReason? = null

    // Human-readable detail accompanying lastDisconnectReason, for the dialog body.
    @Volatile
    var lastDisconnectMessage: String? = null

    /** Shared [RfbClient.onSessionEnded]/[SpiceClient.onSessionEnded] target. */
    private fun recordSessionEnd(reason: ConsoleDisconnectReason, detail: String) {
        lastDisconnectReason = reason
        lastDisconnectMessage = detail
        setConnectionState(ConnectionState.DISCONNECTED)
    }

    private val _title = MutableStateFlow(connectParams.vmName)
    val title: StateFlow<String> = _title.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    /** False (text) until [markGraphical] or [markSpice] is called; never reset afterward. */
    private val _isGraphicalMode = MutableStateFlow(false)
    val isGraphicalMode: StateFlow<Boolean> = _isGraphicalMode.asStateFlow()

    /** TEXT until [markGraphical] (→ RFB) or [markSpice] (→ SPICE); never reverts. */
    private val _displayMode = MutableStateFlow(ConsoleDisplayMode.TEXT)
    val displayMode: StateFlow<ConsoleDisplayMode> = _displayMode.asStateFlow()

    // Bumped on every markGraphical/markSpice so a bound ViewHolder rebinds
    // even when the mode value is unchanged — MutableStateFlow dedupes equal
    // values, so a second markGraphical with a NEW client while already in
    // RFB mode would otherwise never re-emit and the fresh client's input
    // and render callbacks would never get wired.
    private val _graphicalAttachSeq = MutableStateFlow(0)
    val graphicalAttachSeq: StateFlow<Int> = _graphicalAttachSeq.asStateFlow()

    // Tab position and ordering — same role as SSHTab.tabIndex/VncTab.tabIndex.
    var tabIndex: Int = 0
        internal set

    /** Set by the connect/reconnect path once wired (steps 6c/6e). */
    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    /**
     * Record that this tab's session has switched to graphical mode,
     * attaching the live [RfbClient] driving it. One-way — matches
     * `VMConsoleActivity.switchToGraphical`, which has no code path back to
     * text mode within a session.
     */
    fun markGraphical(client: RfbClient) {
        rfbClient = client
        // Wire the close-policy hook at attach time so a disconnect reaches
        // this tab even while its page is recycled (view listeners unbound).
        lastDisconnectReason = null
        lastDisconnectMessage = null
        client.onSessionEnded = ::recordSessionEnd
        _graphicalAttachSeq.value = _graphicalAttachSeq.value + 1
        _displayMode.value = ConsoleDisplayMode.RFB
        _isGraphicalMode.value = true
        Logger.d("ConsoleTab", "Console tab ${getDisplayTitle()} switched to graphical mode")
    }

    /**
     * Record that this tab's session is SPICE-driven, attaching the (not yet
     * started) [SpiceClient]. Decided at connect time; never changes afterward.
     * SPICE sessions are NOT parked in [VncBackgroundSessionStore] — the native
     * session has no detach/reattach support this round, so the client simply
     * keeps running while the tab is backgrounded, like a text console.
     */
    fun markSpice(client: SpiceClient) {
        spiceClient = client
        // Same close-policy wiring as markGraphical, on the SPICE hook.
        lastDisconnectReason = null
        lastDisconnectMessage = null
        client.onSessionEnded = ::recordSessionEnd
        _graphicalAttachSeq.value = _graphicalAttachSeq.value + 1
        _displayMode.value = ConsoleDisplayMode.SPICE
        _isGraphicalMode.value = true
        Logger.d("ConsoleTab", "Console tab ${getDisplayTitle()} switched to SPICE mode")
    }

    /**
     * True exactly once — for the first bind of this tab's SPICE session.
     * The caller that wins this race performs the single [SpiceClient.start].
     */
    fun shouldStartSpice(): Boolean = spiceStartRequested.compareAndSet(false, true)

    /** Set custom title (user-defined), same contract as SSHTab/VncTab. */
    fun setCustomTitle(newTitle: String) {
        if (newTitle.isNotBlank()) {
            _title.value = newTitle
            Logger.d("ConsoleTab", "Set custom title for tab: $newTitle")
        }
    }

    /** Activate this tab (mark as current/visible). */
    fun activate() {
        _isActive.value = true
        Logger.d("ConsoleTab", "Activated console tab ${getDisplayTitle()}")
    }

    /** Deactivate this tab (mark as background). */
    fun deactivate() {
        _isActive.value = false
        Logger.d("ConsoleTab", "Deactivated console tab ${getDisplayTitle()}")
    }

    /** Get display title for tab bar. */
    fun getDisplayTitle(): String = _title.value

    /**
     * True once this tab's graphical session is parked in
     * [VncBackgroundSessionStore] (step 6e background-parking parity).
     * Text-mode sessions are never parked — there is nothing worth pausing
     * at the protocol layer, and the tab's own `TermuxBridge`/`HypervisorConsoleManager`
     * keep running unattended exactly like an SSH tab does.
     */
    fun isParked(): Boolean = VncBackgroundSessionStore.contains(storeKey)

    /** Tear down this tab's session — disconnects the manager and clears live handles. */
    fun cleanup() {
        Logger.d("ConsoleTab", "Cleaning up console tab ${getDisplayTitle()}")
        try {
            consoleManager?.disconnect()
        } catch (e: Exception) {
            Logger.d("ConsoleTab", "consoleManager.disconnect() suppressed: ${e.message}")
        }
        try {
            // Same ordering the SPICE branch below uses: detach the callbacks
            // before stopping, so a disconnect notification racing stop() cannot
            // reach the VncView of a tab that is already being torn down.
            rfbClient?.listener = null
            rfbClient?.onSessionEnded = null
            rfbClient?.stop()
        } catch (e: Exception) {
            Logger.d("ConsoleTab", "rfbClient.stop() suppressed: ${e.message}")
        }
        try {
            // Drop the listener and the session-end hook BEFORE stopping: both
            // reference the SpiceView (and through it this tab's activity), and
            // the native worker can still deliver a disconnect callback while
            // stop() is unwinding. Clearing first makes that callback a no-op
            // instead of a late edit on a torn-down view.
            spiceClient?.listener = null
            spiceClient?.onSessionEnded = null
            spiceClient?.stop()
        } catch (e: Exception) {
            Logger.d("ConsoleTab", "spiceClient.stop() suppressed: ${e.message}")
        }
        try {
            onCleanup?.invoke()
        } catch (e: Exception) {
            Logger.d("ConsoleTab", "onCleanup hook suppressed: ${e.message}")
        }
        onCleanup = null
        consoleManager = null
        rfbClient = null
        spiceClient = null
        termuxBridge = null
        VncBackgroundSessionStore.discard(storeKey)
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConsoleTab) return false
        return tabId == other.tabId
    }

    override fun hashCode(): Int = tabId.hashCode()

    override fun toString(): String =
        "ConsoleTab(id=$tabId, vm=${connectParams.vmName}, type=${connectParams.type}, " +
            "graphical=${_isGraphicalMode.value}, state=${_connectionState.value})"
}
