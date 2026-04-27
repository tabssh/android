package io.github.tabssh.ssh.forwarding

import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Wave 3.4 — Best-effort HTTP probe of a freshly forwarded local port.
 *
 * We send a minimal HEAD / 1.0 request and look for a `HTTP/` response line.
 * Returns true on any HTTP-shaped response (2xx, 3xx, 4xx, 5xx all qualify
 * — the point is "it's a web server, offer to open in browser"). False on
 * timeout, connect-refused, or non-HTTP banner.
 *
 * Localhost-only by design (the forwarded port lives on 127.0.0.1). Total
 * budget ~1s so we don't block the UI.
 */
object HttpPortProbe {

    private const val TAG = "HttpPortProbe"
    private const val CONNECT_TIMEOUT_MS = 800
    private const val READ_TIMEOUT_MS = 800

    suspend fun probe(localPort: Int): Boolean = withContext(Dispatchers.IO) {
        var s: Socket? = null
        try {
            s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", localPort), CONNECT_TIMEOUT_MS)
            s.soTimeout = READ_TIMEOUT_MS

            // Minimal HTTP/1.0 HEAD — most servers reply, including HTTPS-only
            // ones (we'll see a TLS alert which doesn't start with `HTTP/` and
            // we therefore correctly say "not HTTP" — the user can still try
            // https:// manually).
            OutputStreamWriter(s.getOutputStream()).apply {
                write("HEAD / HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                flush()
            }
            val firstLine = s.getInputStream().bufferedReader().readLine().orEmpty()
            val ok = firstLine.startsWith("HTTP/")
            Logger.d(TAG, "Probe :$localPort first-line='$firstLine' → ${if (ok) "HTTP" else "non-HTTP"}")
            ok
        } catch (e: Exception) {
            Logger.d(TAG, "Probe :$localPort failed: ${e.message}")
            false
        } finally {
            try { s?.close() } catch (_: Exception) {}
        }
    }
}
