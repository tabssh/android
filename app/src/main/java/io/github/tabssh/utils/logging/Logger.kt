package io.github.tabssh.utils.logging

import android.content.Context
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import io.github.tabssh.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Centralized logging system for TabSSH — two tiers, two audiences.
 *
 * DEBUG log (tabssh_debug.log, DEBUG_LOG builds only): verbose diagnostics —
 * D/I chatter plus W/E with full sanitized stack traces. Protected against
 * noise at the writer: identical consecutive lines collapse into a single
 * "repeated N times" summary and D-level lines are rate-capped per tag, so
 * no call site can flood the file or push real events past export caps.
 *
 * APP log (tabssh_app.log, always on, sanitized for public sharing): the
 * end-user record — session/auth/security lifecycle events (allowlisted
 * I-level tags plus the explicit event() API) and W/E entries condensed to
 * exception class + message + top frame: just enough detail to fix.
 *
 * Both sinks share one bounded single-writer executor and keep a persistent
 * buffered writer per file — no per-line open/write/close cycles.
 *
 * Crashes are captured via UncaughtExceptionHandler (writeCrashSync).
 */
object Logger {

    private var debugMode = false
    private var logToFile = false

    // Minimum log level — fed by the `debug_log_level` ListPreference
    // (preferences_logging.xml). Lower numbers = more verbose. Used by
    // shouldLog() to drop entries below the user-selected threshold.
    private const val LVL_VERBOSE = 0
    private const val LVL_DEBUG = 1
    private const val LVL_INFO = 2
    private const val LVL_WARNING = 3
    private const val LVL_ERROR = 4
    @Volatile private var minLevel: Int = LVL_DEBUG

    private fun levelFromPref(value: String?): Int = when (value) {
        "verbose" -> LVL_VERBOSE
        "debug"   -> LVL_DEBUG
        "info"    -> LVL_INFO
        "warning" -> LVL_WARNING
        "error"   -> LVL_ERROR
        else      -> LVL_DEBUG
    }

    private fun shouldLog(level: Int): Boolean = level >= minLevel

    private var logFile: File? = null
    // Always-on sanitized log for bug reports
    private var appLogFile: File? = null
    private var appContext: Context? = null

