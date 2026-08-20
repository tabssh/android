package io.github.tabssh.sftp

import android.content.Context
import io.github.tabssh.R
import io.github.tabssh.utils.Format
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
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
    
    // bytes per second
    private val _speed = MutableStateFlow(0L)
    val speed: StateFlow<Long> = _speed.asStateFlow()

    // estimated time remaining in milliseconds
    private val _eta = MutableStateFlow(0L)
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
        Logger.d("TransferTask", "Created transfer task: $type $sourceName -> $destinationName")
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
        // Update speed every second
        if (timeDiff > 1000) {
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
    fun getProgressString(context: Context): String = context.getString(
        R.string.transfer_progress_fmt,
        Format.size(context, bytesTransferred),
        Format.size(context, totalBytes),
        getProgressPercentage()
    )

    /**
     * Get formatted speed string
     */
    fun getSpeedString(context: Context): String = Format.rate(context, _speed.value)

    /**
     * Get formatted ETA string
     */
    fun getETAString(context: Context): String {
        val etaMs = _eta.value
        return if (etaMs > 0) {
            Format.duration(context, etaMs)
        } else {
            context.getString(R.string.transfer_eta_unknown)
        }
    }
    
    /**
     * Name of the file the bytes are read from — the raw value, for logs and
     * for callers that compose their own display text.
     */
    val sourceName: String
        get() = when (type) {
            TransferType.UPLOAD -> File(localPath).name
            TransferType.DOWNLOAD -> File(remotePath).name
        }

    /**
     * Name of the file the bytes are written to — the raw value, for logs and
     * for callers that compose their own display text.
     */
    val destinationName: String
        get() = when (type) {
            TransferType.UPLOAD -> File(remotePath).name
            TransferType.DOWNLOAD -> File(localPath).name
        }

    /**
     * Get display name for the transfer; the source/destination separator is
     * a string resource so translations control its form and direction.
     */
    fun getDisplayName(context: Context): String =
        context.getString(R.string.transfer_display_name_fmt, sourceName, destinationName)
    
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
    // Queued but not started
    PENDING,
    // Currently transferring
    ACTIVE,
    // Paused by user
    PAUSED,
    // Finished successfully
    COMPLETED,
    // Failed with error
    ERROR,
    // Cancelled by user
    CANCELLED
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
