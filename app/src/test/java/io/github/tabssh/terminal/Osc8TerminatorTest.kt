package io.github.tabssh.terminal

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for [TermuxBridge.OSC8_PATTERN], the pattern that finds
 * complete OSC 8 hyperlinks in rendered terminal output so their anchor text
 * can be made tappable.
 *
 * The bug these lock down: the pattern originally accepted only ESC \ as the
 * OSC string terminator. BEL (0x07) is equally valid and is what many real
 * tools emit, so BEL-terminated links rendered their anchor text but were
 * never tracked, and therefore were not tappable.
 */
class Osc8TerminatorTest {

    private val esc = "\u001b"
    private val bel = "\u0007"

    /** ESC ] 8 ; params ; url ST anchor ESC ] 8 ; ; ST, with a choice of ST. */
    private fun link(url: String, anchor: String, st: String, params: String = ""): String =
        "$esc]8;$params;$url$st$anchor$esc]8;;$st"

    private fun firstMatch(text: String) = TermuxBridge.OSC8_PATTERN.find(text)

    @Test
    fun `ESC backslash terminated link is matched`() {
        val st = "$esc\\"
        val m = firstMatch(link("https://example.com", "Example", st))
        requireNotNull(m) { "ESC-backslash terminated link must match" }
        assertEquals("https://example.com", m.groupValues[2])
        assertEquals("Example", m.groupValues[3])
    }

    @Test
    fun `BEL terminated link is matched`() {
        val m = firstMatch(link("https://example.com", "Example", bel))
        requireNotNull(m) { "BEL terminated link must match - this is the regression" }
        assertEquals("https://example.com", m.groupValues[2])
        assertEquals("Example", m.groupValues[3])
    }

    @Test
    fun `mixed terminators within one link are matched`() {
        // A producer may legitimately use BEL to close the opener and ESC backslash
        // to close the terminator, or the reverse; both halves are independent.
        val a = firstMatch("$esc]8;;https://example.com${bel}Text$esc]8;;$esc\\")
        requireNotNull(a) { "BEL opener with ESC-backslash closer must match" }
        assertEquals("Text", a.groupValues[3])

        val b = firstMatch("$esc]8;;https://example.com$esc\\" + "Text" + "$esc]8;;$bel")
        requireNotNull(b) { "ESC-backslash opener with BEL closer must match" }
        assertEquals("Text", b.groupValues[3])
    }

    @Test
    fun `url group does not swallow its own BEL terminator`() {
        // If the URL character class allowed BEL, the match would run past the
        // opener's terminator and capture the anchor text into the URL.
        val m = firstMatch(link("https://example.com/a", "Anchor", bel))
        requireNotNull(m)
        assertTrue(bel !in m.groupValues[2], "URL group must not contain BEL")
        assertTrue(esc !in m.groupValues[2], "URL group must not contain ESC")
        assertEquals("https://example.com/a", m.groupValues[2])
    }

    @Test
    fun `params group is captured separately from the url`() {
        val m = firstMatch(link("https://example.com", "Example", bel, params = "id=42"))
        requireNotNull(m)
        assertEquals("id=42", m.groupValues[1])
        assertEquals("https://example.com", m.groupValues[2])
    }

    @Test
    fun `anchor may span newlines`() {
        // DOT_MATCHES_ALL: a link whose anchor text wrapped across a line must
        // still be recognised as one link.
        val m = firstMatch(link("https://example.com", "wrapped\nanchor", bel))
        requireNotNull(m)
        assertEquals("wrapped\nanchor", m.groupValues[3])
    }

    @Test
    fun `an unterminated opener does not match`() {
        assertNull(firstMatch("$esc]8;;https://example.com"))
        assertNull(firstMatch("$esc]8;;https://example.com${bel}Anchor"))
    }

    @Test
    fun `multiple links on one line are all found`() {
        val text = link("https://a.example", "A", bel) + " and " +
            link("https://b.example", "B", "$esc\\")
        val urls = TermuxBridge.OSC8_PATTERN.findAll(text).map { it.groupValues[2] }.toList()
        assertEquals(listOf("https://a.example", "https://b.example"), urls)
    }

    @Test
    fun `matching is non greedy across adjacent links`() {
        // A greedy anchor group would merge two adjacent links into one match
        // whose anchor spanned the first link's closer.
        val text = link("https://a.example", "A", bel) + link("https://b.example", "B", bel)
        val anchors = TermuxBridge.OSC8_PATTERN.findAll(text).map { it.groupValues[3] }.toList()
        assertEquals(listOf("A", "B"), anchors)
    }

    @Test
    fun `tracked link targets still pass through the sanitizer`() {
        // The pattern only locates candidates; the allowlist decides what is
        // tappable. A matched javascript: target must still be rejected.
        val m = firstMatch(link("javascript:alert(1)", "Click", bel))
        requireNotNull(m) { "the pattern matches structurally regardless of scheme" }
        assertNull(
            TermuxBridge.sanitizeOsc8Url(m.groupValues[2]),
            "a matched but disallowed scheme must be rejected before it is made tappable"
        )
        assertEquals(
            "https://example.com",
            TermuxBridge.sanitizeOsc8Url(
                firstMatch(link("https://example.com", "Click", bel))!!.groupValues[2]
            )
        )
    }
}
