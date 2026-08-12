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
import io.github.tabssh.R
import io.github.tabssh.docker.DockerSessionManager
import io.github.tabssh.docker.transport.DockerImageSummary
import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.ui.adapters.DockerImageAdapter
import io.github.tabssh.ui.dialogs.DockerActionSheet
import io.github.tabssh.ui.dialogs.DockerErrorPresenter
import io.github.tabssh.ui.dialogs.DockerInspectDialog
import io.github.tabssh.ui.dialogs.PullImageDialog
import kotlinx.coroutines.launch

/**
 * Images destination: list with size/created, FAB opens the pull dialog with
 * per-layer progress, tap (or long-press) opens the action sheet with inspect
 * first and destructive remove last. Load failures render inline with retry.
 */
class DockerImagesFragment : DockerPageFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var errorState: LinearLayout
    private lateinit var textError: TextView
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
        errorState = view.findViewById(R.id.error_state)
        textError = view.findViewById(R.id.text_error)
        textEmpty = view.findViewById(R.id.text_empty)
        progressBar = view.findViewById(R.id.progress_bar)
        fabAction = view.findViewById(R.id.fab_action)

        textEmpty.setText(R.string.docker_images_empty)
        view.findViewById<TextView>(R.id.text_empty_hint).setText(R.string.docker_images_empty_hint)
        view.findViewById<ImageView>(R.id.image_empty).setImageResource(R.drawable.ic_docker_image)
        fabAction.contentDescription = getString(R.string.docker_pull_image_desc)

        view.findViewById<MaterialButton>(R.id.button_retry).setOnClickListener {
            session?.let { onSessionReady(it) }
        }

        adapter = DockerImageAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Tap runs the most common action (inspect) in one touch; the overflow
        // button and long-press open the full action sheet.
        adapter.setOnItemClickListener { image ->
            inspectImage(
                image.repoTags.firstOrNull() ?: image.id,
                image.repoTags.firstOrNull() ?: getString(R.string.docker_image_dangling)
            )
        }
        adapter.setOnMoreClickListener { image -> showImageMenu(image) }
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
        errorState.visibility = View.GONE
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
                else -> {
                    recyclerView.visibility = View.GONE
                    emptyState.visibility = View.GONE
                    errorState.visibility = View.VISIBLE
                    textError.text = DockerErrorPresenter.messageFor(requireContext(), result)
                }
            }
        }
    }

    private fun showImageMenu(image: DockerImageSummary) {
        if (!isAdded) return
        val ref = image.repoTags.firstOrNull() ?: image.id
        val title = image.repoTags.firstOrNull()
            ?: getString(R.string.docker_image_dangling)
        DockerActionSheet.show(
            requireContext(), title, image.id.take(24),
            listOf(
                DockerActionSheet.Action(R.drawable.ic_info, getString(R.string.docker_option_inspect)) {
                    inspectImage(ref, title)
                },
                DockerActionSheet.Action(
                    R.drawable.ic_clear, getString(R.string.docker_action_remove), destructive = true
                ) {
                    confirmRemove(ref, title)
                }
            )
        )
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
