package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.storage.database.entities.SyncLogEntry

/**
 * Adapter for displaying [SyncLogEntry] rows in [io.github.tabssh.ui.activities.SyncLogActivity].
 * Only [SyncLogEntry.description] (a short, pre-sanitized summary) and the
 * resolution outcome are shown — never raw conflicting field values.
 */
class SyncLogAdapter(private var entries: List<SyncLogEntry>) :
    RecyclerView.Adapter<SyncLogAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textTimestamp: TextView = view.findViewById(R.id.text_sync_log_timestamp)
        val textResolution: TextView = view.findViewById(R.id.text_sync_log_resolution)
        val textDescription: TextView = view.findViewById(R.id.text_sync_log_description)
        val textDevice: TextView = view.findViewById(R.id.text_sync_log_device)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sync_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        val context = holder.itemView.context

        val dateFormat = java.text.SimpleDateFormat("MMM dd, HH:mm:ss", java.util.Locale.US)
        holder.textTimestamp.text = dateFormat.format(java.util.Date(entry.timestamp))

        holder.textResolution.text = resolutionLabel(context, entry.resolution)
        holder.textResolution.setTextColor(
            androidx.core.content.ContextCompat.getColor(context, resolutionColor(entry.resolution))
        )

        holder.textDescription.text = entry.description
        holder.textDevice.text = context.getString(R.string.sync_log_entity_and_device, entry.entityType, entry.deviceName)
    }

    override fun getItemCount() = entries.size

    fun updateEntries(newEntries: List<SyncLogEntry>) {
        val old = entries
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = newEntries.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition].id == newEntries[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition] == newEntries[newItemPosition]
        })
        entries = newEntries
        diff.dispatchUpdatesTo(this)
    }

    private fun resolutionLabel(context: android.content.Context, resolution: String): String = when (resolution) {
        SyncLogEntry.RESOLUTION_AUTO_MERGED -> context.getString(R.string.sync_log_resolution_auto_merged)
        SyncLogEntry.RESOLUTION_KEPT_LOCAL -> context.getString(R.string.sync_log_resolution_kept_local)
        SyncLogEntry.RESOLUTION_KEPT_REMOTE -> context.getString(R.string.sync_log_resolution_kept_remote)
        SyncLogEntry.RESOLUTION_KEPT_BOTH -> context.getString(R.string.sync_log_resolution_kept_both)
        SyncLogEntry.RESOLUTION_SKIPPED -> context.getString(R.string.sync_log_resolution_skipped)
        SyncLogEntry.RESOLUTION_DEFERRED -> context.getString(R.string.sync_log_resolution_deferred)
        else -> resolution
    }

    private fun resolutionColor(resolution: String): Int = when (resolution) {
        SyncLogEntry.RESOLUTION_AUTO_MERGED -> R.color.status_success
        SyncLogEntry.RESOLUTION_KEPT_LOCAL -> R.color.status_neutral
        SyncLogEntry.RESOLUTION_KEPT_REMOTE -> R.color.status_neutral
        SyncLogEntry.RESOLUTION_KEPT_BOTH -> R.color.status_success
        SyncLogEntry.RESOLUTION_SKIPPED -> R.color.status_warning
        SyncLogEntry.RESOLUTION_DEFERRED -> R.color.status_warning
        else -> R.color.status_neutral
    }
}
