package io.github.tabssh.ui.fragments.containers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.tabssh.R
import io.github.tabssh.containers.ContainerSessionManager
import io.github.tabssh.containers.transport.ContainerProjectSummary
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.ui.adapters.ContainerProjectAdapter
import io.github.tabssh.ui.dialogs.ContainerActionSheet
import io.github.tabssh.ui.dialogs.ContainerErrorPresenter
import io.github.tabssh.ui.dialogs.ContainerInspectDialog
import io.github.tabssh.ui.utils.ContainerText
import kotlinx.coroutines.launch

/**
 * Projects destination (Incus and LXC/LXD): list with the active project
 * marked, tap opens the action sheet to switch to it or inspect it. Switching
 * scopes every other destination to the chosen project, so it refreshes the
 * whole host view rather than only this list. The engine owns project
 * authoring, so there is no FAB.
 */
class ContainerProjectsFragment : ContainerPageFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var errorState: LinearLayout
    private lateinit var textError: TextView
    private lateinit var textEmpty: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var adapter: ContainerProjectAdapter
    private var actionInFlight = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_container_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recyclerView = view.findViewById(R.id.recycler_list)
        emptyState = view.findViewById(R.id.empty_state)
        errorState = view.findViewById(R.id.error_state)
        textError = view.findViewById(R.id.text_error)
        textEmpty = view.findViewById(R.id.text_empty)
        progressBar = view.findViewById(R.id.progress_bar)

        textEmpty.setText(R.string.container_projects_empty)
        view.findViewById<TextView>(R.id.text_empty_hint).setText(R.string.container_projects_empty_hint)
        view.findViewById<ImageView>(R.id.image_empty).setImageResource(R.drawable.ic_container_project)
        // Projects are created with the engine's own tooling; nothing to add.
        view.findViewById<FloatingActionButton>(R.id.fab_action).visibility = View.GONE

        adapter = ContainerProjectAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        adapter.setOnItemClickListener { project -> showProjectMenu(project) }
        adapter.setOnMoreClickListener { project -> showProjectMenu(project) }
        adapter.setOnItemLongClickListener { project -> showProjectMenu(project) }

        view.findViewById<MaterialButton>(R.id.button_retry).setOnClickListener {
            session?.let { onSessionReady(it) }
        }

        // Base class wires sessionFlow/refreshFlow into onSessionReady.
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onSessionReady(session: ContainerSessionManager.ContainerSession) {
        progressBar.visibility = View.VISIBLE
        errorState.visibility = View.GONE
        startLoad {
            val result = session.transport.listProjects()
            if (!isAdded) return@startLoad
            progressBar.visibility = View.GONE
            when (result) {
                is ContainerResult.Success -> {
                    adapter.updateList(result.value)
                    val empty = result.value.isEmpty()
                    recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
                    emptyState.visibility = if (empty) View.VISIBLE else View.GONE
                }
                else -> {
                    recyclerView.visibility = View.GONE
                    emptyState.visibility = View.GONE
                    errorState.visibility = View.VISIBLE
                    textError.text = ContainerErrorPresenter.messageFor(requireContext(), result)
                }
            }
        }
    }

    private fun showProjectMenu(project: ContainerProjectSummary) {
        if (!isAdded) return
        val actions = mutableListOf<ContainerActionSheet.Action>()
        // The active project is already the scope; offering to switch to it
        // would be a no-op action the user cannot tell apart from a failure.
        if (!project.active) {
            actions += ContainerActionSheet.Action(
                R.drawable.ic_container_project, getString(R.string.container_project_select)
            ) {
                selectProject(project)
            }
        }
        actions += ContainerActionSheet.Action(
            R.drawable.ic_info, getString(R.string.container_option_inspect)
        ) {
            inspectProject(project)
        }
        ContainerActionSheet.show(requireContext(), project.name, project.description, actions)
    }

    private fun selectProject(project: ContainerProjectSummary) {
        val current = session ?: return
        if (actionInFlight) return
        actionInFlight = true
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = current.transport.selectProject(project.name)
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                when (result) {
                    is ContainerResult.Success -> {
                        // A bidi-override in a daemon-supplied name could make
                        // the toast read as a different project than the one
                        // now in scope.
                        Toast.makeText(
                            requireContext(),
                            getString(
                                R.string.container_project_selected_fmt,
                                ContainerText.display(project.name)
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                        // Every other destination is now scoped differently.
                        manager.refreshFlow.tryEmit(Unit)
                    }
                    else -> ContainerErrorPresenter.present(requireContext(), result)
                }
            } finally {
                actionInFlight = false
            }
        }
    }

    private fun inspectProject(project: ContainerProjectSummary) {
        val current = session ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = current.transport.inspectProject(project.name)
            if (!isAdded) return@launch
            when (result) {
                is ContainerResult.Success ->
                    ContainerInspectDialog.show(requireContext(), project.name, result.value)
                else -> ContainerErrorPresenter.present(requireContext(), result)
            }
        }
    }
}
