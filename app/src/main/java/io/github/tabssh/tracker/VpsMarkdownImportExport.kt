package io.github.tabssh.tracker

import io.github.tabssh.storage.database.entities.VpsHost
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
 * ## Tenant   - hosteons.com
 * dns         - 82.29.128.43    - 2402:d0c0:12:47ab::1   [40Gx2G]   [dns.casjaydns.com]  [June 7th, yearly    $79.99]  [primary dns/backup mx]
 * ----------------------------------------------
 * ```
 *
 * This is human-formatted, whitespace-aligned text, not strict data — the
 * parser is deliberately permissive (bracket-group extraction + positional
 * token assignment) rather than a strict grammar, and every field beyond
 * hostname/ipv4/ipv6 tolerates being missing.
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
    // get() is never null, so the force-unwrap is safe here.
    private fun ThreadLocal<SimpleDateFormat>.format(): SimpleDateFormat = get()!!

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

            val tenant = currentTenant
            if (tenant.isNullOrBlank()) {
                warnings.add("Line ${idx + 1}: data row before any '## Tenant - <provider>' header — skipped")
                continue
            }

            val brackets = BRACKET_REGEX.findAll(line).map { it.groupValues[1].trim() }.toList()
            val preBracket = BRACKET_REGEX.replace(line, "").trim()
            val idTokens = preBracket.split(Regex("""\s*-\s*""")).map { it.trim() }
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

            var renewalRaw: String? = null
            var price: String? = null
            if (renewalBracket != null) {
                val collapsed = renewalBracket.replace(Regex("""\s+"""), " ").trim()
                val tokens = collapsed.split(" ")
                val priceToken = tokens.lastOrNull()?.takeIf { it.startsWith("$") || it.equals("TBD", true) }
                if (tokens.size >= 2 && priceToken != null) {
                    price = priceToken
                    renewalRaw = tokens.dropLast(1).joinToString(" ").trim().ifBlank { null }
                } else {
                    renewalRaw = collapsed
                }
            }

            val billingCycle = renewalRaw?.let { raw ->
                val lower = raw.lowercase(Locale.US)
                when {
                    "yearly" in lower || "annual" in lower -> "yearly"
                    "monthly" in lower -> "monthly"
                    "weekly" in lower -> "weekly"
                    "daily" in lower -> "daily"
                    else -> null
                }
            }

            val renewalDate = renewalRaw?.let { parseBestEffortDate(it) }

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
     * yearly") by computing the next occurrence of that month/day from now.
     * Non-date text ("Free/Never", "TBD") returns null.
     */
    fun parseBestEffortDate(rawText: String): Long? {
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
        // (everything up to the first comma) and project the next occurrence.
        val monthDayFragment = cleaned.substringBefore(",").trim()
        return try {
            val parsed = MONTH_DAY_FORMAT.format().parse(monthDayFragment) ?: return null
            val target = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            target.time = parsed
            val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            target.set(Calendar.YEAR, now.get(Calendar.YEAR))
            if (target.before(now)) {
                target.add(Calendar.YEAR, 1)
            }
            target.timeInMillis
        } catch (_: Exception) {
            null
        }
    }

    /** Regenerate the grouped-by-tenant Markdown shape from DB rows. */
    fun export(hosts: List<VpsHost>): String {
        val sb = StringBuilder()
        val byTenant = hosts.groupBy { it.tenant }.toSortedMap()
        for ((tenant, tenantHosts) in byTenant) {
            sb.append("## Tenant   - ").append(tenant).append('\n')
            for (h in tenantHosts.sortedBy { it.hostname }) {
                sb.append(h.hostname)
                sb.append("   - ").append(h.ipv4.orEmpty())
                sb.append("   - ").append(h.ipv6.orEmpty())
                sb.append("   [").append(h.specs.orEmpty()).append(']')
                sb.append("   [").append(h.linkedDomain.orEmpty()).append(']')
                val renewalField = buildString {
                    append(h.renewalRaw.orEmpty())
                    if (!h.price.isNullOrBlank()) {
                        if (isNotEmpty()) append("    ")
                        append(h.price)
                    }
                }
                sb.append("   [").append(renewalField).append(']')
                sb.append("   [").append(h.description.orEmpty()).append(']')
                sb.append('\n')
            }
            sb.append(SEPARATOR).append('\n')
        }
        return sb.toString()
    }

    /** Format an entity's [VpsHost.renewalDate] as "Month d, yyyy" for display, or null if unset. */
    fun formatRenewalDate(epochMillis: Long): String = EXPORT_EXACT_DATE_FORMAT.format().format(epochMillis)
}
