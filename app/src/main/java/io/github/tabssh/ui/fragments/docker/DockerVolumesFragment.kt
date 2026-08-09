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
import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.docker.transport.DockerVolumeSummary
import io.github.tabssh.ui.adapters.DockerVolumeAdapter
import io.github.tabssh.ui.dialogs.DockerActionSheet
import io.github.tabssh.ui.dialogs.DockerErrorPresenter
import io.github.tabssh.ui.dialogs.DockerInspectDialog
import kotlinx.coroutines.launch

/**
 * Volumes destination: list with driver/mountpoint, FAB creates a named
 * volume, tap (or long-press) opens the action sheet with inspect first and
 * destructive remove last. Load failures render inline with retry.
 */
class DockerVolumesFragment : DockerPageFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var errorState: LinearLayout
    private lateinit var textError: TextView
    private lateinit var textEmpty: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var fabAction: FloatingActionButton
    private lateinit var adapter: DockerVolumeAdapter

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

        textEmpty.setText(R.string.docker_volumes_empty)
        view.findViewById<TextView>(R.id.text_empty_hint).setText(R.string.docker_volumes_empty_hint)
        view.findViewById<ImageView>(R.id.image_empty).setImageResource(R.drawable.ic_docker_volume)
        fabAction.contentDescription = getString(R.string.docker_create_volume_title)

        adapter = DockerVolumeAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        adapter.setOnItemClickListener { volume -> showVolumeMenu(volume) }
        adapter.setOnItemLongClickListener { volume -> showVolumeMenu(volume) }

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
        viewLifecycleOwner.lifecycleScope.launch {
            val result = session.transport.listVolumes()
            if (!isAdded) return@launch
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
            .setTitle(R.string.docker_create_volume_title)
            .setView(view)
            .setPositiveButton(R.string.docker_create) { _, _ ->
                val name = editName.text?.toString()?.trim().orEmpty()
                val driver = editDriver.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(
                        requireContext(), R.string.docker_error_name_required, Toast.LENGTH_SHORT
                    ).show()
                } else {
                    createVolume(name, driver.ifEmpty { null })
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun createVolume(name: String, driver: String?) {
        val current = session ?: return
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val result = current.transport.createVolume(name, driver)
            if (!isAdded) return@launch
            progressBar.visibility = View.GONE
            when (result) {
                is DockerResult.Success -> onSessionReady(current)
                else -> DockerErrorPresenter.present(requireContext(), result)
            }
        }
    }

    private fun showVolumeMenu(volume: DockerVolumeSummary) {
        if (!isAdded) return
        DockerActionSheet.show(
            requireContext(), volume.name, volume.driver,
            listOf(
                DockerActionSheet.Action(R.drawable.ic_info, getString(R.string.docker_option_inspect)) {
                    inspectVolume(volume)
                },
                DockerActionSheet.Action(
                    R.drawable.ic_clear, getString(R.string.docker_action_remove), destructive = true
                ) {
                    confirmRemove(volume)
                }
            )
        )
    }

    private fun inspectVolume(volume: DockerVolumeSummary) {
        val current = session ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = current.transport.inspectVolume(volume.name)
            if (!isAdded) return@launch
            when (result) {
                is DockerResult.Success ->
                    DockerInspectDialog.show(requireContext(), volume.name, result.value)
                else -> DockerErrorPresenter.present(requireContext(), result)
            }
        }
    }

    private fun confirmRemove(volume: DockerVolumeSummary) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(volume.name)
            .setMessage(getString(R.string.docker_remove_volume_message, volume.name))
            .setPositiveButton(R.string.delete) { _, _ -> removeVolume(volume.name) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removeVolume(name: String) {
        val current = session ?: return
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val result = current.transport.removeVolume(name)
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
        }
    }
}
