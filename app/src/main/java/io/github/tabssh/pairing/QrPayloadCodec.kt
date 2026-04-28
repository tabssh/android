package io.github.tabssh.pairing

/**
 * Minimal CBOR decoder for the fixed schemas in QR_PAIRING.md.
 *
 * Why roll our own: `co.nstant.in:cbor` is unmaintained and `com.upokecenter:cbor`
 * is ~700 KB. Our schemas are small + fixed (no dynamic tags, no streaming),
 * so a hand-written ~120-line decoder beats a library by every metric.
 *
 * Supported CBOR features (RFC 8949):
 *  - Major type 0: unsigned integer (additional info 0–27, sizes 1/2/4/8 bytes)
 *  - Major type 2: byte string (definite length only)
 *  - Major type 3: text string (definite length only, UTF-8)
 *  - Major type 4: array (definite length only)
 *  - Major type 5: map (definite length only, text-string keys)
 *  - Major type 7: simple values: false (0xf4), true (0xf5), null (0xf6)
 *
 * Not supported (we don't need them):
 *  - Major type 1 (negative ints), major type 6 (tags), indefinite lengths,
 *    floating-point, big int / big float, dates, half-precision floats.
 */
internal object Cbor {

    sealed class Value {
        data class IntValue(val value: Long) : Value()
        data class BytesValue(val value: ByteArray) : Value() {
            override fun equals(other: Any?) = other is BytesValue && value.contentEquals(other.value)
            override fun hashCode() = value.contentHashCode()
        }
        data class TextValue(val value: String) : Value()
        data class ArrayValue(val items: List<Value>) : Value()
        data class MapValue(val entries: Map<String, Value>) : Value()
        data object True : Value()
        data object False : Value()
        data object Null : Value()
    }

    class CborException(message: String) : RuntimeException(message)

    fun decode(bytes: ByteArray): Value = Decoder(bytes).decodeOne()

    private class Decoder(private val bytes: ByteArray) {
        private var pos = 0

        fun decodeOne(): Value {
            val initial = readByte()
            val major = (initial ushr 5) and 0x07
            val ai = initial and 0x1F
            return when (major) {
                0 -> Value.IntValue(readUInt(ai))
                2 -> {
                    val n = readUInt(ai).toInt()
                    Value.BytesValue(readBytes(n))
                }
                3 -> {
                    val n = readUInt(ai).toInt()
                    Value.TextValue(String(readBytes(n), Charsets.UTF_8))
                }
                4 -> {
                    val n = readUInt(ai).toInt()
                    val items = ArrayList<Value>(n)
                    repeat(n) { items.add(decodeOne()) }
                    Value.ArrayValue(items)
                }
                5 -> {
                    val n = readUInt(ai).toInt()
                    val map = LinkedHashMap<String, Value>(n)
                    repeat(n) {
                        val key = decodeOne()
                        if (key !is Value.TextValue) throw CborException("non-text map key at byte $pos")
                        map[key.value] = decodeOne()
                    }
                    Value.MapValue(map)
                }
                7 -> when (ai) {
                    20 -> Value.False
                    21 -> Value.True
                    22, 23 -> Value.Null   // null and undefined both map to Null for our schemas
                    else -> throw CborException("unsupported simple value ai=$ai at byte ${pos - 1}")
                }
                else -> throw CborException("unsupported major type $major at byte ${pos - 1}")
            }
        }

        private fun readByte(): Int {
            if (pos >= bytes.size) throw CborException("unexpected end of CBOR at byte $pos")
            return bytes[pos++].toInt() and 0xFF
        }

