package io.github.tabssh.network

import io.github.tabssh.BuildConfig
import android.content.Context
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * PART 9 — one shared OkHttpClient for the whole app.
 *
 * Every REST/console/cloud/hypervisor client derives its own instance from
 * [client] via `.newBuilder()` rather than constructing `OkHttpClient()`
 * directly. `newBuilder()` copies the connection pool and dispatcher from
 * this instance (so all callers share sockets/threads) while still letting
 * each site override timeouts or install per-host TLS policy (optional
 * platform validation for infrastructure endpoints, console websocket
 * idle timeouts, etc.).
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

    private const val HTTP_CACHE_SIZE_BYTES = 10L * 1024L * 1024L

    @Volatile
    var client: OkHttpClient = OkHttpClient.Builder()
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
        private set

    /**
     * Install one bounded disk cache before app networking starts. OkHttp
     * caches only GET responses the server marks cacheable and automatically
     * revalidates stale entries with ETag/Last-Modified validators. Responses
     * marked `no-store` or otherwise not cacheable remain uncached.
     */
    fun initialize(context: Context) {
        synchronized(this) {
            if (client.cache != null) return
            val cache = Cache(
                File(context.applicationContext.cacheDir, "http-cache"),
                HTTP_CACHE_SIZE_BYTES
            )
            client = client.newBuilder().cache(cache).build()
        }
    }
}
