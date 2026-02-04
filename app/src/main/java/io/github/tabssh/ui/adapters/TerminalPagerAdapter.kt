package io.github.tabssh.ui.adapters

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
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
    private val onUrlDetected: ((String) -> Unit)? = null,
    private val gesturesEnabled: Boolean = false,
    private val multiplexerType: io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType = io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType.NONE,
    private val onCommandSent: ((ByteArray) -> Unit)? = null
) : RecyclerView.Adapter<TerminalPagerAdapter.TerminalViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TerminalViewHolder {
        val terminalView = TerminalView(parent.context)
        terminalView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        return TerminalViewHolder(terminalView, fontSize, onUrlDetected, gesturesEnabled, multiplexerType, onCommandSent)
    }

    override fun onBindViewHolder(holder: TerminalViewHolder, position: Int) {
        if (position < tabs.size) {
            val tab = tabs[position]
            holder.bind(tab)
        }
    }

    override fun getItemCount(): Int = tabs.size

    /**
     * ViewHolder for terminal view in ViewPager2
     */
    class TerminalViewHolder(
        val terminalView: TerminalView,
        private val fontSize: Int,
        private val onUrlDetected: ((String) -> Unit)?,
        private val gesturesEnabled: Boolean,
        private val multiplexerType: io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType,
        private val onCommandSent: ((ByteArray) -> Unit)?
    ) : RecyclerView.ViewHolder(terminalView) {

        fun bind(tab: SSHTab) {
            // Attach terminal emulator to this view
            terminalView.attachTerminalEmulator(tab.terminal)

            // Apply font size
            terminalView.setFontSize(fontSize)

            // Set up URL detection callback
            if (onUrlDetected != null) {
                terminalView.onUrlDetected = onUrlDetected
            }
            
            // Set up gesture support
            if (gesturesEnabled) {
                terminalView.enableGestureSupport(multiplexerType)
                terminalView.onCommandSent = onCommandSent
            }

            // Terminal theme is already applied in TerminalView initialization
            // based on connection profile settings

            Logger.d("TerminalPagerAdapter", "Bound terminal for tab: ${tab.profile.getDisplayName()}")
        }
    }
}
