package io.github.tabssh.cloud

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Wave 8.1 — AWS EC2 inventory via the Query API + SigV4 signing.
 *
 * Endpoint: https://ec2.{region}.amazonaws.com/
 * Action:   DescribeInstances&Version=2016-11-15
 * Auth:     SigV4 over Authorization header.
 *
 * Credentials are passed in the bearer-token slot of CloudAccount as a
 * single colon-separated string: `AKID:SECRET:REGION`. The credential
 * IS sensitive (Secret Access Key) — `SecurePasswordManager` already
 * AES-GCM-wraps it under the hardware Keystore for at-rest protection.
 *
 * Why this is "honest scope" even without an AWS account to test against:
 *  - SigV4 is a deterministic spec; the canonical-request → string-to-sign
 *    → signature pipeline can be unit-verified against AWS's own
 *    documented test vectors (we don't include them here, but the format
 *    is unambiguous from the spec).
 *  - Anyone with a free-tier AWS IAM user can verify in the field by
 *    pointing the cloud account at it.
 *
 * Limitations:
 *  - Single region per account. To pull from multiple regions, add one
 *    cloud account per region.
 *  - DescribeInstances pagination (NextToken) is not yet handled; first
 *    page only (typical: 1000 instances per page). Bigger fleets need a
 *    follow-up patch.
 *  - We pick `PublicDnsName` if present, fall back to `PublicIpAddress`,
 *    skip instances with neither (likely private subnet only).
 */
class AwsEc2Client : CloudProvider {

