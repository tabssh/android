package io.github.tabssh.ui.activities

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.tabssh.R
import io.github.tabssh.crypto.tls.HypervisorTrustManagerFactory
import io.github.tabssh.network.SharedHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Runs an external IdP authorization-code + PKCE login and exchanges its token at vCenter. */
class VMwareOAuthActivity : AppCompatActivity() {
    private var authService: AuthorizationService? = null
    private var exchangeStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == ACTION_COMPLETE || intent.action == ACTION_CANCEL) {
            handleAuthorizationResult(intent)
        } else {
            beginAuthorization()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthorizationResult(intent)
    }

    override fun onDestroy() {
        authService?.dispose()
        authService = null
        super.onDestroy()
    }

    private fun beginAuthorization() {
        val issuer = intent.getStringExtra(EXTRA_ISSUER).orEmpty().trim()
        val clientId = intent.getStringExtra(EXTRA_CLIENT_ID).orEmpty().trim()
        val issuerUri = Uri.parse(issuer)
        if (issuerUri.scheme != "https" || issuerUri.host.isNullOrBlank() || clientId.isBlank() || clientId.length > 512) {
            fail("Enter a valid HTTPS OIDC issuer and public client ID in the hypervisor profile.")
            return
        }

        AuthorizationServiceConfiguration.fetchFromIssuer(issuerUri) { serviceConfiguration, exception ->
            if (isFinishing || isDestroyed) return@fetchFromIssuer
            if (exception != null || serviceConfiguration == null) {
                fail("Could not load the identity provider's OpenID configuration.")
                return@fetchFromIssuer
            }
            val redirectUri = Uri.parse(REDIRECT_URI)
            val request = AuthorizationRequest.Builder(
                serviceConfiguration,
                clientId,
                ResponseTypeValues.CODE,
                redirectUri
            )
                .setScope("openid profile")
                .build()
            authService = AuthorizationService(this)
            val completeIntent = Intent(this, VMwareOAuthActivity::class.java)
                .setAction(ACTION_COMPLETE)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val cancelIntent = Intent(this, VMwareOAuthActivity::class.java)
                .setAction(ACTION_CANCEL)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val complete = PendingIntent.getActivity(this, 4101, completeIntent, pendingIntentFlags())
            val cancel = PendingIntent.getActivity(this, 4102, cancelIntent, pendingIntentFlags())
            authService?.performAuthorizationRequest(request, complete, cancel)
        }
    }

    private fun handleAuthorizationResult(resultIntent: Intent) {
        if (exchangeStarted) return
        if (resultIntent.action == ACTION_CANCEL) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val response = AuthorizationResponse.fromIntent(resultIntent)
        val exception = AuthorizationException.fromIntent(resultIntent)
        if (exception != null || response == null) {
            fail("Identity provider sign-in was cancelled or rejected.")
            return
        }
        exchangeStarted = true
        val service = authService ?: AuthorizationService(this).also { authService = it }
        service.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenException ->
            if (isFinishing || isDestroyed) return@performTokenRequest
            val idpAccessToken = tokenResponse?.accessToken
            if (tokenException != null || idpAccessToken.isNullOrBlank()) {
                fail("The identity provider did not return an access token.")
                return@performTokenRequest
            }
            lifecycleScope.launch {
                try {
                    val vcenterToken = withContext(Dispatchers.IO) { exchangeAtVCenter(idpAccessToken) }
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_ACCESS_TOKEN, vcenterToken))
                    finish()
                } catch (_: Exception) {
                    fail("vCenter rejected the identity-provider token exchange.")
                }
            }
        }
    }

    private fun exchangeAtVCenter(idpAccessToken: String): String {
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, 443)
        val audience = intent.getStringExtra(EXTRA_AUDIENCE).orEmpty().trim()
        val verifySsl = intent.getBooleanExtra(EXTRA_VERIFY_SSL, false)
        val pin = intent.getStringExtra(EXTRA_PIN)
        if (host.isBlank() || host.length > 253 || host.any { it.isISOControl() } || port !in 1..65535) {
            throw IOException("Invalid vCenter endpoint")
        }
        val builder = SharedHttpClient.client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
        HypervisorTrustManagerFactory.installTrust(
            builder, verifySsl, pin, HypervisorTrustManagerFactory.CapturedPin(), host, port
        )
        val form = FormBody.Builder()
            .add("grant_type", TOKEN_EXCHANGE_GRANT)
            .add("subject_token", idpAccessToken)
            .add("subject_token_type", ACCESS_TOKEN_TYPE)
            .apply { if (audience.isNotBlank()) add("audience", audience) }
            .build()
        val request = Request.Builder()
            .url("https://$host:$port/api/vcenter/authentication/token")
            .addHeader("Authorization", "Bearer $idpAccessToken")
            .post(form)
            .build()
        builder.build().newCall(request).execute().use { response ->
            val source = response.body?.source() ?: throw IOException("Empty vCenter token response")
            source.request(MAX_BODY_BYTES + 1L)
            if (source.buffer.size > MAX_BODY_BYTES) throw IOException("vCenter token response too large")
            val body = source.readByteString().utf8()
            if (!response.isSuccessful) throw IOException("vCenter token exchange failed: ${response.code}")
            val token = JSONObject(body).optString("access_token")
            if (token.isBlank() || token.length > MAX_TOKEN_LENGTH || token.any { it.isISOControl() }) {
                throw IOException("vCenter returned an invalid access token")
            }
            return token
        }
    }

    private fun fail(message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.status_error)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                setResult(RESULT_CANCELED)
                finish()
            }
            .setOnCancelListener {
                setResult(RESULT_CANCELED)
                finish()
            }
            .show()
    }

    private fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    companion object {
        const val EXTRA_ISSUER = "issuer"
        const val EXTRA_CLIENT_ID = "client_id"
        const val EXTRA_AUDIENCE = "audience"
        const val EXTRA_HOST = "vcenter_host"
        const val EXTRA_PORT = "vcenter_port"
        const val EXTRA_VERIFY_SSL = "verify_ssl"
        const val EXTRA_PIN = "pinned_certificate"
        const val EXTRA_ACCESS_TOKEN = "vcenter_access_token"
        private const val REDIRECT_URI = "io.github.tabssh:/oauth2redirect"
        private const val ACTION_COMPLETE = "io.github.tabssh.VMWARE_OAUTH_COMPLETE"
        private const val ACTION_CANCEL = "io.github.tabssh.VMWARE_OAUTH_CANCEL"
        private const val TOKEN_EXCHANGE_GRANT = "urn:ietf:params:oauth:grant-type:token-exchange"
        private const val ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token"
        private const val MAX_BODY_BYTES = 1024L * 1024L
        private const val MAX_TOKEN_LENGTH = 8192
    }
}