    // Bounded, drop-oldest queue — an unbounded queue (the Executors.
    // newSingleThreadExecutor() default) lets a chatty session (VNC/mosh
    // streaming, per-frame redraws) pile up thousands of pending file writes
    // faster than the single writer thread can drain them. That backlog
    // means the writer thread stays continuously busy doing tiny synchronous
    // FileWriter open/write/close cycles, which saturates disk I/O and
    // contends with anything else on the device touching storage — a
    // contributor to the settings-page ANR under load. Capping the queue and
    // dropping the oldest pending write when full keeps the writer caught up
    // to "now" instead of processing a multi-minute-old backlog.
    private const val MAX_QUEUED_WRITES = 256
    private val writeQueue = ArrayBlockingQueue<Runnable>(MAX_QUEUED_WRITES)
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, writeQueue
    ) { r, exec ->
        exec.queue.poll()
        exec.execute(r)
    }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private const val TAG_PREFIX = "TabSSH"
    private const val LOG_FILE_NAME = "tabssh_debug.log"
    // Sanitized for public sharing
    private const val APP_LOG_FILE_NAME = "tabssh_app.log"

    // Issue #36 — Cap log files at 1 MiB and keep up to N rotated copies.
    // Was: a single 10 MiB file with one `.old` backup → up to 20 MiB on
    // disk and a single rotation copying that whole thing. Now we keep
    // `.log + .log.1 .. .log.{N-1}` so each rotation is fast (just renames),
    // total on-disk is bounded at MAX_LOG_SIZE * MAX_LOG_FILES, and a
    // pathologically chatty session can't grow a single file forever.
    // 1 MiB per file
    private const val MAX_LOG_SIZE = 1 * 1024 * 1024
    // 1 MiB per file
    private const val MAX_APP_LOG_SIZE = 1 * 1024 * 1024
    // .log + .log.1..4
    private const val MAX_LOG_FILES = 5

    // Counters for anonymizing hosts/users in app log
    private val hostMap = mutableMapOf<String, String>()
    private val userMap = mutableMapOf<String, String>()
    private var hostCounter = 0
    private var userCounter = 0

    // Issue #12 — jump-host registry so bastions render as jump1/jump2/…
    // distinct from target hosts (server1/server2/…). Callers register the
    // jump-host string BEFORE the first log line that mentions it; the
    // sanitizer consults this set on every hostname/IP substitution and
    // routes matching entries to a separate map/counter.
    // Case-insensitive on hostnames; IP literals compared verbatim.
    private val jumpHostSet = mutableSetOf<String>()
    private val jumpHostMap = mutableMapOf<String, String>()
    private var jumpHostCounter = 0

    /**
     * Register a host as a jump host so subsequent log lines mentioning it
     * anonymize to `jump{N}` instead of `server{N}` / `IP{N}`. Idempotent.
     * Must be called BEFORE any log line that includes the host string —
     * the first sanitize pass caches the mapping.
     */
    fun registerJumpHost(host: String) {
        synchronized(jumpHostSet) {
            jumpHostSet.add(host.lowercase())
        }
    }

    private fun anonForHost(host: String, fallback: () -> String): String {
        val isJump = synchronized(jumpHostSet) { host.lowercase() in jumpHostSet }
        return if (isJump) {
            jumpHostMap.getOrPut(host) { "jump${++jumpHostCounter}" }
        } else {
            fallback()
        }
    }

    // ── Sanitization patterns — compiled ONCE at object load, never per-call ──
    // Inline Regex(...) inside sanitizeForPublic() was allocating a new pattern
    // object on every log write; pre-compiling here drops that cost to zero.
    private val RE_PASSWORD   = Regex("""(?i)password[=:]\s*\S+""")
    private val RE_PASSWD     = Regex("""(?i)passwd[=:]\s*\S+""")
    private val RE_USERNAME   = Regex("""(?i)username[=:]\s*\S+""")
    private val RE_USER_KV    = Regex("""(?i)\buser[=:]\s*\S+""")
    private val RE_TOKEN      = Regex("""(?i)\btoken[=:]\s*\S+""")
    private val RE_SECRET     = Regex("""(?i)\bsecret[=:]\s*\S+""")
    private val RE_AUTH_KV    = Regex("""(?i)\bauth(?:key|token)?[=:]\s*\S+""")
    private val RE_APIKEY     = Regex("""(?i)api[_-]?key[=:]\s*\S+""")
    // URL query-string secrets, e.g. "...?vncticket=abc123&host=..." (Proxmox/
    // XCP-ng/Xen Orchestra console URLs embed live session tickets in the query).
    private val RE_URL_QUERY_SECRET = Regex(
        """(?i)\b(vncticket|ticket|session_id|sessionid|access_token|sig|signature)=[^&\s]+"""
    )
    // HTTP Authorization headers (Basic/Bearer credentials).
    private val RE_AUTH_HEADER = Regex("""(?i)authorization\s*[:=]\s*(basic|bearer)\s+\S+""")
    // JSON-quoted credential fields, e.g. {"password":"hunter2"}.
    private val RE_JSON_CREDENTIAL = Regex(
        """(?i)"(password|passwd|token|secret|api_key|apikey|passphrase)"\s*:\s*"[^"]*""""
    )
    private val RE_SSH_PRIV   = Regex("""-----BEGIN[^-]+-----[\s\S]*?-----END[^-]+-----""")
    private val RE_SSH_PUB    = Regex("""ssh-(rsa|ed25519|ecdsa|dss)\s+\S+""")
    // SSH key fingerprints: SHA256:base64 or MD5:xx:xx:... (produced by ssh-keygen -l)
    private val RE_FP_SHA256  = Regex("""SHA256:[A-Za-z0-9+/]{43}={0,1}""")
    private val RE_FP_MD5     = Regex("""MD5:(?:[0-9a-fA-F]{2}:){15}[0-9a-fA-F]{2}""")
    // IPv6 — four patterns to cover all RFC 4291 forms without false-positives.
    // Order matters in sanitizeForPublic: IPv4-mapped must run before bare IPv4
    // so the whole address is replaced as a unit.
    // RE_IPV6_BRACKET : [addr] notation used in URIs, e.g. [::1], [fe80::1%eth0]
    // RE_IPV6_V4MAPPED: ::ffff:d.d.d.d and x:x:x:x:x:x:d.d.d.d mixed forms
    // RE_IPV6_FULL    : fully-expanded 8-group, no :: e.g. 2001:db8:0:0:0:0:0:1
    // RE_IPV6_COMP    : compressed form that contains ::, e.g. ::1, fe80::1.
    //                   The mandatory :: distinguishes it from HH:MM:SS timestamps.
    private val RE_IPV6_BRACKET  = Regex("""\[(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}(?:%[a-zA-Z0-9._~-]+)?\]""")
    private val RE_IPV6_V4MAPPED = Regex("""(?<![:\w])(?:[0-9a-fA-F]{1,4}:){1,6}(?:\d{1,3}\.){3}\d{1,3}(?![:\w])""")
    private val RE_IPV6_FULL     = Regex("""(?<![:\w])(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}(?![:\w])""")
    private val RE_IPV6_COMP     = Regex("""(?<![:\w])(?:[0-9a-fA-F]{1,4}:){0,6}::(?:(?:[0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{0,4})?(?![:\w])""")
    private val RE_IPV4          = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    // RE_HOSTNAME: multi-label so a full FQDN (host.example.com) is matched
    // and replaced as ONE unit — a two-label pattern used to match only
    // "host.example" and leave a real-TLD residue like "server2.com" in the
    // sanitized log (issue #12 comment thread). TLD must start with a letter
    // so version numbers like "2.0", "8.7", "SSH-2.0" are not matched.
    private val RE_HOSTNAME      = Regex("""\b(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z][a-zA-Z0-9]+\b""")
    private val RE_USER_AT    = Regex("""([a-zA-Z0-9_.-]+)@([a-zA-Z0-9.-]+)""")
    private val RE_HOME_PATH  = Regex("""/home/[a-zA-Z0-9_-]+""")
    private val RE_USERS_PATH = Regex("""/Users/[a-zA-Z0-9_-]+""")
    // Ports (issue #12 comment thread — ports leaked in shared logs).
    // RE_PORT_KV redacts explicit "port 2222" / "port=2222" / "port: 2222"
    // forms anywhere; RE_ANON_HOST_PORT redacts the ":port" suffix left
    // behind after a host was anonymized to server{N}/IP{N}/jump{N}.
    private val RE_PORT_KV        = Regex("""(?i)\bport[=:\s]\s*\d{1,5}\b""")
    private val RE_ANON_HOST_PORT = Regex("""\b((?:server|IP|jump)\d+):\d{1,5}\b""")
    // Host labels that are always safe to keep as-is. A host is preserved
    // verbatim only when one of its dot-separated labels EXACTLY equals an
    // entry here — substring matching preserved private hosts like
    // "myandroid.example", and the old blanket .com/.org/.net suffix bypass
    // preserved nearly every real user domain verbatim (issue #12 comment
    // thread). Covers SSH algorithm vendor domains (openssh.com, libssh.org),
    // JVM/Android runtime packages, and third-party library packages so
    // stack traces and algorithm-name strings like
    // "chacha20-poly1305@openssh.com" stay readable.
    private val SAFE_DOMAIN_LABELS = setOf(
        // public cloud / OS / dev tool domains — add diagnostic context
        "android", "androidx", "google", "github",
        // SSH algorithm vendor domains embedded in algorithm names
        "openssh", "libssh", "dropbear",
        // JVM / Android runtime packages that appear in stack traces
        "java", "javax", "kotlin", "kotlinx", "dalvik", "sun",
        // third-party libraries whose class names appear in stack traces
        "okhttp", "okhttp3", "jsch", "jcraft", "okio",
    )

    // True when any dot-separated label of [host] exactly matches a safe
    // label — e.g. "com.jcraft.jsch.Session" (jcraft), "java.lang" (java),
    // "chacha20-poly1305@openssh.com"'s host part (openssh).
    private fun isSafeHost(host: String): Boolean =
        host.lowercase().split('.').any { it in SAFE_DOMAIN_LABELS }

    /**
     * Initialize the logger
     * @param context Application context
     * @param debugMode Whether to enable debug logging to file
     *
     * Note: Crash handling is done by TabSSHApplication which calls writeCrashSync()
     */
    fun initialize(context: Context, debugMode: Boolean) {
        appContext = context.applicationContext
        this.debugMode = debugMode
        this.logToFile = debugMode

        // Read user-selected verbosity from `debug_log_level` ListPreference;
        // re-read on every log call would be wasteful, so we cache and let
        // updateMinLevelFromPrefs() refresh when SettingsActivity changes it.
        minLevel = levelFromPref(io.github.tabssh.TabSSHApplication.get().preferencesManager.getDebugLogLevel())

        // Always enable app log (sanitized for public sharing)
        appLogFile = File(context.filesDir, APP_LOG_FILE_NAME)
        writeToAppLog("I", "Logger", "=== TabSSH Started ===")
        writeToAppLog("I", "Logger", "App Version: ${getAppVersion(context)}")
        writeToAppLog("I", "Logger", "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        writeToAppLog("I", "Logger", "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")

        if (debugMode) {
            // Enable file logging
            logFile = File(context.filesDir, LOG_FILE_NAME)

            i("Logger", "=== TabSSH Debug Logging Started ===")
            i("Logger", "App Version: ${getAppVersion(context)}")
            i("Logger", "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            i("Logger", "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        } else {
            // Debug mode disabled - delete log file if it exists
            logFile = File(context.filesDir, LOG_FILE_NAME)
            if (logFile?.exists() == true) {
                logFile?.delete()
                Log.i("$TAG_PREFIX:Logger", "Debug logging disabled - log file deleted")
            }
            logFile = null
            logToFile = false
        }
    }

    /**
     * Get app version string
     */
    private fun getAppVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = PackageInfoCompat.getLongVersionCode(pInfo)
            "${pInfo.versionName} ($code)"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Re-read the `debug_log_level` preference at runtime — called by
     * SettingsActivity after the user changes the ListPreference so the
     * new threshold takes effect immediately without restarting the app.
     */
    fun updateMinLevelFromPrefs() {
        appContext ?: return
        minLevel = levelFromPref(io.github.tabssh.TabSSHApplication.get().preferencesManager.getDebugLogLevel())
    }

    // logcat itself is only sanitized in release builds — debug builds keep
    // raw logcat output so local development can see full message content.
    private fun logcatMessage(message: String): String =
        if (BuildConfig.DEBUG) message else sanitizeForPublic(message)

    fun d(tag: String, message: String, throwable: Throwable? = null) {
        if (debugMode && shouldLog(LVL_DEBUG)) {
            Log.d("$TAG_PREFIX:$tag", logcatMessage(message), throwable)
            writeToFile("D", tag, message, throwable)
        }
        // Debug messages NOT written to app log (too verbose, may contain sensitive data)
    }

    // Last-emit timestamp per throttle key, guarded by its own lock — kept
    // separate from the executor so throttling decisions never wait on the
    // (potentially backlogged) file-write queue.
    private val throttleTimestamps = mutableMapOf<String, Long>()

    /**
     * Rate-limited variant of d() for call sites that fire many times per
     * second during an active session (per-frame redraw scheduling, per-byte
     * PTY writes). Unthrottled, these calls flood the single-writer executor
     * with tiny synchronous file opens, which both saturates disk I/O — a
     * contributor to main-thread jank/ANRs on slower storage — and truncates
     * exported logs long before anything useful in them is captured.
     * At most one call per (tag, key) is written every [minIntervalMs].
     */
    fun dThrottled(tag: String, key: String, minIntervalMs: Long, message: () -> String) {
        if (!debugMode || !shouldLog(LVL_DEBUG)) return
        if (!shouldEmitThrottled(tag, key, minIntervalMs)) return
        val resolved = message()
        Log.d("$TAG_PREFIX:$tag", logcatMessage(resolved), null)
        writeToFile("D", tag, resolved, null)
    }

    // Injectable so the unit test can advance time deterministically instead
    // of sleeping; production always reads the wall clock.
    internal var throttleClock: () -> Long = { System.currentTimeMillis() }

    /**
     * Records an emit for (tag, key) and reports whether the caller may write.
     * Shared by every throttled level so one call site can never be rate
     * limited on two independent clocks.
     */
    internal fun shouldEmitThrottled(tag: String, key: String, minIntervalMs: Long): Boolean {
        val throttleKey = "$tag:$key"
        val now = throttleClock()
        return synchronized(throttleTimestamps) {
            val last = throttleTimestamps[throttleKey]
            if (last == null || now - last >= minIntervalMs) {
                throttleTimestamps[throttleKey] = now
                true
            } else {
                false
            }
        }
    }

    fun i(tag: String, message: String, throwable: Throwable? = null) {
        if (!shouldLog(LVL_INFO)) return
        Log.i("$TAG_PREFIX:$tag", logcatMessage(message), throwable)
        if (logToFile) {
            writeToFile("I", tag, message, throwable)
        }
        // App log is the end-user record: every subsystem's I-level events
        // reach it (hypervisors, clouds, protocols, sync — present and
        // future) EXCEPT known UI/render chatter tags, which stay in the
        // debug log only. A per-tag rate cap inside writeToAppLog guards
        // against any non-listed tag turning chatty.
        if (tag !in APP_CHATTER_TAGS) {
            writeToAppLog("I", tag, message, throwable, rateCapped = true)
        }
    }

    /**
     * Log an end-user-meaningful event: always reaches the app log —
     * bypassing both the chatter denylist and the I-level rate cap
     * (plus logcat and the debug log). Use for things a user reading
     * their own log should see — connect, disconnect, auth outcome,
     * backup completed, key imported.
     */
    fun event(tag: String, message: String) {
        Log.i("$TAG_PREFIX:$tag", logcatMessage(message))
        if (logToFile) {
            writeToFile("I", tag, message, null)
        }
        writeToAppLog("I", tag, message, null, rateCapped = false)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (!shouldLog(LVL_WARNING)) return
        Log.w("$TAG_PREFIX:$tag", logcatMessage(message), throwable)
        if (logToFile) {
            writeToFile("W", tag, message, throwable)
        }
        // Write sanitized version to app log
        writeToAppLog("W", tag, message, throwable)
    }

    /**
     * Rate-limited variant of w() for warnings that repeat every frame while
     * a misconfiguration persists (a render path that never got wired up).
     * The condition is worth a warning — demoting it to debug would hide it
     * from the app log and from non-debug builds — but the repetition is not,
     * so at most one call per (tag, key) is emitted every [minIntervalMs].
     */
    fun wThrottled(tag: String, key: String, minIntervalMs: Long, message: () -> String) {
        if (!shouldLog(LVL_WARNING)) return
        if (!shouldEmitThrottled(tag, key, minIntervalMs)) return
        val resolved = message()
        Log.w("$TAG_PREFIX:$tag", logcatMessage(resolved), null)
        if (logToFile) {
            writeToFile("W", tag, resolved, null)
        }
        writeToAppLog("W", tag, resolved, null)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!shouldLog(LVL_ERROR)) return
        Log.e("$TAG_PREFIX:$tag", logcatMessage(message), throwable)
        if (logToFile) {
            writeToFile("E", tag, message, throwable)
        }
        // Write sanitized version to app log
        writeToAppLog("E", tag, message, throwable)
    }

    fun wtf(tag: String, message: String, throwable: Throwable? = null) {
        Log.wtf("$TAG_PREFIX:$tag", logcatMessage(message), throwable)
        if (logToFile) {
            writeToFile("WTF", tag, message, throwable)
        }
        // Write sanitized version to app log
        writeToAppLog("WTF", tag, message, throwable)
    }

    /**
     * Persistent buffered writer for one log sink. All methods run on the
     * single writer thread only — never call from other threads. Replaces
     * the old per-line FileWriter open/write/close cycle, whose syscall
     * storm under a chatty session saturated storage I/O (a contributor to
     * the settings-page ANR). Detects external delete/swap of the target
     * file (clearLogs, disableDebugMode) and transparently reopens.
     */
    private class SinkWriter(private val baseName: String, private val maxSize: Long, private val onRotate: (() -> Unit)? = null) {
        private var writer: BufferedWriter? = null
        private var openedFile: File? = null

        fun writeLine(target: File?, line: String) {
            val f = target ?: return
            try {
                if (f.exists() && f.length() > maxSize) {
                    close()
                    Logger.rotateLogFiles(f, baseName)
                    onRotate?.invoke()
                }
                if (writer == null || openedFile != f || !f.exists()) {
                    close()
                    writer = BufferedWriter(FileWriter(f, true))
                    openedFile = f
                }
                writer?.let {
                    it.append(line)
                    it.flush()
                }
            } catch (e: Exception) {
                close()
                Log.e("$TAG_PREFIX:Logger", "Failed to write to $baseName", e)
            }
        }

        fun close() {
            try { writer?.close() } catch (_: Exception) {}
            writer = null
            openedFile = null
        }
    }

    /**
     * Writer-side noise control — the systemic replacement for chasing
     * individual chatty call sites. Two independent mechanisms:
     *  1. Duplicate collapse: identical consecutive level/tag/message lines
     *     become one line plus a "repeated N times" summary when the stream
     *     moves on — a stuck loop can no longer fill the log.
     *  2. Per-tag rate cap (rate-capped levels only, i.e. D): at most
     *     [MAX_TAG_LINES_PER_WINDOW] lines per tag per second; overflow is
     *     dropped and acknowledged with one "suppressed N lines" summary —
     *     per-frame/per-byte logging can never dominate the file again.
     * All state is touched from the single writer thread only.
     * Internal (not private) with an injectable clock so the unit test can
     * drive window boundaries deterministically.
     */
    internal class NoiseGate(private val now: () -> Long = System::currentTimeMillis) {
        private var lastKey: String? = null
        private var lastTag: String = ""
        private var repeatCount = 0
        private val windowStart = mutableMapOf<String, Long>()
        private val windowCount = mutableMapOf<String, Int>()
        private val suppressed = mutableMapOf<String, Int>()

        fun filter(level: String, tag: String, body: String, rateCapped: Boolean, timestamp: String): List<String> {
            val out = mutableListOf<String>()
            val key = "$level/$tag: $body"

            if (key == lastKey) {
                repeatCount++
                return out
            }
            if (repeatCount > 0) {
                out.add("$timestamp I/$TAG_PREFIX:$lastTag: (previous line repeated $repeatCount more times)\n")
                repeatCount = 0
            }
            lastKey = key
            lastTag = tag

            if (rateCapped) {
                val now = now()
                val start = windowStart[tag] ?: 0L
                if (now - start >= RATE_WINDOW_MS) {
                    val dropped = suppressed.remove(tag) ?: 0
                    if (dropped > 0) {
                        out.add("$timestamp I/$TAG_PREFIX:$tag: (rate cap: suppressed $dropped lines in the last ${RATE_WINDOW_MS / 1000}s)\n")
                    }
                    windowStart[tag] = now
                    windowCount[tag] = 0
                }
                val count = (windowCount[tag] ?: 0) + 1
                windowCount[tag] = count
                if (count > MAX_TAG_LINES_PER_WINDOW) {
                    suppressed[tag] = (suppressed[tag] ?: 0) + 1
                    return out
                }
            }

            out.add("$timestamp $level/$TAG_PREFIX:$tag: $body\n")
            return out
        }
    }

    private const val RATE_WINDOW_MS = 1000L
    private const val MAX_TAG_LINES_PER_WINDOW = 8

    private val debugSink = SinkWriter(LOG_FILE_NAME, MAX_LOG_SIZE.toLong())
    private val debugGate = NoiseGate()
    private val appGate = NoiseGate()
    private val appSink = SinkWriter(APP_LOG_FILE_NAME, MAX_APP_LOG_SIZE.toLong()) {
        // Reset anonymization maps on app-log rotation (writer thread).
        hostMap.clear()
        userMap.clear()
        hostCounter = 0
        userCounter = 0
        jumpHostMap.clear()
        jumpHostCounter = 0
        // NOTE: jumpHostSet is intentionally NOT cleared here.
        // The registry is populated at connect time by SSHConnection
        // and must survive log rotation so mid-session lines keep
        // resolving jump hosts to jump{N}. It resets in clearAppLog().
    }

    // I-level tags whose lines are UI/render/internal chatter, NOT
    // end-user events — these stay out of the app log (they remain in the
    // debug log). Everything else passes through: a denylist means every
    // hypervisor, cloud provider, protocol, and future subsystem reaches
    // the end-user record automatically instead of silently dropping out
    // of a hand-maintained allowlist. The app log records what happened,
    // not how it was drawn. As a safety net against a chatty non-listed
    // tag, I-level app-log lines are also rate-capped per tag (see
    // writeToAppLog); W/E are never rate-capped anywhere.
    private val APP_CHATTER_TAGS = setOf(
        "TerminalView", "TerminalEmulator", "TerminalManager", "TermuxBridge",
        "TabTerminalActivity", "MainActivity", "ConnectionEditActivity",
        "SettingsActivity", "Settings", "SessionPersistenceManager",
        "ThemeManager", "ThemeValidator", "PerformanceManager",
        "ConsoleWebSocket", "RfbClient", "RfbDecoder", "SocketRelay",
        "VncKeepAliveService", "ANSIParser", "TerminalBuffer",
    )

    /**
     * Write to the debug log asynchronously through the persistent sink.
     * Sanitizes at write time (on the writer thread, never the caller's),
     * then runs the noise gate: duplicate collapse for every level, plus
     * the per-tag rate cap for D-level lines.
     */
    private fun writeToFile(level: String, tag: String, message: String, throwable: Throwable?) {
        if (!logToFile || logFile == null) return

        executor.execute {
            val timestamp = dateFormat.format(Date())
            // Sanitize before writing — debug log is pre-sanitized at write
            // time so the full blob never needs expensive post-hoc regex.
            val body = buildString {
                append(sanitizeForPublic(message))
                throwable?.let {
                    append("\n")
                    append(sanitizeForPublic(Log.getStackTraceString(it)))
                }
            }
            val rateCapped = level == "D" && throwable == null
            for (line in debugGate.filter(level, tag, body, rateCapped, timestamp)) {
                debugSink.writeLine(logFile, line)
            }
        }
    }

    /**
     * Condense a throwable to one app-log-sized line: exception class,
     * message, and the top stack frame — just enough detail to fix. The
     * full sanitized stack goes to the debug log, never the app log.
     */
    private fun condenseThrowable(t: Throwable): String {
        val top = t.stackTrace.firstOrNull()?.let {
            " at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
        } ?: ""
        return "${t.javaClass.simpleName}: ${t.message ?: "no message"}$top"
    }

    /**
     * Write to app log (sanitized for public sharing, always enabled).
     * The end-user record: W/E entries arrive condensed (class + message +
     * top frame via condenseThrowable), duplicate lines collapse via the
     * noise gate, and writes go through the persistent buffered sink.
     */
    private fun writeToAppLog(level: String, tag: String, message: String, throwable: Throwable? = null, rateCapped: Boolean = false) {
        if (appLogFile == null) return

        executor.execute {
            try {
                val timestamp = dateFormat.format(Date())
                val body = buildString {
                    append(sanitizeForPublic(message))
                    throwable?.let {
                        append(" — ")
                        append(sanitizeForPublic(condenseThrowable(it)))
                    }
                }
                for (line in appGate.filter(level, tag, body, rateCapped, timestamp)) {
                    appSink.writeLine(appLogFile, line)
                }
            } catch (e: Exception) {
                // Don't create an infinite loop by writing back through the
                // logger, but DO surface to logcat at least once per session
                // so a silently-failing app log can be diagnosed without
                // having to rebuild the app with a debugger attached.
                if (!appLogWriteFailureReported) {
                    appLogWriteFailureReported = true
                    Log.e("$TAG_PREFIX:Logger",
                        "App log write failed (further failures suppressed): ${e.message}", e)
                }
            }
        }
    }
    @Volatile private var appLogWriteFailureReported = false

    /**
     * Issue #36 — N-file log rotation.
     *
     * Renames are cheap (no file copying), so a rotation triggered on a
     * 1 MiB file completes in microseconds even on slow storage:
     *   .log.4   →  deleted
     *   .log.3   →  .log.4
     *   .log.2   →  .log.3
     *   .log.1   →  .log.2
     *   .log     →  .log.1
     *
     * Caller is responsible for opening a fresh `.log` file afterwards.
     */
    private fun rotateLogFiles(currentFile: File, baseName: String) {
        val parent = currentFile.parentFile ?: return
        // Drop the oldest.
        File(parent, "$baseName.${MAX_LOG_FILES - 1}").delete()
        // Shift older copies up one slot. Walk top-down so we don't
        // overwrite a slot before reading from it.
        for (i in (MAX_LOG_FILES - 2) downTo 1) {
            val src = File(parent, "$baseName.$i")
            val dst = File(parent, "$baseName.${i + 1}")
            if (src.exists()) src.renameTo(dst)
        }
        // Move the current file into the .1 slot.
        currentFile.renameTo(File(parent, "$baseName.1"))
    }

    /**
     * Sanitize [message] before writing to any log file.
     *
     * All regex patterns are pre-compiled class-level constants — this
     * function performs zero pattern-object allocations per call.
     *
     * Redacts / anonymizes:
     *  • credential key=value pairs  (password, passwd, username, user, token,
     *                                 secret, auth, api_key, …)
     *  • SSH private-key PEM blocks
     *  • SSH public keys
     *  • SSH key fingerprints         (SHA256:… and MD5:xx:xx:… forms) → [FINGERPRINT]
     *  • user@host patterns → user1@server1 (session-consistent mapping;
     *                         SSH algorithm names like chacha20-poly1305@openssh.com
     *                         are preserved verbatim via the safe-domain list)
     *  • IPv6 addresses     → IP1, IP2, … (all RFC 4291 forms; bracket, v4-mapped,
     *                         fully-expanded, and compressed; processed before IPv4
     *                         so mixed-form addresses are replaced as a unit)
     *  • IPv4 addresses     → IP1, IP2, … (session-consistent)
     *  • private hostnames  → server1, server2, … (safe-domain list preserved;
     *                         version numbers like "2.0" / "8.7" are excluded by
     *                         requiring at least one letter in the TLD)
     *  • /home/<name> and /Users/<name> paths
     */
    private fun sanitizeForPublic(message: String): String {
        var s = message

        // ── URL query-string secrets / auth headers / JSON credentials ────
        // Run before the host/IP rewriting passes below so tickets embedded
        // in console URLs are redacted before any host anonymization touches
        // the same string.
        s = RE_URL_QUERY_SECRET.replace(s) { m -> "${m.groupValues[1]}=[REDACTED]" }
        s = RE_AUTH_HEADER.replace(s, "Authorization: [REDACTED]")
        s = RE_JSON_CREDENTIAL.replace(s) { m -> "\"${m.groupValues[1]}\": \"xxxxx\"" }

        // ── credentials (key=value / key:value pairs) ─────────────────────
        s = RE_PASSWORD .replace(s, "password=[REDACTED]")
        s = RE_PASSWD   .replace(s, "passwd=[REDACTED]")
        s = RE_USERNAME .replace(s, "username=[REDACTED]")
        s = RE_USER_KV  .replace(s, "user=[REDACTED]")
        s = RE_TOKEN    .replace(s, "token=[REDACTED]")
        s = RE_SECRET   .replace(s, "secret=[REDACTED]")
        s = RE_AUTH_KV  .replace(s, "auth=[REDACTED]")
        s = RE_APIKEY   .replace(s, "api_key=[REDACTED]")

        // ── SSH key material ──────────────────────────────────────────────
        s = RE_SSH_PRIV .replace(s, "[SSH KEY REDACTED]")
        s = RE_SSH_PUB  .replace(s, "[SSH PUBLIC KEY]")

        // ── SSH key fingerprints ──────────────────────────────────────────
        s = RE_FP_SHA256.replace(s, "[FINGERPRINT]")
        s = RE_FP_MD5   .replace(s, "[FINGERPRINT]")

        // ── user@host (before individual IP/hostname passes to avoid double
        //    processing the same token) ────────────────────────────────────
        // SSH algorithm names like "chacha20-poly1305@openssh.com" match
        // user@host syntactically but are not credentials. Preserve any
        // match whose host part is a safe domain verbatim.
        s = RE_USER_AT.replace(s) { match ->
            val host = match.groupValues[2]
            if (isSafeHost(host)) {
                // algorithm name / public domain — keep as-is
                match.value
            } else {
                val anonUser = userMap.getOrPut(match.groupValues[1]) { "user${++userCounter}" }
                val anonHost = anonForHost(host) { hostMap.getOrPut(host) { "server${++hostCounter}" } }
                "$anonUser@$anonHost"
            }
        }

        // ── IPv6 addresses (before IPv4 — mixed forms must be caught whole) ──
        // Bracket notation first so the surrounding [ ] are included in the
        // replacement; v4-mapped before bare IPv4 for the same reason.
        s = RE_IPV6_BRACKET.replace(s) { match ->
            "[${anonForHost(match.value) { hostMap.getOrPut(match.value) { "IP${++hostCounter}" } }}]"
        }
        s = RE_IPV6_V4MAPPED.replace(s) { match ->
            anonForHost(match.value) { hostMap.getOrPut(match.value) { "IP${++hostCounter}" } }
        }
        s = RE_IPV6_FULL.replace(s) { match ->
            anonForHost(match.value) { hostMap.getOrPut(match.value) { "IP${++hostCounter}" } }
        }
        s = RE_IPV6_COMP.replace(s) { match ->
            anonForHost(match.value) { hostMap.getOrPut(match.value) { "IP${++hostCounter}" } }
        }

        // ── IPv4 addresses → IP1, IP2, … ─────────────────────────────────
        s = RE_IPV4.replace(s) { match ->
            anonForHost(match.value) { hostMap.getOrPut(match.value) { "IP${++hostCounter}" } }
        }

        // ── private hostnames → server1, server2, … ──────────────────────
        // Well-known public domains and Android/Google/GitHub identifiers are
        // preserved verbatim — they add diagnostic context without leaking
        // anything private.
        s = RE_HOSTNAME.replace(s) { match ->
            val host = match.value
            if (isSafeHost(host)) {
                host
            } else {
                anonForHost(host) { hostMap.getOrPut(host) { "server${++hostCounter}" } }
            }
        }

        // ── ports (after host passes so anonymized host:port forms exist) ─
        s = RE_PORT_KV       .replace(s, "port=[PORT]")
        s = RE_ANON_HOST_PORT.replace(s) { m -> "${m.groupValues[1]}:[PORT]" }

        // ── home-directory paths that reveal usernames ────────────────────
        s = RE_HOME_PATH .replace(s, "/home/[user]")
        s = RE_USERS_PATH.replace(s, "/Users/[user]")

        return s
    }

    /**
     * Public surface for sanitizing arbitrary text (e.g. crash stack traces
     * stored outside the normal logging path before sharing them).
     */
    fun sanitize(text: String): String = sanitizeForPublic(text)

    /**
     * Strip the query string (and fragment) from [url] before logging it.
     *
     * Console URLs (Proxmox `vncticket=`, XCP-ng/Xen Orchestra `session_id=`)
     * carry live, single-use session credentials in the query — logging the
     * whole URL leaks a working session ticket even after sanitizeForPublic()
     * runs. Callers that need to log a console/API URL should log only
     * scheme+host+path via this helper, never the raw URL.
     */
    fun urlForLogging(url: String): String =
        url.substringBefore('?').substringBefore('#')

    /**
     * Reduce a shell command line to something safe to log: the first
     * token (the program name / argv[0]) plus a token count and total
     * length. Full command lines can carry secrets passed as arguments
     * (e.g. `mysql -p<password>`, tokens on a curl invocation) and must
     * never be logged verbatim.
     */
    fun commandForLogging(command: String): String {
        val trimmed = command.trim()
        val firstToken = trimmed.substringBefore(' ')
        val tokenCount = if (trimmed.isEmpty()) 0 else trimmed.split(Regex("\\s+")).size
        return "$firstToken (${tokenCount} tokens, ${command.length} chars)"
    }

    /**
     * Sanitize stack trace for public sharing
     */
    private fun sanitizeStackTrace(throwable: Throwable): String {
        val stackTrace = Log.getStackTraceString(throwable)
        return sanitizeForPublic(stackTrace)
    }

    /**
     * Write crash report SYNCHRONOUSLY
     * Called from UncaughtExceptionHandler - must complete before process dies
     */
    fun writeCrashSync(thread: Thread, throwable: Throwable) {
        val timestamp = dateFormat.format(Date())
        val stackTrace = Log.getStackTraceString(throwable)

        // Write to debug log if enabled — sanitize here too so the debug log
        // is pre-sanitized at write time and never needs post-hoc scrubbing.
        logFile?.let { file ->
            try {
                // Rotate if needed (Issue #36 — N-file rotation).
                if (file.exists() && file.length() > MAX_LOG_SIZE) {
                    rotateLogFiles(file, LOG_FILE_NAME)
                }

                val sanitizedCrashStack = sanitizeForPublic(stackTrace)
                val sanitizedCrashMsg   = sanitizeForPublic(throwable.message ?: "")

                // Write crash synchronously
                FileWriter(file, true).use { writer ->
                    writer.append("\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: ════════════════════════════════════════\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: UNCAUGHT EXCEPTION - APP CRASHED\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: Thread: ${thread.name} (id=${thread.id})\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: Exception: ${throwable.javaClass.name}\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: Message: $sanitizedCrashMsg\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: ════════════════════════════════════════\n")
                    writer.append(sanitizedCrashStack)
                    writer.append("\n$timestamp WTF/$TAG_PREFIX:CRASH: ════════════════════════════════════════\n\n")
                    writer.flush()
                }

                Log.e("$TAG_PREFIX:Logger", "Crash logged to file: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e("$TAG_PREFIX:Logger", "Failed to write crash to log file", e)
            }
        }

        // Always write sanitized crash to app log
        appLogFile?.let { file ->
            try {
                val sanitizedStack = sanitizeForPublic(stackTrace)
                val sanitizedMessage = sanitizeForPublic(throwable.message ?: "")

                FileWriter(file, true).use { writer ->
                    writer.append("\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: ════════════════════════════════════════\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: APP CRASHED\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: Thread: ${thread.name}\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: Exception: ${throwable.javaClass.name}\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: Message: $sanitizedMessage\n")
                    writer.append("$timestamp WTF/$TAG_PREFIX:CRASH: ════════════════════════════════════════\n")
                    writer.append(sanitizedStack)
                    writer.append("\n$timestamp WTF/$TAG_PREFIX:CRASH: ════════════════════════════════════════\n\n")
                    writer.flush()
                }
            } catch (e: Exception) {
                // Silently fail
            }
        }
    }

    fun getLogFile(): File? = logFile
    fun getAppLogFile(): File? = appLogFile

    /**
     * Get sanitized app log for public sharing (GitHub issues, pastebin, etc.)
     * Safe to share publicly - no sensitive data
     */
    fun getAppLog(): String {
        val file = appLogFile
        if (file == null || !file.exists()) {
            return buildString {
                appendLine("=== TabSSH Application Log ===")
                appendLine("Status: No logs recorded yet")
                appendLine()
                appendLine("This log is safe to share publicly.")
                appendLine("All sensitive information (IPs, hostnames, usernames) is anonymized.")
            }
        }

        return try {
            val logs = file.readText()
            // Issue #36 — concatenate rotated backups (.1 .. .{N-1}) in
            // age order, oldest last, so the human reader sees newest events
            // first followed by older context. Legacy `.old` is also picked
            // up to handle rolling upgrades from the previous rotation scheme.
            val parent = file.parentFile
            val rotated = buildString {
                if (parent != null) {
                    for (i in 1 until MAX_LOG_FILES) {
                        val rf = File(parent, "$APP_LOG_FILE_NAME.$i")
                        if (rf.exists()) {
                            appendLine()
                            appendLine()
                            appendLine("=== Rotated log .$i ===")
                            append(rf.readText())
                        }
                    }
                    val legacy = File(parent, "$APP_LOG_FILE_NAME.old")
                    if (legacy.exists()) {
                        appendLine()
                        appendLine()
                        appendLine("=== Legacy rotated log (.old) ===")
                        append(legacy.readText())
                    }
                }
            }

            buildString {
                appendLine("=== TabSSH Application Log ===")
                appendLine("Generated: ${dateFormat.format(Date())}")
                appendLine("App Version: ${io.github.tabssh.BuildConfig.VERSION_NAME} (${io.github.tabssh.BuildConfig.VERSION_CODE})")
                appendLine("Build Commit: ${io.github.tabssh.BuildConfig.GIT_COMMIT_ID ?: "unknown"}")
                appendLine("Log Size: ${file.length()} bytes")
                appendLine()
                appendLine("NOTE: This log is SAFE TO SHARE PUBLICLY.")
                appendLine("All IPs, hostnames, and usernames are anonymized.")
                appendLine("(e.g., 'server1', 'user1', 'IP1' are placeholders)")
                appendLine("═══════════════════════════════════════════════════")
                appendLine()
                append(logs)
                append(rotated)
            }
        } catch (e: Exception) {
            "Failed to read app log: ${e.message}"
        }
    }

    /**
     * Clear all logs (debug log only, not app log)
     */
    fun clearLogs() {
        // Release the cached writer on the writer thread so the delete below
        // doesn't leave it appending to an unlinked inode.
        executor.execute { debugSink.close() }
        logFile?.delete()
        // Issue #36 — also clear all rotated backups
        logFile?.parentFile?.let { dir ->
            // Drop legacy `.old` if it survived a rolling upgrade
            File(dir, "$LOG_FILE_NAME.old").delete()
            for (i in 1 until MAX_LOG_FILES) {
                File(dir, "$LOG_FILE_NAME.$i").delete()
            }
        }
        if (logToFile) {
            i("Logger", "Debug log cleared by user")
        }
    }

    /**
     * Clear app log (the sanitized public log)
     */
    fun clearAppLog() {
        executor.execute { appSink.close() }
        appLogFile?.delete()
        appLogFile?.parentFile?.let { dir ->
            File(dir, "$APP_LOG_FILE_NAME.old").delete()
            for (i in 1 until MAX_LOG_FILES) {
                File(dir, "$APP_LOG_FILE_NAME.$i").delete()
            }
        }
        // Reset anonymization maps
        hostMap.clear()
        userMap.clear()
        hostCounter = 0
        userCounter = 0
        jumpHostMap.clear()
        jumpHostCounter = 0
        synchronized(jumpHostSet) { jumpHostSet.clear() }
        i("Logger", "App log cleared by user")
    }

    fun isDebugMode(): Boolean = debugMode

    /**
     * Get ALL logs as string (for debugging/support)
     */
    fun getAllLogs(): String {
        val file = logFile
        if (file == null || !file.exists()) {
            return buildString {
                appendLine("=== TabSSH Debug Log ===")
                appendLine("Status: Debug logging is ${if (debugMode) "ENABLED" else "DISABLED"}")
                appendLine()
                if (!debugMode) {
                    appendLine("To enable debug logging:")
                    appendLine("1. Go to Settings > Logging")
                    appendLine("2. Enable 'Debug Logging'")
                    appendLine("3. Reproduce the issue")
                    appendLine("4. Come back here to copy logs")
                } else {
                    appendLine("No logs recorded yet. Reproduce the issue and try again.")
                }
            }
        }

        return try {
            val logs = file.readText()
            // Issue #36 — concatenate rotated debug logs .1..N-1 plus
            // legacy `.old`. Order: newest first, then older slots.
            val parent = file.parentFile
            val rotated = buildString {
                if (parent != null) {
                    for (i in 1 until MAX_LOG_FILES) {
                        val rf = File(parent, "$LOG_FILE_NAME.$i")
                        if (rf.exists()) {
                            appendLine()
                            appendLine()
                            appendLine("=== Rotated log .$i ===")
                            append(rf.readText())
                        }
                    }
                    val legacy = File(parent, "$LOG_FILE_NAME.old")
                    if (legacy.exists()) {
                        appendLine()
                        appendLine()
                        appendLine("=== Legacy rotated log (.old) ===")
                        append(legacy.readText())
                    }
                }
            }

            buildString {
                appendLine("=== TabSSH Debug Log ===")
                appendLine("Exported: ${dateFormat.format(Date())}")
                appendLine("App Version: ${io.github.tabssh.BuildConfig.VERSION_NAME} (${io.github.tabssh.BuildConfig.VERSION_CODE})")
                appendLine("Build Commit: ${io.github.tabssh.BuildConfig.GIT_COMMIT_ID ?: "unknown"}")
                appendLine("Debug Log: $debugMode")
                appendLine("Log File: ${file.absolutePath}")
                appendLine("Log Size: ${file.length()} bytes")
                appendLine("========================")
                appendLine()
                append(logs)
                append(rotated)
            }
        } catch (e: Exception) {
            "Failed to read logs: ${e.message}\n${Log.getStackTraceString(e)}"
        }
    }

    /**
     * Force enable debug mode (for troubleshooting without going to settings)
     */
    fun forceEnableDebugMode(context: Context) {
        // Already enabled
        if (debugMode) return

        debugMode = true
        logToFile = true
        appContext = context.applicationContext
        logFile = File(context.filesDir, LOG_FILE_NAME)

        i("Logger", "=== Debug Log Force-Enabled ===")
        i("Logger", "App Version: ${getAppVersion(context)}")
        i("Logger", "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        i("Logger", "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    }

    /**
     * Disable debug mode and delete log file
     */
    fun disableDebugMode() {
        if (!debugMode) return

        i("Logger", "=== Debug Log Disabled ===")

        debugMode = false
        logToFile = false

        // Delete log files (Issue #36 — N-file rotation cleanup)
        executor.execute { debugSink.close() }
        logFile?.delete()
        logFile?.parentFile?.let { dir ->
            File(dir, "$LOG_FILE_NAME.old").delete()
            for (i in 1 until MAX_LOG_FILES) {
                File(dir, "$LOG_FILE_NAME.$i").delete()
            }
        }
        logFile = null
    }

    /**
     * Get debug logs as string
     */
    fun getDebugLogs(): String {
        // The raw `files/debug.log` path was dead code — nothing in this
        // app ever wrote to that file. Only the sanitized `logFile` path
        // (written via writeToFile(), which calls sanitizeForPublic())
        // is a real source of debug logs.
        return logFile?.let { file ->
            if (file.exists()) {
                file.readLines().filter { it.contains(" D/") }.takeLast(200).joinToString("\n")
            } else ""
        } ?: "Debug logging not enabled"
    }

    // ── Per-host log ─────────────────────────────────────────────────────────

    // Rotation cap comes from the host_log_max_size_mb preference (default 1 MB),
    // set via the user-visible SeekBarPreference (preferences_logging.xml, range
    // 1–10 MB) — see resolveHostLogMaxSize().
    private const val ONE_MB_BYTES = 1024L * 1024L

    private fun resolveHostLogMaxSize(): Long {
        val mb = io.github.tabssh.TabSSHApplication.get().preferencesManager.getHostLogMaxSizeMb()
            .coerceIn(1, 10)
        return mb * ONE_MB_BYTES
    }

    /**
     * Write a single event to the per-connection host log.
     *
     * Each connection profile gets its own file:
     *   `files/host_logs/{connectionId}.log`
     *
     * Format (OpenSSH-style):
     *   `YYYY-MM-DD HH:mm:ss.SSS LEVEL user@host:port message`
     *
     * Opt-in: only writes when the `host_logging_enabled` preference is true.
     * Contains real hostnames / usernames — never leaves the device unless
     * the user manually exports it. Cap: 1 MiB, single rotation → `.log.1`.
     */
    fun logHostEvent(connectionId: String, username: String, host: String, port: Int, level: String, message: String) {
        val ctx = appContext ?: return
        if (!io.github.tabssh.TabSSHApplication.get().preferencesManager.isHostLoggingEnabled()) return
        val maxBytes = resolveHostLogMaxSize()
        executor.execute {
            try {
                val logsDir = File(ctx.filesDir, "host_logs")
                if (!logsDir.exists()) logsDir.mkdirs()

                val logFile = File(logsDir, "$connectionId.log")
                if (logFile.exists() && logFile.length() > maxBytes) {
                    val backup = File(logsDir, "$connectionId.log.1")
                    backup.delete()
                    logFile.renameTo(backup)
                }

                val timestamp = dateFormat.format(Date())
                val line = "$timestamp ${level.padEnd(5)} $username@$host:$port $message\n"
                FileWriter(logFile, true).use { it.append(line) }
            } catch (e: Exception) {
                Log.e("$TAG_PREFIX:Logger", "Failed to write host log for $connectionId", e)
            }
        }
    }

    /**
     * Get list of host log files, sorted newest-modified first.
     */
    fun getHostLogFiles(): List<File> {
        val logsDir = appContext?.let { File(it.filesDir, "host_logs") }
        return if (logsDir?.exists() == true && logsDir.isDirectory) {
            logsDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".log") && !it.name.endsWith(".log.1") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        } else {
            emptyList()
        }
    }

    /**
     * Get recent logs from memory (for log viewer)
     */
    fun getRecentLogs(): List<io.github.tabssh.ui.activities.LogViewerActivity.LogEntry> {
        val logs = mutableListOf<io.github.tabssh.ui.activities.LogViewerActivity.LogEntry>()

        logFile?.let { file ->
            if (file.exists()) {
                try {
                    file.readLines().takeLast(500).forEach { line ->
                        // Parse: 2025-12-19 12:34:56.123 I/TabSSH:TAG: Message
                        val regex = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) (\w+)/TabSSH:(\w+): (.*)$""")
                        regex.find(line)?.let { match ->
                            val (timestamp, level, tag, message) = match.destructured
                            val levelDisplay = when (level) {
                                "D" -> "DEBUG"
                                "I" -> "INFO"
                                "W" -> "WARN"
                                "E" -> "ERROR"
                                "WTF" -> "FATAL"
                                else -> level
                            }
                            logs.add(io.github.tabssh.ui.activities.LogViewerActivity.LogEntry(
                                timestamp, levelDisplay, tag, message
                            ))
                        }
                    }
                } catch (e: Exception) {
                    e("Logger", "Failed to read logs", e)
                }
            }
        }

        return logs
    }
}
