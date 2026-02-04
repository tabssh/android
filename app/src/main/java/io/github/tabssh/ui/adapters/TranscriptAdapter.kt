package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.terminal.recording.TranscriptManager

class TranscriptAdapter(
    private val onView: (TranscriptManager.Transcript) -> Unit,
    private val onShare: (TranscriptManager.Transcript) -> Unit,
    private val onDelete: (TranscriptManager.Transcript) -> Unit
) : ListAdapter<TranscriptManager.Transcript, TranscriptAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_transcript, parent, false)
        return ViewHolder(view, onView, onShare, onDelete)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val onView: (TranscriptManager.Transcript) -> Unit,
        private val onShare: (TranscriptManager.Transcript) -> Unit,
        private val onDelete: (TranscriptManager.Transcript) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val textName: TextView = itemView.findViewById(R.id.text_name)
        private val textInfo: TextView = itemView.findViewById(R.id.text_info)
        private val btnView: ImageButton = itemView.findViewById(R.id.btn_view)
        private val btnShare: ImageButton = itemView.findViewById(R.id.btn_share)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete)
        
        fun bind(transcript: TranscriptManager.Transcript) {
            textName.text = transcript.name
            textInfo.text = "${TranscriptManager.formatFileSize(transcript.size)} • ${TranscriptManager.formatTimestamp(transcript.timestamp)}"
            
            itemView.setOnClickListener { onView(transcript) }
            btnView.setOnClickListener { onView(transcript) }
            btnShare.setOnClickListener { onShare(transcript) }
            btnDelete.setOnClickListener { onDelete(transcript) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<TranscriptManager.Transcript>() {
        override fun areItemsTheSame(old: TranscriptManager.Transcript, new: TranscriptManager.Transcript) = 
            old.file.absolutePath == new.file.absolutePath
        override fun areContentsTheSame(old: TranscriptManager.Transcript, new: TranscriptManager.Transcript) = 
            old == new
    }
}
