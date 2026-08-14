package io.github.tabssh.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.ui.utils.DockerText
import org.json.JSONArray
import org.json.JSONObject

/**
 * Scrollable monospace viewer for raw docker inspect JSON (containers,
 * images, volumes, networks). Pretty-prints when the payload parses.
 */
object DockerInspectDialog {

    /** Show [json] under [title], pretty-printed when possible. */
    fun show(context: Context, title: String, json: String) {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_docker_inspect, null)
        // Both the title and the payload come from the remote daemon — strip
        // control/bidi characters and cap the blob so a pathological inspect
        // response cannot reorder the dialog or exhaust memory.
        view.findViewById<TextView>(R.id.text_inspect).text =
            DockerText.block(prettyPrint(json))
        MaterialAlertDialogBuilder(context)
            .setTitle(DockerText.display(title))
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    /** 2-space indented JSON, or the raw text when it does not parse. */
    private fun prettyPrint(json: String): String {
        val trimmed = json.trim()
        return try {
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
                else -> json
            }
        } catch (_: Exception) {
            json
        }
    }
}
