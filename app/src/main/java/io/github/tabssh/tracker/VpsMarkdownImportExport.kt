package io.github.tabssh.tracker

import io.github.tabssh.storage.database.entities.VpsHost
import io.github.tabssh.utils.RenewalUrgency
import io.github.tabssh.utils.logging.Logger
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Best-effort Markdown import/export for the VPS Hosting Tracker, matching
 * the grouped-by-tenant layout of `~/Documents/VPS.md`:
 *
 * ```
 * ## Tenant   - example-host.com
 * dns         - 198.51.100.10   - 2001:db8:12:47ab::1     [40Gx2G]   [dns.example.com]    [June 7th, yearly    $79.99]  [primary dns/backup mx]
 * ----------------------------------------------
 * ```
 *
 * This is human-formatted, whitespace-aligned text, not strict data — the
 * parser is deliberately permissive (bracket-group extraction + positional
 * token assignment) rather than a strict grammar, and every field beyond
 * hostname/ipv4/ipv6 tolerates being missing. A top-level title line, blank
 * lines, and an optional wrapping ` ```text ` / ` ``` ` fenced-code-block
 * (common when the source note is kept in a Markdown-aware editor) are all
 * skipped rather than misread as data rows. Recognized billing-cycle
 * keywords: yearly/annually, monthly, weekly, daily, biennially/biannually
 * (every 2 years), triennially/triannually (every 3 years), and an
 * arbitrary "N years"/"N months"/"N weeks" (or "yr(s)"/"mo(s)"/"wk(s)")
 * cycle such as "5 years" or "10 years" for longer prepaid terms. A fully
 * explicit one-time date with no recognized cycle word is kept as-is
 * (`billingCycle = null`) rather than treated as an error.
 */
object VpsMarkdownImportExport {

    private const val TAG = "VpsMarkdownImportExport"

