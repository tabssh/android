package io.github.tabssh.utils.paste

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasteDetectorTest {

    @Test
    fun `pste subdomains map to their service`() {
        assertEquals(PasteServices.CAIRNBIN, PasteDetector.typeForHost("cb.pste.us"))
        assertEquals(PasteServices.LENPASTE, PasteDetector.typeForHost("lp.pste.us"))
        assertEquals(PasteServices.MICROBIN, PasteDetector.typeForHost("mb.pste.us"))
        assertEquals(PasteServices.PASTEBIN, PasteDetector.typeForHost("pb.pste.us"))
    }

    @Test
    fun `bare pste us and unknown hosts need a probe`() {
        assertNull(PasteDetector.typeForHost("pste.us"))
        assertNull(PasteDetector.typeForHost("mybin.example.org"))
        assertNull(PasteDetector.typeForHost("xx.pste.us"))
    }

    @Test
    fun `well known public hosts are recognised`() {
        assertEquals(PasteServices.PASTEBIN, PasteDetector.typeForHost("pastebin.com"))
        assertEquals(PasteServices.NULLPOINTER, PasteDetector.typeForHost("0x0.st"))
        assertEquals(PasteServices.TERMBIN, PasteDetector.typeForHost("termbin.com"))
        assertEquals(PasteServices.DPASTE, PasteDetector.typeForHost("dpaste.org"))
    }

    @Test
    fun `host extraction handles ports paths and credentials`() {
        assertEquals("paste.example.org", PasteDetector.hostOf("https://paste.example.org:8443/x/y"))
        assertEquals("paste.example.org", PasteDetector.hostOf("https://user@paste.example.org"))
        assertEquals("termbin.com", PasteDetector.hostOf("termbin.com:9999"))
    }

    @Test
    fun `probe responses are classified`() {
        assertEquals(PasteServices.CAIRNBIN, PasteDetector.classify("Made with apimgr/cairnbin", null, null))
        assertEquals(PasteServices.LENPASTE, PasteDetector.classify(null, "{\"bodyMaxlength\": 1}", null))
        assertEquals(PasteServices.MICROBIN, PasteDetector.classify(null, null, "<title>MicroBin</title>"))
        assertEquals(PasteServices.STIKKED, PasteDetector.classify(null, null, "Powered by stikked"))
        assertEquals(PasteServices.HASTEBIN, PasteDetector.classify(null, null, "<title>Hastebin</title>"))
        assertNull(PasteDetector.classify(null, null, "<html>hello</html>"))
        assertNull(PasteDetector.classify(null, null, null))
    }

    @Test
    fun `cairnbin signature wins over the lenpaste lookalike API`() {
        val serverInfo = "{\"bodyMaxlength\": 10485760}"
        assertEquals(PasteServices.CAIRNBIN, PasteDetector.classify("apimgr/cairnbin", serverInfo, "MicroBin"))
    }

    @Test
    fun `registry ids are unique and unknown ids fall back to autodetect`() {
        assertEquals(PasteServices.IDS.size, PasteServices.IDS.toSet().size)
        assertEquals(PasteServices.AUTO, PasteServices.normalise("retired"))
        assertEquals(PasteServices.TERMBIN, PasteServices.normalise("termbin"))
        assertEquals("https://cb.pste.us", PasteServices.defaultUrl(PasteServices.CAIRNBIN))
        assertEquals("https://pste.us", PasteServices.defaultUrl(PasteServices.AUTO))
        assertTrue(PasteServices.IDS.containsAll(listOf("cairnbin", "termbin", "hastebin", "nullpointer", "dpaste")))
    }

    @Test
    fun `termbin address accepts host port and urls`() {
        assertEquals("termbin.com" to 9999, TermbinProvider.parseHostPort("termbin.com"))
        assertEquals("mybin.example.org" to 4000, TermbinProvider.parseHostPort("mybin.example.org:4000"))
        assertEquals("mybin.example.org" to 9999, TermbinProvider.parseHostPort("tcp://mybin.example.org/"))
    }
}
