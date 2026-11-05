package io.github.tabssh.utils.paste

/**
 * Registry of every paste service the app can upload to. [AUTO] probes the
 * configured server and picks the matching entry (see [PasteDetector]).
 */
object PasteServices {
    const val AUTO = "auto"
    const val CAIRNBIN = "cairnbin"
    const val MICROBIN = "microbin"
    const val LENPASTE = "lenpaste"
    const val STIKKED = "stikked"
    const val PASTEBIN = "pastebin"
    const val HASTEBIN = "hastebin"
    const val DPASTE = "dpaste"
    const val NULLPOINTER = "nullpointer"
    const val TERMBIN = "termbin"

    data class Info(val id: String, val label: String, val defaultUrl: String)

    val ALL: List<Info> = listOf(
        Info(AUTO, "Autodetect", "https://pste.us"),
        Info(CAIRNBIN, "Cairnbin", "https://cb.pste.us"),
        Info(MICROBIN, "MicroBin", "https://mb.pste.us"),
        Info(LENPASTE, "Lenpaste", "https://lp.pste.us"),
        Info(STIKKED, "Stikked", "https://pste.us"),
        Info(PASTEBIN, "Pastebin", "https://pastebin.com"),
        Info(HASTEBIN, "Hastebin", "https://hastebin.com"),
        Info(DPASTE, "dpaste", "https://dpaste.org"),
        Info(NULLPOINTER, "0x0.st", "https://0x0.st"),
        Info(TERMBIN, "termbin", "termbin.com:9999")
    )

    val IDS: List<String> = ALL.map { it.id }

    fun byId(id: String): Info? = ALL.firstOrNull { it.id == id }

    /** Falls back to [AUTO] for unknown or retired ids. */
    fun normalise(id: String): String = if (id in IDS) id else AUTO

    /** Services whose upload talks plain HTTP to a configurable base URL. */
    fun defaultUrl(id: String): String = byId(id)?.defaultUrl ?: ALL.first().defaultUrl
}
