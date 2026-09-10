package io.github.tabssh.tracker

import io.github.tabssh.storage.database.entities.Domain
import io.github.tabssh.utils.logging.Logger
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Hand-rolled CSV import/export for the Domain Tracker. **Export** always
 * writes the NameCheap "Domain List" shape (`~/Documents/Domain_List.csv`):
 *
 * ```
 * Domain Name,Domain privacy protection status,Domain status at NC,Domain auto-renew status,Domain expiration date
 * amd64.us,OFF,Active,ON,Dec 16 2026
 * ```
 *
 * **Import** is header-driven rather than tied to one registrar's exact
 * column order, so a domain-list export from NameCheap, GoDaddy, Porkbun,
 * or another registrar can all be dropped in as-is: the header row is
 * matched case-insensitively against a synonym list per field (see
 * [COLUMN_SYNONYMS]) and the expiration date is tried against a list of
 * common registrar date formats (see [DATE_FORMATS]) rather than one fixed
 * pattern. A CSV with no recognized header row at all falls back to the
 * original fixed 5-column NameCheap order for backward compatibility. A
 * shorter 4-column shape with no privacy-protection column at all —
 * `Domain Name,Domain status,Domain auto-renew status,Domain expiration
 * date` — is also recognized via the same header matching; when that
 * column is absent (or present but blank), [Domain.privacyProtection]
 * defaults to "N/A" rather than an empty string. No external CSV library is
 * used — mirrors the character-by-character quoted-field handling in
 * [io.github.tabssh.ssh.config.BulkImportParser].
 */
object DomainCsvImportExport {

    private const val TAG = "DomainCsvImportExport"

    const val HEADER = "Domain Name,Domain privacy protection status,Domain status at NC,Domain auto-renew status,Domain expiration date"

    // Registrar exports without a privacy-protection column (e.g. the
    // shorter "Domain Name,Domain status,Domain auto-renew status,Domain
    // expiration date" shape) leave the field genuinely unknown rather than
    // "off" — record that as "N/A" instead of an empty string so the UI
    // doesn't render a blank value next to the other columns.
    private const val PRIVACY_UNSET = "N/A"

    // Registrar CSV exports don't share a column order or exact header text
    // (verified against NameCheap's own docs; GoDaddy and Porkbun don't
    // publish an exact header schema), so each logical field is matched
    // against every synonym below, case-insensitively, with whitespace/
    // punctuation ignored (see normalizeHeader()).
    // Synonyms are run through normalizeHeader() below so punctuation like
    // the hyphen in "auto-renew" doesn't have to match a header's hyphen
    // exactly — both sides collapse to the same letters-and-spaces form.
    private val COLUMN_SYNONYMS: Map<String, List<String>> = mapOf(
        "domainName" to listOf("domain name", "domain", "domainname", "name"),
        "privacyProtection" to listOf(
            "domain privacy protection status", "privacy protection", "privacy", "whois privacy",
            "id protection", "domain privacy"
        ),
        "statusAtRegistrar" to listOf(
            "domain status at nc", "status", "domain status", "locked", "domain lock status"
        ),
        "autoRenew" to listOf(
            "domain auto-renew status", "auto renew", "auto-renew", "autorenew", "renewal", "renew"
        ),
        "expirationDate" to listOf(
            "domain expiration date", "expiration date", "expiry date", "expires", "expiration",
            "exp date", "renewal date", "valid until"
        )
    ).mapValues { (_, synonyms) -> synonyms.map { normalizeHeader(it) } }

    // Tried in order against each expiration-date cell — NameCheap uses
    // "MMM dd yyyy" ("Dec 16 2026"); GoDaddy- and Porkbun-style exports
    // commonly use a slash or ISO date instead. All parsed/formatted in
    // UTC so a date string never shifts by a day across timezones.
    private val DATE_FORMATS: List<ThreadLocal<SimpleDateFormat>> = listOf(
        "MMM dd yyyy", "MM/dd/yyyy", "M/d/yyyy", "yyyy-MM-dd", "MMM d, yyyy", "MMMM d, yyyy"
    ).map { pattern ->
        ThreadLocal.withInitial {
            SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
        }
    }

