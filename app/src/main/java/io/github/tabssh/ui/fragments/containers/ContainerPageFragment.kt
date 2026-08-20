package io.github.tabssh.ui.fragments.containers

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.color.MaterialColors
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.containers.ContainerSessionManager
import io.github.tabssh.ui.activities.ContainerHostManagerActivity
import io.github.tabssh.utils.coroutines.SingleFlightLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Base for the nine ContainerHostManagerActivity destination fragments.
 * Wires the activity's shared session flow (transport acquired once) and
 * refresh ticks into one [onSessionReady] callback.
 */
abstract class ContainerPageFragment : Fragment() {

    protected val manager: ContainerHostManagerActivity
        get() = requireActivity() as ContainerHostManagerActivity

    protected val app: TabSSHApplication
        get() = requireActivity().application as TabSSHApplication

    /** The current live session, or null before acquisition completes. */
    protected val session: ContainerSessionManager.ContainerSession?
        get() = (activity as? ContainerHostManagerActivity)?.sessionFlow?.value

    /** Called with a ready session on first emission and on every refresh tick. */
    protected abstract fun onSessionReady(session: ContainerSessionManager.ContainerSession)

    // sessionFlow and refreshFlow can both fire in quick succession (initial
    // sessionFlow emission racing a refreshFlow tick, or a forced
    // re-acquire re-emitting) — without tracking the in-flight load, two
    // concurrent onSessionReady loads race and whichever ContainerResult lands
    // last wins, even if it is the stale one.
    private val loader = SingleFlightLoader()

    /**
     * Cancels any load already started through this helper, then runs
     * [block] as the new one — a subclass's onSessionReady should route its
     * whole load through this instead of calling
     * `viewLifecycleOwner.lifecycleScope.launch` directly.
     */
    protected fun startLoad(block: suspend CoroutineScope.() -> Unit): Job =
        loader.launchIn(viewLifecycleOwner.lifecycleScope, block)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Pull-to-refresh on list destinations (fragment_container_list.xml) —
        // same reload path as the toolbar refresh action; the fragments show
        // their own progress indicator, so the spinner stops immediately.
        view.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)?.let { swipe ->
            swipe.setColorSchemeColors(
                MaterialColors.getColor(swipe, com.google.android.material.R.attr.colorPrimary)
            )
            swipe.setProgressBackgroundColorSchemeColor(
                MaterialColors.getColor(swipe, com.google.android.material.R.attr.colorSurface)
            )
            swipe.setOnRefreshListener {
                swipe.isRefreshing = false
                session?.let { onSessionReady(it) }
            }
        }

        // viewLifecycleOwner: both collects drive view updates, so they must
        // die with the view tree (HypervisorsFragment pattern).
        viewLifecycleOwner.lifecycleScope.launch {
            manager.sessionFlow.filterNotNull().collect { ready ->
                if (!isAdded) return@collect
                onSessionReady(ready)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            manager.refreshFlow.collect {
                if (!isAdded) return@collect
                session?.let { onSessionReady(it) }
            }
        }
    }
}
