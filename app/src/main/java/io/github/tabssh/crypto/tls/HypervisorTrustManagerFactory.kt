package io.github.tabssh.crypto.tls

import android.annotation.SuppressLint
import io.github.tabssh.utils.logging.Logger
import okhttp3.OkHttpClient
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Configures TLS for user-managed infrastructure endpoints.
 *
 * When [verifySsl] is enabled, OkHttp's platform defaults validate the
 * certificate chain and hostname. When disabled, TLS encryption remains enabled
 * but the certificate is not used as a stable host identity: infrastructure
 * providers routinely rotate certificates. SSH transports use the separate
 * known-hosts verifier for host identity.
 */
object HypervisorTrustManagerFactory {

    private const val TAG = "HypervisorTrust"

    /** Retained for source compatibility with callers that still read old pin columns. */
    class CapturedPin {
        @Volatile
        var sha256: String? = null
    }

    /**
     * Apply the profile's optional TLS certificate validation setting.
     * Legacy pin arguments remain accepted so existing database and client
     * constructors can be migrated without a destructive schema change.
     */
    @SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
    fun installTrust(
        builder: OkHttpClient.Builder,
        verifySsl: Boolean,
        pinnedSha256: String?,
        captured: CapturedPin,
        host: String = "",
        port: Int = 0,
        onPinCaptured: (() -> Unit)? = null
    ) {
        // Certificate pins are deliberately no longer used for infrastructure
        // identity. Clear stale in-memory captures to prevent old callers from
        // persisting values after this policy change.
        captured.sha256 = null
        if (verifySsl) return

        Logger.w(
            TAG,
            "TLS certificate validation disabled for ${if (host.isNotEmpty()) "$host:$port" else "infrastructure endpoint"}"
        )
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("Empty certificate chain")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustAll), java.security.SecureRandom())
        builder.sslSocketFactory(context.socketFactory, trustAll)
        builder.hostnameVerifier { _, _ -> true }
    }
}
