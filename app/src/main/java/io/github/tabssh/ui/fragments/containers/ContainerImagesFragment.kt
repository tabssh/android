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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.tabssh.R
import io.github.tabssh.containers.ContainerSessionManager
import io.github.tabssh.containers.transport.ContainerImageSummary
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.ui.adapters.ContainerImageAdapter
import io.github.tabssh.ui.dialogs.ContainerActionSheet
import io.github.tabssh.ui.dialogs.ContainerErrorPresenter
import io.github.tabssh.ui.dialogs.ContainerInspectDialog
import io.github.tabssh.ui.dialogs.PullImageDialog
import io.github.tabssh.ui.utils.ContainerText
import kotlinx.coroutines.launch

/**
 * Images destination: list with size/created, FAB opens the pull dialog with
 * per-layer progress, tap (or long-press) opens the action sheet with inspect
 * first and destructive remove last. Load failures render inline with retry.
 */
class ContainerImagesFragment : ContainerPageFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var errorState: LinearLayout
    private lateinit var textError: TextView
    private lateinit var textEmpty: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var fabAction: FloatingActionButton
    private lateinit var adapter: ContainerImageAdapter
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
        fabAction = view.findViewById(R.id.fab_action)

        textEmpty.setText(R.string.container_images_empty)
        view.findViewById<TextView>(R.id.text_empty_hint).setText(R.string.container_images_empty_hint)
        view.findViewById<ImageView>(R.id.image_empty).setImageResource(R.drawable.ic_container_image)
        fabAction.contentDescription = getString(R.string.container_pull_image_desc)

        view.findViewById<MaterialButton>(R.id.button_retry).setOnClickListener {
            session?.let { onSessionReady(it) }
        }

        adapter = ContainerImageAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Tap runs the most common action (inspect) in one touch; the overflow
        // button and long-press open the full action sheet.
        adapter.setOnItemClickListener { image ->
            inspectImage(
                image.repoTags.firstOrNull() ?: image.id,
                image.repoTags.firstOrNull() ?: getString(R.string.container_image_dangling)
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

    override fun onSessionReady(session: ContainerSessionManager.ContainerSession) {
        progressBar.visibility = View.VISIBLE
        errorState.visibility = View.GONE
        startLoad {
            val result = session.transport.listImages()
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

    private fun showImageMenu(image: ContainerImageSummary) {
        if (!isAdded) return
        val ref = image.repoTags.firstOrNull() ?: image.id
        val title = image.repoTags.firstOrNull()
            ?: getString(R.string.container_image_dangling)
        ContainerActionSheet.show(
            requireContext(), title, image.id.take(24),
            listOf(
                ContainerActionSheet.Action(R.drawable.ic_info, getString(R.string.container_option_inspect)) {
                    inspectImage(ref, title)
                },
                ContainerActionSheet.Action(
                    R.drawable.ic_clear, getString(R.string.container_action_remove), destructive = true
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
                is ContainerResult.Success ->
                    ContainerInspectDialog.show(requireContext(), title, result.value)
                else -> ContainerErrorPresenter.present(requireContext(), result)
            }
        }
    }

    private fun confirmRemove(ref: String, title: String) {
        if (!isAdded) return
        // The tag comes from the daemon and lands in a dialog title/message.
        val safeTitle = ContainerText.display(title)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(safeTitle)
            .setMessage(getString(R.string.container_remove_image_message, safeTitle))
            .setPositiveButton(R.string.delete) { _, _ -> removeImage(ref) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removeImage(ref: String) {
        val current = session ?: return
        // The confirm dialog can be re-shown from the sheet while the first
        // removal is still in flight; a second rmi only produces a spurious
        // "no such image" error dialog.
        if (actionInFlight) return
        actionInFlight = true
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = current.transport.removeImage(ref)
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                when (result) {
                    is ContainerResult.Success -> {
                        Toast.makeText(
                            requireContext(), R.string.container_action_success, Toast.LENGTH_SHORT
                        ).show()
                        onSessionReady(current)
                    }
                    else -> ContainerErrorPresenter.present(requireContext(), result)
                }
            } finally {
                actionInFlight = false
            }
        }
    }
}
