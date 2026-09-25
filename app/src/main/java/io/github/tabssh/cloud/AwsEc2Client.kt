package io.github.tabssh.cloud

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
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
 * Endpoint: https://ec2.{region}.amazonaws.com/ (`.amazonaws.com.cn` for
 *           the China regions — see [ec2Host])
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
 *  - We pick `PublicDnsName` if present, fall back to `PublicIpAddress`,
 *    skip instances with neither (likely private subnet only).
 */
class AwsEc2Client : CloudProvider {

    override val type = CloudProviderType.AWS

    private val http: OkHttpClient = io.github.tabssh.network.SharedHttpClient.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
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

        val out = mutableListOf<ImportCandidate>()
        // Loop through all pages using NextToken until AWS returns no more results.
        var nextToken: String? = null
        do {
            val query = buildString {
                append("Action=DescribeInstances&Version=2016-11-15")
                if (nextToken != null) {
                    // Append the raw cursor; canonicalQueryString applies the
                    // single RFC-3986 encoding used for both signing and URL.
                    append("&NextToken=")
                    append(nextToken)
                }
            }
            val xml = awsGetXml(accessKey, secretKey, region, query)
            out += parseInstances(xml, region, accountName)
            nextToken = tagValue(xml, "nextToken")
        } while (nextToken != null)