        private fun readBytes(n: Int): ByteArray {
            if (n < 0 || pos + n > bytes.size) throw CborException("byte string out of range (need $n at byte $pos)")
            val out = bytes.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        private fun readUInt(ai: Int): Long = when {
            ai < 24 -> ai.toLong()
            ai == 24 -> readByte().toLong()
            ai == 25 -> ((readByte().toLong() shl 8) or readByte().toLong())
            ai == 26 -> {
                ((readByte().toLong() shl 24) or
                        (readByte().toLong() shl 16) or
                        (readByte().toLong() shl 8) or
                        readByte().toLong())
            }
            ai == 27 -> {
                var v = 0L
                repeat(8) { v = (v shl 8) or readByte().toLong() }
                v
            }
            else -> throw CborException("indefinite-length not supported (ai=$ai) at byte ${pos - 1}")
        }
    }
}

/**
 * Codec layer that turns raw QR scanner output into typed pairing structs.
 *
 * Pipeline: scanned text → base64 decode → CBOR decode → typed structs.
 *
 * Why base64 + byte-mode QR: `qrcodegen` (desktop) renders byte-mode QRs
 * faithfully, but ZXing's `ScanContract` returns the contents as a `String`.
 * Round-tripping arbitrary bytes through a String is fragile (encoding
 * mangling). Standard base64 is ASCII-clean — survives the String boundary
 * intact. The ~25 % capacity hit is fine since real payloads sit comfortably
 * under the limit (see capacity tables in QR_PAIRING.md).
 */
object QrPayloadCodec {

    private const val ENVELOPE_VERSION_SUPPORTED = 1
    private const val PAYLOAD_VERSION_SUPPORTED = 1

    /**
     * Decode the outer envelope. Throws on malformed input.
     */
    fun decodeEnvelope(scannedText: String): QrEnvelope {
        val cbor = base64Decode(scannedText.trim())
        val root = Cbor.decode(cbor)
        if (root !is Cbor.Value.MapValue) throw IllegalArgumentException("envelope root is not a map")
        val map = root.entries

        val version = (map.intField("version") ?: throw IllegalArgumentException("missing version")).toInt()
        if (version > ENVELOPE_VERSION_SUPPORTED) {
            throw UnsupportedVersionException("envelope version $version > $ENVELOPE_VERSION_SUPPORTED")
        }

        val salt = map.bytesField("salt") ?: throw IllegalArgumentException("missing salt")
        val nonce = map.bytesField("nonce") ?: throw IllegalArgumentException("missing nonce")
        val ciphertext = map.bytesField("ciphertext") ?: throw IllegalArgumentException("missing ciphertext")

        return QrEnvelope(version, salt, nonce, ciphertext)
    }

    /**
     * Decode the inner payload from a decrypted CBOR blob. Throws on malformed
     * input or unsupported version.
     */
    fun decodePayload(plaintextCbor: ByteArray): PairingPayload {
        val root = Cbor.decode(plaintextCbor)
        if (root !is Cbor.Value.MapValue) throw IllegalArgumentException("payload root is not a map")
        val map = root.entries

        val version = (map.intField("version") ?: throw IllegalArgumentException("missing version")).toInt()
        if (version > PAYLOAD_VERSION_SUPPORTED) {
            throw UnsupportedVersionException("payload version $version > $PAYLOAD_VERSION_SUPPORTED")
        }

        val expiresAt = map.intField("expires_at") ?: throw IllegalArgumentException("missing expires_at")
        val deviceLabel = map.textField("device_label")

        val connections = (map["connections"] as? Cbor.Value.ArrayValue)?.items?.map { decodeConnection(it) }
            ?: emptyList()
        val groups = (map["groups"] as? Cbor.Value.ArrayValue)?.items?.map { decodeGroup(it) }
            ?: emptyList()
        val identities = (map["identities"] as? Cbor.Value.ArrayValue)?.items?.map { decodeIdentity(it) }
            ?: emptyList()

        return PairingPayload(
            version = version,
            expiresAt = expiresAt,
            deviceLabel = deviceLabel,
            connections = connections,
            groups = groups,
            identities = identities,
        )
    }

