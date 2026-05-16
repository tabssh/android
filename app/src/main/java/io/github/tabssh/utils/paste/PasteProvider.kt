package io.github.tabssh.utils.paste

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface PasteProvider {
    /** Upload [content] and return the public URL. Throws on failure. */
    suspend fun upload(title: String, content: String): String
}

private val sharedHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

class MicroBinProvider(private val baseUrl: String) : PasteProvider {
    override suspend fun upload(title: String, content: String): String = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val textType = "text/plain".toMediaType()
        // MicroBin POSTs to /upload (multipart/form-data) and responds with
        // a 302 redirect to the paste view URL. OkHttp follows the redirect
        // automatically; the final request URL is the shareable paste URL.
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("content", null, content.toRequestBody(textType))
            .addFormDataPart("expiration", null, "0".toRequestBody(textType))
            .addFormDataPart("burn_after", null, "0".toRequestBody(textType))
            .addFormDataPart("syntax_highlight", null, "plain".toRequestBody(textType))
            .addFormDataPart("privacy", null, "0".toRequestBody(textType))
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
            response.request.url.toString()
        }
    }
}

class LenpasteProvider(private val baseUrl: String) : PasteProvider {
    override suspend fun upload(title: String, content: String): String = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        // CasPaste API (compatible with lp.pste.us): POST /api/v1/new
        // Returns JSON: {"id":"...","url":"...","createTime":...,"deleteTime":...}
        val requestBody = FormBody.Builder()
            .add("title", title)
            .add("body", content)
            .build()
        val request = Request.Builder()
            .url("$base/api/v1/new")
            .post(requestBody)
            .build()
        val responseBody = sharedHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("CasPaste upload failed: HTTP ${response.code}")
            response.body?.string() ?: throw Exception("CasPaste returned empty response")
        }
        JSONObject(responseBody).getString("url")
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
            response.body?.string()?.trim() ?: throw Exception("Stikked returned empty response")
        }
    }
}

class PastebinProvider(private val apiKey: String) : PasteProvider {
    override suspend fun upload(title: String, content: String): String = withContext(Dispatchers.IO) {
        val requestBody = FormBody.Builder()
            .add("api_dev_key", apiKey)
            .add("api_option", "paste")
            .add("api_paste_code", content)
            .add("api_paste_name", title)
            .add("api_paste_private", "1")
            .add("api_paste_expire_date", "1W")
            .build()
        val request = Request.Builder()
            .url("https://pastebin.com/api/api_post.php")
            .post(requestBody)
            .build()
        val responseText = sharedHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Pastebin upload failed: HTTP ${response.code}")
            response.body?.string()?.trim() ?: throw Exception("Pastebin returned empty response")
        }
        if (responseText.startsWith("Bad API request")) throw Exception(responseText)
        responseText
    }
}

object PasteProviderFactory {
    fun create(prefs: io.github.tabssh.storage.preferences.PreferenceManager): PasteProvider {
        return when (prefs.getPasteService()) {
            "lenpaste" -> LenpasteProvider(prefs.getPasteLenpasteUrl())
            "stikked"  -> StikkedProvider(prefs.getPasteStikkedUrl())
            "pastebin" -> PastebinProvider(prefs.getPastebinApiKey())
            "microbin" -> MicroBinProvider(prefs.getPasteMicrobinUrl())
            else       -> StikkedProvider(prefs.getPasteStikkedUrl()) // default
        }
    }
}
