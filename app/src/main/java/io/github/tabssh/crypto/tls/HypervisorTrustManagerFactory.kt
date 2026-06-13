package io.github.tabssh.crypto.tls

import android.annotation.SuppressLint
import io.github.tabssh.utils.logging.Logger
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS pinning for hypervisor REST + WebSocket clients and cloud provider APIs.
 *
 *   verifySsl = false           → trust-all (MITM-able; only for user-opted hosts).
 *   verifySsl = true, no pin    → try the system CA first.
 *                                   • System CA accepts (publicly-trusted cert, e.g.
 *                                     OCI, Let's Encrypt on Proxmox): silently capture
 *                                     the leaf SHA-256 and accept. The caller persists
 *                                     it so the next connect uses the stored pin.
 *                                   • System CA rejects (self-signed / private CA):
 *                                     show the user a TOFU dialog so they can verify
 *                                     the fingerprint out-of-band before pinning.
 *   verifySsl = true, pin set   → enforce match. On mismatch show the "cert changed"
 *                                 dialog. If still rejected, throw CertificateException;
 *                                 the TLS handshake aborts cleanly and OkHttp surfaces
 *                                 the failure as the caller's request error.
 *
 * Hostname verification is intentionally bypassed: hypervisor TLS certs are
 * routinely self-signed with a CN/SAN that doesn't match the user's bookmark
 * hostname (an IP, a `*.local`, or `localhost`). Pin-by-fingerprint replaces it.
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
        // Lint correctly notices this is trust-all; we intentionally
        // opt into it ONLY when the user has checked "verifySsl = false"
        // on the hypervisor profile. The warning is logged loudly above.
        val trustAll = object : X509TrustManager {
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(c: Array<X509Certificate>, t: String) {}
            @SuppressLint("TrustAllX509TrustManager")
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
     *
     * No prior pin (TOFU path):
     *   1. Try the Android system CA store. If the cert is publicly trusted
     *      (OCI endpoints, Let's Encrypt on Proxmox, etc.) accept silently
     *      and capture the pin — no dialog needed; system CA already vetted it.
     *   2. If the system CA rejects it (self-signed / private CA), show the
     *      user a TOFU dialog to verify the fingerprint out-of-band.
     *
     * Prior pin set:
     *   SHA-256 must match (case-insensitive). On mismatch show the
     *   "cert changed" dialog; if still rejected throw CertificateException
     *   and abort the TLS handshake.
     */
    private fun installPinning(
        builder: OkHttpClient.Builder,
        pinnedSha256: String?,
        captured: CapturedPin,
        host: String,
        port: Int
    ) {
        // Resolved once at install time and closed over by the TrustManager.
        val systemTm = resolveSystemTrustManager()

        // checkClientTrusted is empty by design — we are the TLS client
        // here, never validating an inbound client cert. checkServerTrusted
        // does the real TOFU pinning work below.
        val pinning = object : X509TrustManager {
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(c: Array<X509Certificate>, t: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, t: String) {
                if (chain.isEmpty()) {
                    throw CertificateException("Empty certificate chain")
                }
                val presented = sha256Hex(chain[0].encoded)

                // Pin matches → silent OK. Also accept if the user already
                // accepted this cert in the current session (ACCEPT_AND_PIN
                // sets captured.sha256 before it can be persisted to the DB).
                if ((!pinnedSha256.isNullOrBlank() && pinnedSha256.equals(presented, ignoreCase = true)) ||
                    (!captured.sha256.isNullOrBlank() && captured.sha256.equals(presented, ignoreCase = true))) {
                    Logger.d(TAG, "Cert pin match — leaf SHA-256: $presented")
                    return
                }

                if (pinnedSha256.isNullOrBlank()) {
                    // TOFU — no stored pin yet.
                    // Try the system CA first. Publicly-trusted certs (e.g. OCI,
                    // cloud provider endpoints, Let's Encrypt) are accepted silently
                    // and pinned so future connects verify the same leaf.
                    val systemTrusted = if (systemTm != null) {
                        try {
                            systemTm.checkServerTrusted(chain, t)
                            true
                        } catch (_: CertificateException) {
                            false
                        }
                    } else {
                        false
                    }

                    if (systemTrusted) {
                        captured.sha256 = presented
                        Logger.i(TAG, "Publicly-trusted cert accepted silently — captured pin: $presented (host=$host:$port)")
                        return
                    }

                    // Self-signed / private CA — ask the user to verify the fingerprint.
                    Logger.i(TAG, "TOFU prompt — leaf SHA-256: $presented (host=$host:$port)")
                    val action = HypervisorCertPromptDialog.promptNewHost(host, port, presented)
                    when (action) {
                        HypervisorCertPromptDialog.Action.ACCEPT_AND_PIN -> {
                            captured.sha256 = presented
                            Logger.i(TAG, "User accepted + pinned: $presented")
                        }
                        HypervisorCertPromptDialog.Action.ACCEPT_ONCE -> {
                            // Don't touch captured holder — DB stays unchanged.
                            // Connection succeeds for this session only.
                            Logger.i(TAG, "User accepted once-only — pin NOT captured")
                        }
                        HypervisorCertPromptDialog.Action.REJECT -> {
                            Logger.w(TAG, "User rejected cert — aborting handshake")
                            throw CertificateException(
                                "User rejected certificate for $host:$port\n" +
                                "First-time pin not accepted.\n" +
                                "SHA-256: $presented"
                            )
                        }
                    }
                } else {
                    // Prior pin set but presented cert doesn't match → possible MITM or cert rotation.
                    Logger.w(TAG, "Cert pin MISMATCH — pinned $pinnedSha256 vs presented $presented (host=$host:$port)")
                    val action = HypervisorCertPromptDialog.promptChangedCert(
                        host, port, pinnedSha256, presented
                    )
                    when (action) {
                        HypervisorCertPromptDialog.Action.ACCEPT_AND_PIN -> {
                            captured.sha256 = presented
                            Logger.i(TAG, "User accepted pin update: $pinnedSha256 → $presented")
                        }
                        HypervisorCertPromptDialog.Action.ACCEPT_ONCE -> {
                            Logger.i(TAG, "User accepted changed cert once-only — pin NOT updated")
                        }
                        HypervisorCertPromptDialog.Action.REJECT -> {
                            Logger.w(TAG, "User rejected changed cert — aborting handshake")
                            throw CertificateException(
                                "User rejected certificate change for $host:$port\n" +
                                "Pinned:    SHA-256 $pinnedSha256\n" +
                                "Presented: SHA-256 $presented"
                            )
                        }
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

    /**
     * Resolve the Android system X509TrustManager using the platform's
     * default TrustManagerFactory. Returns null if the platform API is
     * unavailable (should not happen on any supported Android version).
     */
    private fun resolveSystemTrustManager(): X509TrustManager? = try {
        val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        tmf.init(null as java.security.KeyStore?)
        tmf.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
    } catch (e: Exception) {
        Logger.w(TAG, "Could not resolve system TrustManager: ${e.message}")
        null
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString(":") { "%02x".format(it) }
    }
}