    private fun decodeConnection(v: Cbor.Value): ExportedConnection {
        val m = (v as? Cbor.Value.MapValue)?.entries ?: throw IllegalArgumentException("connection is not a map")
        return ExportedConnection(
            name = m.textField("name") ?: throw IllegalArgumentException("connection.name missing"),
            host = m.textField("host") ?: throw IllegalArgumentException("connection.host missing"),
            port = m.intField("port")?.toInt() ?: 22,
            username = m.textField("username") ?: throw IllegalArgumentException("connection.username missing"),
            protocol = m.textField("protocol") ?: "ssh",
            authType = m.textField("auth_type") ?: "PASSWORD",
            terminalType = m.textField("terminal_type") ?: "xterm-256color",
            encoding = m.textField("encoding") ?: "UTF-8",
            compression = m.boolField("compression") ?: true,
            keepAlive = m.boolField("keep_alive") ?: true,
            x11Forwarding = m.boolField("x11_forwarding") ?: false,
            useMosh = m.boolField("use_mosh") ?: false,
            agentForwarding = m.boolField("agent_forwarding") ?: false,
            theme = m.textField("theme") ?: "dracula",
            colorTag = m.intField("color_tag")?.toInt() ?: 0,
            groupName = m.textField("group_name"),
            identityName = m.textField("identity_name"),
            envVars = m.textField("env_vars"),
            postConnectScript = m.textField("post_connect_script"),
            proxyHost = m.textField("proxy_host"),
            proxyPort = m.intField("proxy_port")?.toInt(),
            proxyType = m.textField("proxy_type"),
            proxyUsername = m.textField("proxy_username"),
            proxyAuthType = m.textField("proxy_auth_type"),
            sshKeyPublic = m.textField("ssh_key_public"),
            sshKeyFingerprint = m.textField("ssh_key_fingerprint"),
        )
    }

    private fun decodeGroup(v: Cbor.Value): ExportedGroup {
        val m = (v as? Cbor.Value.MapValue)?.entries ?: throw IllegalArgumentException("group is not a map")
        return ExportedGroup(
            name = m.textField("name") ?: throw IllegalArgumentException("group.name missing"),
            parentName = m.textField("parent_name"),
            color = m.textField("color"),
            icon = m.textField("icon"),
            sortOrder = m.intField("sort_order")?.toInt() ?: 0,
        )
    }

    private fun decodeIdentity(v: Cbor.Value): ExportedIdentity {
        val m = (v as? Cbor.Value.MapValue)?.entries ?: throw IllegalArgumentException("identity is not a map")
        return ExportedIdentity(
            name = m.textField("name") ?: throw IllegalArgumentException("identity.name missing"),
            username = m.textField("username") ?: throw IllegalArgumentException("identity.username missing"),
            authType = m.textField("auth_type") ?: "PASSWORD",
            description = m.textField("description"),
        )
    }

    private fun Map<String, Cbor.Value>.intField(key: String): Long? =
        (this[key] as? Cbor.Value.IntValue)?.value

    private fun Map<String, Cbor.Value>.textField(key: String): String? {
        val v = this[key] ?: return null
        return when (v) {
            is Cbor.Value.TextValue -> v.value
            Cbor.Value.Null -> null
            else -> null
        }
    }

    private fun Map<String, Cbor.Value>.bytesField(key: String): ByteArray? =
        (this[key] as? Cbor.Value.BytesValue)?.value

    private fun Map<String, Cbor.Value>.boolField(key: String): Boolean? =
        when (this[key]) {
            Cbor.Value.True -> true
            Cbor.Value.False -> false
            else -> null
        }

    private fun base64Decode(s: String): ByteArray {
        // android.util.Base64 (not java.util.Base64) — the latter requires
        // API 26 and we support API 21+. The DEFAULT flag tolerates either
        // standard or URL-safe alphabets and missing padding.
        val flags = android.util.Base64.DEFAULT or
                android.util.Base64.NO_WRAP or
                android.util.Base64.URL_SAFE
        // First try url-safe; if that errors out on a '+' or '/' fall back
        // to standard alphabet by stripping those flags.
        return try {
            android.util.Base64.decode(s.replace("\\s+".toRegex(), ""), flags)
        } catch (e: IllegalArgumentException) {
            android.util.Base64.decode(
                s.replace("\\s+".toRegex(), ""),
                android.util.Base64.DEFAULT or android.util.Base64.NO_WRAP,
            )
        }
    }

    class UnsupportedVersionException(message: String) : RuntimeException(message)
}
