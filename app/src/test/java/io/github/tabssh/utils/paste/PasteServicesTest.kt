package io.github.tabssh.utils.paste

import org.junit.Assert.assertEquals
import org.junit.Test

class PasteServicesTest {

    @Test
    fun `retired upstream hosts default to their pste us equivalents`() {
        assertEquals("https://cb.pste.us", PasteServices.defaultUrl(PasteServices.CAIRNBIN))
        assertEquals("https://stikked.pste.us", PasteServices.defaultUrl(PasteServices.STIKKED))
        assertEquals("https://hb.pste.us", PasteServices.defaultUrl(PasteServices.HASTEBIN))
        assertEquals("https://0x0.pste.us", PasteServices.defaultUrl(PasteServices.NULLPOINTER))
        assertEquals("https://pastebin.com", PasteServices.defaultUrl(PasteServices.PASTEBIN))
    }
}
