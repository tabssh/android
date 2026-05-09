package io.github.tabssh.hypervisor.oci

/**
 * One profile inside an OCI `~/.oci/config` file. The fields below are
 * exactly what we need to drive onboarding — `keyFile` is the *path
 * hint* the config wrote (only used to suggest a basename in the SAF
 * pem-picker; we don't follow it). `securityTokenFile`, when present,
 * means this profile uses a 1-hour CLI-renewable session token, which
 * is unsupported (we hard-reject during onboarding).
 */
data class OciConfigProfile(
    val name: String,
    val userOcid: String?,
    val fingerprint: String?,
    val keyFile: String?,
    val tenancyOcid: String?,
    val region: String?,
    val securityTokenFile: String?,
    /** Captures any other key=value lines so we don't silently lose them
     *  during a round-trip if we ever need to. */
    val extras: Map<String, String> = emptyMap()
) {
    /** True when this profile uses session-token auth, which TabSSH does
     *  not support (1-hour expiry, CLI-only renewal). */
    val usesSessionToken: Boolean get() = !securityTokenFile.isNullOrBlank()

    /** True when the four fields needed for API-key auth are populated. */
    val isComplete: Boolean
        get() = !userOcid.isNullOrBlank() &&
            !fingerprint.isNullOrBlank() &&
            !tenancyOcid.isNullOrBlank() &&
            !region.isNullOrBlank()
}

/**
 * Tiny INI-style parser for OCI's `~/.oci/config`. The format is
 * `[profile]` headers + `key=value` lines, with `#` and `;` comments.
 * Whitespace around `=` is trimmed. Quoted values are passed through
 * untouched (we don't strip quotes; OCI's CLI doesn't write them).
 *
 * No external INI dependency — keeps the cold-start cost off the build.
 */
object OciConfigParser {

    private val knownKeys = setOf(
        "user", "fingerprint", "key_file", "tenancy", "region",
        "security_token_file", "pass_phrase"
    )

    /**
     * Parse the contents of a config file. Returns one [OciConfigProfile]
     * per `[name]` section. A leading no-section block (rare) is
     * promoted to a `DEFAULT` section. Empty input returns empty list.
     */
    fun parse(text: String): List<OciConfigProfile> {
        val sections = LinkedHashMap<String, MutableMap<String, String>>()
        var current: String? = null
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1, line.length - 1).trim().ifEmpty { "DEFAULT" }
                sections.getOrPut(current) { mutableMapOf() }
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            val sectionName = current ?: "DEFAULT".also {
                sections.getOrPut(it) { mutableMapOf() }
            }
            sections[sectionName]!![key] = value
        }

        return sections.map { (name, kv) ->
            val extras = kv.filterKeys { it !in knownKeys }
            OciConfigProfile(
                name = name,
                userOcid = kv["user"],
                fingerprint = kv["fingerprint"],
                keyFile = kv["key_file"],
                tenancyOcid = kv["tenancy"],
                region = kv["region"],
                securityTokenFile = kv["security_token_file"],
                extras = extras
            )
        }
    }
}
