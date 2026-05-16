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
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("content", null, content.toRequestBody(textType))
            .addFormDataPart("title", null, title.toRequestBody(textType))
            .addFormDataPart("privacy", null, "1".toRequestBody(textType))
            .addFormDataPart("syntax_highlight", null, "text".toRequestBody(textType))
            .addFormDataPart("expiration", null, "1d".toRequestBody(textType))
            .addFormDataPart("burn_after", null, "0".toRequestBody(textType))
            .build()
        val request = Request.Builder()
            .url("$base/api/save")
            .post(requestBody)
            .build()
        val responseBody = sharedHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("MicroBin upload failed: HTTP ${response.code}")
            response.body?.string() ?: throw Exception("MicroBin returned empty response")
        }
        val json = JSONObject(responseBody)
        if (json.has("url")) return@withContext json.getString("url")
        val id = json.optString("id").ifBlank { throw Exception("MicroBin response missing id field") }
        "$base/$id"
    }
}

class LenpasteProvider(private val baseUrl: String) : PasteProvider {
    override suspend fun upload(title: String, content: String): String = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val requestBody = FormBody.Builder()
            .add("title", title)
            .add("body", content)
            .add("lifetime", "-1")
            .build()
        val request = Request.Builder()
            .url("$base/api/paste/create")
            .post(requestBody)
            .build()
        val responseBody = sharedHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Lenpaste upload failed: HTTP ${response.code}")
            response.body?.string() ?: throw Exception("Lenpaste returned empty response")
        }
        val id = JSONObject(responseBody).optString("id").ifBlank {
            throw Exception("Lenpaste response missing id field")
        }
        "$base/$id"
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
