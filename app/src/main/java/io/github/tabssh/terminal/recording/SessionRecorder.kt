package io.github.tabssh.terminal.recording

import android.content.Context
import io.github.tabssh.utils.logging.Logger
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class SessionRecorder(
    private val context: Context,
    private val connectionName: String
) {

    private companion object {
        // Hard ceiling on a single transcript. Recording is fed from the SSH
        // read loop, so a `yes` or a runaway log tail would otherwise fill the
        // device's storage; at the cap we close the file cleanly instead.
        const val MAX_TRANSCRIPT_BYTES = 32L * 1024 * 1024
    }

    // Guards fileWriter, isRecording and bytesWritten: recordOutput runs on the
    // bridge's IO read loop while start/stopRecording are invoked from Main.
    private val lock = Any()

    @Volatile
    private var isRecording = false
    private var currentFile: File? = null
    private var fileWriter: FileWriter? = null
    private var bytesWritten = 0L

    fun startRecording() {
        synchronized(lock) {
            if (isRecording) return

            var w: FileWriter? = null
            try {
                val transcriptsDir = File(context.getExternalFilesDir(null), "Transcripts")
                transcriptsDir.mkdirs()

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val sanitizedName = connectionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                currentFile = File(transcriptsDir, "session_${sanitizedName}_${timestamp}.log")
                w = FileWriter(currentFile, true)

                w.write("# TabSSH Session: $connectionName - ${Date()}\n\n")
                w.flush()

                fileWriter = w
                bytesWritten = 0
                isRecording = true
                Logger.i("SessionRecorder", "Started recording")
            } catch (e: Exception) {
                // If FileWriter was allocated but the initial write/flush failed,
                // release the underlying fd immediately so a retry does not leak.
                try { w?.close() } catch (_: Exception) {}
                fileWriter = null
                Logger.e("SessionRecorder", "Failed to start", e)
            }
        }
    }

    fun recordOutput(data: String) {
        // Cheap unsynchronized pre-check on a volatile read: the common case is
        // "not recording", and taking the lock for every read-loop chunk would
        // put the SSH data path behind the UI thread's start/stop calls.
        if (!isRecording) return
        synchronized(lock) {
            val w = fileWriter ?: return
            try {
                w.write(data)
                w.flush()
                bytesWritten += data.length
                if (bytesWritten >= MAX_TRANSCRIPT_BYTES) {
                    Logger.w("SessionRecorder", "Transcript hit the ${MAX_TRANSCRIPT_BYTES / (1024 * 1024)}MB cap, stopping recording")
                    stopRecordingLocked()
                }
            } catch (e: Exception) {
                Logger.e("SessionRecorder", "Write failed", e)
            }
        }
    }

    fun stopRecording() {
        synchronized(lock) { stopRecordingLocked() }
    }

    /**
     * Close the transcript. Caller must hold [lock].
     */
    private fun stopRecordingLocked() {
        if (!isRecording) return

        // Always flip the flag and close the fd, even if the trailing write
        // fails — leaving isRecording=true would block any restart and keep
        // the file handle open until process death.
        val w = fileWriter
        fileWriter = null
        isRecording = false
        try {
            w?.write("\n# Session ended: ${Date()}\n")
        } catch (e: Exception) {
            Logger.e("SessionRecorder", "Stop write failed", e)
        }
        try { w?.close() } catch (e: Exception) {
            Logger.e("SessionRecorder", "Stop close failed", e)
        }
        Logger.i("SessionRecorder", "Stopped recording")
    }

    fun isRecording(): Boolean = isRecording

    fun getCurrentFilePath(): String? = synchronized(lock) { currentFile?.absolutePath }
}
