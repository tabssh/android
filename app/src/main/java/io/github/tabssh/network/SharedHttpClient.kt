package io.github.tabssh.network

import io.github.tabssh.BuildConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * PART 9 — one shared OkHttpClient for the whole app.
 *
 * Every REST/console/cloud/hypervisor client derives its own instance from
 * [client] via `.newBuilder()` rather than constructing `OkHttpClient()`
 * directly. `newBuilder()` copies the connection pool and dispatcher from
 * this instance (so all callers share sockets/threads) while still letting
 * each site override timeouts or install per-host TLS trust (hypervisor
 * pinning, trust-all dev mode, console websocket idle timeouts, etc.).
 *
 * Base timeouts here are deliberately generous defaults for a plain REST
 * call; sites with tighter or looser needs override them on the derived
 * builder — that override is the point of `newBuilder()`, not a violation
 * of "one client".
 */
object SharedHttpClient {

    private const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 15L
    private const val DEFAULT_READ_TIMEOUT_SECONDS = 30L
    private const val DEFAULT_WRITE_TIMEOUT_SECONDS = 30L

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(DEFAULT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(DEFAULT_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "tabssh/${BuildConfig.VERSION_NAME}")
                .build()
            chain.proceed(request)
        }
        .build()
}
