package io.github.tabssh.tracker

import io.github.tabssh.storage.database.entities.Domain
import io.github.tabssh.storage.database.entities.VpsHost
import java.net.InetAddress

/**
 * Merge rules for re-importing the Domain and VPS trackers: a row in the file that is already
 * tracked updates that row in place instead of adding a duplicate. A domain is identified by its
 * name alone (it can only be registered once); a VPS host by its IPv4 or IPv6 address. Fields the
 * file does not carry (reminder settings, canceled state, created date, notes) stay as they were.
 */
object TrackerImportMerge {

    data class Outcome<T>(val rows: List<T>, val added: Int, val updated: Int)

    /** Domains match on the case-insensitive domain name; the same name twice in one file collapses to the later row. */
    fun mergeDomains(parsed: List<Domain>, existing: List<Domain>): Outcome<Domain> {
        val rows = LinkedHashMap<String, Domain>()
        val byName = HashMap<String, String>()
        for (d in existing) {
            rows[d.id] = d
            byName[domainKey(d.domainName)] = d.id
        }
        val existingIds = existing.mapTo(HashSet()) { it.id }
        val updatedIds = HashSet<String>()
        var added = 0
        for (incoming in parsed) {
            val key = domainKey(incoming.domainName)
            val priorId = byName[key]
            val prior = priorId?.let { rows[it] }
            if (prior == null) {
                rows[incoming.id] = incoming
                byName[key] = incoming.id
                added++
                continue
            }
            rows[prior.id] = incoming.copy(
                id = prior.id,
                createdAt = prior.createdAt,
                canceledAt = prior.canceledAt,
                notes = prior.notes,
                reminderDaysBefore = prior.reminderDaysBefore,
                // A new expiry is a new reminder cycle, so the "already reminded" mark no longer applies.
                lastReminderSentAt = if (incoming.expirationDate == prior.expirationDate) prior.lastReminderSentAt else null
            )
            if (prior.id in existingIds) updatedIds += prior.id
        }
        return Outcome(rows.values.toList(), added, updatedIds.size)
    }

    /**
     * VPS hosts match on IPv4, then IPv6. A row with no address at all (still provisioning) falls
     * back to tenant plus hostname. The same host twice in one file collapses to the later row.
     */
    fun mergeVps(parsed: List<VpsHost>, existing: List<VpsHost>): Outcome<VpsHost> {
        val rows = LinkedHashMap<String, VpsHost>()
        val byIpv4 = HashMap<String, String>()
        val byIpv6 = HashMap<String, String>()
        val byName = HashMap<String, String>()
        fun index(h: VpsHost) {
            normalizeIpv4(h.ipv4)?.let { byIpv4[it] = h.id }
            normalizeIpv6(h.ipv6)?.let { byIpv6[it] = h.id }
            if (normalizeIpv4(h.ipv4) == null && normalizeIpv6(h.ipv6) == null) byName[vpsNameKey(h)] = h.id
        }
        for (h in existing) {
            rows[h.id] = h
            index(h)
        }
        val existingIds = existing.mapTo(HashSet()) { it.id }
        val updatedIds = HashSet<String>()
        var added = 0
        for (incoming in parsed) {
            val v4 = normalizeIpv4(incoming.ipv4)
            val v6 = normalizeIpv6(incoming.ipv6)
            val priorId = v4?.let { byIpv4[it] }
                ?: v6?.let { byIpv6[it] }
                ?: if (v4 == null && v6 == null) byName[vpsNameKey(incoming)] else null
            val prior = priorId?.let { rows[it] }
            if (prior == null) {
                rows[incoming.id] = incoming
                index(incoming)
                added++
                continue
            }
            val merged = incoming.copy(
                id = prior.id,
                createdAt = prior.createdAt,
                canceledAt = prior.canceledAt,
                reminderDaysBefore = prior.reminderDaysBefore,
                // A new renewal date is a new reminder cycle, so the "already reminded" mark no longer applies.
                lastReminderSentAt = if (incoming.renewalDate == prior.renewalDate) prior.lastReminderSentAt else null
            )
            rows[prior.id] = merged
            index(merged)
            if (prior.id in existingIds) updatedIds += prior.id
        }
        return Outcome(rows.values.toList(), added, updatedIds.size)
    }

    private fun domainKey(name: String): String = name.trim().lowercase()

    private fun vpsNameKey(h: VpsHost): String = "${h.tenant.trim().lowercase()}\u0000${h.hostname.trim().lowercase()}"

    internal fun normalizeIpv4(value: String?): String? = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** Lowercases and expands an IPv6 literal so `2001:db8::1` and `2001:0DB8:0:0:0:0:0:1` are the same address. */
    internal fun normalizeIpv6(value: String?): String? {
        val text = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (':' !in text || !text.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' }) return text
        return try {
            InetAddress.getByName(text).hostAddress?.lowercase() ?: text
        } catch (e: Exception) {
            text
        }
    }
}
