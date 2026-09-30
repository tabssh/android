package io.github.tabssh.utils

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedTextReaderTest {

    @Test
    fun `accepts content exactly at the byte limit`() {
        assertEquals(
            "hello",
            BoundedTextReader.readUtf8(ByteArrayInputStream("hello".toByteArray()), 5)
        )
    }

    @Test
    fun `rejects content one byte over the limit instead of returning a truncated prefix`() {
        assertFailsWith<IOException> {
            BoundedTextReader.readUtf8(ByteArrayInputStream("hello!".toByteArray()), 5)
        }
    }

    @Test
    fun `decodes multibyte UTF-8 while enforcing the limit in bytes`() {
        assertEquals(
            "é",
            BoundedTextReader.readUtf8(ByteArrayInputStream("é".toByteArray(Charsets.UTF_8)), 2)
        )
        assertFailsWith<IOException> {
            BoundedTextReader.readUtf8(ByteArrayInputStream("é".toByteArray(Charsets.UTF_8)), 1)
        }
    }
}
