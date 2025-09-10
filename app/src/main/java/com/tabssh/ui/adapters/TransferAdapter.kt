package com.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tabssh.databinding.ItemTransferBinding
import com.tabssh.sftp.TransferTask
import com.tabssh.sftp.TransferState
import com.tabssh.sftp.TransferType

/**
 * Adapter for displaying file transfer progress
 */
class TransferAdapter(
    private val transfers: List<TransferTask>,
    private val onTransferCancel: (TransferTask) -> Unit,
    private val onTransferPause: (TransferTask) -> Unit,
    private val onTransferResume: (TransferTask) -> Unit
) : RecyclerView.Adapter<TransferAdapter.TransferViewHolder>() {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransferViewHolder {
        val binding = ItemTransferBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TransferViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: TransferViewHolder, position: Int) {
        if (position < transfers.size) {
            holder.bind(transfers[position])
        }
    }
    
    override fun getItemCount(): Int = transfers.size
    
    inner class TransferViewHolder(
        private val binding: ItemTransferBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(transfer: TransferTask) {
            binding.apply {
                // Transfer info
                textTransferName.text = transfer.getDisplayName()
                textTransferStatus.text = getStatusText(transfer)
                textTransferProgress.text = transfer.getProgressString()
                textTransferSpeed.text = transfer.getSpeedString()
                textTransferEta.text = transfer.getETAString()
                
                // Progress bar
                progressTransfer.progress = transfer.getProgressPercentage()
                progressTransfer.isIndeterminate = transfer.state.value == TransferState.PENDING
                
                // Transfer type icon
                iconTransferType.setImageResource(
                    when (transfer.type) {
                        TransferType.UPLOAD -> com.tabssh.R.drawable.ic_upload
                        TransferType.DOWNLOAD -> com.tabssh.R.drawable.ic_download
                    }
                )
                
                // Control buttons
                setupControlButtons(transfer)
                
                // Status color
                val statusColor = when (transfer.state.value) {
                    TransferState.ACTIVE -> com.tabssh.R.color.info
                    TransferState.COMPLETED -> com.tabssh.R.color.success
                    TransferState.ERROR -> com.tabssh.R.color.error
                    TransferState.PAUSED -> com.tabssh.R.color.warning
                    TransferState.CANCELLED -> com.tabssh.R.color.gray_500
                    else -> com.tabssh.R.color.gray_500
                }
                
                textTransferStatus.setTextColor(androidx.core.content.ContextCompat.getColor(root.context, statusColor))
                
                // Accessibility
                root.contentDescription = buildString {
                    append(if (transfer.type == TransferType.UPLOAD) "Upload" else "Download")
                    append(" transfer: ${transfer.getDisplayName()}")
                    append(". Status: ${getStatusText(transfer)}")
                    append(". Progress: ${transfer.getProgressPercentage()}%")
                    if (transfer.isActive()) {
                        append(". Speed: ${transfer.getSpeedString()}")
                        append(". ETA: ${transfer.getETAString()}")
                    }
                }
            }
        }
        
        private fun setupControlButtons(transfer: TransferTask) {
            binding.apply {
                when (transfer.state.value) {
                    TransferState.ACTIVE -> {
                        btnTransferAction.text = "Pause"
                        btnTransferAction.setOnClickListener { onTransferPause(transfer) }
                        btnTransferCancel.setOnClickListener { onTransferCancel(transfer) }
                        
                        btnTransferAction.visibility = android.view.View.VISIBLE
                        btnTransferCancel.visibility = android.view.View.VISIBLE
                    }
                    TransferState.PAUSED -> {
                        btnTransferAction.text = "Resume"
                        btnTransferAction.setOnClickListener { onTransferResume(transfer) }
                        btnTransferCancel.setOnClickListener { onTransferCancel(transfer) }
                        
                        btnTransferAction.visibility = android.view.View.VISIBLE
                        btnTransferCancel.visibility = android.view.View.VISIBLE
                    }
                    TransferState.PENDING -> {
                        btnTransferAction.text = "Cancel"
                        btnTransferAction.setOnClickListener { onTransferCancel(transfer) }
                        
                        btnTransferAction.visibility = android.view.View.VISIBLE
                        btnTransferCancel.visibility = android.view.View.GONE
                    }
                    else -> {
                        // Completed, error, or cancelled
                        btnTransferAction.visibility = android.view.View.GONE
                        btnTransferCancel.visibility = android.view.View.GONE
                    }
                }
            }
        }
        
        private fun getStatusText(transfer: TransferTask): String {
            return when (transfer.state.value) {
                TransferState.PENDING -> "Pending"
                TransferState.ACTIVE -> "Transferring"
                TransferState.PAUSED -> "Paused"
                TransferState.COMPLETED -> "Completed"
                TransferState.ERROR -> "Error"
                TransferState.CANCELLED -> "Cancelled"
            }
        }
    }
}