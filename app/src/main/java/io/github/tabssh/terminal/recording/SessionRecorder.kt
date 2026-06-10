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
    
    private var isRecording = false
    private var currentFile: File? = null
    private var fileWriter: FileWriter? = null
    
    fun startRecording() {
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
    
    fun recordOutput(data: String) {
        if (isRecording) {
            try {
                fileWriter?.write(data)
                fileWriter?.flush()
            } catch (e: Exception) {
                Logger.e("SessionRecorder", "Write failed", e)
            }
        }
    }
    
    fun stopRecording() {
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
    fun getCurrentFilePath(): String? = currentFile?.absolutePath
}
