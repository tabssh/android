package io.github.tabssh.utils.paste

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Token flow of the Cairnbin upload: reuse a stored token, replace it when the server issues a new one. */
class CairnbinProviderTest {

    private class MemoryTokens(var token: String? = null) : CairnbinTokenStore {
        override fun get(baseUrl: String): String? = token
        override fun set(baseUrl: String, token: String?) { this.token = token }
    }

    private class FakeServer(private val respond: (authorization: String?) -> Pair<Int, String>) : Interceptor {
        val sent = mutableListOf<String?>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val request: Request = chain.request()
            val auth = request.header("Authorization")
            sent += auth
            val (code, body) = respond(auth)
            return Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun created(token: String? = null) = 201 to buildString {
        append("""{"ok":true,"data":{"id":"abc","link":"https://pste.us/abc"""")
        if (token != null) append(""","owner_token":"$token"""")
        append("}}")
    }

    private fun provider(server: FakeServer, tokens: MemoryTokens) =
        CairnbinProvider("https://pste.us/", tokens, OkHttpClient.Builder().addInterceptor(server).build())

    @Test
    fun `first upload sends no token and keeps the one the server issues`() = runBlocking {
        val tokens = MemoryTokens()
        val server = FakeServer { created("tok_new") }
        assertEquals("https://pste.us/abc", provider(server, tokens).upload("t", "c"))
        assertEquals(listOf<String?>(null), server.sent)
        assertEquals("tok_new", tokens.token)
    }

    @Test
    fun `a stored token is sent and kept when the server issues no new one`() = runBlocking {
        val tokens = MemoryTokens("tok_old")
        val server = FakeServer { created() }
        provider(server, tokens).upload("t", "c")
        assertEquals(listOf<String?>("Bearer tok_old"), server.sent)
        assertEquals("tok_old", tokens.token)
    }

    @Test
    fun `a rejected token is dropped and the upload repeats without it`() = runBlocking {
        val tokens = MemoryTokens("tok_bad")
        val server = FakeServer { auth ->
            if (auth != null) 401 to """{"ok":false,"error":"INVALID_TOKEN","message":"invalid token"}"""
            else created("tok_fresh")
        }
        assertEquals("https://pste.us/abc", provider(server, tokens).upload("t", "c"))
        assertEquals(listOf<String?>("Bearer tok_bad", null), server.sent)
        assertEquals("tok_fresh", tokens.token)
    }

    @Test
    fun `a failure without a token is not retried and keeps nothing`() {
        val tokens = MemoryTokens()
        val server = FakeServer { 500 to """{"ok":false}""" }
        assertFailsWith<Exception> { runBlocking { provider(server, tokens).upload("t", "c") } }
        assertEquals(1, server.sent.size)
        assertNull(tokens.token)
    }
}
