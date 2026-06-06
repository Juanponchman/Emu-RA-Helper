package io.github.mayusi.emuhelper.data.source

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

data class SpeedResult(
    val mbps: Double,           // megabits per second
    val bytesPerSec: Double,    // bytes per second
    val verdict: String,        // plain-English classification
    val minutesPerGb: Double    // estimated minutes to download 1 GB at this speed
)

data class DeviceInfo(
    val totalRamMb: Long,
    val availRamMb: Long,
    val cpuCores: Int,
    val model: String
)

/**
 * Measures real downlink throughput so the app can tell the user whether a slow
 * download is the network or the app. Tests against a neutral endpoint with a
 * few parallel ranged requests — representative of an actual file transfer — for a
 * fixed time budget, then reports megabits/sec + a verdict + minutes-per-GB.
 */
@Singleton
class SpeedTester @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val okHttpClient: OkHttpClient
) {
    // A neutral, content-agnostic throughput endpoint (Cloudflare's speed-test
    // service returns a stream of N bytes). Using this instead of a real content
    // source keeps the measurement independent of any download collection.
    private val testUrl = "https://speed.cloudflare.com/__down?bytes=104857600" // 100 MB

    suspend fun run(connections: Int = 6, budgetMs: Long = 6000): SpeedResult = withContext(Dispatchers.IO) {
        val url = testUrl

        val totalBytes = java.util.concurrent.atomic.AtomicLong(0)
        val start = System.currentTimeMillis()

        // Each connection streams the test endpoint and we cut it off at the time
        // budget. Summing bytes across all connections = aggregate downlink throughput.
        withTimeoutOrNull(budgetMs + 1000) {
            coroutineScope {
                (0 until connections).map { i ->
                    async {
                        try {
                            val req = Request.Builder().url(url)
                                .header("User-Agent", "Mozilla/5.0")
                                .header("Accept-Encoding", "identity")
                                .build()
                            okHttpClient.newCall(req).execute().use { resp ->
                                val body = resp.body ?: return@use
                                body.byteStream().use { input ->
                                    val buf = ByteArray(128 * 1024)
                                    var r: Int
                                    while (input.read(buf).also { r = it } != -1) {
                                        totalBytes.addAndGet(r.toLong())
                                        if (System.currentTimeMillis() - start > budgetMs) break
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("EmuHelper", "speedtest conn $i failed", e)
                        }
                    }
                }.awaitAll()
            }
        }

        val elapsedSec = ((System.currentTimeMillis() - start).coerceAtLeast(1)) / 1000.0
        val bps = totalBytes.get() / elapsedSec
        val mbps = bps * 8.0 / 1_000_000.0
        val minPerGb = if (bps > 1) (1_073_741_824.0 / bps) / 60.0 else 0.0
        val verdict = when {
            mbps <= 0.5 -> "Very slow connection"
            mbps < 15 -> "Slow connection — this is your network, not the app"
            mbps < 50 -> "Decent connection"
            mbps < 150 -> "Fast connection"
            else -> "Very fast connection"
        }
        Log.i("EmuHelper", "speedtest: ${"%.1f".format(mbps)} Mbit/s (${"%.2f".format(bps/1048576)} MB/s)")
        SpeedResult(mbps = mbps, bytesPerSec = bps, verdict = verdict, minutesPerGb = minPerGb)
    }

    fun deviceInfo(): DeviceInfo {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return DeviceInfo(
            totalRamMb = mi.totalMem / (1024 * 1024),
            availRamMb = mi.availMem / (1024 * 1024),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            model = "${Build.MANUFACTURER} ${Build.MODEL}"
        )
    }
}