    private val DATE_FORMAT: ThreadLocal<SimpleDateFormat> get() = DATE_FORMATS[0]

    // Single accessor for the per-thread SimpleDateFormat idiom used by
    // every ThreadLocal field above — ThreadLocal.withInitial() guarantees
    // get() is never null, so the force-unwrap is safe here (mirrors the
    // same pattern in VpsMarkdownImportExport).
    private fun ThreadLocal<SimpleDateFormat>.format(): SimpleDateFormat = get()!!

    data class ParseResult(val domains: List<Domain>, val warnings: List<String>)

    /** Lowercased, whitespace/punctuation-collapsed header text for synonym matching. */
    private fun normalizeHeader(value: String): String =
        value.trim().lowercase(Locale.US).replace(Regex("""[^a-z]+"""), " ").trim().replace(Regex("""\s+"""), " ")

    /** Best-effort expiration date parse tried against every recognized registrar date format. */
    private fun parseExpirationDate(text: String): Long? {
        if (text.isBlank()) return null
        for (fmt in DATE_FORMATS) {
            try {
                val parsed = fmt.format().parse(text)
                if (parsed != null) return parsed.time
            } catch (_: ParseException) {
                // Try the next format.
            }
        }
        return null
    }

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

        val headerFields = splitCsvLine(lines[0]).map { normalizeHeader(it) }
        val columnIndex: Map<String, Int> = COLUMN_SYNONYMS.mapNotNull { (field, synonyms) ->
            val idx = headerFields.indexOfFirst { it in synonyms }
            if (idx >= 0) field to idx else null
        }.toMap()

        // A recognized header needs at minimum a domain-name column — any
        // other combination of matched/unmatched columns still works
        // (missing fields just import as blank/null), but with none matched
        // at all this isn't a header row, so fall back to the original
        // fixed NameCheap 5-column order for backward compatibility.
        val hasRecognizedHeader = columnIndex.containsKey("domainName")
        val startIndex = if (hasRecognizedHeader) 1 else 0

        for ((idx, line) in lines.withIndex()) {
            if (idx < startIndex) continue
            val fields = splitCsvLine(line)

            val domainName: String
            val privacy: String
            val status: String
            val autoRenew: String
            val expirationText: String
            if (hasRecognizedHeader) {
                fun field(name: String): String = columnIndex[name]?.let { fields.getOrNull(it)?.trim() }.orEmpty()
                domainName = field("domainName")
                privacy = field("privacyProtection")
                status = field("statusAtRegistrar")
                autoRenew = field("autoRenew")
                expirationText = field("expirationDate")
            } else {
                if (fields.size < 5) {
                    warnings.add("Line ${idx + 1}: expected 5 fields, found ${fields.size} — skipped")
                    continue
                }
                domainName = fields[0].trim()
                privacy = fields[1].trim()
                status = fields[2].trim()
                autoRenew = fields[3].trim()
                expirationText = fields[4].trim()
            }

            if (domainName.isEmpty()) {
                warnings.add("Line ${idx + 1}: empty domain name — skipped")
                continue
            }
            val expirationDate = expirationText.takeIf { it.isNotBlank() }?.let {
                parseExpirationDate(it) ?: run {
                    warnings.add("Line ${idx + 1}: could not parse expiration date '$it'")
                    null
                }
            }
            domains.add(
                Domain(
                    id = UUID.randomUUID().toString(),
                    domainName = domainName,
                    privacyProtection = privacy.ifBlank { PRIVACY_UNSET },
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
            val expiration = d.expirationDate?.let { DATE_FORMAT.format().format(it) }.orEmpty()
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
