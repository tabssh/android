package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * NetworkRoute — a saved, reusable "how to reach the server" definition.
 *
 * This is the reachability half of the "Routing & Forwarding" feature (the
 * tunnel half is [PortForward]). A route describes a proxy or SSH jump host
 * that a connection dials THROUGH before authenticating to the real target.
 * One route can be picked by many connections, or set once as the global
 * default route — so proxy/jump config is defined once instead of being
 * re-entered inline on every host.
 *
 * A connection selects a route by storing this row's [id] in
 * `connections.route_id` (null = inherit the global default; the sentinel
 * "DIRECT" = force a direct connection, ignoring the global default).
 *
 * Secrets never live here (PART 0 / PART 6): a proxy or jump-host password is
 * Keystore-backed, keyed by this route's id. Only non-secret routing metadata
 * is stored in this table.
 */
@Serializable
@Entity(
    tableName = "network_routes",
    indices = [
        Index("connection_id"),
        Index("key_id")
    ]
)
data class NetworkRoute(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    val name: String = "",

    // NetworkRouteType.name — parsed via [routeType], unknown falls back safely.
    @ColumnInfo(name = "type")
    val type: String = NetworkRouteType.PROXY_SOCKS5.name,

    // Proxy/jump endpoint. For a built-in Tor route this is the loopback SOCKS
    // listener the bundled tor process binds (see [builtInTor]); host is set to
    // 127.0.0.1 and the effective port is resolved at connect time.
    @ColumnInfo(name = "host")
    val host: String? = null,

    @ColumnInfo(name = "port")
    val port: Int = 0,

    // Proxy auth username, or the jump-host login user.
    @ColumnInfo(name = "username")
    val username: String? = null,

    // JUMP_HOST only: "PASSWORD" or "KEY" (mirrors the legacy proxy_auth_type).
    @ColumnInfo(name = "auth_type")
    val authType: String? = null,

    // JUMP_HOST with key auth: the StoredKey id used to authenticate.
    @ColumnInfo(name = "key_id")
    val keyId: String? = null,

    // JUMP_HOST alternative: reuse a saved ConnectionProfile as the jump host
    // instead of the manual host/port/username fields above. Null = manual.
    @ColumnInfo(name = "connection_id")
    val connectionId: String? = null,

    // Marks the bundled-Tor preset: selecting this route starts the embedded
    // tor process (TorManager) and routes SOCKS5 through its loopback listener.
    // An Orbot route is an ordinary PROXY_SOCKS5 with this flag false.
    @ColumnInfo(name = "built_in_tor")
    val builtInTor: Boolean = false,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0
) {
    /**
     * The route type, parsed from the stored string. Unknown values fall back
     * to PROXY_SOCKS5 so a bad row can never crash the picker.
     */
    val routeType: NetworkRouteType
        get() = NetworkRouteType.fromString(type)

    val isProxy: Boolean
        get() = routeType.isProxy

    val isJumpHost: Boolean
        get() = routeType == NetworkRouteType.JUMP_HOST

    /**
     * True when a jump-host route reuses a saved connection rather than a
     * manually entered endpoint.
     */
    val usesSavedConnection: Boolean
        get() = isJumpHost && !connectionId.isNullOrBlank()

    /**
     * Human-readable one-line summary for the routes list.
     */
    fun getSummary(): String {
        return when (routeType) {
            NetworkRouteType.JUMP_HOST ->
                if (usesSavedConnection) "SSH jump via saved connection"
                else "SSH jump → ${username?.let { "$it@" } ?: ""}${host ?: "?"}:$port"
            NetworkRouteType.PROXY_HTTP -> "HTTP proxy → ${host ?: "?"}:$port"
            NetworkRouteType.PROXY_SOCKS4 -> "SOCKS4 proxy → ${host ?: "?"}:$port"
            NetworkRouteType.PROXY_SOCKS5 ->
                if (builtInTor) "Tor (built-in)"
                else "SOCKS5 proxy → ${host ?: "?"}:$port"
        }
    }

    companion object {
        /**
         * Sentinel [ConnectionProfile.routeId] value meaning "force a direct
         * connection" — do not apply the global default route. A null routeId
         * means "inherit the global default"; this value means "opt out".
         */
        const val DIRECT = "DIRECT"

        /** Standard loopback SOCKS port Orbot exposes by default. */
        const val ORBOT_SOCKS_PORT = 9050

        /**
         * Build a NetworkRoute from the legacy inline proxy columns on a
         * ConnectionProfile. Returns null when the profile has no proxy
         * configured (proxyType/proxyHost blank). Used by the one-time
         * inline-proxy → route data migration.
         */
        fun fromLegacyProfileProxy(profile: ConnectionProfile): NetworkRoute? {
            val legacyType = profile.proxyType?.trim()?.uppercase()
            if (legacyType.isNullOrBlank()) return null
            val host = profile.proxyHost?.trim()
            if (host.isNullOrBlank() && legacyType != "SSH") return null

            val type = NetworkRouteType.fromLegacyProxyType(legacyType)
            val label = when (type) {
                NetworkRouteType.JUMP_HOST -> "Jump: ${host ?: profile.host}"
                else -> "${type.displayName}: ${host ?: "?"}:${profile.proxyPort ?: 0}"
            }
            return NetworkRoute(
                name = label,
                type = type.name,
                host = host,
                port = profile.proxyPort ?: 0,
                username = profile.proxyUsername,
                authType = if (type == NetworkRouteType.JUMP_HOST) profile.proxyAuthType else null,
                keyId = if (type == NetworkRouteType.JUMP_HOST) profile.proxyKeyId else null
            )
        }
    }
}

/**
 * The kinds of network route. Stored as the enum name string. A route is
 * either a client-side proxy (HTTP/SOCKS4/SOCKS5, applied via JSch setProxy)
 * or an SSH jump host (dialed first, then a local forward to the target).
 */
@Serializable
enum class NetworkRouteType(val displayName: String) {
    PROXY_HTTP("HTTP proxy"),
    PROXY_SOCKS4("SOCKS4 proxy"),
    PROXY_SOCKS5("SOCKS5 proxy"),
    JUMP_HOST("SSH jump host");

    val isProxy: Boolean
        get() = this != JUMP_HOST

    companion object {
        fun fromString(value: String): NetworkRouteType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PROXY_SOCKS5

        /**
         * Map a legacy `connections.proxy_type` value ("HTTP" / "SOCKS4" /
         * "SOCKS5" / "SSH") to a route type.
         */
        fun fromLegacyProxyType(value: String?): NetworkRouteType =
            when (value?.trim()?.uppercase()) {
                "HTTP" -> PROXY_HTTP
                "SOCKS4" -> PROXY_SOCKS4
                "SOCKS5" -> PROXY_SOCKS5
                "SSH" -> JUMP_HOST
                else -> PROXY_SOCKS5
            }
    }
}
