package io.github.tabssh.ui.adapters

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.themes.definitions.Theme
import io.github.tabssh.ui.tabs.SSHTab
import io.github.tabssh.ui.views.TerminalView
import io.github.tabssh.utils.logging.Logger

/**
 * ViewPager2 adapter for swipeable terminal tabs
 * Each page displays a terminal view connected to an SSH session
 */
class TerminalPagerAdapter(
    private val tabs: List<SSHTab>,
    private val fontSize: Int = 14,
    private val fontValue: String = "monospace",
    private val onUrlDetected: ((String) -> Unit)? = null,
    private val gesturesEnabled: Boolean = false,
    private val multiplexerType: io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType = io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType.NONE,
    private val customPrefix: String? = null,
    private val onCommandSent: ((ByteArray) -> Unit)? = null,
    private var currentTheme: Theme? = null
) : RecyclerView.Adapter<TerminalPagerAdapter.TerminalViewHolder>() {

    // Track bound view holders for theme updates
    private val boundViewHolders = mutableSetOf<TerminalViewHolder>()

    /**
     * Update theme for all bound terminal views
     */
    fun setTheme(theme: Theme) {
        currentTheme = theme
        boundViewHolders.forEach { holder ->
            holder.terminalView.applyTheme(theme)
        }
    }

    /**
     * Get terminal view at position (for theme application)
     */
    fun getTerminalViewAt(position: Int): TerminalView? {
        return boundViewHolders.find { it.adapterPosition == position }?.terminalView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TerminalViewHolder {
        val terminalView = TerminalView(parent.context)
        terminalView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        return TerminalViewHolder(
            terminalView,
            fontSize,
            fontValue,
            onUrlDetected,
            gesturesEnabled,
            multiplexerType,
            customPrefix,
            onCommandSent
        )
    }

    override fun onBindViewHolder(holder: TerminalViewHolder, position: Int) {
        if (position < tabs.size) {
            val tab = tabs[position]
            holder.bind(tab)

            // Apply current theme
            currentTheme?.let { theme ->
                holder.terminalView.applyTheme(theme)
            }

            // Track bound holder
            boundViewHolders.add(holder)
        }
    }

    override fun onViewRecycled(holder: TerminalViewHolder) {
        super.onViewRecycled(holder)
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
        private val onCommandSent: ((ByteArray) -> Unit)?
    ) : RecyclerView.ViewHolder(terminalView) {

        fun bind(tab: SSHTab) {
            // Attach terminal emulator to this view
            terminalView.attachTerminalEmulator(tab.terminal)

            // Apply font
            terminalView.setFont(fontValue)

            // Apply font size
            terminalView.setFontSize(fontSize)

            // Set up URL detection callback
            if (onUrlDetected != null) {
                terminalView.onUrlDetected = onUrlDetected
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
}
