package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.databinding.ItemTransferBinding
import io.github.tabssh.sftp.TransferTask
import io.github.tabssh.sftp.TransferState
import io.github.tabssh.sftp.TransferType

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
                textTransferName.text = transfer.getDisplayName(root.context)
                textTransferStatus.text = root.context.getString(statusTextRes(transfer))
                textTransferProgress.text = transfer.getProgressString(root.context)
                textTransferSpeed.text = transfer.getSpeedString(root.context)
                textTransferEta.text = transfer.getETAString(root.context)
                
                // Progress bar
                progressTransfer.progress = transfer.getProgressPercentage()
                progressTransfer.isIndeterminate = transfer.state.value == TransferState.PENDING
                
                // Transfer type icon
                iconTransferType.setImageResource(
                    when (transfer.type) {
                        TransferType.UPLOAD -> io.github.tabssh.R.drawable.ic_upload
                        TransferType.DOWNLOAD -> io.github.tabssh.R.drawable.ic_download
                    }
                )
                
                // Control buttons
                setupControlButtons(transfer)
                
                // Status color
                val statusColor = when (transfer.state.value) {
                    TransferState.ACTIVE -> io.github.tabssh.R.color.status_info
                    TransferState.COMPLETED -> io.github.tabssh.R.color.status_success
                    TransferState.ERROR -> io.github.tabssh.R.color.status_error
                    TransferState.PAUSED -> io.github.tabssh.R.color.status_warning
                    TransferState.CANCELLED -> io.github.tabssh.R.color.status_neutral
                    else -> io.github.tabssh.R.color.status_neutral
                }
                
                textTransferStatus.setTextColor(androidx.core.content.ContextCompat.getColor(root.context, statusColor))
                
                // Accessibility
                root.contentDescription = buildString {
                    val kind = root.context.getString(
                        if (transfer.type == TransferType.UPLOAD) {
                            R.string.transferrow_type_upload
                        } else {
                            R.string.transferrow_type_download
                        }
                    )
                    append(
                        root.context.getString(
                            R.string.transferrow_a11y_fmt,
                            kind,
                            transfer.getDisplayName(root.context),
                            root.context.getString(statusTextRes(transfer)),
                            transfer.getProgressPercentage()
                        )
                    )
                    if (transfer.isActive()) {
                        append(
                            root.context.getString(
                                R.string.transferrow_a11y_active_fmt,
                                transfer.getSpeedString(root.context),
                                transfer.getETAString(root.context)
                            )
                        )
                    }
                }
            }
        }
        
        private fun setupControlButtons(transfer: TransferTask) {
            binding.apply {
                when (transfer.state.value) {
                    TransferState.ACTIVE -> {
                        btnTransferAction.setText(R.string.transferrow_action_pause)
                        btnTransferAction.setOnClickListener { onTransferPause(transfer) }
                        btnTransferCancel.setOnClickListener { onTransferCancel(transfer) }
                        
                        btnTransferAction.visibility = android.view.View.VISIBLE
                        btnTransferCancel.visibility = android.view.View.VISIBLE
                    }
                    TransferState.PAUSED -> {
                        btnTransferAction.setText(R.string.transferrow_action_resume)
                        btnTransferAction.setOnClickListener { onTransferResume(transfer) }
                        btnTransferCancel.setOnClickListener { onTransferCancel(transfer) }
                        
                        btnTransferAction.visibility = android.view.View.VISIBLE
                        btnTransferCancel.visibility = android.view.View.VISIBLE
                    }
                    TransferState.PENDING -> {
                        btnTransferAction.setText(R.string.cancel)
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
        
        private fun statusTextRes(transfer: TransferTask): Int {
            return when (transfer.state.value) {
                TransferState.PENDING -> R.string.transferrow_status_pending
                TransferState.ACTIVE -> R.string.transferrow_status_active
                TransferState.PAUSED -> R.string.transferrow_status_paused
                TransferState.COMPLETED -> R.string.transferrow_status_completed
                TransferState.ERROR -> R.string.transferrow_status_error
                TransferState.CANCELLED -> R.string.transferrow_status_cancelled
            }
        }
    }
}