    private const val SEPARATOR = "----------------------------------------------"
    private val HEADER_REGEX = Regex("""^##\s*Tenant\s*-\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)
    private val BRACKET_REGEX = Regex("""\[([^\]]*)]""")
    private val ORDINAL_SUFFIX_REGEX = Regex("""(\d+)(st|nd|rd|th)""", RegexOption.IGNORE_CASE)

    private val EXACT_DATE_FORMATS: List<ThreadLocal<SimpleDateFormat>> = listOf(
        "MMMM d, yyyy", "MMM d, yyyy", "MMMM d yyyy", "MMM d yyyy"
    ).map { pattern ->
        ThreadLocal.withInitial {
            SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = true
            }
        }
    }
    private val MONTH_DAY_FORMAT: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("MMMM d", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = true
        }
    }
    private val EXPORT_EXACT_DATE_FORMAT: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("MMMM d, yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    // Single accessor for the per-thread SimpleDateFormat idiom used by all
    // three ThreadLocal fields above — ThreadLocal.withInitial() guarantees
    // get() is never null; checkNotNull states that invariant without
    // a force-unwrap (AI.md PART 0 bans `!!` outside tests).
    private fun ThreadLocal<SimpleDateFormat>.format(): SimpleDateFormat =
        checkNotNull(get()) { "ThreadLocal SimpleDateFormat initializer returned null" }

    // One pass over the renewal text finds the cycle wherever it sits, so the
    // same expression both normalizes it and tells us which span to strip.
    // Ordering matters: "biannually"/"triannually" must precede "annually" or
    // the alternation would match the "annually" tail and report a plain
    // yearly cycle. A leading "every" is swallowed with the match so stripping
    // it never leaves a dangling word behind, and the letter guards keep a
    // cycle word from being matched inside a longer one — without the trailing
    // guard "August 10 monthly" would read "10 month" as an N-unit cycle and
    // leave "August ly" behind as the date.
    private val CYCLE_REGEX = Regex(
        """(?<![A-Za-z])(?:every\s+)?(?:(\d+)\s*(years?|yrs?|months?|mos?|weeks?|wks?)|""" +
            """(biennially|biennial|biannually|biannual|triennially|triennial|""" +
            """triannually|triannual|yearly|annually|annual|monthly|weekly|daily))(?![A-Za-z])""",
        RegexOption.IGNORE_CASE
    )

    /**
     * A renewal field split into the part that is actually a date and the
     * billing cycle it carried. [dateText] is what the tracker stores in
     * `renewalRaw`; the cycle lives only in `billingCycle` so the UI never
     * renders "April 30, 2027, triennially" as if the cadence were part of
     * the date.
     */
    data class RenewalParts(val dateText: String?, val billingCycle: String?)

    /**
     * Pull the billing cycle out of a free-text renewal field.
     *
     * No recognizable cycle word → the text is returned unchanged with a null
     * cycle; a field that is nothing but a cycle ("yearly") leaves a null
     * [RenewalParts.dateText]. Both spellings of the 2-/3-year cadences are
     * accepted on input and normalized to the correct term for storage
     * ("biannually" is a common misspelling of "biennially"; the correct term
     * means "twice a year", a different cadence).
     */
    fun splitRenewal(raw: String?): RenewalParts {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return RenewalParts(null, null)

        val match = CYCLE_REGEX.find(text) ?: return RenewalParts(text, null)
        val cycle = normalizeCycle(match) ?: return RenewalParts(text, null)

        val remainder = tidyRenewalText(text.removeRange(match.range))
        return RenewalParts(remainder.ifBlank { null }, cycle)
    }

    private fun normalizeCycle(match: MatchResult): String? {
        val count = match.groupValues[1]
        val unit = match.groupValues[2]
        if (count.isNotEmpty() && unit.isNotEmpty()) {
            val n = count.toIntOrNull() ?: return null
            val lowerUnit = unit.lowercase(Locale.US)
            val unitWord = when {
                lowerUnit.startsWith("year") || lowerUnit.startsWith("yr") -> "year"
                lowerUnit.startsWith("month") || lowerUnit.startsWith("mo") -> "month"
                else -> "week"
            }
            return "$n $unitWord${if (n == 1) "" else "s"}"
        }
        return when (match.groupValues[3].lowercase(Locale.US)) {
            "biennially", "biennial", "biannually", "biannual" -> "biennially"
            "triennially", "triennial", "triannually", "triannual" -> "triennially"
            "yearly", "annually", "annual" -> "yearly"
            "monthly" -> "monthly"
            "weekly" -> "weekly"
            "daily" -> "daily"
            else -> null
        }
    }

    /** Collapse the whitespace and orphaned commas that removing a cycle span leaves behind. */
    private fun tidyRenewalText(text: String): String {
        var out = text.replace(Regex("""\s+"""), " ")
        while (out.contains(Regex("""\s*,\s*,"""))) {
            out = out.replace(Regex("""\s*,\s*,"""), ",")
        }
        return out.trim().trim(',', ' ').trim()
    }

    data class ParseResult(val hosts: List<VpsHost>, val warnings: List<String>)

    fun parse(text: String): ParseResult {
        val warnings = mutableListOf<String>()
        val hosts = mutableListOf<VpsHost>()
        val now = System.currentTimeMillis()
        var currentTenant: String? = null

        for ((idx, rawLine) in text.lineSequence().withIndex()) {
            val line = rawLine.trimEnd()
            if (line.isBlank() || line.trim() == SEPARATOR || line.trim().all { it == '-' }) continue

            val headerMatch = HEADER_REGEX.find(line.trim())
            if (headerMatch != null) {
                currentTenant = headerMatch.groupValues[1].trim()
                continue
            }
            if (line.trim().startsWith("#")) continue
            // Personal notes often wrap the tenant/host table in a fenced code
            // block (```text ... ```) to keep alignment in a Markdown viewer —
            // skip fence markers rather than misreading them as a data row.
            if (line.trim().startsWith("```")) continue

            val tenant = currentTenant
            if (tenant.isNullOrBlank()) {
                warnings.add("Line ${idx + 1}: data row before any '## Tenant - <provider>' header — skipped")
                continue
            }

            val brackets = BRACKET_REGEX.findAll(line).map { it.groupValues[1].trim() }.toList()
            val preBracket = BRACKET_REGEX.replace(line, "").trim()
            // Column separator is "-" preceded by whitespace and followed by whitespace or
            // end-of-segment (a blank trailing field leaves "-" last after trim); the
            // whitespace requirement keeps hyphenated hostnames like "my-host" intact,
            // and the lookahead preserves empty tokens so blank fields keep their column.
            val idTokens = preBracket.split(Regex("""\s+-(?=\s|$)""")).map { it.trim() }
            val hostname = idTokens.getOrNull(0).orEmpty()
            if (hostname.isEmpty()) {
                warnings.add("Line ${idx + 1}: no hostname found — skipped")
                continue
            }
            val ipv4 = idTokens.getOrNull(1)?.takeIf { it.isNotBlank() }
            val ipv6 = idTokens.getOrNull(2)?.takeIf { it.isNotBlank() }

            val specs = brackets.getOrNull(0)?.takeIf { it.isNotBlank() }
            val linkedDomain = brackets.getOrNull(1)?.takeIf { it.isNotBlank() }
            val renewalBracket = brackets.getOrNull(2)?.takeIf { it.isNotBlank() }
            val description = brackets.getOrNull(3)?.takeIf { it.isNotBlank() }

            var renewalField: String? = null
            var price: String? = null
            if (renewalBracket != null) {
                val collapsed = renewalBracket.replace(Regex("""\s+"""), " ").trim()
                val tokens = collapsed.split(" ")
                val priceToken = tokens.lastOrNull()?.takeIf { it.startsWith("$") || it.equals("TBD", true) }
                if (tokens.size >= 2 && priceToken != null) {
                    price = priceToken
                    renewalField = tokens.dropLast(1).joinToString(" ").trim().ifBlank { null }
                } else {
                    renewalField = collapsed
                }
            }

            // The cycle is a separate field, not part of the date — strip it
            // from the stored text so the tracker shows "April 30, 2027"
            // rather than "April 30, 2027, triennially". Export re-attaches it.
            val (renewalRaw, billingCycle) = splitRenewal(renewalField)

            val renewalDate = renewalRaw?.let { parseBestEffortDate(it, billingCycle) }

            hosts.add(
                VpsHost(
                    id = UUID.randomUUID().toString(),
                    tenant = tenant,
                    hostname = hostname,
                    ipv4 = ipv4,
                    ipv6 = ipv6,
                    specs = specs,
                    linkedDomain = linkedDomain,
                    renewalRaw = renewalRaw,
                    renewalDate = renewalDate,
                    billingCycle = billingCycle,
                    price = price,
                    description = description,
                    createdAt = now,
                    modifiedAt = now
                )
            )
        }
        Logger.d(TAG, "Parsed ${hosts.size} VPS host(s), ${warnings.size} warning(s)")
        return ParseResult(hosts, warnings)
    }

    /**
     * Best-effort date parse for renewal text. Handles exact dates with a
     * 4-digit year ("May 15, 2027") and year-less recurring text ("June 7th,
     * yearly") by anchoring that month/day to this year, then projecting it
     * to the next occurrence using [billingCycle] (e.g. a "10" with a
     * "monthly" cycle rolls forward month-by-month rather than assuming a
     * yearly cadence — see [RenewalUrgency.effectiveDate], which this
     * delegates the rollover to so the two stay in sync). A null/unrecognized
     * cycle falls back to the previous yearly-recurrence behavior. Non-date
     * text ("Free/Never", "TBD") returns null.
     */
    fun parseBestEffortDate(rawText: String, billingCycle: String? = null): Long? {
        val cleaned = ORDINAL_SUFFIX_REGEX.replace(rawText, "$1").trim()
        if (cleaned.isEmpty()) return null

        for (fmt in EXACT_DATE_FORMATS) {
            try {
                val parsed = fmt.format().parse(cleaned)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
                // Try the next format.
            }
        }

        // Year-less recurring text: take the leading "Month Day" fragment
        // (everything up to the first comma), anchor it to this year, then
        // project forward to the next occurrence using the actual cycle.
        val monthDayFragment = cleaned.substringBefore(",").trim()
        return try {
            val parsed = MONTH_DAY_FORMAT.format().parse(monthDayFragment) ?: return null
            val target = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            target.time = parsed
            val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            target.set(Calendar.YEAR, now.get(Calendar.YEAR))
            RenewalUrgency.effectiveDate(target.timeInMillis, billingCycle ?: "yearly", now.timeInMillis)
        } catch (_: Exception) {
            null
        }
    }

    private fun renewalFieldText(h: VpsHost): String = buildString {
        append(h.renewalRaw.orEmpty())
        // `renewalRaw` no longer carries the cycle, so the round-tripped file
        // keeps its "<date>, <cycle> <price>" shape only if export puts it
        // back. Rows written before the split still embed it — appending a
        // second copy there would corrupt the field, so check first.
        val cycle = h.billingCycle
        if (!cycle.isNullOrBlank() && splitRenewal(h.renewalRaw).billingCycle == null) {
            if (isNotEmpty()) append(", ")
            append(cycle)
        }
        if (!h.price.isNullOrBlank()) {
            if (isNotEmpty()) append(' ')
            append(h.price)
        }
    }

    private fun bracket(value: String?): String = "[" + value.orEmpty() + "]"

    /**
     * Regenerate the grouped-by-tenant Markdown shape from DB rows, column-
     * aligned to match the hand-maintained source file's look: hostname/
     * ipv4/ipv6 padded to the widest value across the whole export (not
     * just the current tenant block, so alignment stays consistent even
     * for single-host blocks), and each bracketed field padded to the
     * widest bracketed value before the next field starts.
     */
    fun export(hosts: List<VpsHost>): String {
        val sb = StringBuilder()
        val byTenant = hosts.groupBy { it.tenant }.toSortedMap()

        val hostnameWidth = (hosts.maxOfOrNull { it.hostname.length } ?: 0) + 2
        val ipv4Width = (hosts.maxOfOrNull { (it.ipv4 ?: "").length } ?: 0) + 2
        val ipv6Width = (hosts.maxOfOrNull { (it.ipv6 ?: "").length } ?: 0) + 2
        val specsWidth = hosts.maxOfOrNull { bracket(it.specs).length } ?: 0
        val domainWidth = hosts.maxOfOrNull { bracket(it.linkedDomain).length } ?: 0
        val renewalWidth = hosts.maxOfOrNull { bracket(renewalFieldText(it)).length } ?: 0

        for ((tenant, tenantHosts) in byTenant) {
            sb.append("## Tenant   - ").append(tenant).append('\n')
            for (h in tenantHosts.sortedBy { it.hostname }) {
                sb.append(h.hostname.padEnd(hostnameWidth)).append("- ")
                sb.append((h.ipv4.orEmpty()).padEnd(ipv4Width)).append("- ")
                sb.append((h.ipv6.orEmpty()).padEnd(ipv6Width))
                sb.append(bracket(h.specs).padEnd(specsWidth)).append("  ")
                sb.append(bracket(h.linkedDomain).padEnd(domainWidth)).append("  ")
                sb.append(bracket(renewalFieldText(h)).padEnd(renewalWidth)).append("  ")
                sb.append(bracket(h.description))
                sb.append('\n')
            }
            sb.append(SEPARATOR).append('\n')
        }
        return sb.toString()
    }

    /** Format an entity's [VpsHost.renewalDate] as "Month d, yyyy" for display, or null if unset. */
    fun formatRenewalDate(epochMillis: Long): String = EXPORT_EXACT_DATE_FORMAT.format().format(epochMillis)
}
