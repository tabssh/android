package io.github.tabssh.terminal.recording

import android.content.Context
import io.github.tabssh.utils.VideoRecordingStorage
import io.github.tabssh.utils.logging.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Writes an [asciicast v2](https://docs.asciinema.org/manual/asciicast/v2/)
 * `.cast` file — a JSON-lines event stream of terminal output + timing.
 *
 * Companion to the mp4 side of the session video recorder
 * (TODO.AI.md item 53): SSH-tab-only, tapping the same
 * [io.github.tabssh.terminal.TermuxBridge] output hook mechanism the
 * plain-text [SessionRecorder] already uses, but through its own independent
 * `castRecorder` field so the two features (existing "Transcript" vs. this
 * new "Session Recording") never share or contend for state.
 *
 * API deliberately mirrors [SessionRecorder]'s shape (start/record/stop/
 * isRecording/getCurrentFilePath) for a consistent call-site pattern.
 */
class AsciinemaCastWriter(
    private val context: Context,
    private val connectionName: String,
    private val columns: Int,
    private val rows: Int
) {

    private companion object {
        // Same cap rationale as SessionRecorder.MAX_TRANSCRIPT_BYTES: bounds
        // worst-case storage for a runaway/looping remote output stream.
        const val MAX_CAST_BYTES = 32L * 1024 * 1024
    }

    // Guards writer/isRecording/bytesWritten/startElapsedNanos: recordOutput
    // runs on the bridge's IO read loop while start/stop run on Main.
    private val lock = Any()

    @Volatile
    private var isRecording = false
    private var currentFilename: String? = null
    private var writer: Writer? = null
    private var bytesWritten = 0L
    private var startNanos = 0L

    fun startRecording() {
        synchronized(lock) {
            if (isRecording) return

            var w: Writer? = null
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
                val sanitizedName = connectionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val filename = "cast_${sanitizedName}_$timestamp.cast"

                val out = VideoRecordingStorage.openCastOutputStream(context, filename)
                    ?: throw java.io.IOException("Unable to open output stream for $filename")
                w = OutputStreamWriter(out, Charsets.UTF_8)

                val header = JSONObject().apply {
                    put("version", 2)
                    put("width", columns)
                    put("height", rows)
                    put("timestamp", System.currentTimeMillis() / 1000)
                    put("title", connectionName)
                }
                w.write(header.toString())
                w.write("\n")
                w.flush()

                writer = w
                currentFilename = filename
                bytesWritten = 0
                startNanos = System.nanoTime()
                isRecording = true
                Logger.i("AsciinemaCastWriter", "Started cast recording: $filename")
            } catch (e: Exception) {
                try { w?.close() } catch (_: Exception) {}
                writer = null
                Logger.e("AsciinemaCastWriter", "Failed to start", e)
            }
        }
    }

    fun recordOutput(data: String) {
        // Cheap unsynchronized pre-check, matching SessionRecorder.recordOutput
        // (same "SSH read loop vs. Main thread" rationale).
        if (!isRecording) return
        synchronized(lock) {
            val w = writer ?: return
            try {
                val elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0
                val event = JSONArray().apply {
                    put(elapsedSeconds)
                    put("o")
                    put(data)
                }
                w.write(event.toString())
                w.write("\n")
                w.flush()
                bytesWritten += data.length
                if (bytesWritten >= MAX_CAST_BYTES) {
                    Logger.w("AsciinemaCastWriter", "Cast hit the ${MAX_CAST_BYTES / (1024 * 1024)}MB cap, stopping recording")
                    stopRecordingLocked()
                }
            } catch (e: Exception) {
                Logger.e("AsciinemaCastWriter", "Write failed", e)
            }
        }
    }

    fun stopRecording() {
        synchronized(lock) { stopRecordingLocked() }
    }

    /**
     * Close the cast file. Caller must hold [lock].
     */
    private fun stopRecordingLocked() {
        if (!isRecording) return

        val w = writer
        writer = null
        isRecording = false
        try { w?.close() } catch (e: Exception) {
            Logger.e("AsciinemaCastWriter", "Stop close failed", e)
        }
        currentFilename?.let { VideoRecordingStorage.finalizePendingFile(context, it) }
        Logger.i("AsciinemaCastWriter", "Stopped cast recording")
    }

    fun isRecording(): Boolean = isRecording

    fun getCurrentFilePath(): String? = synchronized(lock) { currentFilename }
}
