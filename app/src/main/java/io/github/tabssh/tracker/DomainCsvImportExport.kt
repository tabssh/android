package io.github.tabssh.tracker

import io.github.tabssh.storage.database.entities.Domain
import io.github.tabssh.utils.logging.Logger
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Hand-rolled CSV import/export for the Domain Tracker, matching the
 * NameCheap "Domain List" export format exactly (`~/Documents/Domain_List.csv`):
 *
 * ```
 * Domain Name,Domain privacy protection status,Domain status at NC,Domain auto-renew status,Domain expiration date
 * amd64.us,OFF,Active,ON,Dec 16 2026
 * ```
 *
 * Dates use `MMM dd yyyy` (three-letter month, e.g. "Dec 16 2026"). No
 * external CSV library is used — mirrors the character-by-character quoted-
 * field handling in [io.github.tabssh.ssh.config.BulkImportParser].
 */
object DomainCsvImportExport {

    private const val TAG = "DomainCsvImportExport"

    const val HEADER = "Domain Name,Domain privacy protection status,Domain status at NC,Domain auto-renew status,Domain expiration date"

    private val DATE_FORMAT: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("MMM dd yyyy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
    }

    data class ParseResult(val domains: List<Domain>, val warnings: List<String>)

    /**
     * Parse CSV text into [Domain] rows. New UUIDs are minted for every row
     * (import is a merge/replace-by-name operation the caller decides on,
     * not an id-preserving round-trip) and `reminderDaysBefore` defaults to
     * the entity default of 7.
     */
    fun parse(text: String): ParseResult {
        val warnings = mutableListOf<String>()
        val domains = mutableListOf<Domain>()
        val now = System.currentTimeMillis()

        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return ParseResult(emptyList(), warnings)

        // Skip the header line unconditionally when present — the format is
        // fixed, so we don't try to remap columns by header text.
        val startIndex = if (lines[0].trim().startsWith("Domain Name", ignoreCase = true)) 1 else 0

        for ((idx, line) in lines.withIndex()) {
            if (idx < startIndex) continue
            val fields = splitCsvLine(line)
            if (fields.size < 5) {
                warnings.add("Line ${idx + 1}: expected 5 fields, found ${fields.size} — skipped")
                continue
            }
            val domainName = fields[0].trim()
            if (domainName.isEmpty()) {
                warnings.add("Line ${idx + 1}: empty domain name — skipped")
                continue
            }
            val privacy = fields[1].trim()
            val status = fields[2].trim()
            val autoRenew = fields[3].trim()
            val expirationText = fields[4].trim()
            val expirationDate = try {
                if (expirationText.isEmpty()) null else DATE_FORMAT.get()!!.parse(expirationText)?.time
            } catch (e: ParseException) {
                warnings.add("Line ${idx + 1}: could not parse expiration date '$expirationText'")
                null
            }
            domains.add(
                Domain(
                    id = UUID.randomUUID().toString(),
                    domainName = domainName,
                    privacyProtection = privacy,
                    statusAtRegistrar = status,
                    autoRenew = autoRenew,
                    expirationDate = expirationDate,
                    createdAt = now,
                    modifiedAt = now
                )
            )
        }
        Logger.d(TAG, "Parsed ${domains.size} domain(s), ${warnings.size} warning(s)")
        return ParseResult(domains, warnings)
    }

    /** Write [domains] back out in the exact same header/column/date-format shape. */
    fun export(domains: List<Domain>): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        for (d in domains) {
            val expiration = d.expirationDate?.let { DATE_FORMAT.get()!!.format(it) }.orEmpty()
            sb.append(escapeCsvField(d.domainName)).append(',')
                .append(escapeCsvField(d.privacyProtection)).append(',')
                .append(escapeCsvField(d.statusAtRegistrar)).append(',')
                .append(escapeCsvField(d.autoRenew)).append(',')
                .append(escapeCsvField(expiration)).append('\n')
        }
        return sb.toString()
    }

    /** Quote a field only when it contains a comma, quote, or newline. */
    private fun escapeCsvField(value: String): String {
        return if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    /** Minimal CSV split honouring double-quoted fields with embedded commas/quotes. */
    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
