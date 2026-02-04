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
        
        try {
            val transcriptsDir = File(context.getExternalFilesDir(null), "Transcripts")
            transcriptsDir.mkdirs()
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val sanitizedName = connectionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            currentFile = File(transcriptsDir, "session_${sanitizedName}_${timestamp}.log")
            fileWriter = FileWriter(currentFile, true)
            
            fileWriter?.write("# TabSSH Session: $connectionName - ${Date()}\n\n")
            fileWriter?.flush()
            
            isRecording = true
            Logger.i("SessionRecorder", "Started recording")
        } catch (e: Exception) {
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
        
        try {
            fileWriter?.write("\n# Session ended: ${Date()}\n")
            fileWriter?.close()
            isRecording = false
            Logger.i("SessionRecorder", "Stopped recording")
        } catch (e: Exception) {
            Logger.e("SessionRecorder", "Stop failed", e)
        }
    }
    
    fun isRecording(): Boolean = isRecording
    fun getCurrentFilePath(): String? = currentFile?.absolutePath
}
