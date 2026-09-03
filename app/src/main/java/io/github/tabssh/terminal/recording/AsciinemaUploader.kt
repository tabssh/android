package io.github.tabssh.terminal.recording

import android.content.Context
import androidx.core.content.edit
import io.github.tabssh.network.SharedHttpClient
import io.github.tabssh.utils.VideoRecordingStorage
import io.github.tabssh.utils.logging.Logger
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Uploads a finished `.cast` file (from [AsciinemaCastWriter]) to an
 * asciinema-API-compatible server — official `asciinema.org` by default, or
 * any self-hosted instance the user points the "Asciinema Server"
 * preference (`preferences_recording.xml`'s `asciinema_server_url` key) at.
 *
 * Uses the same upload protocol as the official `asciinema` CLI: a
 * multipart POST of the cast file to `{server}/api/asciicasts`,
 * authenticated with HTTP Basic auth carrying a per-install, randomly
 * generated id as the username (blank password) — this is how the CLI
 * associates uploads with an anonymous "installation" without requiring an
 * account. The response body is the plain-text URL of the uploaded cast.
 *
 * Goes through [SharedHttpClient] (PART 9: one OkHttp client app-wide),
 * deriving a longer-timeout instance via `newBuilder()` since cast files can
 * be tens of MB for a long session.
 */
object AsciinemaUploader {

    private const val TAG = "AsciinemaUploader"
    private const val PREFS_NAME = "asciinema_uploader"
    private const val KEY_INSTALL_ID = "install_id"
    const val DEFAULT_SERVER_URL = "https://asciinema.org"

    private val uploadClient by lazy {
        SharedHttpClient.client.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Uploads [castFile] to [serverUrl] (defaults to [DEFAULT_SERVER_URL]
     * when the preference is blank). Runs blocking network IO — callers
     * must invoke on `Dispatchers.IO`. Returns the server's response URL on
     * success.
     */
    @Throws(IOException::class)
    fun upload(context: Context, castFile: File, serverUrl: String): String {
        val baseUrl = serverUrl.trim().ifBlank { DEFAULT_SERVER_URL }.trimEnd('/')
        val endpoint = "$baseUrl/api/asciicasts"
        val installId = installId(context)
        val credential = Credentials.basic(installId, "")

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "asciicast",
                castFile.name,
                castFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", credential)
            .post(body)
            .build()

        uploadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} ${response.message}")
            }
            val resultUrl = response.body?.string()?.trim()
            if (resultUrl.isNullOrBlank()) {
                throw IOException("Empty response from $baseUrl")
            }
            Logger.i(TAG, "Uploaded ${castFile.name} to $resultUrl")
            return resultUrl
        }
    }

    /**
     * Resolves [filename] (a finished `.cast` already saved via
     * [VideoRecordingStorage]) to a shareable content Uri, copies its bytes
     * into a private cache file, and uploads that copy — mirrors the
     * "resolve then read" shape [VideoRecordingStorage.shareableUriFor]'s
     * other caller ([io.github.tabssh.ui.activities.TabTerminalActivity
     * .shareRecordingFile]) already uses, since a MediaStore `content://`
     * Uri (the common case on API 29+) has no direct filesystem [File] path
     * to hand OkHttp. The cache copy is deleted once the request completes,
     * win or lose.
     */
    @Throws(IOException::class)
    fun uploadByFilename(context: Context, filename: String, legacyFile: File?, serverUrl: String): String {
        val uri = VideoRecordingStorage.shareableUriFor(context, filename, legacyFile)
            ?: throw IOException("Could not resolve $filename for upload")
        val tempFile = File(context.cacheDir, "upload_$filename")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("Could not open $filename for upload")
            return upload(context, tempFile, serverUrl)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Stable per-install random id, generated once and persisted — never
     * tied to any account/user-identifying value, matching the official
     * CLI's anonymous-upload identity model.
     */
    private fun installId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_INSTALL_ID, generated) }
        return generated
    }
}
