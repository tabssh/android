package io.github.tabssh.utils.paste

import io.github.tabssh.network.readBoundedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

interface PasteProvider {
    /** Upload [content] and return the public URL. Throws on failure. */
    suspend fun upload(title: String, content: String): String
}

internal val sharedHttpClient: OkHttpClient by lazy {
    io.github.tabssh.network.SharedHttpClient.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

class MicroBinProvider(private val baseUrl: String) : PasteProvider {
    override suspend fun upload(title: String, content: String): String = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val textType = "text/plain".toMediaType()
        // MicroBin: POST /upload (multipart/form-data).
        // Server returns 302 → /upload/{slug}. OkHttp follows the redirect;
        // response.request.url is the final view URL — that is the shareable link.
        // expiration=0 means "never expire" (stored as Unix timestamp 0 in the DB).
        // uploader_password must be present even when no server password is set.
        // privacy=0 → public paste.
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("content", null, content.toRequestBody(textType))
            .addFormDataPart("title", null, title.toRequestBody(textType))
            .addFormDataPart("expiration", null, "0".toRequestBody(textType))
            .addFormDataPart("burn_after", null, "0".toRequestBody(textType))
            .addFormDataPart("syntax_highlight", null, "plain".toRequestBody(textType))
            .addFormDataPart("privacy", null, "0".toRequestBody(textType))
            .addFormDataPart("uploader_password", null, "".toRequestBody(textType))
            .addFormDataPart("encrypt_client", null, "false".toRequestBody(textType))
            .addFormDataPart("encrypted_random_key", null, "".toRequestBody(textType))
            .addFormDataPart("random_key", null, "".toRequestBody(textType))
            .addFormDataPart("plain_key", null, "".toRequestBody(textType))
            .build()
        val request = Request.Builder()
            .url("$base/upload")
            .post(requestBody)
            .build()
        sharedHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("MicroBin upload failed: HTTP ${response.code}")
            response.request.url.toString().also { url ->
                if (!url.startsWith("http")) throw Exception("MicroBin returned unexpected response: $url")
            }
        }
    }
}

class LenpasteProvider(private val baseUrl: String) : PasteProvider {
    override suspend fun upload(title: String, content: String): String = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        // Lenpaste API v1: POST /api/v1/new
        // Returns JSON: {"id":"<pasteId>"} — NO url field.
        // Paste view URL is constructed as ${base}/${id}.
        val requestBody = FormBody.Builder()
            .add("title", title)
            .add("body", content)
            .build()
        val request = Request.Builder()
            .url("$base/api/v1/new")
            .post(requestBody)
            .build()
        val responseBody = sharedHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Lenpaste upload failed: HTTP ${response.code}")
            response.body?.readBoundedText(1024 * 1024L) ?: throw Exception("Lenpaste returned empty response")
        }
        val id = JSONObject(responseBody).getString("id")
        "$base/$id".also { url ->
            if (!url.startsWith("http")) throw Exception("Lenpaste returned unexpected id: $id")
        }
    }
}

class StikkedProvider(private val baseUrl: String) : PasteProvider {
    override suspend fun upload(title: String, content: String): String = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val requestBody = FormBody.Builder()
            .add("text", content)
            .add("title", title)
            .add("lang", "text")
            .add("expire", "0")
            .build()
        val request = Request.Builder()
            .url("$base/api/create")
            .post(requestBody)
            .build()
        sharedHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Stikked upload failed: HTTP ${response.code}")
            val url = response.body?.readBoundedText(1024 * 1024L)?.trim() ?: throw Exception("Stikked returned empty response")
            if (!url.startsWith("http")) throw Exception("Stikked returned unexpected response: $url")
            url
        }
    }
}

