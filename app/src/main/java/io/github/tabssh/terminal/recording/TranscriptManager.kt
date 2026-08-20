package io.github.tabssh.terminal.recording

import android.content.Context
import io.github.tabssh.R
import io.github.tabssh.utils.Format
import io.github.tabssh.utils.logging.Logger
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages saved session transcripts
 */
object TranscriptManager {

    // Upper bound on how much of a transcript is materialised for viewing or
    // sharing. Transcripts are capped at 32 MB on disk; loading one of those
    // into a String for a TextView is an out-of-memory kill on a low-end
    // device, so only the tail is returned.
    private const val MAX_VIEW_BYTES = 1L * 1024 * 1024

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
    
    /**
     * Read a transcript for display. Files larger than [MAX_VIEW_BYTES] are
     * truncated to their tail, which is the part of a session a user is
     * actually looking for.
     */
    fun getTranscriptContent(context: Context, transcript: Transcript): String {
        return try {
            val length = transcript.file.length()
            if (length <= MAX_VIEW_BYTES) {
                transcript.file.readText()
            } else {
                RandomAccessFile(transcript.file, "r").use { raf ->
                    raf.seek(length - MAX_VIEW_BYTES)
                    val bytes = ByteArray(MAX_VIEW_BYTES.toInt())
                    raf.readFully(bytes)
                    // Drop the leading partial line: it can also carry the tail
                    // half of a split multi-byte UTF-8 character.
                    val text = String(bytes, Charsets.UTF_8)
                    val body = text.substringAfter('\n', text)
                    val banner = context.getString(
                        R.string.transcript_truncated_banner_fmt,
                        Format.size(context, MAX_VIEW_BYTES),
                        Format.size(context, length)
                    )
                    "$banner\n\n$body"
                }
            }
        } catch (e: Exception) {
            Logger.e("TranscriptManager", "Failed to read transcript", e)
            context.getString(R.string.transcript_read_error)
        }
    }
    
    fun formatTimestamp(timestamp: Long): String {
        val date = Date(timestamp)
        val format = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
        return format.format(date)
    }
}
