package io.github.tabssh.ui.fragments.docker

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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.docker.DockerSessionManager
import io.github.tabssh.docker.transport.DockerNetworkSummary
import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.ui.adapters.DockerNetworkAdapter
import io.github.tabssh.ui.dialogs.DockerActionSheet
import io.github.tabssh.ui.dialogs.DockerErrorPresenter
import io.github.tabssh.ui.dialogs.DockerInspectDialog
import io.github.tabssh.ui.utils.DockerNames
import io.github.tabssh.ui.utils.DockerText
import kotlinx.coroutines.launch

/**
 * Networks destination: list with driver/scope, FAB creates a network, tap
 * (or long-press) opens the action sheet with inspect first and destructive
 * remove last. Load failures render inline with retry.
 */
class DockerNetworksFragment : DockerPageFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var errorState: LinearLayout
    private lateinit var textError: TextView
    private lateinit var textEmpty: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var fabAction: FloatingActionButton
    private lateinit var adapter: DockerNetworkAdapter
    private var actionInFlight = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_docker_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recyclerView = view.findViewById(R.id.recycler_list)
        emptyState = view.findViewById(R.id.empty_state)
        errorState = view.findViewById(R.id.error_state)
        textError = view.findViewById(R.id.text_error)
        textEmpty = view.findViewById(R.id.text_empty)
        progressBar = view.findViewById(R.id.progress_bar)
        fabAction = view.findViewById(R.id.fab_action)

        textEmpty.setText(R.string.docker_networks_empty)
        view.findViewById<TextView>(R.id.text_empty_hint).setText(R.string.docker_networks_empty_hint)
        view.findViewById<ImageView>(R.id.image_empty).setImageResource(R.drawable.ic_docker_network)
        fabAction.contentDescription = getString(R.string.docker_create_network_title)

        adapter = DockerNetworkAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Tap runs the most common action (inspect) in one touch; the overflow
        // button and long-press open the full action sheet.
        adapter.setOnItemClickListener { network -> inspectNetwork(network) }
        adapter.setOnMoreClickListener { network -> showNetworkMenu(network) }
        adapter.setOnItemLongClickListener { network -> showNetworkMenu(network) }

        fabAction.setOnClickListener { showCreateDialog() }

        view.findViewById<MaterialButton>(R.id.button_retry).setOnClickListener {
            session?.let { onSessionReady(it) }
        }

        // Base class wires sessionFlow/refreshFlow into onSessionReady.
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onSessionReady(session: DockerSessionManager.DockerSession) {
        progressBar.visibility = View.VISIBLE
        errorState.visibility = View.GONE
        startLoad {
            val result = session.transport.listNetworks()
            if (!isAdded) return@startLoad
            progressBar.visibility = View.GONE
            when (result) {
                is DockerResult.Success -> {
                    adapter.updateList(result.value)
                    val empty = result.value.isEmpty()
                    recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
                    emptyState.visibility = if (empty) View.VISIBLE else View.GONE
                }
                else -> {
                    recyclerView.visibility = View.GONE
                    emptyState.visibility = View.GONE
                    errorState.visibility = View.VISIBLE
                    textError.text = DockerErrorPresenter.messageFor(requireContext(), result)
                }
            }
        }
    }

    private fun showCreateDialog() {
        if (!isAdded) return
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_create_named, null)
        val editName = view.findViewById<TextInputEditText>(R.id.edit_name)
        val editDriver = view.findViewById<TextInputEditText>(R.id.edit_driver)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.docker_create_network_title)
            .setView(view)
            .setPositiveButton(R.string.docker_create) { _, _ ->
                val name = editName.text?.toString()?.trim().orEmpty()
                val driver = editDriver.text?.toString()?.trim().orEmpty()
                // Validate before the value reaches the transport: a name with
                // whitespace or a leading dash would be parsed as CLI flags.
                if (name.isEmpty()) {
                    Toast.makeText(
                        requireContext(), R.string.docker_error_name_required, Toast.LENGTH_SHORT
                    ).show()
                } else if (!DockerNames.isValidResourceName(name)) {
                    Toast.makeText(
                        requireContext(), R.string.docker_error_name_format, Toast.LENGTH_LONG
                    ).show()
                } else if (driver.isNotEmpty() && !DockerNames.isValidDriverName(driver)) {
                    Toast.makeText(
                        requireContext(), R.string.docker_error_driver_format, Toast.LENGTH_LONG
                    ).show()
                } else {
                    createNetwork(name, driver.ifEmpty { null })
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun createNetwork(name: String, driver: String?) {
        val current = session ?: return
        // Network creation is not idempotent from the user's point of view — a
        // repeat tap surfaces a spurious "already exists" error dialog.
        if (actionInFlight) return
        actionInFlight = true
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = current.transport.createNetwork(name, driver)
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                when (result) {
                    is DockerResult.Success -> onSessionReady(current)
                    else -> DockerErrorPresenter.present(requireContext(), result)
                }
            } finally {
                actionInFlight = false
            }
        }
    }

    private fun showNetworkMenu(network: DockerNetworkSummary) {
        if (!isAdded) return
        DockerActionSheet.show(
            requireContext(), network.name, network.driver,
            listOf(
                DockerActionSheet.Action(R.drawable.ic_info, getString(R.string.docker_option_inspect)) {
                    inspectNetwork(network)
                },
                DockerActionSheet.Action(
                    R.drawable.ic_clear, getString(R.string.docker_action_remove), destructive = true
                ) {
                    confirmRemove(network)
                }
            )
        )
    }

    private fun inspectNetwork(network: DockerNetworkSummary) {
        val current = session ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = current.transport.inspectNetwork(network.id)
            if (!isAdded) return@launch
            when (result) {
                is DockerResult.Success ->
                    DockerInspectDialog.show(requireContext(), network.name, result.value)
                else -> DockerErrorPresenter.present(requireContext(), result)
            }
        }
    }

    private fun confirmRemove(network: DockerNetworkSummary) {
        if (!isAdded) return
        // A bidi-override in a daemon-supplied name could make the
        // confirmation read as a different network than the one being deleted.
        val safeName = DockerText.display(network.name)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(safeName)
            .setMessage(getString(R.string.docker_remove_network_message, safeName))
            .setPositiveButton(R.string.delete) { _, _ -> removeNetwork(network.id) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removeNetwork(id: String) {
        val current = session ?: return
        if (actionInFlight) return
        actionInFlight = true
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = current.transport.removeNetwork(id)
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                when (result) {
                    is DockerResult.Success -> {
                        Toast.makeText(
                            requireContext(), R.string.docker_action_success, Toast.LENGTH_SHORT
                        ).show()
                        onSessionReady(current)
                    }
                    else -> DockerErrorPresenter.present(requireContext(), result)
                }
            } finally {
                actionInFlight = false
            }
        }
    }
}
