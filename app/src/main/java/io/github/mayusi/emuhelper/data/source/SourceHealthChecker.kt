package io.github.mayusi.emuhelper.data.source

import android.util.Log
import io.github.mayusi.emuhelper.data.config.Catalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A single source URL result from a health check. */
data class SourceHealth(
    val console: String,
    val url: String,
    val alive: Boolean,
    /** HTTP status code as a string (e.g. "200", "403") or a short error description. */
    val detail: String
)

/**
 * Pings each configured source endpoint and reports which are alive/dead.
 * Uses a lightweight HEAD or ranged-GET request with a short timeout so the
 * full check finishes in reasonable time even for large catalogs.
 *
 * Concurrency is bounded by a [Semaphore] with [MAX_CONCURRENCY] permits.
 */
@Singleton
class SourceHealthChecker @Inject constructor(
    okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "SourceHealthChecker"
        private const val MAX_CONCURRENCY = 6
        private const val TIMEOUT_MS = 12_000L
    }

    /** Cookie-less client so health checks never carry session credentials. */
    private val client: OkHttpClient = okHttpClient
        .newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Checks all endpoints in [Catalog.IA_LINKS] and returns a [SourceHealth] per URL.
     * [onProgress] is called after each URL completes with (done, total) counts.
     */
    suspend fun checkAll(
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<SourceHealth> = withContext(Dispatchers.IO) {
        // Flatten to a stable list of (console, url) pairs.
        val work: List<Pair<String, String>> = Catalog.IA_LINKS
            .entries
            .flatMap { (console, urls) -> urls.map { console to it } }

        val total = work.size
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val semaphore = Semaphore(MAX_CONCURRENCY)

        coroutineScope {
            work.map { (console, url) ->
                async {
                    val result = semaphore.withPermit { probe(console, url) }
                    val completed = done.incrementAndGet()
                    onProgress(completed, total)
                    result
                }
            }.awaitAll()
        }
    }

    /** Send a HEAD request (or ranged GET fallback) and interpret the response. */
    private fun probe(console: String, url: String): SourceHealth {
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { resp ->
                val code = resp.code
                val alive = code in 200..399 // 2xx, 3xx — reachable
                if (!alive && code == 405) {
                    // HEAD not allowed: fall back to a ranged GET
                    return rangedGet(console, url)
                }
                Log.d(TAG, "HEAD $url -> $code")
                SourceHealth(console, url, alive, code.toString())
            }
        } catch (e: Exception) {
            val msg = e.message?.take(80) ?: e.javaClass.simpleName
            Log.d(TAG, "probe $url -> exception: $msg")
            SourceHealth(console, url, alive = false, detail = msg)
        }
    }

    /** Fallback for servers that reject HEAD: fetch first byte only. */
    private fun rangedGet(console: String, url: String): SourceHealth {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "Mozilla/5.0")
                .header("Range", "bytes=0-0")
                .build()

            client.newCall(request).execute().use { resp ->
                resp.body?.close()
                val code = resp.code
                val alive = code in 200..399
                Log.d(TAG, "GET(ranged) $url -> $code")
                SourceHealth(console, url, alive, "$code")
            }
        } catch (e: Exception) {
            val msg = e.message?.take(80) ?: e.javaClass.simpleName
            SourceHealth(console, url, alive = false, detail = msg)
        }
    }
}
