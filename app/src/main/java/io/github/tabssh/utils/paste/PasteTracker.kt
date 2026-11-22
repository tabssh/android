package io.github.tabssh.utils.paste

import io.github.tabssh.network.readBoundedText
import io.github.tabssh.storage.database.dao.PasteRecordDao
import io.github.tabssh.storage.database.entities.PasteRecord
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Keystore-held per-paste delete tokens. */
interface PasteDeleteTokens {
    fun get(recordId: Long): String?
    fun set(recordId: Long, token: String?)
}

/** What happened when a paste was asked to delete itself on its server. */
sealed class PasteDeleteOutcome {
    /** The server deleted it. */
    object Deleted : PasteDeleteOutcome()

    /** The server has no such paste, or does not accept our token for it (it also answers 404 for both). */
    object NotFound : PasteDeleteOutcome()

    /** The token was refused (401/403) or is missing; the server copy is untouched. */
    object TokenRejected : PasteDeleteOutcome()

    /** The service offers no delete; the record can only leave the app list. */
    object Unsupported : PasteDeleteOutcome()

    data class Failed(val message: String) : PasteDeleteOutcome()
}

/**
 * Remembers the pastes this device created and deletes them on the server where the service allows
 * it: Cairnbin with the server's owner token, and a Lenpaste-compatible server with the token it
 * issued for the paste. Everything else is tracked only (date, time, link).
 */
