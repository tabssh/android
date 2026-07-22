package io.github.tabssh.ui.adapters

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.hypervisor.console.rfb.RfbConstants
import io.github.tabssh.hypervisor.vnc.console.VncConsoleChannel
import io.github.tabssh.themes.definitions.Theme
import io.github.tabssh.ui.tabs.Tab
import io.github.tabssh.ui.tabs.VncTab
import io.github.tabssh.ui.views.TerminalView
import io.github.tabssh.ui.views.VncView
import io.github.tabssh.utils.logging.Logger

/**
 * ViewPager2 adapter for swipeable tabs. Each page is either an SSH terminal
 * ([TerminalViewHolder]) or a VNC framebuffer ([VncViewHolder]), per the
 * VNC-tab-swipe integration (AI.md §11.7.2, TODO.AI.md step 4). [getItemViewType]
 * picks the holder type; both share the same [ViewPager2] and swipe gate.
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
    }

    // Track bound view holders (both kinds) for theme/pref updates and lookup.
    private val boundViewHolders = mutableSetOf<RecyclerView.ViewHolder>()

    private fun boundTerminalHolders(): List<TerminalViewHolder> =
        boundViewHolders.filterIsInstance<TerminalViewHolder>()

    /**
     * Update theme for all bound terminal views. VNC pages have no text
     * theme to apply.
     */
    fun setTheme(theme: Theme) {
        currentTheme = theme
        boundTerminalHolders().forEach { holder ->
            holder.terminalView.applyTheme(theme)
        }
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
    }

    /**
     * Update line spacing on all bound terminal views.
     * Called from applyTerminalUiPrefs() when the user changes the preference.
     */
    fun setLineSpacingPercent(percent: Int) {
        lineSpacingPercent = percent
        boundTerminalHolders().forEach { it.terminalView.setLineSpacingPercent(percent) }
    }

    /**
     * Get terminal view at position (for theme application). Returns null
     * for a VNC page.
     */
    fun getTerminalViewAt(position: Int): TerminalView? {
        return boundTerminalHolders().find { it.bindingAdapterPosition == position }?.terminalView
    }

    /** Get the VNC view at position (for zoom/reconnect controls). Returns null for an SSH page. */
    fun getVncViewAt(position: Int): VncView? {
        return boundViewHolders.filterIsInstance<VncViewHolder>()
            .find { it.bindingAdapterPosition == position }?.vncView
    }

    override fun getItemViewType(position: Int): Int = when (tabs[position]) {
        is Tab.Ssh -> VIEW_TYPE_SSH
        is Tab.Vnc -> VIEW_TYPE_VNC
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
        }
        boundViewHolders.add(holder)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is VncViewHolder) holder.unbind()
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
     * swipeable tab. Until entry points are consolidated onto this tab
     * system (TODO.AI.md step 6), `rfbClient` is null for most tabs — the
     * page then renders blank with no input wired, and re-binds cleanly once
     * a client is attached.
     */
    class VncViewHolder(val vncView: VncView) : RecyclerView.ViewHolder(vncView) {

        private var channel: VncConsoleChannel? = null

        fun bind(vncTab: VncTab) {
            unwireCallbacks()
            val rfbClient = vncTab.rfbClient
            if (rfbClient == null) {
                Logger.d("TerminalPagerAdapter", "Bound VNC tab with no live session yet: ${vncTab.getDisplayTitle()}")
                return
            }
            val ch = VncConsoleChannel(rfbClient)
            channel = ch
            rfbClient.listener = vncView.asRfbListener()
            vncView.onPointerEvent = { x, y, mask -> ch.sendPointerEvent(x, y, mask) }
            vncView.onKeyEvent = { keysym, down -> if (down) ch.sendKey(keysym) }
            vncView.onTextInput = { text -> ch.sendText(text) }
            vncView.onBackspace = { ch.sendKey(RfbConstants.KEY_BACK_SPACE) }
            vncView.onViewSizeReady = { w, h -> ch.resizeToPixels(w, h) }
            Logger.d("TerminalPagerAdapter", "Bound VNC tab: ${vncTab.getDisplayTitle()}")
        }

        /** Called from [onViewRecycled] — drop the channel and callbacks so a recycled page can't drive a stale client. */
        fun unbind() {
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
}
