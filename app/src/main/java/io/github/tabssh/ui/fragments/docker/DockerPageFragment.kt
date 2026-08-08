package io.github.tabssh.ui.fragments.docker

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.docker.DockerSessionManager
import io.github.tabssh.ui.activities.DockerHostManagerActivity
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Base for the six DockerHostManagerActivity destination fragments.
 * Wires the activity's shared session flow (transport acquired once, PLAN.AI.md
 * step 21) and refresh ticks into one [onSessionReady] callback.
 */
abstract class DockerPageFragment : Fragment() {

    protected val manager: DockerHostManagerActivity
        get() = requireActivity() as DockerHostManagerActivity

    protected val app: TabSSHApplication
        get() = requireActivity().application as TabSSHApplication

    /** The current live session, or null before acquisition completes. */
    protected val session: DockerSessionManager.DockerSession?
        get() = (activity as? DockerHostManagerActivity)?.sessionFlow?.value

    /** Called with a ready session on first emission and on every refresh tick. */
    protected abstract fun onSessionReady(session: DockerSessionManager.DockerSession)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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
