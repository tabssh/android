package io.github.tabssh.docker.registry

import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.network.SharedHttpClient
import io.github.tabssh.storage.database.entities.RegistryCredential
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Registry HTTP API v2 client for update digest checks.
 *
 * The only operation the update checker needs is `HEAD /v2/{name}/manifests/
 * {reference}` with the multi-arch Accept set, reading `Docker-Content-Digest`
 * from the response. Auth is challenge-driven: the first request goes out
 * anonymous; a 401 `WWW-Authenticate: Bearer …` triggers the token flow
 * (Docker Hub's auth.docker.io realm included), a `Basic` challenge retries
 * with the stored credential. Secrets arrive as parameters — this class never
 * touches the Keystore and never logs a secret.
 *
 * Declared `open` so unit tests can substitute a canned-digest subclass.
 */
open class RegistryClient(
    client: OkHttpClient? = null
) {

    /** One parsed WWW-Authenticate challenge: scheme + key/value parameters. */
    data class AuthChallenge(
        val scheme: String,
        val params: Map<String, String>
    ) {
        val realm: String? get() = params["realm"]
        val service: String? get() = params["service"]
        val scope: String? get() = params["scope"]
    }

    companion object {
        private const val TAG = "RegistryClient"
        private const val TIMEOUT_S = 30L

        /**
         * Accept set for manifest HEADs — OCI index, OCI manifest, Docker
         * manifest list v2, Docker manifest v2 — so multi-arch images answer
         * with the index/list digest that inspect RepoDigests records.
         */
        val MANIFEST_ACCEPT = listOf(
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.docker.distribution.manifest.v2+json"
        ).joinToString(", ")

        // token="value" or token=bareword parameter of an auth challenge.
        private val CHALLENGE_PARAM = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([^\s,]+))""")

        /**
         * Parse a `WWW-Authenticate` header ("Bearer realm=…,service=…" /
         * "Basic realm=…") into scheme + parameter map. Pure and testable;
         * returns null for a blank or scheme-less header.
         */
        fun parseWwwAuthenticate(header: String?): AuthChallenge? {
            val trimmed = header?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val space = trimmed.indexOf(' ')
            val scheme = (if (space < 0) trimmed else trimmed.substring(0, space)).trim()
            if (scheme.isEmpty() || scheme.contains('=')) return null
            val params = LinkedHashMap<String, String>()
            if (space >= 0) {
                for (m in CHALLENGE_PARAM.findAll(trimmed.substring(space + 1))) {
                    params[m.groupValues[1].lowercase()] =
                        m.groupValues[2].ifEmpty { m.groupValues[3] }
                }
            }
            return AuthChallenge(scheme, params)
        }

        /** RFC 7617 Basic value for a username/secret pair. */
        fun basicAuthValue(username: String, secret: String): String =
            "Basic " + "$username:$secret".encodeUtf8().base64()

        /** The default pull scope for a repository when the challenge has none. */
        fun pullScope(repository: String): String = "repository:$repository:pull"

        /** Largest token-exchange or manifest body read into memory (1 MiB). */
        const val MAX_BODY_BYTES = 1L * 1024 * 1024

        /** Cap on registry-supplied text echoed into a DockerResult detail. */
        const val MAX_DETAIL_CHARS = 512

        // Token realms that are trusted to receive the credentials of the
        // registry that issued the challenge, beyond the same-host default.
        private val REALM_ALIASES = mapOf(
            ImageRef.DOCKER_HUB_API_HOST to setOf("auth.docker.io"),
            "index.docker.io" to setOf("auth.docker.io")
        )

        /**
         * Whether the stored secret may be sent to [realm] for a challenge
         * issued by [apiHost].
         *
         * A `WWW-Authenticate` header is attacker-controlled data from the
         * moment a registry (or anything impersonating one over a redirect)
         * answers 401, so the realm decides where the credential travels.
         * Requiring https and a matching host stops a hostile registry from
         * naming `http://evil/` — or any third-party host — as the realm and
         * harvesting the Basic credential for another registry. An untrusted
         * realm is not fatal: the exchange still happens anonymously, which
         * is all a public image needs.
         */
        fun realmAcceptsCredentials(realm: HttpUrl, apiHost: String): Boolean {
            if (!realm.isHttps) return false
            val host = realm.host.lowercase()
            val registry = apiHost.substringBefore(':').lowercase()
            if (host == registry) return true
            return REALM_ALIASES[registry]?.contains(host) == true
        }
    }

    private val http: OkHttpClient = client ?: SharedHttpClient.client.newBuilder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /**
     * Resolve the current manifest digest ("sha256:…") for [ref].
     *
     * Auth ladder: (1) anonymous; (2) on a Bearer challenge, exchange at the
     * challenge realm — with Basic credentials when [credential]+[secret] are
     * present, anonymously otherwise (the Docker Hub public flow) — and retry
     * with the bearer token; (3) on a Basic challenge, retry with Basic; (4)
     * for authType "token" the stored secret is additionally tried verbatim
     * as a Bearer token when the exchange path yields nothing.
     */
    open suspend fun fetchManifestDigest(
        ref: ImageRef,
        credential: RegistryCredential? = null,
        secret: String? = null
    ): DockerResult<String> = withContext(Dispatchers.IO) {
        val url = "https://${ref.apiHost}/v2/${ref.repository}/manifests/${ref.manifestReference}"
        try {
            // Attempt 1 — anonymous.
            var challengeHeader: String? = null
            headManifest(url, null).use { response ->
                if (response.isSuccessful) return@withContext digestFrom(response, ref)
                if (response.code != 401 && response.code != 403) {
                    return@withContext classify(response, ref)
                }
                challengeHeader = response.header("WWW-Authenticate")
            }

            // Attempt 2 — satisfy the challenge.
            val challenge = parseWwwAuthenticate(challengeHeader)
            val authValue = when (challenge?.scheme?.lowercase()) {
                "bearer" -> bearerFor(challenge, ref, credential, secret)
                "basic" ->
                    if (credential != null && !secret.isNullOrEmpty()) {
                        basicAuthValue(credential.username, secret)
                    } else {
                        null
                    }
                else -> null
            }
                // authType "token": last resort, the secret IS the bearer token.
                ?: secret?.takeIf { credential?.authType == "token" && it.isNotEmpty() }
                    ?.let { "Bearer $it" }
                ?: return@withContext DockerResult.PermissionDenied(
                    "Registry requires authentication for ${ref.canonicalRepository}",
                    detail = challengeHeader?.take(MAX_DETAIL_CHARS)
                )

            headManifest(url, authValue).use { response ->
                if (response.isSuccessful) {
                    digestFrom(response, ref)
                } else {
                    classify(response, ref)
                }
            }
        } catch (e: IOException) {
            DockerResult.Error(
                "Registry ${ref.apiHost} unreachable", e.message
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DockerResult.Error("Registry check failed for ${ref.canonicalRepository}", e.message)
        }
    }

    /**
     * Read a response body as bytes, refusing anything over [MAX_BODY_BYTES].
     *
     * A registry is an untrusted remote peer: a plain `body.bytes()` lets it
     * decide how much of this process's heap to consume. `peekBody` caps the
     * read, and one byte over the cap is treated as a refusal rather than a
     * truncation so nothing downstream parses or hashes a half document.
     */
    private fun readBoundedBytes(response: Response): ByteArray? {
        val peeked = response.peekBody(MAX_BODY_BYTES + 1).bytes()
        if (peeked.size > MAX_BODY_BYTES) {
            Logger.w(TAG, "response body exceeded $MAX_BODY_BYTES bytes; refused")
            return null
        }
        return peeked
    }

    /** Text form of [readBoundedBytes]. */
    private fun readBounded(response: Response): String? =
        readBoundedBytes(response)?.toString(Charsets.UTF_8)

    /** One manifest HEAD with the multi-arch Accept set. */
    private fun headManifest(url: String, authorization: String?): Response {
        val builder = Request.Builder()
            .url(url)
            .head()
            .header("Accept", MANIFEST_ACCEPT)
        if (authorization != null) builder.header("Authorization", authorization)
        return http.newCall(builder.build()).execute()
    }

    /**
     * Bearer token exchange at the challenge realm. Anonymous exchange (no
     * Authorization on the token request) is the Docker Hub public-image
     * flow: GET {realm}?service={service}&scope=repository:{name}:pull.
     */
    private fun bearerFor(
        challenge: AuthChallenge,
        ref: ImageRef,
        credential: RegistryCredential?,
        secret: String?
    ): String? {
        val realmUrl = challenge.realm?.toHttpUrlOrNull() ?: return null
        if (!realmUrl.isHttps) {
            Logger.w(TAG, "token realm ${realmUrl.host} rejected: not https")
            return null
        }
        val urlBuilder = realmUrl.newBuilder()
        challenge.service?.let { urlBuilder.addQueryParameter("service", it) }
        urlBuilder.addQueryParameter("scope", challenge.scope ?: pullScope(ref.repository))
        val builder = Request.Builder().url(urlBuilder.build()).get()
        val mayAuthenticate = realmAcceptsCredentials(realmUrl, ref.apiHost)
        if (credential != null && credential.username.isNotEmpty() && !secret.isNullOrEmpty()) {
            if (mayAuthenticate) {
                builder.header("Authorization", basicAuthValue(credential.username, secret))
            } else {
                Logger.w(
                    TAG,
                    "token realm ${realmUrl.host} is not ${ref.apiHost}; exchanging anonymously " +
                        "(credential=xxxxx withheld)"
                )
            }
        }
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                Logger.w(TAG, "token exchange at ${realmUrl.host} failed (HTTP ${response.code})")
                return null
            }
            val body = JSONObject(readBounded(response).orEmpty())
            val token = body.optString("token").ifEmpty { body.optString("access_token") }
            return token.takeIf { it.isNotEmpty() }?.let { "Bearer $it" }
        }
    }

    /**
     * Digest from a successful manifest response — the Docker-Content-Digest
     * header normally; a GET + local sha256 of the exact manifest bytes as
     * the fallback for registries that omit the header on HEAD.
     */
    private fun digestFrom(response: Response, ref: ImageRef): DockerResult<String> {
        // The header is registry-supplied: only a well-formed digest is taken,
        // anything else falls through to hashing the manifest locally.
        response.header("Docker-Content-Digest")?.trim()?.takeIf { ImageRef.isValidDigest(it) }?.let {
            return DockerResult.Success(it)
        }
        // Preserve the auth the HEAD used for the fallback GET.
        val get = response.request.newBuilder().get().build()
        http.newCall(get).execute().use { body ->
            if (!body.isSuccessful) return classify(body, ref)
            val bytes = readBoundedBytes(body)
                ?: return DockerResult.Error("Manifest body was empty or oversized for ${ref.canonicalRepository}")
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            return DockerResult.Success(
                "sha256:" + sha.joinToString("") { "%02x".format(it) }
            )
        }
    }

    /** Map a registry HTTP failure onto the shared DockerResult failures. */
    private fun classify(response: Response, ref: ImageRef): DockerResult<Nothing> = when (response.code) {
        404 -> DockerResult.NotFound(
            "Image ${ref.canonicalRepository}:${ref.tag} not found on ${ref.apiHost}"
        )
        401, 403 -> DockerResult.PermissionDenied(
            "Registry ${ref.apiHost} rejected the credentials for ${ref.canonicalRepository}"
        )
        else -> DockerResult.Error(
            "Registry ${ref.apiHost} returned HTTP ${response.code} for ${ref.canonicalRepository}"
        )
    }
}
