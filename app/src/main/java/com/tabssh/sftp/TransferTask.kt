package com.tabssh.sftp

import com.tabssh.utils.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Represents a file transfer operation with progress tracking
 * Supports pause/resume and cancellation
 */
class TransferTask(
    val id: String,
    val type: TransferType,
    val localPath: String,
    val remotePath: String,
    val totalBytes: Long,
    val listener: TransferListener? = null
) {
    
    private val _state = MutableStateFlow(TransferState.PENDING)
    val state: StateFlow<TransferState> = _state.asStateFlow()
    
    private val _bytesTransferred = AtomicLong(0)
    val bytesTransferred: Long get() = _bytesTransferred.get()
    
    private val _speed = MutableStateFlow(0L) // bytes per second
    val speed: StateFlow<Long> = _speed.asStateFlow()
    
    private val _eta = MutableStateFlow(0L) // estimated time remaining in milliseconds
    val eta: StateFlow<Long> = _eta.asStateFlow()
    
    private val cancelled = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    
    private var startTime = System.currentTimeMillis()
    private var lastProgressTime = startTime
    private var lastBytesTransferred = 0L
    
    // Transfer result
    private val _result = MutableStateFlow<TransferResult?>(null)
    val result: StateFlow<TransferResult?> = _result.asStateFlow()
    
    init {
        Logger.d("TransferTask", "Created transfer task: $type ${getDisplayName()}")
    }
    
    /**
     * Update transfer state
     */
    fun updateState(newState: TransferState) {
        val oldState = _state.value
        _state.value = newState
        
        if (newState == TransferState.ACTIVE && oldState != TransferState.ACTIVE) {
            startTime = System.currentTimeMillis()
            lastProgressTime = startTime
        }
        
        Logger.d("TransferTask", "Transfer $id state changed: $oldState -> $newState")
        listener?.onStateChanged(this, newState)
    }
    
    /**
     * Add bytes transferred and update progress
     */
    fun addBytesTransferred(bytes: Long) {
        _bytesTransferred.addAndGet(bytes)
        updateProgress()
    }
    
    /**
     * Set bytes transferred (for resume operations)
     */
    fun setBytesTransferred(bytes: Long) {
        _bytesTransferred.set(bytes)
        updateProgress()
    }
    
    private fun updateProgress() {
        val currentTime = System.currentTimeMillis()
        val currentBytes = _bytesTransferred.get()
        
        // Calculate transfer speed
        val timeDiff = currentTime - lastProgressTime
        if (timeDiff > 1000) { // Update speed every second
            val bytesDiff = currentBytes - lastBytesTransferred
            val speedBps = if (timeDiff > 0) (bytesDiff * 1000) / timeDiff else 0
            _speed.value = speedBps
            
            // Calculate ETA
            if (speedBps > 0 && totalBytes > 0) {
                val remainingBytes = totalBytes - currentBytes
                val etaMs = (remainingBytes * 1000) / speedBps
                _eta.value = etaMs
            }
            
            lastProgressTime = currentTime
            lastBytesTransferred = currentBytes
        }
    }
    
    /**
     * Notify progress to listener
     */
    fun notifyProgress() {
        listener?.onProgress(this, _bytesTransferred.get(), totalBytes)
    }
    
    /**
     * Get progress percentage (0-100)
     */
    fun getProgressPercentage(): Int {
        return if (totalBytes > 0) {
            ((bytesTransferred * 100) / totalBytes).toInt().coerceIn(0, 100)
        } else 0
    }
    
    /**
     * Get formatted progress string
     */
    fun getProgressString(): String {
        val transferred = formatBytes(bytesTransferred)
        val total = formatBytes(totalBytes)
        val percentage = getProgressPercentage()
        return "$transferred / $total ($percentage%)"
    }
    
    /**
     * Get formatted speed string
     */
    fun getSpeedString(): String {
        val currentSpeed = _speed.value
        return if (currentSpeed > 0) {
            "${formatBytes(currentSpeed)}/s"
        } else {
            "0 B/s"
        }
    }
    
    /**
     * Get formatted ETA string
     */
    fun getETAString(): String {
        val etaMs = _eta.value
        return if (etaMs > 0) {
            formatDuration(etaMs)
        } else {
            "--:--"
        }
    }
    
    /**
     * Get display name for the transfer
     */
    fun getDisplayName(): String {
        return when (type) {
            TransferType.UPLOAD -> "${File(localPath).name} → ${File(remotePath).name}"
            TransferType.DOWNLOAD -> "${File(remotePath).name} → ${File(localPath).name}"
        }
    }
    
    /**
     * Pause the transfer
     */
    fun pause() {
        if (_state.value == TransferState.ACTIVE) {
            paused.set(true)
            updateState(TransferState.PAUSED)
            Logger.d("TransferTask", "Transfer paused: $id")
            listener?.onPaused(this)
        }
    }
    
    /**
     * Resume the transfer
     */
    fun resume() {
        if (_state.value == TransferState.PAUSED) {
            paused.set(false)
            updateState(TransferState.ACTIVE)
            Logger.d("TransferTask", "Transfer resumed: $id")
            listener?.onResumed(this)
        }
    }
    
    /**
     * Cancel the transfer
     */
    fun cancel() {
        cancelled.set(true)
        updateState(TransferState.CANCELLED)
        _result.value = TransferResult.Cancelled
        Logger.d("TransferTask", "Transfer cancelled: $id")
        listener?.onCancelled(this)
    }
    
    /**
     * Complete the transfer with result
     */
    fun complete(result: TransferResult) {
        _result.value = result
        updateState(when (result) {
            is TransferResult.Success -> TransferState.COMPLETED
            is TransferResult.Error -> TransferState.ERROR
            is TransferResult.Cancelled -> TransferState.CANCELLED
        })
        
        Logger.d("TransferTask", "Transfer completed: $id with result $result")
        listener?.onCompleted(this, result)
    }
    
    // State checks
    fun isCancelled(): Boolean = cancelled.get()
    fun isPaused(): Boolean = paused.get()
    fun isActive(): Boolean = _state.value == TransferState.ACTIVE
    fun isCompleted(): Boolean = _state.value == TransferState.COMPLETED
    fun hasError(): Boolean = _state.value == TransferState.ERROR
    
    // Utility functions
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
    
    private fun formatDuration(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        
        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes % 60, seconds % 60)
            minutes > 0 -> String.format("%02d:%02d", minutes, seconds % 60)
            else -> String.format("00:%02d", seconds)
        }
    }
}

/**
 * Transfer types
 */
enum class TransferType {
    UPLOAD,
    DOWNLOAD
}

/**
 * Transfer states
 */
enum class TransferState {
    PENDING,    // Queued but not started
    ACTIVE,     // Currently transferring
    PAUSED,     // Paused by user
    COMPLETED,  // Finished successfully
    ERROR,      // Failed with error
    CANCELLED   // Cancelled by user
}

/**
 * Transfer results
 */
sealed class TransferResult {
    object Success : TransferResult()
    data class Error(val message: String) : TransferResult()
    object Cancelled : TransferResult()
}

/**
 * Transfer event listener
 */
interface TransferListener {
    fun onStateChanged(transfer: TransferTask, newState: TransferState) {}
    fun onProgress(transfer: TransferTask, bytesTransferred: Long, totalBytes: Long) {}
    fun onCompleted(transfer: TransferTask, result: TransferResult) {}
    fun onPaused(transfer: TransferTask) {}
    fun onResumed(transfer: TransferTask) {}
    fun onCancelled(transfer: TransferTask) {}
}