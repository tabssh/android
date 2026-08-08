package io.github.tabssh.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.docker.transport.DockerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Image pull dialog (PLAN.AI.md step 23): image reference input plus live
 * per-layer progress rows fed by the transport's pullImage Flow. The dialog
 * stays open during the pull; dismissing it cancels the stream.
 */
object PullImageDialog {

    /** Show the pull dialog; [onDone] fires after a successful pull. */
    fun show(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        transport: DockerTransport,
        onDone: () -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_pull_image, null)
        val editRef = view.findViewById<TextInputEditText>(R.id.edit_ref)
        val containerLayers = view.findViewById<LinearLayout>(R.id.container_layers)
        val textStatus = view.findViewById<TextView>(R.id.text_status)

        var pullJob: Job? = null
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.docker_pull_title)
            .setView(view)
            .setPositiveButton(R.string.docker_pull_action, null)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { pullJob?.cancel() }
            .create()

        dialog.setOnShowListener {
            val pullButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            // Manual click handler so the dialog stays open while pulling.
            pullButton.setOnClickListener {
                val ref = editRef.text?.toString()?.trim().orEmpty()
                if (ref.isEmpty()) {
                    Toast.makeText(context, R.string.docker_pull_error_ref, Toast.LENGTH_SHORT)
                        .show()
                    return@setOnClickListener
                }
                pullButton.isEnabled = false
                editRef.isEnabled = false
                containerLayers.removeAllViews()
                textStatus.visibility = View.GONE

                // One progress row per layer id, plus one row for global events.
                val layerRows = mutableMapOf<String, TextView>()
                var failed = false
                pullJob = lifecycleOwner.lifecycleScope.launch {
                    transport.pullImage(ref).collect { event ->
                        val key = event.layerId ?: ""
                        val row = layerRows.getOrPut(key) {
                            val text = TextView(context)
                            text.textSize = 12f
                            text.typeface = android.graphics.Typeface.MONOSPACE
                            containerLayers.addView(text)
                            text
                        }
                        val progress = if (event.totalBytes > 0) {
                            val percent = event.currentBytes * 100 / event.totalBytes
                            " $percent%"
                        } else {
                            ""
                        }
                        val prefix = if (key.isEmpty()) "" else "$key: "
                        row.text = "$prefix${event.status}$progress"
                        if (event.error != null) {
                            failed = true
                            textStatus.visibility = View.VISIBLE
                            textStatus.text =
                                context.getString(R.string.docker_pull_failed, event.error)
                        }
                    }
                    if (!failed) {
                        textStatus.visibility = View.VISIBLE
                        textStatus.setText(R.string.docker_pull_done)
                        onDone()
                    }
                    pullButton.isEnabled = true
                    editRef.isEnabled = true
                }
            }
        }
        dialog.show()
    }
}
