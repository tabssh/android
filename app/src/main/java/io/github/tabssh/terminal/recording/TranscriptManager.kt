package io.github.tabssh.terminal.recording

import android.content.Context
import io.github.tabssh.utils.logging.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages saved session transcripts
 */
object TranscriptManager {
    
    data class Transcript(
        val file: File,
        val name: String,
        val size: Long,
        val timestamp: Long
    )
    
    fun getTranscriptsDirectory(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "Transcripts")
        dir.mkdirs()
        return dir
    }
    
    fun getAllTranscripts(context: Context): List<Transcript> {
        val dir = getTranscriptsDirectory(context)
        return dir.listFiles { file -> file.extension == "log" }
            ?.map { file ->
                Transcript(
                    file = file,
                    name = file.nameWithoutExtension,
                    size = file.length(),
                    timestamp = file.lastModified()
                )
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }
    
    fun deleteTranscript(transcript: Transcript): Boolean {
        return try {
            transcript.file.delete()
        } catch (e: Exception) {
            Logger.e("TranscriptManager", "Failed to delete transcript", e)
            false
        }
    }
    
    fun getTranscriptContent(transcript: Transcript): String {
        return try {
            transcript.file.readText()
        } catch (e: Exception) {
            Logger.e("TranscriptManager", "Failed to read transcript", e)
            "Error reading transcript"
        }
    }
    
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
    
    fun formatTimestamp(timestamp: Long): String {
        val date = Date(timestamp)
        val format = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
        return format.format(date)
    }
}
