package io.github.tabssh.utils

import java.text.NumberFormat
import java.util.Locale

/**
 * Shared human-readable formatting for user-facing values.
 *
 * One implementation per value kind, never per-screen ad-hoc formatting
 * (AI.md PART 7 § Human-Readable Values). Machine surfaces — JSON payloads,
 * log lines — keep their raw values and must not call in here.
 */
object Format {

    /**
     * A count with the current locale's grouping separators, e.g. `12,847` in
     * en-US and `12.847` in de-DE.
     */
    fun count(value: Int): String =
        NumberFormat.getIntegerInstance(Locale.getDefault()).format(value.toLong())
}