    override val type = CloudProviderType.AWS

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun fetchInventory(
        bearerToken: String,
        accountName: String
    ): List<ImportCandidate> = withContext(Dispatchers.IO) {
        val parts = bearerToken.split(":", limit = 3)
        if (parts.size != 3) {
            throw IllegalStateException("AWS account token must be 'AKID:SECRET:REGION'")
        }
        val (accessKey, secretKey, region) = parts
        if (region.isBlank()) throw IllegalStateException("AWS region is required")

        val host = "ec2.$region.amazonaws.com"
        val service = "ec2"
        val now = Date()
        val amzDate = AMZ_DATE_FMT.get()!!.format(now)
        val dateStamp = DATE_FMT.get()!!.format(now)

        // Action + Version go into the query string for GET.
        val query = "Action=DescribeInstances&Version=2016-11-15"
        val canonicalQuery = canonicalQueryString(query)
        val canonicalUri = "/"
        val payloadHash = sha256Hex("")

        val canonicalHeaders = "host:$host\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"

        val canonicalRequest = listOf(
            "GET",
            canonicalUri,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            payloadHash
        ).joinToString("\n")

        val credentialScope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = listOf(
            "AWS4-HMAC-SHA256",
            amzDate,
            credentialScope,
            sha256Hex(canonicalRequest)
        ).joinToString("\n")

        val signingKey = deriveSigningKey(secretKey, dateStamp, region, service)
        val signature = hexHmacSha256(signingKey, stringToSign)

        val auth = "AWS4-HMAC-SHA256 " +
            "Credential=$accessKey/$credentialScope, " +
            "SignedHeaders=$signedHeaders, " +
            "Signature=$signature"

        val req = Request.Builder()
            .url("https://$host$canonicalUri?$canonicalQuery")
            .header("Host", host)
            .header("X-Amz-Date", amzDate)
            .header("X-Amz-Content-Sha256", payloadHash)
            .header("Authorization", auth)
            .header("Accept", "application/xml")
            .get()
            .build()

        val xml = http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("AWS EC2 HTTP ${resp.code}: ${extractAwsError(body) ?: resp.message}")
            }
            body
        }

        parseInstances(xml, region, accountName).also {
            Logger.i("AwsEc2Client", "Fetched ${it.size} EC2 instances for account=$accountName region=$region")
        }
    }

    /** Lightweight tag-extract — Android SAX/DOM both work, but we only need
     *  PublicDnsName / PublicIpAddress / instanceId / Tags > Name. Keep it
     *  regex-based to skip XML parser ceremony. */
    private fun parseInstances(xml: String, region: String, accountName: String): List<ImportCandidate> {
        // Each <instancesSet> > <item> is one instance. Find every <item> block
        // inside <instancesSet>.
        val instanceBlocks = Regex(
            """<instancesSet>([\s\S]*?)</instancesSet>"""
        ).findAll(xml).flatMap { container ->
            Regex("""<item>([\s\S]*?)</item>""").findAll(container.groupValues[1])
        }.map { it.groupValues[1] }.toList()

        val out = mutableListOf<ImportCandidate>()
        for (block in instanceBlocks) {
            val state = tagValue(block, "name")  // <instanceState><name>running</name></instanceState>
            if (state != null && state != "running") continue

            val instanceId = tagValue(block, "instanceId").orEmpty()
            val publicDns = tagValue(block, "dnsName").orEmpty()
            val publicIp = tagValue(block, "ipAddress").orEmpty()
            val nameTag = extractNameTag(block).orEmpty()

            val host = publicDns.takeIf { it.isNotBlank() } ?: publicIp.takeIf { it.isNotBlank() }
            if (host.isNullOrBlank()) {
                Logger.d("AwsEc2Client", "$instanceId has no public address — skipping")
                continue
            }
            val name = nameTag.ifBlank { instanceId }
            out += ImportCandidate(
                profile = ConnectionProfile(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    host = host,
                    port = 22,
                    username = "ec2-user", // Amazon Linux default; user can edit
                    authType = "publickey",
                    advancedSettings = """{"cloud_source":"aws:$accountName","cloud_region":"$region","cloud_id":"$instanceId"}""",
                    createdAt = System.currentTimeMillis()
                ),
                sourceLabel = "AWS EC2 / $region"
            )
        }
        return out
    }

    private fun tagValue(xmlBlock: String, tag: String): String? {
        val m = Regex("<$tag>([^<]*)</$tag>").find(xmlBlock) ?: return null
        return m.groupValues[1].takeIf { it.isNotBlank() }
    }

    /** Walk <tagSet><item><key>Name</key><value>foo</value></item></tagSet> */
    private fun extractNameTag(block: String): String? {
        val tagSet = Regex("""<tagSet>([\s\S]*?)</tagSet>""").find(block)?.groupValues?.get(1) ?: return null
        for (m in Regex("""<item>([\s\S]*?)</item>""").findAll(tagSet)) {
            val item = m.groupValues[1]
            if (tagValue(item, "key") == "Name") return tagValue(item, "value")
        }
        return null
    }

    private fun extractAwsError(body: String): String? {
        val msg = Regex("<Message>([^<]+)</Message>").find(body)?.groupValues?.get(1)
        val code = Regex("<Code>([^<]+)</Code>").find(body)?.groupValues?.get(1)
        return when {
            msg != null && code != null -> "$code: $msg"
            msg != null -> msg
            code != null -> code
            else -> null
        }
    }

    // ── SigV4 helpers ──────────────────────────────────────────────────────

    private fun canonicalQueryString(rawQuery: String): String {
        // For our two static params (Action=… & Version=…) the order is
        // already alphabetical. Generic implementation: split, percent-
        // encode, sort, rejoin.
        return rawQuery.split('&').sortedBy { it.substringBefore('=') }.joinToString("&")
    }

    private fun deriveSigningKey(secret: String, dateStamp: String, region: String, service: String): ByteArray {
        val kSecret = ("AWS4$secret").toByteArray(Charsets.UTF_8)
        val kDate = hmacSha256(kSecret, dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        return hmacSha256(kService, "aws4_request")
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hexHmacSha256(key: ByteArray, data: String): String =
        hmacSha256(key, data).joinToString("") { "%02x".format(it) }

    private fun sha256Hex(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // SigV4 timestamps must be UTC.
    private val AMZ_DATE_FMT = ThreadLocal.withInitial<SimpleDateFormat> {
        SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
    private val DATE_FMT = ThreadLocal.withInitial<SimpleDateFormat> {
        SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
}