class PasteTracker(
    private val dao: PasteRecordDao,
    private val owner: CairnbinTokenStore,
    private val deleteTokens: PasteDeleteTokens,
    private val client: OkHttpClient = sharedHttpClient
) {

    /** Stores a record for [result]. Never throws: failing to track must not fail the upload. */
    suspend fun record(result: PasteResult, title: String) {
        val service = result.service ?: return
        val server = result.serverUrl ?: return
        try {
            val kind = when {
                result.ownerToken -> PasteRecord.DELETE_OWNER
                result.deleteToken != null -> PasteRecord.DELETE_TOKEN
                else -> PasteRecord.DELETE_NONE
            }
            val remoteId = result.remoteId ?: result.url.trimEnd('/').substringAfterLast('/')
            val id = dao.insertIgnore(
                PasteRecord(
                    service = service,
                    serverUrl = server,
                    remoteId = remoteId,
                    url = result.url,
                    title = title,
                    createdAt = System.currentTimeMillis(),
                    deleteKind = kind
                )
            )
            if (id > 0 && result.deleteToken != null) deleteTokens.set(id, result.deleteToken)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "could not record paste: ${e.message}")
        }
    }

    /** Deletes the server copy; the caller removes the record when the outcome allows it. */
    suspend fun delete(record: PasteRecord): PasteDeleteOutcome = withContext(Dispatchers.IO) {
        try {
            when (record.deleteKind) {
                PasteRecord.DELETE_OWNER -> {
                    val token = owner.get(record.serverUrl) ?: return@withContext PasteDeleteOutcome.TokenRejected
                    val request = Request.Builder()
                        .url("${record.serverUrl}/api/v1/pastes/${record.remoteId}".toHttpUrl())
                        .delete()
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer $token")
                        .build()
                    outcomeOf(client.newCall(request).execute().use { it.code })
                }
                PasteRecord.DELETE_TOKEN -> {
                    val token = deleteTokens.get(record.id) ?: return@withContext PasteDeleteOutcome.TokenRejected
                    val url = "${record.serverUrl}/api/remove".toHttpUrl().newBuilder()
                        .addQueryParameter("id", record.remoteId)
                        .addQueryParameter("deleteToken", token)
                        .build()
                    outcomeOf(client.newCall(Request.Builder().url(url).delete().build()).execute().use { it.code })
                }
                else -> PasteDeleteOutcome.Unsupported
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            PasteDeleteOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun outcomeOf(code: Int): PasteDeleteOutcome = when {
        code in 200..299 -> PasteDeleteOutcome.Deleted
        code == 404 -> PasteDeleteOutcome.NotFound
        code == 401 || code == 403 -> PasteDeleteOutcome.TokenRejected
        else -> PasteDeleteOutcome.Failed("HTTP $code")
    }

    /** Removes the record and its stored delete token. */
    suspend fun forget(record: PasteRecord) {
        dao.deleteById(record.id)
        deleteTokens.set(record.id, null)
    }

    /**
     * Brings the record list for a Cairnbin [serverUrl] in line with the server: pastes the owner
     * token lists that we do not have are added, and owner-token records the server no longer
     * lists (expired, burned, deleted elsewhere) are dropped. Returns false when nothing could be
     * confirmed (no token, token no longer valid, server unreachable), leaving the list untouched.
     */
    suspend fun syncOwned(serverUrl: String): Boolean = withContext(Dispatchers.IO) {
        val base = serverUrl.trimEnd('/')
        val token = owner.get(base) ?: return@withContext false
        try {
            val check = Request.Builder().url("$base/api/v1/server/auth/check").get()
                .header("Accept", "application/json").header("Authorization", "Bearer $token").build()
            val checkCode = client.newCall(check).execute().use { it.code }
            if (checkCode == 401 || checkCode == 403) {
                Logger.w(TAG, "owner token for $base is no longer valid; dropping it")
                owner.set(base, null)
                return@withContext false
            }
            if (checkCode !in 200..299) return@withContext false

            val seen = mutableSetOf<String>()
            var page = 1
            var pages = 1
            while (page <= pages) {
                val url = "$base/api/v1/pastes".toHttpUrl().newBuilder()
                    .addQueryParameter("scope", "owned").addQueryParameter("page", page.toString()).build()
                val request = Request.Builder().url(url).get().header("Accept", "application/json")
                    .header("Authorization", "Bearer $token").build()
                val body = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
                    response.body?.readBoundedText(4 * 1024 * 1024L).orEmpty()
                }
                val json = JSONObject(body)
                pages = json.optJSONObject("pagination")?.optInt("pages", 1)?.coerceAtLeast(1) ?: 1
                val data = json.optJSONArray("data") ?: return@withContext false
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    val id = item.optString("id").ifBlank { continue }
                    seen += id
                    dao.insertIgnore(
                        PasteRecord(
                            service = PasteServices.CAIRNBIN,
                            serverUrl = base,
                            remoteId = id,
                            url = "$base/$id",
                            title = item.optString("title").takeIf { it != "Untitled" }.orEmpty(),
                            createdAt = parseTime(item.optString("created_at")),
                            deleteKind = PasteRecord.DELETE_OWNER
                        )
                    )
                }
                page++
            }
            dao.getByServerAndKind(base, PasteRecord.DELETE_OWNER)
                .filter { it.remoteId !in seen }
                .forEach { forget(it) }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "owned-paste sync with $base failed: ${e.message}")
            false
        }
    }

    /** ISO-8601 with an offset (java.time needs API 26; the app supports 24); now when unparsable. */
    private fun parseTime(value: String): Long = try {
        val trimmed = value.replace(Regex("""\.\d+"""), "")
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).parse(trimmed)?.time
            ?: System.currentTimeMillis()
    } catch (_: Exception) {
        System.currentTimeMillis()
    }

    companion object {
        private const val TAG = "PasteTracker"

        fun from(
            db: io.github.tabssh.storage.database.TabSSHDatabase,
            prefs: io.github.tabssh.storage.preferences.PreferenceManager
        ) = PasteTracker(db.pasteRecordDao(), PreferenceCairnbinTokens(prefs), PreferencePasteDeleteTokens(prefs))
    }
}

/** Keystore-backed [PasteDeleteTokens] over the preferences' secret store. */
internal class PreferencePasteDeleteTokens(
    private val prefs: io.github.tabssh.storage.preferences.PreferenceManager
) : PasteDeleteTokens {
    override fun get(recordId: Long): String? = prefs.getPasteDeleteToken(recordId)
    override fun set(recordId: Long, token: String?) = prefs.setPasteDeleteToken(recordId, token)
}
