package io.github.tabssh.network

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedResponseBodyTest {
    @Test
    fun `chunked responses cannot exceed the cap`() {
        val body = object : ResponseBody() {
            private val buffer = Buffer().writeUtf8("too long")
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = buffer
        }
        assertFailsWith<IOException> { body.readBoundedText(3) }
    }

    @Test
    fun `exact byte limit is accepted and charset is preserved`() {
        val body = byteArrayOf(0xE9.toByte()).toResponseBody("text/plain; charset=iso-8859-1".toMediaType())
        assertEquals("é", body.readBoundedText(1))
    }
}