class PastebinProvider(private val apiKey: String, private val baseUrl: String) : PasteProvider {
    override suspend fun upload(title: String, content: String): String = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val requestBody = FormBody.Builder()
            .add("api_dev_key", apiKey)
            .add("api_option", "paste")
            .add("api_paste_code", content)
            .add("api_paste_name", title)
            .add("api_paste_private", "1")
            .add("api_paste_expire_date", "1W")
            .build()
        val request = Request.Builder()
            .url("$base/api/api_post.php")
            .post(requestBody)
            .build()
        val responseText = sharedHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Pastebin upload failed: HTTP ${response.code}")
            response.body?.readBoundedText(1024 * 1024L)?.trim() ?: throw Exception("Pastebin returned empty response")
        }
        if (responseText.startsWith("Bad API request")) throw Exception(responseText)
        responseText
    }
}

class CairnbinProvider(private val baseUrl: String) : PasteProvider {
    override suspend fun upload(title: String, content: String): String = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        // Cairnbin's native API requires the double-submit CSRF token: the csrf_token
        // cookie from any page must be echoed in the X-CSRF-Token header.
        val csrf = sharedHttpClient.newCall(Request.Builder().url("$base/").get().build()).execute().use { response ->
            response.headers("Set-Cookie")
                .firstOrNull { it.startsWith("csrf_token=") }
                ?.substringAfter("csrf_token=")?.substringBefore(';')
        } ?: throw Exception("Cairnbin did not issue a CSRF token")
        val payload = JSONObject()
            .put("content", content)
            .put("title", title)
            .put("language", "text")
            .put("expires_in", "never")
            .toString()
        val request = Request.Builder()
            .url("$base/pastes")
            .header("Accept", "application/json")
            .header("Cookie", "csrf_token=$csrf")
            .header("X-CSRF-Token", csrf)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val body = sharedHttpClient.newCall(request).execute().use { response ->
            val text = response.body?.readBoundedText(1024 * 1024L).orEmpty()
            if (!response.isSuccessful) throw Exception("Cairnbin upload failed: HTTP ${response.code}")
            text
        }
        val link = JSONObject(body).optJSONObject("data")?.optString("link").orEmpty()
        if (!link.startsWith("http")) throw Exception("Cairnbin returned unexpected response")
        link
    }
}

class HastebinProvider(private val baseUrl: String) : PasteProvider {
    override suspend fun upload(title: String, content: String): String = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        // haste-server: POST /documents with the raw body, answers {"key":"..."}
        val request = Request.Builder()
            .url("$base/documents")
            .post(content.toRequestBody("text/plain".toMediaType()))
            .build()
        val body = sharedHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Hastebin upload failed: HTTP ${response.code}")
            response.body?.readBoundedText(1024 * 1024L) ?: throw Exception("Hastebin returned empty response")
        }
        val key = JSONObject(body).optString("key")
        if (key.isBlank()) throw Exception("Hastebin returned no key")
        "$base/$key"
    }
}

class DpasteProvider(private val baseUrl: String) : PasteProvider {
    override suspend fun upload(title: String, content: String): String = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val requestBody = FormBody.Builder()
            .add("content", content)
            .add("lexer", "_text")
            .add("format", "url")
            .add("expires", "604800")
            .build()
        val request = Request.Builder().url("$base/api/").post(requestBody).build()
        sharedHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("dpaste upload failed: HTTP ${response.code}")
            // The v2 API answers the bare URL, sometimes JSON-quoted
            val url = response.body?.readBoundedText(1024 * 1024L)?.trim()?.trim('"')
                ?: throw Exception("dpaste returned empty response")
            if (!url.startsWith("http")) throw Exception("dpaste returned unexpected response: $url")
            url
        }
    }
}

class NullPointerProvider(private val baseUrl: String) : PasteProvider {
    override suspend fun upload(title: String, content: String): String = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        // 0x0.st style: multipart "file" part, answers the bare URL. 0x0.st rejects
        // requests without a User-Agent, so set one explicitly.
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "tabssh.txt", content.toRequestBody("text/plain".toMediaType()))
            .addFormDataPart("expires", "168")
            .build()
        val request = Request.Builder()
            .url("$base/")
            .header("User-Agent", "TabSSH")
            .post(requestBody)
            .build()
        sharedHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("0x0.st upload failed: HTTP ${response.code}")
            val url = response.body?.readBoundedText(1024 * 1024L)?.trim()
                ?: throw Exception("0x0.st returned empty response")
            if (!url.startsWith("http")) throw Exception("0x0.st returned unexpected response: $url")
            url
        }
    }
}

