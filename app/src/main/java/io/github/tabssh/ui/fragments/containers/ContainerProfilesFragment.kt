package io.github.tabssh.ui.fragments.containers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.tabssh.R
import io.github.tabssh.containers.ContainerSessionManager
import io.github.tabssh.containers.transport.ContainerProfileSummary
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.ui.adapters.ContainerProfileAdapter
import io.github.tabssh.ui.dialogs.ContainerErrorPresenter
import io.github.tabssh.ui.dialogs.ContainerInspectDialog
import kotlinx.coroutines.launch

/**
 * Profiles destination (Incus and LXC/LXD): list with description and attached
 * devices, tap opens the full profile. The engine owns profile authoring, so
 * this destination reads rather than edits and has no FAB.
 */
class ContainerProfilesFragment : ContainerPageFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var errorState: LinearLayout
    private lateinit var textError: TextView
    private lateinit var textEmpty: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var adapter: ContainerProfileAdapter

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

        textEmpty.setText(R.string.container_profiles_empty)
        view.findViewById<TextView>(R.id.text_empty_hint).setText(R.string.container_profiles_empty_hint)
        view.findViewById<ImageView>(R.id.image_empty).setImageResource(R.drawable.ic_container_profile)
        // Read-only destination: there is no add action to attach the FAB to.
        view.findViewById<FloatingActionButton>(R.id.fab_action).visibility = View.GONE

        adapter = ContainerProfileAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        adapter.setOnItemClickListener { profile -> inspectProfile(profile) }
        adapter.setOnMoreClickListener { profile -> inspectProfile(profile) }
        adapter.setOnItemLongClickListener { profile -> inspectProfile(profile) }

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
            val result = session.transport.listProfiles()
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

    private fun inspectProfile(profile: ContainerProfileSummary) {
        val current = session ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = current.transport.inspectProfile(profile.name)
            if (!isAdded) return@launch
            when (result) {
                is ContainerResult.Success ->
                    ContainerInspectDialog.show(requireContext(), profile.name, result.value)
                else -> ContainerErrorPresenter.present(requireContext(), result)
            }
        }
    }
}
