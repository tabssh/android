package io.github.tabssh.utils.paste

import io.github.tabssh.storage.database.dao.PasteRecordDao
import io.github.tabssh.storage.database.entities.PasteRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasteTrackerTest {

    private class MemoryDao : PasteRecordDao {
        val rows = mutableListOf<PasteRecord>()
        private var next = 1L
        override fun observeAll(): Flow<List<PasteRecord>> = flowOf(rows.toList())
        override suspend fun getAllList() = rows.toList()
        override suspend fun getByServerAndKind(serverUrl: String, kind: String) =
            rows.filter { it.serverUrl == serverUrl && it.deleteKind == kind }
        override suspend fun insertIgnore(record: PasteRecord): Long {
            if (rows.any { it.serverUrl == record.serverUrl && it.remoteId == record.remoteId }) return -1
            val saved = record.copy(id = next++)
            rows += saved
            return saved.id
        }
        override suspend fun deleteById(id: Long) { rows.removeAll { it.id == id } }
    }

    private class Owner(var token: String? = null) : CairnbinTokenStore {
        override fun get(baseUrl: String) = token
        override fun set(baseUrl: String, token: String?) { this.token = token }
    }

    private class Tokens : PasteDeleteTokens {
        val map = mutableMapOf<Long, String>()
        override fun get(recordId: Long) = map[recordId]
        override fun set(recordId: Long, token: String?) { if (token == null) map.remove(recordId) else map[recordId] = token }
    }

    private class Server(val respond: (method: String, url: String, auth: String?) -> Pair<Int, String>) : Interceptor {
        val seen = mutableListOf<String>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val r = chain.request()
            seen += "${r.method} ${r.url}"
            val (code, body) = respond(r.method, r.url.toString(), r.header("Authorization"))
            return Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    private fun tracker(dao: MemoryDao, owner: Owner, tokens: Tokens, server: Server) =
        PasteTracker(dao, owner, tokens, OkHttpClient.Builder().addInterceptor(server).build())

    private val base = "https://pste.us"

    @Test
    fun `recording keeps the link and picks the delete kind from what the server returned`() = runBlocking {
        val dao = MemoryDao()
        val tokens = Tokens()
        val t = tracker(dao, Owner("tok"), tokens, Server { _, _, _ -> 200 to "" })
        t.record(PasteResult("$base/a", "cairnbin", base, "a", ownerToken = true), "one")
        t.record(PasteResult("$base/b", "lenpaste", base, "b", deleteToken = "secret"), "two")
        t.record(PasteResult("$base/c", "hastebin", base, null), "three")
        assertEquals(
            listOf(PasteRecord.DELETE_OWNER, PasteRecord.DELETE_TOKEN, PasteRecord.DELETE_NONE),
            dao.rows.map { it.deleteKind }
        )
        assertEquals("c", dao.rows[2].remoteId)
        assertEquals("secret", tokens.map[dao.rows[1].id])
    }

    @Test
    fun `an owner delete sends the bearer token and maps the status codes`() = runBlocking {
        val dao = MemoryDao()
        val owner = Owner("tok_x")
        val record = PasteRecord(id = 1, service = "cairnbin", serverUrl = base, remoteId = "a", url = "$base/a", createdAt = 0, deleteKind = PasteRecord.DELETE_OWNER)
        for ((code, expected) in listOf(
            200 to PasteDeleteOutcome.Deleted, 404 to PasteDeleteOutcome.NotFound, 401 to PasteDeleteOutcome.TokenRejected
        )) {
            val server = Server { m, _, auth -> assertEquals("DELETE", m); assertEquals("Bearer tok_x", auth); code to "{}" }
            assertEquals(expected, tracker(dao, owner, Tokens(), server).delete(record))
            assertTrue(server.seen.single().endsWith("/api/v1/pastes/a"))
        }
    }

    @Test
    fun `a lenpaste style delete passes the paste token and a tracked-only paste is unsupported`() = runBlocking {
        val tokens = Tokens().also { it.map[2] = "abc" }
        val server = Server { _, _, _ -> 200 to "{}" }
        val record = PasteRecord(id = 2, service = "lenpaste", serverUrl = base, remoteId = "b", url = "$base/b", createdAt = 0, deleteKind = PasteRecord.DELETE_TOKEN)
        assertEquals(PasteDeleteOutcome.Deleted, tracker(MemoryDao(), Owner(), tokens, server).delete(record))
        assertEquals("DELETE https://pste.us/api/remove?id=b&deleteToken=abc", server.seen.single())
        val none = record.copy(deleteKind = PasteRecord.DELETE_NONE)
        assertEquals(PasteDeleteOutcome.Unsupported, tracker(MemoryDao(), Owner(), tokens, server).delete(none))
    }

    @Test
    fun `sync adds pastes the server lists and drops owner records it no longer lists`() = runBlocking {
        val dao = MemoryDao()
        dao.insertIgnore(PasteRecord(service = "cairnbin", serverUrl = base, remoteId = "gone", url = "$base/gone", createdAt = 1, deleteKind = PasteRecord.DELETE_OWNER))
        dao.insertIgnore(PasteRecord(service = "hastebin", serverUrl = base, remoteId = "keep", url = "$base/keep", createdAt = 1, deleteKind = PasteRecord.DELETE_NONE))
        val server = Server { _, url, _ ->
            if (url.contains("auth/check")) 200 to """{"ok":true,"data":{"kind":"owner"}}"""
            else 200 to """{"ok":true,"data":[{"id":"new1","title":"Untitled","created_at":"2026-11-21T17:36:27-05:00"}],"pagination":{"pages":1}}"""
        }
        assertTrue(tracker(dao, Owner("tok"), Tokens(), server).syncOwned(base))
        assertEquals(setOf("new1", "keep"), dao.rows.map { it.remoteId }.toSet())
    }

    @Test
    fun `an invalid owner token is dropped and the list is left alone`() = runBlocking {
        val dao = MemoryDao()
        dao.insertIgnore(PasteRecord(service = "cairnbin", serverUrl = base, remoteId = "a", url = "$base/a", createdAt = 1, deleteKind = PasteRecord.DELETE_OWNER))
        val owner = Owner("tok_dead")
        val ok = tracker(dao, owner, Tokens(), Server { _, _, _ -> 401 to "{}" }).syncOwned(base)
        assertEquals(false, ok)
        assertNull(owner.token)
        assertEquals(1, dao.rows.size)
    }
}