class TermbinProvider(private val hostPort: String) : PasteProvider {
    override suspend fun upload(title: String, content: String): String = withContext(Dispatchers.IO) {
        val (host, port) = parseHostPort(hostPort)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 15_000)
            socket.soTimeout = 15_000
            // termbin/fiche protocol: stream the content, close the write side, read the URL
            socket.getOutputStream().apply {
                write(content.toByteArray(Charsets.UTF_8))
                flush()
            }
            socket.shutdownOutput()
            val reply = socket.getInputStream().readNBytes(4096).toString(Charsets.UTF_8)
                .trim().trim('\u0000')
            if (!reply.startsWith("http")) throw Exception("termbin returned unexpected response: $reply")
            reply
        }
    }

    companion object {
        private const val DEFAULT_PORT = 9999

        /** Accepts "host", "host:port" or a URL; defaults to port 9999. */
        internal fun parseHostPort(raw: String): Pair<String, Int> {
            val authority = raw.trim().substringAfter("://").substringBefore('/')
            val host = authority.substringBeforeLast(':', authority)
            val port = authority.substringAfterLast(':', "").toIntOrNull() ?: DEFAULT_PORT
            return host to port
        }
    }
}

/** Picks the real provider per upload by probing the server (see [PasteDetector]). */
class AutoPasteProvider(
    private val baseUrl: String,
    private val providerFor: (String) -> PasteProvider
) : PasteProvider {
    override suspend fun upload(title: String, content: String): String =
        providerFor(PasteDetector.detect(baseUrl)).upload(title, content)
}

object PasteProviderFactory {
    fun create(prefs: io.github.tabssh.storage.preferences.PreferenceManager): PasteProvider =
        createForService(prefs.getPasteService(), prefs)

    /**
     * Build a provider for [serviceId] (any id in [PasteServices.ALL]). A specific type
     * always wins and uses its own configured server URL; only [PasteServices.AUTO]
     * probes the server to learn the type.
     */
    fun createForService(
        serviceId: String,
        prefs: io.github.tabssh.storage.preferences.PreferenceManager
    ): PasteProvider {
        val id = PasteServices.normalise(serviceId)
        val url = prefs.getPasteUrl(id)
        return if (id == PasteServices.AUTO) {
            AutoPasteProvider(url) { detected -> build(detected, url, prefs) }
        } else {
            build(id, url, prefs)
        }
    }

    internal fun build(
        type: String,
        url: String,
        prefs: io.github.tabssh.storage.preferences.PreferenceManager
    ): PasteProvider = when (type) {
        PasteServices.CAIRNBIN    -> CairnbinProvider(url)
        PasteServices.MICROBIN    -> MicroBinProvider(url)
        PasteServices.LENPASTE    -> LenpasteProvider(url)
        PasteServices.STIKKED     -> StikkedProvider(url)
        PasteServices.PASTEBIN    -> PastebinProvider(prefs.getPastebinApiKey().ifBlank { "anonymous" }, url)
        PasteServices.HASTEBIN    -> HastebinProvider(url)
        PasteServices.DPASTE      -> DpasteProvider(url)
        PasteServices.NULLPOINTER -> NullPointerProvider(url)
        PasteServices.TERMBIN     -> TermbinProvider(url)
        else                      -> throw IllegalArgumentException("Unknown paste service: $type")
    }

    /** Human-readable label shown in the service picker. */
    fun labelForService(
        serviceId: String,
        prefs: io.github.tabssh.storage.preferences.PreferenceManager
    ): String {
        val id = PasteServices.normalise(serviceId)
        val label = PasteServices.byId(id)?.label ?: id
        val suffix = if (id == PasteServices.PASTEBIN) " (API key required on pastebin.com)" else ""
        return "$label  —  ${prefs.getPasteUrl(id)}$suffix"
    }
}
