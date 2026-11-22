package io.github.tabssh.hypervisor.proxmox

/**
 * Proxmox hands out an API token as one string, `user@realm!tokenid:secret` (also seen as
 * `user@realm!tokenid=secret`). The client wants the id part (`user@realm!tokenid`) as the user
 * name and the secret on its own, so a pasted full token is split here.
 */
object ProxmoxTokenParser {

    data class Parsed(val tokenUser: String, val secret: String)

    private val FULL_TOKEN = Regex("""^([^@!:=\s]+@[^!:=\s]+![^!:=\s]+)[:=](.+)$""")

    /** Splits a full token; null when [input] is not one (an id alone, a bare secret, a password). */
    fun parse(input: String): Parsed? {
        val match = FULL_TOKEN.matchEntire(input.trim()) ?: return null
        return Parsed(match.groupValues[1], match.groupValues[2])
    }

    /**
     * Applies [parse] to whichever field the full token was pasted into. Returns the user name and
     * secret to store; unchanged when neither field holds a full token.
     */
    fun normalise(username: String, secret: String): Pair<String, String> {
        parse(secret)?.let { return it.tokenUser to it.secret }
        parse(username)?.let { return it.tokenUser to if (secret.isBlank()) it.secret else secret }
        return username to secret
    }
}
