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
import io.github.tabssh.ui.utils.DockerText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Image pull dialog: image reference input plus live
 * per-layer progress rows fed by the transport's pullImage Flow. The dialog
 * stays open during the pull; dismissing it cancels the stream.
 */
object PullImageDialog {

    /** Upper bound on distinct per-layer progress rows kept in the view tree. */
    private const val MAX_LAYER_ROWS = 200

    /** Layer ids are 12–64 hex chars; anything longer is not a real id. */
    private const val MAX_LAYER_KEY = 64

    /** Progress/error text is one line in a small dialog. */
    private const val MAX_STATUS = 200

    /**
     * True when [ref] looks like an image reference: no leading dash (which
     * `docker pull` would read as an option), no whitespace, and only the
     * characters a registry reference can legally contain.
     */
    internal fun isPlausibleRef(ref: String): Boolean {
        if (ref.isEmpty() || ref.length > 256) return false
        if (ref.startsWith("-")) return false
        // ASCII only — registry references are defined over an ASCII grammar,
        // and Kotlin's isLetterOrDigit() would admit Unicode homoglyphs.
        return ref.all { ch ->
            ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "._-/:@"
        }
    }

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
                // The reference is interpolated into a `docker pull` command
                // line; shell quoting protects against injection but not
                // against a leading dash being parsed as an option, so reject
                // anything that is not a plausible image reference.
                if (!isPlausibleRef(ref)) {
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
                    try {
                        transport.pullImage(ref).collect { event ->
                            val key = DockerText.display(event.layerId.orEmpty(), MAX_LAYER_KEY)
                            // Cap the row count — a hostile or chatty daemon can
                            // emit unbounded distinct layer ids, and one TextView
                            // per id would grow the view tree without limit.
                            val row = layerRows[key] ?: if (layerRows.size >= MAX_LAYER_ROWS) {
                                null
                            } else {
                                val text = TextView(context)
                                text.textSize = 12f
                                text.typeface = android.graphics.Typeface.MONOSPACE
                                containerLayers.addView(text)
                                layerRows[key] = text
                                text
                            }
                            val progress = if (event.totalBytes > 0) {
                                val percent = event.currentBytes * 100 / event.totalBytes
                                " $percent%"
                            } else {
                                ""
                            }
                            val prefix = if (key.isEmpty()) "" else "$key: "
                            val status = DockerText.display(event.status, MAX_STATUS)
                            row?.text = "$prefix$status$progress"
                            if (event.error != null) {
                                failed = true
                                textStatus.visibility = View.VISIBLE
                                textStatus.text = context.getString(
                                    R.string.docker_pull_failed,
                                    DockerText.display(event.error, MAX_STATUS)
                                )
                            }
                        }
                        if (!failed) {
                            textStatus.visibility = View.VISIBLE
                            textStatus.setText(R.string.docker_pull_done)
                            onDone()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // pullImage() is Flow-based: unlike the suspend transport
                        // calls it does not classify its own failures, so a dead
                        // session throws straight into this collector.
                        failed = true
                        textStatus.visibility = View.VISIBLE
                        textStatus.text = context.getString(
                            R.string.docker_pull_failed,
                            DockerText.display(e.message, MAX_STATUS)
                        )
                    } finally {
                        // Re-enable on every exit path — leaving the dialog with
                        // a dead Pull button was the previous failure mode.
                        pullButton.isEnabled = true
                        editRef.isEnabled = true
                    }
                }
            }
        }
        dialog.show()
    }
}
