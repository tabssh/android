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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.tabssh.R
import io.github.tabssh.docker.DockerSessionManager
import io.github.tabssh.docker.transport.DockerImageSummary
import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.ui.adapters.DockerImageAdapter
import io.github.tabssh.ui.dialogs.DockerErrorPresenter
import io.github.tabssh.ui.dialogs.DockerInspectDialog
import io.github.tabssh.ui.dialogs.PullImageDialog
import kotlinx.coroutines.launch

/**
 * Images destination (PLAN.AI.md step 22): list with size/created, FAB opens
 * the pull dialog with per-layer progress, long-press offers inspect/remove.
 */
class DockerImagesFragment : DockerPageFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var textEmpty: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var fabAction: FloatingActionButton
    private lateinit var adapter: DockerImageAdapter

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
        textEmpty = view.findViewById(R.id.text_empty)
        progressBar = view.findViewById(R.id.progress_bar)
        fabAction = view.findViewById(R.id.fab_action)

        textEmpty.setText(R.string.docker_images_empty)
        view.findViewById<TextView>(R.id.text_empty_hint).setText(R.string.docker_images_empty_hint)
        view.findViewById<ImageView>(R.id.image_empty).setImageResource(R.drawable.ic_docker_image)
        fabAction.contentDescription = getString(R.string.docker_pull_image_desc)

        adapter = DockerImageAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        adapter.setOnItemClickListener { image -> showImageMenu(image) }
        adapter.setOnItemLongClickListener { image -> showImageMenu(image) }

        fabAction.setOnClickListener {
            val current = session ?: return@setOnClickListener
            PullImageDialog.show(requireContext(), viewLifecycleOwner, current.transport) {
                onSessionReady(current)
            }
        }

        // Base class wires sessionFlow/refreshFlow into onSessionReady.
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onSessionReady(session: DockerSessionManager.DockerSession) {
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val result = session.transport.listImages()
            if (!isAdded) return@launch
            progressBar.visibility = View.GONE
            when (result) {
                is DockerResult.Success -> {
                    adapter.updateList(result.value)
                    val empty = result.value.isEmpty()
                    recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
                    emptyState.visibility = if (empty) View.VISIBLE else View.GONE
                }
                else -> DockerErrorPresenter.present(requireContext(), result)
            }
        }
    }

    private fun showImageMenu(image: DockerImageSummary) {
        if (!isAdded) return
        val ref = image.repoTags.firstOrNull() ?: image.id
        val title = image.repoTags.firstOrNull()
            ?: getString(R.string.docker_image_dangling)
        val options = arrayOf(
            getString(R.string.docker_option_inspect),
            getString(R.string.docker_action_remove)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> inspectImage(ref, title)
                    1 -> confirmRemove(ref, title)
                }
            }
            .show()
    }

    private fun inspectImage(ref: String, title: String) {
        val current = session ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = current.transport.inspectImage(ref)
            if (!isAdded) return@launch
            when (result) {
                is DockerResult.Success ->
                    DockerInspectDialog.show(requireContext(), title, result.value)
                else -> DockerErrorPresenter.present(requireContext(), result)
            }
        }
    }

    private fun confirmRemove(ref: String, title: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(getString(R.string.docker_remove_image_message, title))
            .setPositiveButton(R.string.delete) { _, _ -> removeImage(ref) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removeImage(ref: String) {
        val current = session ?: return
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val result = current.transport.removeImage(ref)
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
