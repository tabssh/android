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
 *   verifySsl = false           → TOFU-pin without first-use prompts: the first cert
 *                                 seen is captured silently and persisted by the
 *                                 caller; later connects accept only that cert, and
 *                                 a changed cert shows the "cert changed" dialog.
 *                                 Weaker than verifySsl=true (first contact is
 *                                 unauthenticated) but never blanket trust-all.
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
 * Hostname verification is conditional. For a leaf accepted because it chains
 * to a system CA (publicly-trusted endpoints such as OCI), strict RFC 2818
 * hostname verification runs — the cert carries a real hostname and skipping
 * the check would let a valid cert for one host be replayed against another.
 * For a self-signed / TOFU-pinned leaf it is bypassed: hypervisor certs
 * routinely use a CN/SAN that doesn't match the bookmark hostname (an IP, a
 * `*.local`, or `localhost`), and pin-by-fingerprint already binds identity.
 * (The `verifySsl = false` path always bypasses hostname verification — the
 * silently-captured pin binds identity there.)
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
     * the leaf SHA on first-connect TOFU — in BOTH modes; the caller
     * persists it after `authenticate()` so the next connect enforces
     * the stored pin.
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
            // verifySsl=off is a deliberate per-host opt-out of CA/first-use
            // verification, but PART 6 forbids blanket trust-all — TOFU
            // pinning is the compensating control. Log it so a verifySsl=off
            // row is distinguishable in postmortem triage.
            Logger.w(TAG, "TLS verification off for ${if (host.isNotEmpty()) "$host:$port" else "<unspecified host>"} — first-seen cert will be pinned without a prompt (TOFU). Consider verifySsl=on instead.")
            installQuietTofu(builder, pinnedSha256, captured, host, port)
            return
        }
        installPinning(builder, pinnedSha256, captured, host, port)
    }

    /**
     * TOFU pinning for verifySsl=off profiles.
     *
     * No stored pin → accept ANY presented cert silently and capture its
     * SHA-256 (no dialog — the user opted out of first-use verification);
     * the caller persists the capture, so every later connect enforces it.
     * Pin match → silent OK. Pin mismatch → the same "cert changed" dialog
     * as the verifySsl=on path (ACCEPT_AND_PIN / ACCEPT_ONCE / REJECT).
     * Hostname verification is bypassed — the pin binds identity, and
     * verifySsl=off hosts routinely present IP / *.local / mismatched CNs.
     */
    private fun installQuietTofu(
        builder: OkHttpClient.Builder,
        pinnedSha256: String?,
        captured: CapturedPin,
        host: String,
        port: Int
    ) {
        // checkClientTrusted is empty by design — we are the TLS client,
        // never validating an inbound client cert.
        val tofu = object : X509TrustManager {
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(c: Array<X509Certificate>, t: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, t: String) {
                if (chain.isEmpty()) {
                    throw CertificateException("Empty certificate chain")
                }
                val presented = sha256Hex(chain[0].encoded)

                // Stored pin or same-session capture matches → silent OK.
                if ((!pinnedSha256.isNullOrBlank() && pinnedSha256.equals(presented, ignoreCase = true)) ||
                    (!captured.sha256.isNullOrBlank() && captured.sha256.equals(presented, ignoreCase = true))) {
                    Logger.d(TAG, "Cert pin match (verifySsl=off) — leaf SHA-256: $presented")
                    return
                }

                if (pinnedSha256.isNullOrBlank()) {
                    // First contact — capture and pin silently. verifySsl=off
                    // means the user opted out of first-use verification, so
                    // no dialog; the pin still protects every later connect.
                    captured.sha256 = presented
                    Logger.i(TAG, "First-seen cert pinned silently (verifySsl=off): $presented (host=$host:$port)")
                    return
                }

                // Prior pin set but presented cert doesn't match → possible MITM or cert rotation.
                Logger.w(TAG, "Cert pin MISMATCH (verifySsl=off) — pinned $pinnedSha256 vs presented $presented (host=$host:$port)")
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
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tofu), java.security.SecureRandom())
        builder.sslSocketFactory(ctx.socketFactory, tofu)
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

        // Leaf SHA-256s that were accepted because they chain to a system CA
        // (publicly-trusted endpoints such as OCI). For these the certificate
        // carries a meaningful hostname, so hostname verification MUST run —
        // skipping it would let a valid cert for host A be replayed against
        // host B. Self-signed / TOFU-pinned leaves are NOT added here: pinning
        // by fingerprint already binds the identity, so their hostname bypass
        // (needed for IP / *.local / self-signed CN mismatches) is retained.
        // Populated inside checkServerTrusted, read inside the hostname verifier;
        // both run on the handshake thread but concurrent pooled handshakes can
        // overlap, so use a thread-safe set.
        val systemTrustedLeaves = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
                        systemTrustedLeaves.add(presented)
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

        // Hostname verification policy:
        //   • Leaf accepted via the system CA  → run strict RFC 2818 hostname
        //     verification (the cert has a real, meaningful CN/SAN).
        //   • Leaf accepted via pin / TOFU     → bypass (pinning replaces it;
        //     hypervisor certs routinely carry an IP / *.local / mismatched CN).
        val strictVerifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
        builder.hostnameVerifier { hostname, session ->
            val leafSha = try {
                (session.peerCertificates.firstOrNull() as? X509Certificate)?.let { sha256Hex(it.encoded) }
            } catch (e: Exception) {
                Logger.w(TAG, "Could not hash peer leaf cert for $hostname — treating as non-system-trusted", e)
                null
            }
            if (leafSha != null && systemTrustedLeaves.contains(leafSha)) {
                val ok = strictVerifier.verify(hostname, session)
                if (!ok) {
                    Logger.w(TAG, "Hostname verification FAILED for publicly-trusted cert (host=$hostname, leaf=$leafSha)")
                }
                ok
            } else {
                true
            }
        }
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
