package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.terminal.recording.TranscriptManager
import io.github.tabssh.utils.Format

/** What kind of saved artifact a Recordings-browser row represents. */
enum class RecordingKind { VIDEO, CAST, TRANSCRIPT }

/**
 * One row in the unified Recordings browser — a session video (mp4), a
 * terminal cast (`.cast`), or a plain-text session transcript.
 * [transcript] is non-null only for [RecordingKind.TRANSCRIPT] rows, which
 * live in app-private storage managed by [TranscriptManager] rather than
 * MediaStore.
 */
data class RecordingItem(
    val kind: RecordingKind,
    val name: String,
    val size: Long,
    val timestamp: Long,
    val transcript: TranscriptManager.Transcript? = null,
)

class RecordingAdapter(
    private val onOpen: (RecordingItem) -> Unit,
    private val onDelete: (RecordingItem) -> Unit
) : ListAdapter<RecordingItem, RecordingAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
        return ViewHolder(view, onOpen, onDelete)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val onOpen: (RecordingItem) -> Unit,
        private val onDelete: (RecordingItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val iconType: ImageView = itemView.findViewById(R.id.icon_type)
        private val textName: TextView = itemView.findViewById(R.id.text_name)
        private val textInfo: TextView = itemView.findViewById(R.id.text_info)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete)

        fun bind(item: RecordingItem) {
            iconType.setImageResource(
                when (item.kind) {
                    RecordingKind.VIDEO -> R.drawable.ic_videocam
                    RecordingKind.CAST -> R.drawable.ic_play
                    RecordingKind.TRANSCRIPT -> R.drawable.ic_file_text
                }
            )
            textName.text = item.name
            textInfo.text = "${Format.size(itemView.context, item.size)} • ${TranscriptManager.formatTimestamp(item.timestamp)}"

            itemView.setOnClickListener { onOpen(item) }
            btnDelete.setOnClickListener { onDelete(item) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<RecordingItem>() {
        override fun areItemsTheSame(old: RecordingItem, new: RecordingItem) =
            old.kind == new.kind && old.name == new.name
        override fun areContentsTheSame(old: RecordingItem, new: RecordingItem) =
            old == new
    }
}
