package io.github.tabssh.network

import java.io.IOException
import okhttp3.ResponseBody

/** Bounds decoded response bytes, including chunked bodies without Content-Length. */
internal fun ResponseBody.readBoundedText(maxBytes: Long = 16L * 1024 * 1024): String = use {
    require(maxBytes in 1 until Long.MAX_VALUE)
    if (contentLength() > maxBytes || source().request(maxBytes + 1)) {
        throw IOException("Response exceeds the $maxBytes byte limit")
    }
    // Preserve OkHttp's charset and byte-order-mark handling after checking the size.
    string()
}
