package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Direct VNC host — a VPS/bare-metal host exposing a VNC console.
 * Password is stored in Android Keystore under key `vnc_identity_${identityId}`
 * (reusing the VncIdentity Keystore slot) or directly under `vnc_host_${id}`
 * when no identity is linked.
 */
@Serializable
@Entity(
    tableName = "vnc_hosts",
    indices = [
        Index("identity_id"),
        Index("group_id")
    ]
)
data class VncHost(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "host")
    val host: String,

    @ColumnInfo(name = "port")
    val port: Int = 5900,

    /**
     * If set, the effective port is 5900 + displayNumber, overriding [port].
     * Matches the libvirt / QEMU convention where each VM occupies a display
     * number (e.g. `:1` → port 5901).
     */
    @ColumnInfo(name = "display_number")
    val displayNumber: Int? = null,

    /** FK to vnc_identities. Null means no stored credential (user enters on connect). */
    @ColumnInfo(name = "identity_id")
    val identityId: String? = null,

    /**
     * Security negotiation mode:
     *   "auto"                — let the server pick
     *   "none"                — SECURITY_NONE (no auth)
     *   "vnc_auth"            — SECURITY_VNC_AUTH (DES challenge)
     *   "vencrypt_tls_none"   — VeNCrypt TLS, no secondary auth
     *   "vencrypt_x509_none"  — VeNCrypt X.509, no secondary auth
     */
    @ColumnInfo(name = "security_type")
    val securityType: String = "auto",

    @ColumnInfo(name = "tls_verify")
    val tlsVerify: Boolean = false,

    @ColumnInfo(name = "pinned_cert_sha256")
    val pinnedCertSha256: String? = null,

    /**
     * When true, this VNC session is kept alive while the app is backgrounded —
     * the TCP socket is held open and framebuffer-update requests are paused —
     * instead of the default drop-on-background / reconnect-on-resume behavior.
     * On by default so switching tabs/apps and coming back doesn't retrigger the
     * VNC handshake. To bound the battery/data cost of an indefinitely-held
     * socket, [VncKeepAliveService] fully suspends (closes) any session that has
     * sat parked and untouched for longer than its idle timeout (10 minutes) —
     * see `VncBackgroundSessionStore.sweepIdle()`. A suspended session simply
     * reconnects fresh on the next `onResume()`.
     */
    @ColumnInfo(name = "keep_alive_in_background")
    val keepAliveInBackground: Boolean = true,

    /** FK to connection_groups — optional folder assignment. */
    @ColumnInfo(name = "group_id")
    val groupId: String? = null,

    /** ARGB color tag (0 = none). */
    @ColumnInfo(name = "color_tag")
    val colorTag: Int = 0,

    @ColumnInfo(name = "notes")
    val notes: String? = null,

    @ColumnInfo(name = "last_connected")
    val lastConnected: Long = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long
) {
    /** Compute the TCP port to connect to. */
    val effectivePort: Int get() = if (displayNumber != null) 5900 + displayNumber else port
}
