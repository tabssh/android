package io.github.tabssh.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.sync.models.Conflict
import io.github.tabssh.sync.models.ConflictResolution
import io.github.tabssh.sync.models.ConflictResolutionOption
import io.github.tabssh.sync.models.ConflictType

class ConflictResolutionDialog(
    private val context: Context,
    private val conflicts: List<Conflict>,
    private val onResolved: (List<ConflictResolution>) -> Unit
) {

    private val resolutions = mutableListOf<ConflictResolution>()
    private var currentConflictIndex = 0

    fun show() {
        if (conflicts.isEmpty()) {
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
        val radioKeepLocal = view.findViewById<RadioButton>(R.id.radio_keep_local)
        val radioKeepRemote = view.findViewById<RadioButton>(R.id.radio_keep_remote)
        val radioKeepBoth = view.findViewById<RadioButton>(R.id.radio_keep_both)
        val radioSkip = view.findViewById<RadioButton>(R.id.radio_skip)

        titleText.text = when (conflict.conflictType) {
            ConflictType.FIELD_MODIFIED_BOTH_SIDES -> context.getString(R.string.conflict_type_field_modified)
            ConflictType.DELETED_MODIFIED -> context.getString(R.string.conflict_type_deleted_modified)
            ConflictType.CREATED_DUPLICATE -> context.getString(R.string.conflict_type_created_duplicate)
            ConflictType.PREFERENCE_DIVERGED -> context.getString(R.string.conflict_type_preference_diverged)
        }
        entityTypeText.text = when (conflict.entityType) {
            "connection" -> context.getString(R.string.conflict_entity_connection, conflict.entityId)
            "key" -> context.getString(R.string.conflict_entity_key, conflict.entityId)
            "theme" -> context.getString(R.string.conflict_entity_theme, conflict.entityId)
            "host_key" -> context.getString(R.string.conflict_entity_host_key, conflict.entityId)
            else -> context.getString(R.string.conflict_entity_other, conflict.entityId)
        }

        fieldNameText.text = conflict.field?.let { context.getString(R.string.conflict_field_named, it) }
            ?: context.getString(R.string.conflict_field_multiple)
        localValueText.text = context.getString(
            R.string.conflict_local_value, formatValue(conflict.localValue), formatTimestamp(conflict.localTimestamp)
        )
        remoteValueText.text = context.getString(
            R.string.conflict_remote_value, formatValue(conflict.remoteValue), formatTimestamp(conflict.remoteTimestamp)
        )

        // Only the resolutions valid for this conflict's entity/conflict type
        // are shown — e.g. host_key never offers keep-both.
        val availableOptions = conflict.getResolutionOptions()
        radioKeepLocal.visibility = if (ConflictResolutionOption.KEEP_LOCAL in availableOptions) android.view.View.VISIBLE else android.view.View.GONE
        radioKeepRemote.visibility = if (ConflictResolutionOption.KEEP_REMOTE in availableOptions) android.view.View.VISIBLE else android.view.View.GONE
        radioKeepBoth.visibility = if (ConflictResolutionOption.KEEP_BOTH in availableOptions) android.view.View.VISIBLE else android.view.View.GONE
        radioSkip.visibility = if (ConflictResolutionOption.SKIP in availableOptions) android.view.View.VISIBLE else android.view.View.GONE

        // Preselect whichever side has the newer timestamp so "Apply" without
        // touching anything does the last-write-wins-correct thing.
        resolutionGroup.check(
            when (conflict.preselectedResolution()) {
                ConflictResolutionOption.KEEP_REMOTE -> R.id.radio_keep_remote
                else -> R.id.radio_keep_local
            }
        )

        return MaterialAlertDialogBuilder(context)
            .setView(view)
            .setTitle(context.getString(R.string.conflict_dialog_title_format, currentConflictIndex + 1, conflicts.size))
            .setPositiveButton(R.string.conflict_button_apply) { _, _ ->
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
            .setNegativeButton(R.string.conflict_button_keep_all_local) { _, _ ->
                resolveAllRemaining(ConflictResolutionOption.KEEP_LOCAL)
            }
            .setNeutralButton(R.string.conflict_button_keep_all_remote) { _, _ ->
                resolveAllRemaining(ConflictResolutionOption.KEEP_REMOTE)
            }
            .setCancelable(false)
            .create()
    }

    private fun resolveAllRemaining(resolutionOption: ConflictResolutionOption) {
        for (i in currentConflictIndex until conflicts.size) {
            val conflict = conflicts[i]
            // Never force an option a conflict doesn't actually offer (e.g.
            // "Keep All Remote" on a host_key conflict still resolves to
            // keep-remote, which host_key does support).
            val effective = if (resolutionOption in conflict.getResolutionOptions()) {
                resolutionOption
            } else {
                conflict.preselectedResolution()
            }
            resolutions.add(ConflictResolution(
                conflict = conflict,
                resolution = effective,
                applyToAll = true
            ))
        }
        applyResolutions()
    }

    private fun applyResolutions() {
        onResolved(resolutions)
    }

    private fun formatValue(value: Any?): String {
        return when (value) {
            null -> context.getString(R.string.conflict_value_null)
            is String -> if (value.length > 50) value.take(47) + "..." else value
            is Number -> value.toString()
            is Boolean -> value.toString()
            else -> value.toString()
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp == 0L) return context.getString(R.string.conflict_timestamp_unknown)
        val timeDiff = System.currentTimeMillis() - timestamp
        return when {
            timeDiff < 60_000L -> context.getString(R.string.conflict_timestamp_just_now)
            timeDiff < 3600_000L -> context.getString(R.string.conflict_timestamp_minutes_ago, (timeDiff / 60_000).toInt())
            timeDiff < 86400_000L -> context.getString(R.string.conflict_timestamp_hours_ago, (timeDiff / 3600_000).toInt())
            else -> context.getString(R.string.conflict_timestamp_days_ago, (timeDiff / 86400_000).toInt())
        }
    }
}