        Logger.i("AwsEc2Client", "Fetched ${out.size} EC2 instances for account=$accountName region=$region")
        out
    }

    override suspend fun fetchLiveInstances(bearerToken: String): List<CloudInstanceState> =
        withContext(Dispatchers.IO) {
            val parts = bearerToken.split(":", limit = 3)
            if (parts.size != 3) throw IllegalStateException("AWS token must be 'AKID:SECRET:REGION'")
            val (accessKey, secretKey, region) = parts
            if (region.isBlank()) throw IllegalStateException("AWS region is required")

            val out = mutableListOf<CloudInstanceState>()
            var nextToken: String? = null
            do {
                val query = buildString {
                    append("Action=DescribeInstances&Version=2016-11-15")
                    if (nextToken != null) {
                        // Append the raw cursor; canonicalQueryString applies
                        // the single RFC-3986 encoding for signing and URL.
                        append("&NextToken=")
                        append(nextToken)
                    }
                }
                val xml = awsGetXml(accessKey, secretKey, region, query)
                out += parseLiveInstancesPage(xml, region)
                nextToken = tagValue(xml, "nextToken")
            } while (nextToken != null)
            out
        }

    override suspend fun startInstance(bearerToken: String, instanceId: String): Boolean =
        awsInstanceAction(bearerToken, instanceId, "StartInstances")

    override suspend fun stopInstance(bearerToken: String, instanceId: String): Boolean =
        awsInstanceAction(bearerToken, instanceId, "StopInstances")

    override suspend fun restartInstance(bearerToken: String, instanceId: String): Boolean =
        awsInstanceAction(bearerToken, instanceId, "RebootInstances")

    /** EC2 has no separate force restart — same as graceful reboot. */
    override suspend fun forceRestartInstance(bearerToken: String, instanceId: String): Boolean =
        awsInstanceAction(bearerToken, instanceId, "RebootInstances")

    private suspend fun awsInstanceAction(
        bearerToken: String,
        instanceId: String,
        action: String
    ): Boolean = withContext(Dispatchers.IO) {
        val parts = bearerToken.split(":", limit = 3)
        if (parts.size != 3) return@withContext false
        val (accessKey, secretKey, region) = parts
        if (region.isBlank()) return@withContext false

        val query = "Action=$action&InstanceId.1=$instanceId&Version=2016-11-15"
        val host = ec2Host(region)
        val service = "ec2"
        val now = Date()
        val amzDate = AMZ_DATE_FMT.formatter().format(now)
        val dateStamp = DATE_FMT.formatter().format(now)
        val canonicalQuery = canonicalQueryString(query)
        val payloadHash = sha256Hex("")
        val canonicalHeaders = "host:$host\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = "GET\n/\n$canonicalQuery\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val credentialScope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$credentialScope\n${sha256Hex(canonicalRequest)}"
        val signingKey = deriveSigningKey(secretKey, dateStamp, region, service)
        val signature = hexHmacSha256(signingKey, stringToSign)
        val auth = "AWS4-HMAC-SHA256 Credential=$accessKey/$credentialScope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"

        val req = Request.Builder()
            .url("https://$host/?$canonicalQuery")
            .header("Host", host)
            .header("X-Amz-Date", amzDate)
            .header("X-Amz-Content-Sha256", payloadHash)
            .header("Authorization", auth)
            .header("Accept", "application/xml")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) {
                throw CloudAuthException("AWS credentials rejected (HTTP ${resp.code})")
            }
            // EC2 reports errors with a non-2xx status (the AWS Query protocol
            // allows only 400, 500, or a custom awsQueryError code), so the
            // status code is a sufficient success check here.
            resp.isSuccessful
        }
    }

    private fun awsGetXml(
        accessKey: String,
        secretKey: String,
        region: String,
        query: String
    ): String {
        val host = ec2Host(region)
        val service = "ec2"
        val now = Date()
        val amzDate = AMZ_DATE_FMT.formatter().format(now)
        val dateStamp = DATE_FMT.formatter().format(now)
        val canonicalQuery = canonicalQueryString(query)
        val payloadHash = sha256Hex("")
        val canonicalHeaders = "host:$host\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = "GET\n/\n$canonicalQuery\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val credentialScope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$credentialScope\n${sha256Hex(canonicalRequest)}"
        val signingKey = deriveSigningKey(secretKey, dateStamp, region, service)
        val signature = hexHmacSha256(signingKey, stringToSign)
        val auth = "AWS4-HMAC-SHA256 Credential=$accessKey/$credentialScope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"

        val req = Request.Builder()
            .url("https://$host/?$canonicalQuery")
            .header("Host", host)
            .header("X-Amz-Date", amzDate)
            .header("X-Amz-Content-Sha256", payloadHash)
            .header("Authorization", auth)
            .header("Accept", "application/xml")
            .get()
            .build()
        return http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (resp.code == 401 || resp.code == 403) {
                    throw CloudAuthException("AWS credentials rejected (HTTP ${resp.code}): ${extractAwsError(body) ?: resp.message}")
                }
                throw IllegalStateException("AWS EC2 HTTP ${resp.code}: ${extractAwsError(body) ?: resp.message}")
            }
            body
        }
    }

    /** One instance's fields pulled from DescribeInstances XML. */
    private data class Ec2Instance(
        val instanceId: String,
        val state: String,
        val publicDns: String,
        val publicIp: String,
        val privateIp: String,
        val nameTag: String
    )

    /** Parse live-instance state from one page of DescribeInstances XML. */
    private fun parseLiveInstancesPage(xml: String, region: String): List<CloudInstanceState> {
        val out = mutableListOf<CloudInstanceState>()
        for (inst in parseEc2Instances(xml)) {
            val normStatus = when (inst.state) {
                "running" -> "running"
                "stopped", "terminated" -> "stopped"
                "pending" -> "starting"
                "stopping", "shutting-down" -> "stopping"
                else -> "unknown"
            }
            out += CloudInstanceState(
                id = inst.instanceId,
                name = inst.nameTag.ifBlank { inst.instanceId },
                ip = inst.publicIp.takeIf { it.isNotBlank() },
                privateIp = inst.privateIp.takeIf { it.isNotBlank() },
                status = normStatus,
                rawStatus = inst.state,
                region = region
            )
        }
        return out
    }

    private fun parseInstances(xml: String, region: String, accountName: String): List<ImportCandidate> {
        val out = mutableListOf<ImportCandidate>()
        for (inst in parseEc2Instances(xml)) {
            if (inst.state.isNotBlank() && inst.state != "running") continue

            val instanceId = inst.instanceId
            val publicDns = inst.publicDns
            val publicIp = inst.publicIp
            val nameTag = inst.nameTag

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
                    // Amazon Linux default; user can edit
                    username = "ec2-user",
                    authType = "publickey",
                    advancedSettings = cloudAdvancedSettings(
                        "cloud_source" to "aws:$accountName",
                        "cloud_region" to region,
                        "cloud_id" to instanceId
                    ),
                    createdAt = System.currentTimeMillis()
                ),
                sourceLabel = "AWS EC2 / $region"
            )
        }
        return out
    }

    // DescribeInstances nests <item> at several levels (reservationSet, instancesSet,
    // tagSet, networkInterfaceSet, blockDeviceMapping); a non-greedy <item>…</item>
    // regex stops at the first nested close tag, losing Name tags and inventing
    // phantom instances. Walk the tree with XmlPullParser and take an instance's
    // fields only from the direct children of its instancesSet <item>.
    private fun parseEc2Instances(xml: String): List<Ec2Instance> {
        val out = mutableListOf<Ec2Instance>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        // Names of the currently open elements, root first.
        val path = mutableListOf<String>()
        // path.size at the instance <item>; -1 while outside any instance.
        var instanceDepth = -1
        var instanceId = ""
        var state = ""
        var publicDns = ""
        var publicIp = ""
        var privateIp = ""
        var nameTag = ""
        var tagKey = ""
        var tagVal = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    path.add(parser.name)
                    if (instanceDepth < 0 && parser.name == "item" &&
                        path.size >= 2 && path[path.size - 2] == "instancesSet"
                    ) {
                        instanceDepth = path.size
                        instanceId = ""
                        state = ""
                        publicDns = ""
                        publicIp = ""
                        privateIp = ""
                        nameTag = ""
                    }
                    if (instanceDepth > 0 && parser.name == "item" &&
                        path.size == instanceDepth + 2 && path[instanceDepth] == "tagSet"
                    ) {
                        tagKey = ""
                        tagVal = ""
                    }
                }
                XmlPullParser.TEXT -> if (instanceDepth > 0) {
                    val text = parser.text
                    when {
                        path.size == instanceDepth + 1 -> when (path.last()) {
                            "instanceId" -> instanceId += text
                            "dnsName" -> publicDns += text
                            "ipAddress" -> publicIp += text
                            "privateIpAddress" -> privateIp += text
                        }
                        path.size == instanceDepth + 2 &&
                            path[instanceDepth] == "instanceState" && path.last() == "name" ->
                            state += text
                        path.size == instanceDepth + 3 &&
                            path[instanceDepth] == "tagSet" && path[instanceDepth + 1] == "item" ->
                            when (path.last()) {
                                "key" -> tagKey += text
                                "value" -> tagVal += text
                            }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (instanceDepth > 0 && path.size == instanceDepth + 2 &&
                        path[instanceDepth] == "tagSet" && path.last() == "item" && tagKey == "Name"
                    ) {
                        nameTag = tagVal
                    }
                    if (instanceDepth > 0 && path.size == instanceDepth) {
                        if (instanceId.isNotBlank()) {
                            out += Ec2Instance(
                                instanceId = instanceId.trim(),
                                state = state.trim(),
                                publicDns = publicDns.trim(),
                                publicIp = publicIp.trim(),
                                privateIp = privateIp.trim(),
                                nameTag = nameTag.trim()
                            )
                        }
                        instanceDepth = -1
                    }
                    if (path.isNotEmpty()) path.removeAt(path.size - 1)
                }
            }
            event = parser.next()
        }
        return out
    }

    // Only for flat, non-repeating top-level elements (nextToken); instance
    // fields go through parseEc2Instances, which handles the nested <item>s.
    private fun tagValue(xmlBlock: String, tag: String): String? {
        val m = Regex("<$tag>([^<]*)</$tag>").find(xmlBlock) ?: return null
        return m.groupValues[1].takeIf { it.isNotBlank() }
    }

    /**
     * EC2 endpoint host for a region. Standard, GovCloud and ISO partitions
     * all use `amazonaws.com`; only the China regions (`cn-*`) use the
     * `amazonaws.com.cn` suffix, so a China account on the default suffix
     * would fail to resolve.
     */
    private fun ec2Host(region: String): String =
        if (region.startsWith("cn-")) "ec2.$region.amazonaws.com.cn"
        else "ec2.$region.amazonaws.com"

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
        // SigV4 requires every parameter name and value to be RFC-3986
        // percent-encoded, then the pairs sorted by encoded name. The raw
        // query carries unencoded values (notably a NextToken cursor that can
        // contain +, /, = characters); encode them here so the signed
        // canonical string matches the request URL and the signature verifies.
        // Split key from value on the first '=' only — a value may itself
        // contain '=' (base64 padding).
        return rawQuery.split('&')
            .filter { it.isNotEmpty() }
            .map { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) {
                    rfc3986Encode(pair) to ""
                } else {
                    rfc3986Encode(pair.substring(0, eq)) to rfc3986Encode(pair.substring(eq + 1))
                }
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
    }

    // RFC-3986 unreserved characters are never encoded: A-Z a-z 0-9 - _ . ~
    // Everything else is percent-encoded with uppercase hex — including space
    // as %20 (not the '+' that form-encoding / URLEncoder would emit), which
    // is what AWS SigV4 canonicalization demands.
    private fun rfc3986Encode(value: String): String {
        val sb = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            val unreserved = c in 'A'.code..'Z'.code ||
                c in 'a'.code..'z'.code ||
                c in '0'.code..'9'.code ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            if (unreserved) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append("%02X".format(c))
            }
        }
        return sb.toString()
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
    // ThreadLocal.withInitial is API 26; the anonymous-subclass form works on
    // all API levels and is behaviourally identical.
    private val AMZ_DATE_FMT = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
    private val DATE_FMT = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    // initialValue() above never returns null, so get() cannot be null either.
    // checkNotNull states that invariant without a force-unwrap, which AI.md
    // PART 0 bans outside test code.
    private fun ThreadLocal<SimpleDateFormat>.formatter(): SimpleDateFormat =
        checkNotNull(get()) { "ThreadLocal SimpleDateFormat initialValue() returned null" }
}
