package io.github.tabssh.ssh.config

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.utils.logging.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Wave 1.6 — Bulk import from CSV / JSON / PuTTY .reg / Terraform .tf.
 *
 * Returns parsed candidates as [ParsedHost] (a flat preview shape) so the
 * caller can show a confirm UI before persisting. Conversion to
 * [ConnectionProfile] is one-shot via [toConnectionProfile].
 *
 * Format detection is best-effort:
 *  - First non-blank char `[` or first line `[` → JSON
 *  - First line contains `Windows Registry Editor` → PuTTY .reg
 *  - Contains `resource "aws_instance"` etc. → Terraform
 *  - Otherwise CSV (header row required)
 *
 * We deliberately keep the parser dependency-free (org.json + regex) so
 * we don't drag in another lib for an import-once feature.
 */
object BulkImportParser {

    private const val TAG = "BulkImportParser"

    enum class Format { CSV, JSON, PUTTY_REG, TERRAFORM, UNKNOWN }

    /**
     * Flat preview row. Anything missing is null and the UI can show a
     * default-or-prompt. We don't persist this directly — call
     * [toConnectionProfile] when the user confirms.
     */
    data class ParsedHost(
        val name: String,
        val host: String,
        val port: Int = 22,
        val username: String? = null,
        val authType: String? = null,   // "password" / "publickey" / null = ask
        val identityFile: String? = null, // for display only — keys must be imported separately
        val groupName: String? = null,
        val source: String                // human-readable origin, e.g. "CSV row 4"
    ) {
        fun toConnectionProfile(): ConnectionProfile {
            val finalUser = username?.takeIf { it.isNotBlank() } ?: "root"
            val finalAuth = authType?.takeIf { it.isNotBlank() } ?: "password"
            return ConnectionProfile(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { "$finalUser@$host" },
                host = host,
                port = port,
                username = finalUser,
                authType = finalAuth,
                groupId = groupName,
                createdAt = System.currentTimeMillis()
            )
        }
    }

    data class ParseResult(
        val format: Format,
        val hosts: List<ParsedHost>,
        val warnings: List<String>
    )

