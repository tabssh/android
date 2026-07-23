package io.github.tabssh.ui.adapters

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.hypervisor.console.rfb.RfbClient
import io.github.tabssh.hypervisor.console.rfb.RfbConstants
import io.github.tabssh.hypervisor.console.rfb.RfbListener
import io.github.tabssh.hypervisor.vnc.console.VncConsoleChannel
import io.github.tabssh.themes.definitions.Theme
import io.github.tabssh.ui.tabs.ConsoleTab
import io.github.tabssh.ui.tabs.Tab
import io.github.tabssh.ui.tabs.VncTab
import io.github.tabssh.ui.views.TerminalView
import io.github.tabssh.ui.views.VncView
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ViewPager2 adapter for swipeable tabs. Each page is an SSH terminal
 * ([TerminalViewHolder]), a VNC framebuffer ([VncViewHolder]), or a
 * hypervisor console ([ConsoleViewHolder]), per the VNC-tab-swipe
 * integration (AI.md §11.7.2, TODO.AI.md steps 4 and 6a-6c).
 * [getItemViewType] picks the holder type; all three share the same
 * [ViewPager2] and swipe gate.
 */
class TerminalPagerAdapter(
    private val tabs: List<Tab>,
    private val fontSize: Int = 14,
    private val fontValue: String = "monospace",
    private val onUrlDetected: ((String) -> Unit)? = null,
    private val gesturesEnabled: Boolean = false,
    private val multiplexerType: io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType = io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType.NONE,
    private val customPrefix: String? = null,
    private val onCommandSent: ((ByteArray) -> Unit)? = null,
    private var currentTheme: Theme? = null,
    /**
     * Invoked when the user enters selection mode on a per-page
     * TerminalView (e.g. via "Select Text…" in the clipboard menu + drag).
     * Receives the TerminalView so the host activity can start the floating
     * Copy ActionMode against the right view.
     */
    private val onSelectionStarted: ((TerminalView) -> Unit)? = null,
    /**
     * Invoked when selection is cleared from a path other than the
     * ActionMode's own Cancel button (e.g. tap-outside-to-dismiss). Lets
     * the host activity finish the floating ActionMode it started, so
     * swipe-suspend doesn't get stuck. See TerminalView.onSelectionEnded.
     */
    private val onSelectionEnded: (() -> Unit)? = null,
    private val onContextMenuRequested: ((Float, Float) -> Unit)? = null,
    private var reverseScrollDirection: Boolean = false,
    private var lineSpacingPercent: Int = 120
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_SSH = 0
        private const val VIEW_TYPE_VNC = 1
        private const val VIEW_TYPE_CONSOLE = 2
    }

    // Track bound view holders (all kinds) for theme/pref updates and lookup.
    private val boundViewHolders = mutableSetOf<RecyclerView.ViewHolder>()

    private fun boundTerminalHolders(): List<TerminalViewHolder> =
        boundViewHolders.filterIsInstance<TerminalViewHolder>()

    private fun boundConsoleHolders(): List<ConsoleViewHolder> =
        boundViewHolders.filterIsInstance<ConsoleViewHolder>()

    /**
     * Update theme for all bound terminal views — SSH pages and console
     * pages currently in text mode. VNC pages have no text theme to apply.
     */
    fun setTheme(theme: Theme) {
        currentTheme = theme
        boundTerminalHolders().forEach { holder ->
            holder.terminalView.applyTheme(theme)
        }
        boundConsoleHolders().forEach { holder -> holder.applyTheme(theme) }
    }

    /**
     * Update scroll direction on all bound terminal views.
     * Called from applyTerminalUiPrefs() when the user changes the preference.
     */
    fun setReverseScrollDirection(reversed: Boolean) {
        reverseScrollDirection = reversed
        // Snapshot to avoid ConcurrentModificationException if a layout pass
        // triggered by the update causes RecyclerView to recycle a view.
        boundTerminalHolders().forEach { it.terminalView.reverseScrollDirection = reversed }
        boundConsoleHolders().forEach { it.terminalView.reverseScrollDirection = reversed }
    }

    /**
     * Update line spacing on all bound terminal views.
     * Called from applyTerminalUiPrefs() when the user changes the preference.
     */
    fun setLineSpacingPercent(percent: Int) {
        lineSpacingPercent = percent
        boundTerminalHolders().forEach { it.terminalView.setLineSpacingPercent(percent) }
        boundConsoleHolders().forEach { it.terminalView.setLineSpacingPercent(percent) }
    }

    /**
     * Get terminal view at position (for theme application). Returns null
     * for a VNC page or a console page currently in graphical mode.
     */
    fun getTerminalViewAt(position: Int): TerminalView? {
        boundTerminalHolders().find { it.bindingAdapterPosition == position }?.let { return it.terminalView }
        return boundConsoleHolders().find { it.bindingAdapterPosition == position && !it.isGraphicalMode }
            ?.terminalView
    }

    /**
     * Get the VNC view at position (for zoom/reconnect controls). Returns
     * null for an SSH page or a console page currently in text mode.
     */
    fun getVncViewAt(position: Int): VncView? {
        boundViewHolders.filterIsInstance<VncViewHolder>()
            .find { it.bindingAdapterPosition == position }?.let { return it.vncView }
        return boundConsoleHolders().find { it.bindingAdapterPosition == position && it.isGraphicalMode }
            ?.vncView
    }

    override fun getItemViewType(position: Int): Int = when (tabs[position]) {
        is Tab.Ssh -> VIEW_TYPE_SSH
        is Tab.Vnc -> VIEW_TYPE_VNC
        is Tab.Console -> VIEW_TYPE_CONSOLE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_VNC -> {
                val vncView = VncView(parent.context)
                vncView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                VncViewHolder(vncView)
            }
            VIEW_TYPE_CONSOLE -> {
                val terminalView = TerminalView(parent.context)
                terminalView.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                terminalView.reverseScrollDirection = reverseScrollDirection
                terminalView.setLineSpacingPercent(lineSpacingPercent)
                val vncView = VncView(parent.context)
                vncView.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                val container = FrameLayout(parent.context)
                container.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                container.addView(terminalView)
                container.addView(vncView)
                ConsoleViewHolder(container, terminalView, vncView, fontSize, fontValue)
            }
            else -> {
                val terminalView = TerminalView(parent.context)
                terminalView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                terminalView.reverseScrollDirection = reverseScrollDirection
                terminalView.setLineSpacingPercent(lineSpacingPercent)
                TerminalViewHolder(
                    terminalView,
                    fontSize,
                    fontValue,
                    onUrlDetected,
                    gesturesEnabled,
                    multiplexerType,
                    customPrefix,
                    onCommandSent,
                    onSelectionStarted,
                    onSelectionEnded,
                    onContextMenuRequested
                )
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position >= tabs.size) return
        when (val tab = tabs[position]) {
            is Tab.Ssh -> {
                if (holder !is TerminalViewHolder) return
                holder.bind(tab.sshTab)
                currentTheme?.let { theme -> holder.terminalView.applyTheme(theme) }
            }
            is Tab.Vnc -> {
                if (holder !is VncViewHolder) return
                holder.bind(tab.vncTab)
            }
            is Tab.Console -> {
                if (holder !is ConsoleViewHolder) return
                holder.bind(tab.consoleTab)
                currentTheme?.let { theme -> holder.applyTheme(theme) }
            }
        }
        boundViewHolders.add(holder)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is VncViewHolder) holder.unbind()
        if (holder is ConsoleViewHolder) holder.unbind()
        boundViewHolders.remove(holder)
    }

    override fun getItemCount(): Int = tabs.size

    /**
     * ViewHolder for terminal view in ViewPager2
     */
    class TerminalViewHolder(
        val terminalView: TerminalView,
        private val fontSize: Int,
        private val fontValue: String,
        private val onUrlDetected: ((String) -> Unit)?,
        private val gesturesEnabled: Boolean,
        private val multiplexerType: io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType,
        private val customPrefix: String?,
        private val onCommandSent: ((ByteArray) -> Unit)?,
        private val onSelectionStarted: ((TerminalView) -> Unit)? = null,
        private val onSelectionEnded: (() -> Unit)? = null,
        private val onContextMenuRequested: ((Float, Float) -> Unit)? = null
    ) : RecyclerView.ViewHolder(terminalView) {

        fun bind(tab: io.github.tabssh.ui.tabs.SSHTab) {
            // Attach terminal emulator to this view
            terminalView.attachTerminalEmulator(tab.termuxBridge)

            // Apply font
            terminalView.setFont(fontValue)

            // Apply font size
            terminalView.setFontSize(fontSize)

            // Set up URL detection callback
            if (onUrlDetected != null) {
                terminalView.onUrlDetected = onUrlDetected
            }

            // Long-press: show the terminal bottom-sheet menu so the user can
            // access new tab, tab list, session controls, and settings. x/y are
            // passed by TerminalView but not needed since the sheet is full-width.
            terminalView.onContextMenuRequested = onContextMenuRequested

            // Selection-mode entered ("Select Text…" in the clipboard menu + drag, or double-tap)
            // → activity starts the floating Copy ActionMode against THIS view.
            onSelectionStarted?.let { cb ->
                terminalView.onSelectionStarted = { cb(terminalView) }
            }
            onSelectionEnded?.let { cb ->
                terminalView.onSelectionEnded = cb
            }

            // Set up gesture support
            if (gesturesEnabled) {
                terminalView.enableGestureSupport(multiplexerType, customPrefix)
                terminalView.onCommandSent = onCommandSent
            }

            // Terminal theme is already applied in TerminalView initialization
            // based on connection profile settings

            Logger.d("TerminalPagerAdapter", "Bound terminal for tab: ${tab.profile.getDisplayName()}")
        }
    }

    /**
     * ViewHolder for a VNC framebuffer page in ViewPager2. Wires [VncView]'s
     * input/render callbacks to the tab's [VncTab.rfbClient] through the same
     * [VncConsoleChannel] wrapper `VMConsoleActivity` uses, so pointer/key
     * behavior stays identical between the standalone viewer and the
     * swipeable tab. `rfbClient` is null until an entry-point activity
     * (VncHostsActivity, etc.) has connected the socket and constructed the
     * client — until then the page renders blank with no input wired, and
     * re-binds cleanly once a client is attached.
     */
    class VncViewHolder(val vncView: VncView) : RecyclerView.ViewHolder(vncView) {

        private var channel: VncConsoleChannel? = null

        // Tracked so unbind() can detach this now-recycled ViewHolder's
        // listener from the client, which outlives the ViewHolder (it lives
        // on VncTab). Without this, a recycled page's stale VncView keeps
        // receiving RfbListener callbacks after it's no longer showing this
        // tab — corrupting whatever page the RecyclerView recycles it into.
        private var boundClient: RfbClient? = null
        private var boundListener: RfbListener? = null

        fun bind(vncTab: VncTab) {
            unwireCallbacks()
            val rfbClient = vncTab.rfbClient
            if (rfbClient == null) {
                Logger.d("TerminalPagerAdapter", "Bound VNC tab with no live session yet: ${vncTab.getDisplayTitle()}")
                return
            }
            val ch = VncConsoleChannel(rfbClient)
            channel = ch
            boundClient = rfbClient
            val listener = vncView.asRfbListener()
            boundListener = listener
            rfbClient.listener = listener
            vncView.onPointerEvent = { x, y, mask -> ch.sendPointerEvent(x, y, mask) }
            vncView.onKeyEvent = { keysym, down -> if (down) ch.sendKey(keysym) }
            vncView.onTextInput = { text -> ch.sendText(text) }
            vncView.onBackspace = { ch.sendKey(RfbConstants.KEY_BACK_SPACE) }
            vncView.onViewSizeReady = { w, h -> ch.resizeToPixels(w, h) }
            // VNC-tab-swipe integration step 6c — entry-point activities
            // (VncHostsActivity, etc.) only open the socket and construct the
            // RfbClient; they never call start() themselves. Driving the
            // handshake is this ViewHolder's job, deferred until the page
            // actually renders. On a rebind (page recycled while swiping away,
            // then swiped back to) the client is already running and
            // onConnected will not refire — RfbClient.start() is idempotent
            // (a no-op past the first call), so replay the retained
            // framebuffer state manually first, same pattern
            // VMConsoleActivity uses for background reattach.
            if (rfbClient.framebufferWidth > 0 && rfbClient.framebufferHeight > 0) {
                listener.onConnected(
                    rfbClient.framebufferWidth,
                    rfbClient.framebufferHeight,
                    rfbClient.serverDesktopName,
                    rfbClient.framebufferPixels
                )
            }
            rfbClient.start()
            Logger.d("TerminalPagerAdapter", "Bound VNC tab: ${vncTab.getDisplayTitle()}")
        }

        /** Called from [onViewRecycled] — drop the channel and callbacks so a recycled page can't drive a stale client. */
        fun unbind() {
            // Detach this ViewHolder's listener from the client it was bound
            // to — the client outlives the ViewHolder (it lives on VncTab),
            // so leaving the listener wired would let a recycled page keep
            // painting into whatever this ViewHolder gets reused for next.
            // asRfbListener() returns a fresh object per call, so compare
            // against the exact instance bind() assigned, not a new one.
            if (boundClient?.listener === boundListener) {
                boundClient?.listener = null
            }
            boundClient = null
            boundListener = null
            unwireCallbacks()
        }

        private fun unwireCallbacks() {
            channel = null
            vncView.onPointerEvent = null
            vncView.onKeyEvent = null
            vncView.onTextInput = null
            vncView.onBackspace = null
            vncView.onViewSizeReady = null
        }
    }

    /**
     * ViewHolder for a hypervisor-console page in ViewPager2 (VNC-tab-swipe
     * integration step 6c). A console session resolves to either text mode
     * ([TerminalView], [ConsoleTab.termuxBridge]-driven, same wiring as
     * [TerminalViewHolder]) or graphical mode ([VncView],
     * [ConsoleTab.rfbClient]-driven through [VncConsoleChannel], same wiring
     * as [VncViewHolder]) — [container] holds both views and toggles their
     * visibility as [ConsoleTab.isGraphicalMode] flips. That flip is
     * one-way (see [ConsoleTab]), so once graphical mode is bound this
     * holder never falls back to text mode for the same tab. Until entry
     * points are consolidated onto this tab system (TODO.AI.md step 6e),
     * `termuxBridge`/`rfbClient` are null for most tabs — the relevant page
     * then renders blank with no input wired, same discipline
     * [VncViewHolder] already uses for un-consolidated VNC tabs.
     */
    class ConsoleViewHolder(
        private val container: FrameLayout,
        val terminalView: TerminalView,
        val vncView: VncView,
        private val fontSize: Int,
        private val fontValue: String
    ) : RecyclerView.ViewHolder(container) {

        private var channel: VncConsoleChannel? = null
        private var boundClient: RfbClient? = null
        private var boundListener: RfbListener? = null
        private var modeJob: Job? = null
        private var currentTheme: Theme? = null
        private val holderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        /** True once this page is showing the graphical (VNC) side. */
        var isGraphicalMode: Boolean = false
            private set

        fun bind(consoleTab: ConsoleTab) {
            unbind()
            modeJob = holderScope.launch {
                consoleTab.isGraphicalMode.collect { graphical ->
                    if (graphical) bindGraphical(consoleTab) else bindText(consoleTab)
                }
            }
        }

        fun applyTheme(theme: Theme) {
            currentTheme = theme
            if (!isGraphicalMode) terminalView.applyTheme(theme)
        }

        private fun bindText(consoleTab: ConsoleTab) {
            isGraphicalMode = false
            terminalView.visibility = View.VISIBLE
            vncView.visibility = View.GONE
            unwireVnc()
            val bridge = consoleTab.termuxBridge
            if (bridge == null) {
                Logger.d(
                    "TerminalPagerAdapter",
                    "Bound console tab with no live text session yet: ${consoleTab.getDisplayTitle()}"
                )
                return
            }
            terminalView.attachTerminalEmulator(bridge)
            terminalView.setFont(fontValue)
            terminalView.setFontSize(fontSize)
            currentTheme?.let { theme -> terminalView.applyTheme(theme) }
            Logger.d("TerminalPagerAdapter", "Bound console tab (text mode): ${consoleTab.getDisplayTitle()}")
        }

        private fun bindGraphical(consoleTab: ConsoleTab) {
            isGraphicalMode = true
            terminalView.visibility = View.GONE
            vncView.visibility = View.VISIBLE
            unwireVnc()
            val rfbClient = consoleTab.rfbClient
            if (rfbClient == null) {
                Logger.d(
                    "TerminalPagerAdapter",
                    "Bound console tab with no live graphical session yet: ${consoleTab.getDisplayTitle()}"
                )
                return
            }
            val ch = VncConsoleChannel(rfbClient)
            channel = ch
            boundClient = rfbClient
            val listener = vncView.asRfbListener()
            boundListener = listener
            rfbClient.listener = listener
            vncView.onPointerEvent = { x, y, mask -> ch.sendPointerEvent(x, y, mask) }
            vncView.onKeyEvent = { keysym, down -> if (down) ch.sendKey(keysym) }
            vncView.onTextInput = { text -> ch.sendText(text) }
            vncView.onBackspace = { ch.sendKey(RfbConstants.KEY_BACK_SPACE) }
            vncView.onViewSizeReady = { w, h -> ch.resizeToPixels(w, h) }
            // Same discipline as VncViewHolder.bind() — driving the handshake
            // and replaying retained framebuffer state on rebind is this
            // holder's job, not the entry-point activity's (see that method's
            // comment for the full rationale).
            if (rfbClient.framebufferWidth > 0 && rfbClient.framebufferHeight > 0) {
                listener.onConnected(
                    rfbClient.framebufferWidth,
                    rfbClient.framebufferHeight,
                    rfbClient.serverDesktopName,
                    rfbClient.framebufferPixels
                )
            }
            rfbClient.start()
            Logger.d("TerminalPagerAdapter", "Bound console tab (graphical mode): ${consoleTab.getDisplayTitle()}")
        }

        /** Called from [onViewRecycled] — drop the mode collector and VNC callbacks so a recycled page can't drive a stale session. */
        fun unbind() {
            modeJob?.cancel()
            modeJob = null
            unwireVnc()
        }

        private fun unwireVnc() {
            // Detach this ViewHolder's listener from the client it was bound
            // to — see VncViewHolder.unbind() for the full rationale.
            if (boundClient?.listener === boundListener) {
                boundClient?.listener = null
            }
            boundClient = null
            boundListener = null
            channel = null
            vncView.onPointerEvent = null
            vncView.onKeyEvent = null
            vncView.onTextInput = null
            vncView.onBackspace = null
            vncView.onViewSizeReady = null
        }
    }
}
