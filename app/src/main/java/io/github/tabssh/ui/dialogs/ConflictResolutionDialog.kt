package io.github.tabssh.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.github.tabssh.R
import io.github.tabssh.sync.models.Conflict
import io.github.tabssh.sync.models.ConflictResolution
import io.github.tabssh.sync.models.ConflictResolutionOption
import io.github.tabssh.utils.logging.Logger

class ConflictResolutionDialog(
    private val context: Context,
    private val conflicts: List<Conflict>,
    private val onResolved: (List<ConflictResolution>) -> Unit
) {

    companion object {
        private const val TAG = "ConflictResolutionDialog"
    }

    private val resolutions = mutableListOf<ConflictResolution>()
    private var currentConflictIndex = 0

    fun show() {
        if (conflicts.isEmpty()) {
            Logger.d(TAG, "No conflicts to resolve")
            onResolved(emptyList())
            return
        }

        showNextConflict()
    }

    private fun showNextConflict() {
        if (currentConflictIndex >= conflicts.size) {
            applyResolutions()
            return
        }

        val conflict = conflicts[currentConflictIndex]
        val dialog = createConflictDialog(conflict)
        dialog.show()
    }

    private fun createConflictDialog(conflict: Conflict): AlertDialog {
        val view = LayoutInflater.from(context).inflate(
            R.layout.dialog_conflict_resolution,
            null
        )

        val titleText = view.findViewById<TextView>(R.id.conflict_title)
        val entityTypeText = view.findViewById<TextView>(R.id.conflict_entity_type)
        val fieldNameText = view.findViewById<TextView>(R.id.field_name)
        val localValueText = view.findViewById<TextView>(R.id.local_value)
        val remoteValueText = view.findViewById<TextView>(R.id.remote_value)
        val resolutionGroup = view.findViewById<RadioGroup>(R.id.resolution_options)

        titleText.text = "Sync Conflict: ${conflict.conflictType}"
        entityTypeText.text = when (conflict.entityType) {
            "connection" -> "Connection: ${conflict.entityId}"
            "key" -> "SSH Key: ${conflict.entityId}"
            "theme" -> "Theme: ${conflict.entityId}"
            "hostkey" -> "Host Key: ${conflict.entityId}"
            else -> "Item: ${conflict.entityId}"
        }

        fieldNameText.text = "Field: ${conflict.field ?: "Multiple fields"}"
        localValueText.text = "Local: ${formatValue(conflict.localValue)} (${formatTimestamp(conflict.localTimestamp)})"
        remoteValueText.text = "Remote: ${formatValue(conflict.remoteValue)} (${formatTimestamp(conflict.remoteTimestamp)})"

        return AlertDialog.Builder(context)
            .setView(view)
            .setTitle("Resolve Conflict (${currentConflictIndex + 1}/${conflicts.size})")
            .setPositiveButton("Apply") { _, _ ->
                val selectedId = resolutionGroup.checkedRadioButtonId
                val resolutionOption = when (selectedId) {
                    R.id.radio_keep_local -> ConflictResolutionOption.KEEP_LOCAL
                    R.id.radio_keep_remote -> ConflictResolutionOption.KEEP_REMOTE
                    R.id.radio_keep_both -> ConflictResolutionOption.KEEP_BOTH
                    R.id.radio_skip -> ConflictResolutionOption.SKIP
                    else -> ConflictResolutionOption.KEEP_LOCAL
                }

                resolutions.add(ConflictResolution(
                    conflict = conflict,
                    resolution = resolutionOption,
                    applyToAll = false
                ))
                currentConflictIndex++
                showNextConflict()
            }
            .setNegativeButton("Keep All Local") { _, _ ->
                resolveAllRemaining(ConflictResolutionOption.KEEP_LOCAL)
            }
            .setNeutralButton("Keep All Remote") { _, _ ->
                resolveAllRemaining(ConflictResolutionOption.KEEP_REMOTE)
            }
            .setCancelable(false)
            .create()
    }

    private fun resolveAllRemaining(resolutionOption: ConflictResolutionOption) {
        for (i in currentConflictIndex until conflicts.size) {
            resolutions.add(ConflictResolution(
                conflict = conflicts[i],
                resolution = resolutionOption,
                applyToAll = true
            ))
        }
        applyResolutions()
    }

    private fun applyResolutions() {
        Logger.d(TAG, "Applied ${resolutions.size} conflict resolutions")
        onResolved(resolutions)
    }

    private fun formatValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> if (value.length > 50) value.take(47) + "..." else value
            is Number -> value.toString()
            is Boolean -> value.toString()
            else -> value.toString()
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp == 0L) return "unknown"
        val timeDiff = System.currentTimeMillis() - timestamp
        return when {
            timeDiff < 60_000L -> "just now"
            timeDiff < 3600_000L -> "${timeDiff / 60_000}m ago"
            timeDiff < 86400_000L -> "${timeDiff / 3600_000}h ago"
            else -> "${timeDiff / 86400_000}d ago"
        }
    }
}
