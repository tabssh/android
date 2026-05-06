package io.github.tabssh.crypto.tls

import io.github.tabssh.utils.logging.Logger
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Phase-1 TLS pinning for hypervisor REST + WebSocket clients.
 *
 *   verifySsl = false           → trust-all (today's behavior, MITM-able).
 *   verifySsl = true, no pin    → capture leaf SHA-256 on first connect;
 *                                 the caller reads it via `CapturedPin`
 *                                 and persists it to the DB after a
 *                                 successful authenticate(). The very
 *                                 first connect is therefore TOFU
 *                                 (Trust On First Use) — same model
 *                                 SSH host keys use.
 *   verifySsl = true, pin set   → enforce match. On mismatch the trust
 *                                 manager throws CertificateException;
 *                                 the TLS handshake aborts cleanly and
 *                                 OkHttp surfaces the failure as the
 *                                 caller's request error.
 *
 * Hostname verification is intentionally bypassed in BOTH the pinning
 * and trust-all paths: hypervisor TLS certs are routinely self-signed
 * with a CN/SAN that doesn't match the user's bookmark hostname (an
 * IP, a `*.local`, or `localhost`). Pin-by-fingerprint replaces it.
 *
 * Phase 2 (deferred) adds the user-facing prompt dialogs for first-pin
 * confirm and pin-changed warning, modelled on `HostKeyVerifier`.
 */
object HypervisorTrustManagerFactory {

    private const val TAG = "HypervisorTrust"

    /**
     * Mutable holder for a pin captured during a TLS handshake.
     * The trust manager runs synchronously inside the handshake — the
     * caller reads this AFTER `authenticate()` returns to persist a
     * newly-captured pin.
     */
    class CapturedPin {
        @Volatile
        var sha256: String? = null
    }

    /**
     * Configure an OkHttpClient.Builder with the right TLS stack for
     * the given (verifySsl, pinnedSha256) combo. `captured` receives
     * the leaf SHA on first-connect TOFU; ignore it when verifySsl is
     * false or a pin is already set.
     *
     * `host` / `port` are display-only — used by the user-prompt
     * dialogs (Phase 2) so the user sees which server they're being
     * asked to verify.
     */
    fun installTrust(
        builder: OkHttpClient.Builder,
        verifySsl: Boolean,
        pinnedSha256: String?,
        captured: CapturedPin,
        host: String = "",
        port: Int = 0
    ) {
        if (!verifySsl) {
            // Trust-all is a deliberate per-host bypass, but the user
            // should be able to see in the debug log that they took it.
            // Without this line, a `verifySsl=false` row was indistinguish-
            // able from a properly-pinned one in postmortem triage.
            Logger.w(TAG, "TLS trust-all enabled for ${if (host.isNotEmpty()) "$host:$port" else "<unspecified host>"} — connection is MITM-able. Consider pinning instead.")
            installTrustAll(builder)
            return
        }
        installPinning(builder, pinnedSha256, captured, host, port)
    }

    /** Install a TrustManager that accepts every cert. */
    private fun installTrustAll(builder: OkHttpClient.Builder) {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, t: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, t: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustAll), java.security.SecureRandom())
        builder.sslSocketFactory(ctx.socketFactory, trustAll)
        builder.hostnameVerifier { _, _ -> true }
    }

    /**
     * Install a TrustManager that pins the leaf cert SHA-256.
     * - No prior pin (`pinnedSha256 == null`): capture, accept, return.
     *   The `captured` holder lets the caller persist after success.
     * - Prior pin set: SHA-256 must match (case-insensitive). Mismatch
     *   throws CertificateException — TLS handshake aborts.
     */
    private fun installPinning(
        builder: OkHttpClient.Builder,
        pinnedSha256: String?,
        captured: CapturedPin,
        host: String,
        port: Int
    ) {
        val pinning = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, t: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, t: String) {
                if (chain.isEmpty()) {
                    throw CertificateException("Empty certificate chain")
                }
                val presented = sha256Hex(chain[0].encoded)

                // Pin matches → silent OK.
                if (!pinnedSha256.isNullOrBlank() &&
                    pinnedSha256.equals(presented, ignoreCase = true)) {
                    Logger.d(TAG, "Cert pin match — leaf SHA-256: $presented")
                    return
                }

                // Either no prior pin (TOFU) or mismatch. Both cases need
                // user consent. The prompt dialog blocks this thread up
                // to 30 s waiting on the UI; default REJECT on timeout.
                val action = if (pinnedSha256.isNullOrBlank()) {
                    Logger.i(TAG, "TOFU prompt — leaf SHA-256: $presented (host=$host:$port)")
                    HypervisorCertPromptDialog.promptNewHost(host, port, presented)
                } else {
                    Logger.w(
                        TAG,
                        "Cert pin MISMATCH prompt — pinned $pinnedSha256 vs presented $presented (host=$host:$port)"
                    )
                    HypervisorCertPromptDialog.promptChangedCert(
                        host, port, pinnedSha256, presented
                    )
                }

                when (action) {
                    HypervisorCertPromptDialog.Action.ACCEPT_AND_PIN -> {
                        // Mark the captured holder so persistCapturedPinIfAny
                        // writes the new pin to the DB after authenticate().
                        captured.sha256 = presented
                        Logger.i(TAG, "User accepted + pinned: $presented")
                    }
                    HypervisorCertPromptDialog.Action.ACCEPT_ONCE -> {
                        // Don't touch captured holder — DB stays unchanged.
                        // Connection succeeds for this session only.
                        Logger.i(TAG, "User accepted (once-only) — pin NOT updated")
                    }
                    HypervisorCertPromptDialog.Action.REJECT -> {
                        Logger.w(TAG, "User rejected cert — aborting handshake")
                        throw CertificateException(
                            "User rejected hypervisor certificate.\n" +
                            (if (pinnedSha256.isNullOrBlank()) {
                                "First-time pin not accepted."
                            } else {
                                "Pinned:    SHA-256 $pinnedSha256\n" +
                                "Presented: SHA-256 $presented"
                            })
                        )
                    }
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(pinning), java.security.SecureRandom())
        builder.sslSocketFactory(ctx.socketFactory, pinning)
        // Bypass hostname verification — pinning replaces it (see kdoc).
        builder.hostnameVerifier { _, _ -> true }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString(":") { "%02x".format(it) }
    }
}
