package io.github.tabssh.hypervisor.oci

import io.github.tabssh.utils.logging.Logger
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.security.MessageDigest
import java.security.Signature
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * OCI HTTP request signer per draft-cavage-http-signatures-08, the variant
 * Oracle Cloud Infrastructure uses for API-key auth. Adds:
 *
 *   - `Date: <RFC 1123 GMT>`
 *   - `Host: <url host>`
 *   - For requests with a body (POST/PUT/PATCH): `x-content-sha256`,
 *     `content-type`, `content-length`
 *   - `Authorization: Signature version="1",...,signature="<b64>"`
 *
 * `keyId` = `${tenancyOcid}/${userOcid}/${fingerprint}`. Algorithm is
 * `rsa-sha256`. See:
 *   https://docs.oracle.com/en-us/iaas/Content/API/Concepts/signingrequests.htm
 *
 * Standalone primitive — no Android deps, no networking. The OkHttp
 * interceptor wrapper at the bottom is for the API client to attach.
 */
class OciSigner(
    private val tenancyOcid: String,
    private val userOcid: String,
    private val fingerprint: String,
    private val keyMaterial: OciKeyMaterial
) {

    private val keyId = "$tenancyOcid/$userOcid/$fingerprint"

    /**
     * Build the `Authorization`/`Signature` header value for a request. The
     * caller must have already populated the headers required by the
     * signing scheme (see `requiredHeadersFor`). The order of names in the
     * `headers` field of the returned signature matches the order they
     * appear in `signingString`.
     */
    fun authorizationFor(
        method: String,
        url: HttpUrl,
        headers: Map<String, String>
    ): String {
        val (signedHeaderNames, signingString) = signingString(method, url, headers)
        val signature = sign(signingString)
        // OCI's "version=1" form (what their SDKs ship); also a valid cavage
        // header — Authorization: Signature version="1",keyId="...",...
        return buildString {
            append("Signature version=\"1\"")
            append(",keyId=\"$keyId\"")
            append(",algorithm=\"rsa-sha256\"")
            append(",headers=\"")
            append(signedHeaderNames.joinToString(" "))
            append("\"")
            append(",signature=\"$signature\"")
        }
    }

    /**
     * Returns the list of headers that must be present (and signed) for
     * the given HTTP method. Body-bearing requests also need
     * `x-content-sha256` / `content-type` / `content-length`.
     */
    fun requiredHeadersFor(method: String): List<String> {
        val base = listOf("(request-target)", "host", "date")
        return if (hasBody(method)) {
            base + listOf("x-content-sha256", "content-type", "content-length")
        } else base
    }

    /** RFC 1123 / `EEE, dd MMM yyyy HH:mm:ss z`, GMT. The format OCI requires. */
    fun rfc1123Now(): String {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("GMT")
        return sdf.format(Date())
    }

    /** SHA-256 of the body, base64-encoded — `x-content-sha256` header. */
    fun bodySha256Base64(body: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(body)
        return Base64.getEncoder().encodeToString(digest)
    }

    /**
     * Build the canonical signing string + the ordered list of header
     * names that go in the signature's `headers="..."` field.
     */
    private fun signingString(
        method: String,
        url: HttpUrl,
        headers: Map<String, String>
    ): Pair<List<String>, String> {
        val names = requiredHeadersFor(method)
        val pathAndQuery = buildString {
            append(url.encodedPath)
            val q = url.encodedQuery
            if (!q.isNullOrEmpty()) {
                append('?').append(q)
            }
        }
        val sb = StringBuilder()
        for ((i, name) in names.withIndex()) {
            if (i > 0) sb.append('\n')
            sb.append(name).append(": ")
            when (name) {
                "(request-target)" -> {
                    sb.append(method.lowercase(Locale.US)).append(' ').append(pathAndQuery)
                }
                "host" -> {
                    val host = headers["host"] ?: headers["Host"]
                    sb.append(host ?: defaultHost(url))
                }
                else -> {
                    val v = lookupHeader(headers, name)
                        ?: throw IllegalStateException("Missing required header for signing: $name")
                    sb.append(v)
                }
            }
        }
        return names to sb.toString()
    }

    private fun lookupHeader(headers: Map<String, String>, name: String): String? {
        // Headers are case-insensitive — try a few common casings.
        return headers[name]
            ?: headers[name.lowercase(Locale.US)]
            ?: headers[name.uppercase(Locale.US)]
            ?: headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private fun defaultHost(url: HttpUrl): String {
        val port = url.port
        val isDefault = (url.scheme == "https" && port == 443) ||
            (url.scheme == "http" && port == 80)
        return if (isDefault) url.host else "${url.host}:$port"
    }

    private fun sign(data: String): String {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(keyMaterial.privateKey)
        sig.update(data.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    private fun hasBody(method: String): Boolean = when (method.uppercase(Locale.US)) {
        "POST", "PUT", "PATCH" -> true
        else -> false
    }

    /**
     * OkHttp interceptor that signs every outgoing request. Adds the
     * required headers (Date, Host, Authorization, plus body digest
     * triplet for body-bearing requests) and emits an `Authorization:
     * Signature ...` header.
     *
     * Use as `client.addInterceptor(signer.asInterceptor())`.
     */
    fun asInterceptor(): Interceptor = Interceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()
        val url = original.url

        // Date + Host are required for every request.
        if (original.header("Date") == null) {
            builder.header("Date", rfc1123Now())
        }
        if (original.header("Host") == null) {
            builder.header("Host", defaultHost(url))
        }

        // Body digest triplet for POST/PUT/PATCH.
        if (hasBody(original.method)) {
            val bodyBytes = original.body?.let { rb ->
                Buffer().also { rb.writeTo(it) }.readByteArray()
            } ?: ByteArray(0)

            // Strip charset and any other parameters from the Content-Type to
            // produce the bare MIME type (e.g. "application/json" not
            // "application/json; charset=utf-8"). OkHttp's String.toRequestBody()
            // injects "; charset=utf-8" when no charset is specified, and
            // BridgeInterceptor unconditionally re-stamps Content-Type from the
            // body — so we MUST replace the body with a ByteArray body carrying
            // the bare type. Otherwise the signed value and the wire value may
            // both carry the charset, but OCI's server-side verifier may
            // normalise the received header before computing its signing string,
            // producing a mismatch and a 401 "Failed to verify the HTTP(S)
            // Signature". Using the bare type matches OCI SDK behaviour (Python,
            // Java, Go SDKs all send "application/json" without charset).
            val bareContentType: String = original.body?.contentType()
                ?.let { mt -> "${mt.type}/${mt.subtype}" }
                ?: "application/json"
            val signingBody = bodyBytes.toRequestBody(bareContentType.toMediaTypeOrNull())
            builder.method(original.method, signingBody)

            if (original.header("x-content-sha256") == null) {
                builder.header("x-content-sha256", bodySha256Base64(bodyBytes))
            }
            if (original.header("content-length") == null) {
                builder.header("content-length", bodyBytes.size.toString())
            }
            if (original.header("content-type") == null) {
                builder.header("content-type", bareContentType)
            }
        }

        // Re-snapshot the headers the builder now sees and produce the auth
        // header from them.
        val snapshot = mutableMapOf<String, String>()
        val tempReq = builder.build()
        for (name in tempReq.headers.names()) {
            snapshot[name] = tempReq.header(name).orEmpty()
        }

        val authValue = authorizationFor(original.method, url, snapshot)
        builder.header("Authorization", authValue)

        // Debug: log the signed header names so OCI signing failures are easy
        // to correlate with the Authorization header on the wire.
        Logger.d("OciSigner", "${original.method} ${url.encodedPath} — signed: ${
            snapshot.keys.joinToString(", ")
        }")

        val signed = builder.build()
        chain.proceed(signed)
    }
}
