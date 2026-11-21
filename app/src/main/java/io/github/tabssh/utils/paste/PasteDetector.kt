package io.github.tabssh.utils.paste

import io.github.tabssh.network.readBoundedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Works out which paste service type runs at a base URL. Known host names are
 * matched first (no network); anything else is probed with read-only requests
 * whose bodies are checked, because a catch-all front end answers 200 for any path.
 */
object PasteDetector {

    private val cache = ConcurrentHashMap<String, String>()

    private const val PSTE_DOMAIN = "pste.us"
    private const val PROBE_LIMIT_BYTES = 1024 * 1024L

    /** Service type implied by the host name alone, or null when it must be probed. */
    fun typeForHost(host: String): String? {
        val h = host.lowercase().trimEnd('.')
        return when {
            h == "pastebin.com" || h.endsWith(".pastebin.com") -> PasteServices.PASTEBIN
            h == "0x0.st" -> PasteServices.NULLPOINTER
            h == "termbin.com" -> PasteServices.TERMBIN
            h == "dpaste.org" || h == "dpaste.com" -> PasteServices.DPASTE
            h == "hastebin.com" -> PasteServices.HASTEBIN
            h.endsWith(".$PSTE_DOMAIN") -> when (h.removeSuffix(".$PSTE_DOMAIN").substringBefore('.')) {
                "cb" -> PasteServices.CAIRNBIN
                "lp" -> PasteServices.LENPASTE
                "mb" -> PasteServices.MICROBIN
                "pb" -> PasteServices.PASTEBIN
                else -> null
            }
            else -> null
        }
    }

    /** Pure classification of probe responses; null when none identifies a type. */
    internal fun classify(
        helpPage: String?,
        serverInfo: String?,
        homePage: String?,
        autodiscover: String? = null
    ): String? = when {
        // A white-labelled server may rename every page, but autodiscover always names the server software.
        isCairnbinAutodiscover(autodiscover) -> PasteServices.CAIRNBIN
        helpPage != null && (helpPage.contains("apimgr/cairnbin") || isCairnbinHelp(helpPage)) -> PasteServices.CAIRNBIN
        serverInfo != null && serverInfo.contains("\"bodyMaxlength\"") -> PasteServices.LENPASTE
        homePage != null && homePage.contains("MicroBin") -> PasteServices.MICROBIN
        homePage != null && homePage.contains("Stikked", ignoreCase = true) -> PasteServices.STIKKED
        homePage != null && homePage.contains("haste", ignoreCase = true) -> PasteServices.HASTEBIN
        else -> null
    }

    /** `/api/autodiscover` answers `{"ok":true,"data":{"server":"cairnbin",...}}` on every Cairnbin instance. */
    internal fun isCairnbinAutodiscover(body: String?): Boolean = body != null && try {
        org.json.JSONObject(body).optJSONObject("data")?.optString("server").equals("cairnbin", ignoreCase = true)
    } catch (_: Exception) {
        false
    }

    /** The Cairnbin help page names the product and documents its REST create endpoint. */
    private fun isCairnbinHelp(page: String): Boolean =
        page.contains("Cairnbin") && page.contains("Create a paste") && page.contains("/server/healthz")

    /** Detects the service type at [baseUrl]; result is remembered per URL. */
    suspend fun detect(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        cache[base]?.let { return it }
        val host = hostOf(base)
        val byHost = typeForHost(host)
        if (byHost != null) return byHost.also { cache[base] = it }
        val type = withContext(Dispatchers.IO) {
            classify(
                autodiscover = fetch("$base/api/autodiscover"),
                helpPage = fetch("$base/server/help"),
                serverInfo = fetch("$base/api/v1/getServerInfo"),
                homePage = fetch("$base/")
            )
        } ?: throw Exception("Could not detect the paste server type at $base; choose a specific paste service in settings")
        cache[base] = type
        return type
    }

    internal fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore(':').substringAfter('@')

    private fun fetch(url: String): String? = try {
        val request = Request.Builder().url(url).get().build()
        sharedHttpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.readBoundedText(PROBE_LIMIT_BYTES) else null
        }
    } catch (_: Exception) {
        null
    }
}