    fun detectFormat(text: String): Format {
        val sample = text.take(2048).trimStart()
        return when {
            sample.startsWith("[") || sample.startsWith("{") -> Format.JSON
            sample.contains("Windows Registry Editor", ignoreCase = true) ||
                sample.contains("[HKEY_CURRENT_USER\\Software\\SimonTatham\\PuTTY", ignoreCase = true) -> Format.PUTTY_REG
            Regex("""resource\s+"(aws_instance|digitalocean_droplet|google_compute_instance|hcloud_server|linode_instance|vsphere_virtual_machine)"""")
                .containsMatchIn(sample) -> Format.TERRAFORM
            sample.contains(",") && sample.lineSequence().firstOrNull()?.let { line ->
                line.split(",").any { it.trim().equals("host", true) || it.trim().equals("hostname", true) }
            } == true -> Format.CSV
            else -> Format.UNKNOWN
        }
    }

    fun parse(text: String, hint: Format = Format.UNKNOWN): ParseResult {
        val format = if (hint != Format.UNKNOWN) hint else detectFormat(text)
        return try {
            when (format) {
                Format.CSV -> parseCsv(text)
                Format.JSON -> parseJson(text)
                Format.PUTTY_REG -> parsePuttyReg(text)
                Format.TERRAFORM -> parseTerraform(text)
                Format.UNKNOWN -> ParseResult(Format.UNKNOWN, emptyList(),
                    listOf("Could not detect format. Supported: CSV (header row), JSON array, PuTTY .reg, Terraform .tf"))
            }
        } catch (e: Exception) {
            Logger.e(TAG, "parse failed for format=$format", e)
            ParseResult(format, emptyList(), listOf("Parse error: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    // ---------- CSV ----------
    private fun parseCsv(text: String): ParseResult {
        val warnings = mutableListOf<String>()
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        if (lines.isEmpty()) return ParseResult(Format.CSV, emptyList(), listOf("CSV is empty"))

        val header = splitCsvLine(lines[0]).map { it.trim().lowercase() }
        val nameIdx = header.indexOfFirst { it == "name" || it == "label" || it == "alias" }
        val hostIdx = header.indexOfFirst { it == "host" || it == "hostname" || it == "address" || it == "ip" }
        val portIdx = header.indexOfFirst { it == "port" }
        val userIdx = header.indexOfFirst { it == "user" || it == "username" }
        val authIdx = header.indexOfFirst { it == "auth" || it == "auth_type" || it == "authtype" }
        val keyIdx = header.indexOfFirst { it == "identity" || it == "identityfile" || it == "key" || it == "keyfile" }
        val groupIdx = header.indexOfFirst { it == "group" || it == "folder" || it == "tag" }

        if (hostIdx < 0) return ParseResult(Format.CSV, emptyList(),
            listOf("CSV header must include 'host' (or 'hostname'/'address'/'ip')"))

        val hosts = lines.drop(1).mapIndexedNotNull { i, line ->
            val cols = splitCsvLine(line)
            val host = cols.getOrNull(hostIdx)?.trim().orEmpty()
            if (host.isBlank()) {
                warnings.add("Row ${i + 2}: blank host — skipped")
                return@mapIndexedNotNull null
            }
            val name = cols.getOrNull(nameIdx)?.trim().orEmpty().ifBlank { host }
            val port = cols.getOrNull(portIdx)?.trim()?.toIntOrNull() ?: 22
            ParsedHost(
                name = name,
                host = host,
                port = port,
                username = cols.getOrNull(userIdx)?.trim()?.takeIf { it.isNotBlank() },
                authType = cols.getOrNull(authIdx)?.trim()?.lowercase()?.takeIf { it.isNotBlank() },
                identityFile = cols.getOrNull(keyIdx)?.trim()?.takeIf { it.isNotBlank() },
                groupName = cols.getOrNull(groupIdx)?.trim()?.takeIf { it.isNotBlank() },
                source = "CSV row ${i + 2}"
            )
        }
        return ParseResult(Format.CSV, hosts, warnings)
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

    // ---------- JSON ----------
    private fun parseJson(text: String): ParseResult {
        val warnings = mutableListOf<String>()
        val trimmed = text.trim()
        val arr: JSONArray = if (trimmed.startsWith("[")) JSONArray(trimmed)
            else JSONArray().put(JSONObject(trimmed))

        val hosts = mutableListOf<ParsedHost>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
            if (obj == null) {
                warnings.add("Index $i is not an object — skipped")
                continue
            }
            val host = (obj.optString("host", "").ifBlank { obj.optString("hostname") })
                .ifBlank { obj.optString("address") }
                .ifBlank { obj.optString("ip") }
            if (host.isBlank()) {
                warnings.add("Index $i: missing host — skipped")
                continue
            }
            hosts.add(
                ParsedHost(
                    name = obj.optString("name").ifBlank { obj.optString("label") }.ifBlank { host },
                    host = host,
                    port = obj.optInt("port", 22),
                    username = obj.optString("user", obj.optString("username", "")).takeIf { it.isNotBlank() },
                    authType = obj.optString("auth", obj.optString("authType", "")).lowercase().takeIf { it.isNotBlank() },
                    identityFile = obj.optString("identityFile", obj.optString("key", "")).takeIf { it.isNotBlank() },
                    groupName = obj.optString("group", obj.optString("folder", "")).takeIf { it.isNotBlank() },
                    source = "JSON [$i]"
                )
            )
        }
        return ParseResult(Format.JSON, hosts, warnings)
    }

    // ---------- PuTTY .reg ----------
    /**
     * PuTTY exports sessions via `regedit /e file.reg HKCU\Software\SimonTatham\PuTTY\Sessions`.
     * Each block looks like:
     *   [HKEY_CURRENT_USER\Software\SimonTatham\PuTTY\Sessions\My%20Server]
     *   "HostName"="example.com"
     *   "PortNumber"=dword:00000016
     *   "UserName"="root"
     *   "PublicKeyFile"="C:\\\\keys\\\\id_rsa.ppk"
     *
     * Session name uses URL-style %xx escaping for spaces and special chars.
     */
    private fun parsePuttyReg(text: String): ParseResult {
        val warnings = mutableListOf<String>()
        val sessionRe = Regex(
            """\[HKEY_CURRENT_USER\\Software\\SimonTatham\\PuTTY\\Sessions\\([^\]]+)\]([\s\S]*?)(?=\n\[|\z)""",
            RegexOption.IGNORE_CASE
        )
        val hosts = mutableListOf<ParsedHost>()
        for (m in sessionRe.findAll(text)) {
            val sessionName = decodeRegName(m.groupValues[1])
            val body = m.groupValues[2]
            val hostName = regString(body, "HostName")
            if (hostName.isNullOrBlank()) {
                warnings.add("Session '$sessionName': no HostName — skipped")
                continue
            }
            val port = regDword(body, "PortNumber") ?: 22
            val user = regString(body, "UserName")
            val keyFile = regString(body, "PublicKeyFile")
            hosts.add(
                ParsedHost(
                    name = sessionName,
                    host = hostName,
                    port = port,
                    username = user,
                    authType = if (!keyFile.isNullOrBlank()) "publickey" else null,
                    identityFile = keyFile,
                    source = "PuTTY '$sessionName'"
                )
            )
        }
        if (hosts.isEmpty()) warnings.add("No PuTTY session blocks found")
        return ParseResult(Format.PUTTY_REG, hosts, warnings)
    }

    private fun regString(body: String, key: String): String? {
        val m = Regex(""""$key"="((?:[^"\\]|\\.)*)"""", RegexOption.IGNORE_CASE).find(body) ?: return null
        return m.groupValues[1]
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
    }

    private fun regDword(body: String, key: String): Int? {
        val m = Regex(""""$key"=dword:([0-9a-fA-F]+)""", RegexOption.IGNORE_CASE).find(body) ?: return null
        return m.groupValues[1].toIntOrNull(16)
    }

    /** Decode PuTTY's %20-style escapes in session names. */
    private fun decodeRegName(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val v = hex.toIntOrNull(16)
                if (v != null) { out.append(v.toChar()); i += 3; continue }
            }
            out.append(c); i++
        }
        return out.toString()
    }

    // ---------- Terraform ----------
    /**
     * Pragmatic regex match for resource blocks with a `connection { ... }` block
     * or top-level `host`/`user`/`port`. Supports the common cases:
     *   resource "aws_instance" "web" { ami = ... }
     *   resource "digitalocean_droplet" "x" { name = "web"; ipv4_address = "..." }
     *
     * We don't try to be HCL-complete — that's a parser project of its own. The
     * goal is "user dropped a tf file, get most of their hosts in one go".
     */
    private fun parseTerraform(text: String): ParseResult {
        val warnings = mutableListOf<String>()
        val resourceRe = Regex(
            """resource\s+"([^"]+)"\s+"([^"]+)"\s*\{([\s\S]*?)\n\}""",
            RegexOption.MULTILINE
        )
        val hosts = mutableListOf<ParsedHost>()
        for (m in resourceRe.findAll(text)) {
            val type = m.groupValues[1]
            val name = m.groupValues[2]
            val body = m.groupValues[3]
            val host = tfString(body, "host")
                ?: tfString(body, "ipv4_address")
                ?: tfString(body, "public_ip")
                ?: tfString(body, "ip_address")
                ?: tfString(body, "hostname")
            if (host.isNullOrBlank()) {
                warnings.add("$type.$name: no host attribute found")
                continue
            }
            hosts.add(
                ParsedHost(
                    name = tfString(body, "name") ?: name,
                    host = host,
                    port = tfString(body, "port")?.toIntOrNull() ?: 22,
                    username = tfString(body, "user") ?: tfString(body, "username"),
                    authType = null,
                    identityFile = tfString(body, "private_key") ?: tfString(body, "key_file"),
                    source = "Terraform $type.$name"
                )
            )
        }
        if (hosts.isEmpty()) warnings.add("No matching resource blocks found")
        return ParseResult(Format.TERRAFORM, hosts, warnings)
    }

    private fun tfString(body: String, key: String): String? {
        val m = Regex("""(?m)^\s*$key\s*=\s*"([^"]*)"""").find(body) ?: return null
        return m.groupValues[1].takeIf { it.isNotBlank() }
    }
}
