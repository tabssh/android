package io.github.tabssh.ui.views

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalViewUrlOffsetTest {

    private val pattern = Regex("(?:https?|ftps?|ssh|git|svn|file)://[^\\s<>\"')\\]\\[\\\\]+", RegexOption.IGNORE_CASE)
    private val text = "some plain words\nsee https://example.com/a now\nmore plain words"

    @Test
    fun `offset on the link returns that link`() {
        val offset = text.indexOf("example")
        assertEquals("https://example.com/a", TerminalView.urlCoveringOffset(text, offset, pattern))
    }

    @Test
    fun `offset on plain text returns null even when a link exists elsewhere`() {
        assertNull(TerminalView.urlCoveringOffset(text, 2, pattern))
        assertNull(TerminalView.urlCoveringOffset(text, text.length - 2, pattern))
    }

    @Test
    fun `with two links the covering one wins`() {
        val two = "https://a.example/x and https://b.example/y"
        assertEquals("https://b.example/y", TerminalView.urlCoveringOffset(two, two.indexOf("b.example"), pattern))
    }

    private fun urls(text: String) =
        TerminalView.findUrlRanges(text, pattern).map { text.substring(it) }

    @Test
    fun `plain and double-quoted links are unchanged`() {
        assertEquals(listOf("https://a.example/x"), urls("see https://a.example/x now"))
        assertEquals(listOf("https://a.example/x"), urls("open \"https://a.example/x\" now"))
        assertEquals(listOf("https://a.example/x"), urls("open 'https://a.example/x' now"))
    }

    @Test
    fun `encoded characters stay in the link`() {
        assertEquals(listOf("https://a.example/a%20b%2Fc"), urls("go https://a.example/a%20b%2Fc ok"))
    }

    @Test
    fun `quoted link keeps an inner apostrophe and parenthesis`() {
        assertEquals(listOf("https://a.example/it's"), urls("x 'https://a.example/it's' y"))
        assertEquals(listOf("https://a.example/o'k(1)"), urls("x \"https://a.example/o'k(1)\" y"))
    }

    @Test
    fun `parenthesis that closes an open one stays`() {
        assertEquals(listOf("https://a.example/Foo_(bar)"), urls("see https://a.example/Foo_(bar) ok"))
        assertEquals(listOf("https://a.example/Foo_(bar)_baz"), urls("see https://a.example/Foo_(bar)_baz ok"))
    }

    @Test
    fun `wrapping parenthesis is not part of the link`() {
        assertEquals(listOf("https://a.example/x"), urls("(see https://a.example/x) ok"))
        assertEquals(listOf("https://a.example/Foo_(bar)"), urls("(see https://a.example/Foo_(bar)) ok"))
    }

    @Test
    fun `tail trimming keeps balanced parenthesis only`() {
        assertEquals("https://a.example/Foo_(bar)", TerminalView.trimUrlTail("https://a.example/Foo_(bar)."))
        assertEquals("https://a.example/x", TerminalView.trimUrlTail("https://a.example/x)."))
        assertEquals("https://a.example/x", TerminalView.trimUrlTail("https://a.example/x,"))
    }

    @Test
    fun `unclosed quote falls back to the base match`() {
        assertEquals(listOf("https://a.example/x"), urls("'https://a.example/x and more"))
    }
}
