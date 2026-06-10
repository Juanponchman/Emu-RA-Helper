package io.github.mayusi.emuhelper.data.source

import android.util.Log
import io.github.mayusi.emuhelper.data.model.GameFile
import io.github.mayusi.emuhelper.di.PersistentCookieJar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class RemoteSource @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val cookieJar: PersistentCookieJar
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private val SKIP_EXTS = setOf(".xml", ".json", ".sqlite", ".txt", ".md", ".torrent", ".log", ".csv", ".pdf", ".jpg", ".png", ".gif", ".ico")
        private const val MIN_FILE_SIZE = 102400L
        private const val MAX_ATTEMPTS = 5
        // Browser-like UA. The configured source host occasionally serves different
        // content to non-browser UAs; this pattern maximises compatibility.
        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    fun isLoggedIn(): Boolean = cookieJar.hasCookies()

    suspend fun login(email: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            // The source host uses a "cookie-acceptance" handshake: the FIRST POST
            // sets a test cookie (and possibly a CSRF token) but just re-serves the
            // login form; only a SECOND POST — once the test cookie is present — actually
            // authenticates. That's the classic "fails first time, works on retry".
            // So we prime the cookies (GET + a throwaway POST), then POST credentials,
            // and retry up to 3x until the session cookie (logged-in-sig) appears.

            // Prime: GET the login page so any initial Set-Cookie lands in the jar.
            try {
                okHttpClient.newCall(
                    Request.Builder().url("https://archive.org/account/login")
                        .header("User-Agent", USER_AGENT).get().build()
                ).execute().use { it.body?.close() }
            } catch (e: Exception) {
                Log.w("EmuHelper", "login: priming GET failed, continuing", e)
            }

            fun postCreds(): Pair<Int, String> {
                val formBody = FormBody.Builder()
                    .add("username", email)
                    .add("password", password)
                    .add("submit-by-login", "1")
                    .add("remember", "1")
                    .build()
                okHttpClient.newCall(
                    Request.Builder().url("https://archive.org/account/login")
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", "https://archive.org/account/login")
                        .header("Origin", "https://archive.org")
                        .post(formBody).build()
                ).execute().use { resp ->
                    return resp.code to (resp.body?.string() ?: "")
                }
            }

            var lastCode = 0
            // The SUCCESS oracle is the auth cookie appearing (hasCookies()). The first
            // POST is the cookie-acceptance round (sets test-cookie, no auth yet) and
            // re-serves the login HTML — which may contain the word "Invalid" in validation
            // markup, so we must NOT treat loose HTML text as a failure. Only an explicit
            // JSON error ({"status":"error"}) or a clear bad-credentials JSON message is
            // a real failure. We always run the full handshake up to 4 times.
            for (attempt in 0 until 4) {
                val (code, body) = postCreds()
                lastCode = code
                Log.i("EmuHelper", "login attempt $attempt: code=$code hasCookies=${cookieJar.hasCookies()}")

                if (cookieJar.hasCookies()) {
                    return@withContext LoginResult.Success
                }
                // Only stop early on a DEFINITIVE bad-credentials signal (JSON, or the
                // specific error phrases from the source host — not the generic word "Invalid").
                val definiteFail =
                    body.contains("\"status\":\"error\"", ignoreCase = true) ||
                    body.contains("incorrect username or password", ignoreCase = true) ||
                    body.contains("no account found", ignoreCase = true) ||
                    body.contains("your password is incorrect", ignoreCase = true) ||
                    body.contains("could not find an account", ignoreCase = true)
                if (definiteFail) {
                    return@withContext LoginResult.Failed("Invalid email or password.")
                }
                // Otherwise it's the cookie-acceptance round — loop and retry so the
                // user only has to press Login ONCE.
                kotlinx.coroutines.delay(150)
            }
            // Exhausted retries without an auth cookie and without a clear error.
            if (cookieJar.hasCookies()) LoginResult.Success
            else LoginResult.Failed("Couldn't sign in (HTTP $lastCode). Check your details and try again.")
        } catch (e: IOException) {
            LoginResult.Failed("Network error: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            LoginResult.Failed("${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
        }
    }

    fun getIdentifier(url: String): String {
        // Matches both /details/<id> and /download/<id>[/subpath...] paths
        val m = Regex("archive\\.org/(?:details|download)/([^/?#]+)").find(url)
        return m?.groupValues?.get(1)?.trim()
            ?: url.trim().trimEnd('/').substringAfterLast('/')
    }

    /**
     * Builds the canonical download URL for a file inside a remote item.
     *
     * [relativeName] is the file's `name` from the metadata, which may include a
     * subdirectory path (e.g. "subdir/Filename.chd"). We must percent-encode each
     * path SEGMENT but keep the "/" separators, like `quote(name, safe="/")`.
     * Using java.net.URLEncoder here is wrong — it encodes "/" as %2F and "+"
     * handling differs, which breaks every file that lives in a subdirectory.
     */
    fun buildDownloadUrl(identifier: String, relativeName: String): String {
        val encodedPath = relativeName.split('/').joinToString("/") { encodeSegment(it) }
        return "https://archive.org/download/$identifier/$encodedPath"
    }

    /**
     * All mirror URLs that serve this file. The source host's metadata exposes several
     * hosts per item — the primary `download` endpoint plus direct `d1`/`d2`
     * datanodes and any `workable` CDN nodes. Returning them all lets the downloader
     * spread segments across hosts and fail a slow/broken node over to another.
     *
     * The canonical download URL is always first (it redirects to a live node and
     * is the safest default).
     */
    suspend fun mirrorUrls(identifier: String, relativeName: String): List<String> = withContext(Dispatchers.IO) {
        val encodedPath = relativeName.split('/').joinToString("/") { encodeSegment(it) }
        val result = LinkedHashSet<String>()
        result.add("https://archive.org/download/$identifier/$encodedPath")
        try {
            val req = Request.Builder().url("https://archive.org/metadata/$identifier")
                .header("User-Agent", USER_AGENT).build()
            okHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return@use
                val root = json.parseToJsonElement(body).jsonObject
                val dir = root["dir"]?.jsonPrimitive?.contentOrNull
                fun add(host: String?, d: String?) {
                    if (!host.isNullOrBlank() && !d.isNullOrBlank()) {
                        result.add("https://$host${d.trimEnd('/')}/$encodedPath")
                    }
                }
                add(root["d1"]?.jsonPrimitive?.contentOrNull, dir)
                add(root["d2"]?.jsonPrimitive?.contentOrNull, dir)
                // workable / alternate CDN nodes carry their own dir
                root["alternate_locations"]?.jsonObject?.get("workable")?.let { wk ->
                    runCatching {
                        wk.jsonArray.forEach { node ->
                            val o = node.jsonObject
                            add(o["server"]?.jsonPrimitive?.contentOrNull, o["dir"]?.jsonPrimitive?.contentOrNull)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("EmuHelper", "mirrorUrls failed for $identifier", e)
        }
        result.toList()
    }

    private fun encodeSegment(segment: String): String {
        val sb = StringBuilder(segment.length + 16)
        for (b in segment.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            // RFC 3986 "unreserved" characters safe to leave unencoded in URL paths.
            val safe = c.toChar().let { ch ->
                ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                    ch == '-' || ch == '_' || ch == '.' || ch == '~'
            }
            if (safe) sb.append(c.toChar())
            else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0x0F])
        }
        return sb.toString()
    }

    /**
     * Fetches the list of game files for a remote source identifier.
     * Returns an empty list when the item is valid but contains no usable game files.
     * Throws on network/parse failures so the caller can surface them.
     */
    suspend fun fetchFileList(iaUrl: String): List<GameFile> = withContext(Dispatchers.IO) {
        val identifier = getIdentifier(iaUrl)
        val request = Request.Builder()
            .url("https://archive.org/metadata/$identifier")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for $identifier")
            }
            val body = response.body?.string()
                ?: throw IOException("Empty body for $identifier")

            if (!body.trimStart().startsWith("{")) {
                Log.w("EmuHelper", "Non-JSON metadata for $identifier: ${body.take(200)}")
                throw IOException("Non-JSON metadata for $identifier")
            }

            val root = json.parseToJsonElement(body).jsonObject
            val files = root["files"]?.jsonArray
                ?: throw IOException("Item '$identifier' has no files (may be dead or restricted)")

            // ---- DIAGNOSTIC: sample the first few raw entries ----
            files.take(3).forEachIndexed { i, f ->
                val obj = f.jsonObject
                val n = obj["name"]?.jsonPrimitive?.content
                val s = obj["size"]?.jsonPrimitive?.content
                val src = obj["source"]?.jsonPrimitive?.content
                Log.i("EmuHelper", "scan/$identifier  sample[$i]: name=$n size=$s source=$src")
            }
            // -----------------------------------------------------

            val kept = files.mapNotNull { f ->
                val obj = f.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (name.endsWith("/")) return@mapNotNull null
                val ext = File(name).extension.lowercase()
                if (".$ext" in SKIP_EXTS) return@mapNotNull null
                val size = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                if (size < MIN_FILE_SIZE) return@mapNotNull null
                GameFile(
                    name = name,
                    filename = File(name).name,
                    size = size,
                    identifier = identifier,
                    sourceUrl = iaUrl
                )
            }
            Log.i("EmuHelper", "scan/$identifier  bodyLen=${body.length} rawFiles=${files.size} kept=${kept.size}")
            kept
        }
    }

    /**
     * Multi-connection download into a LOCAL file using parallel HTTP Range requests.
     *
     * The source host may throttle a single TCP stream; pulling N ranges in parallel
     * multiplies throughput dramatically on big files. We write to a real local File
     * via RandomAccessFile (random-access seek) — SAF content URIs can't seek
     * reliably, so the caller downloads to app cache and then copies the finished
     * file to the user's chosen folder in one sequential pass.
     *
     * Falls back to single-stream if the server won't honour Range or size is unknown.
     *
     * @param onProgress called ~periodically with (bytesDownloadedTotal, bytesPerSecond).
     * @return total bytes written.
     */
    /**
     * Probe each resolved host with a small timed Range GET and rank them
     * fastest-first. Hosts that error, return a rate-limit stub, or are far slower
     * than the best are dropped. Returns a weighted pool where faster hosts appear
     * more often, so segments get distributed toward the fast nodes.
     *
     * CDN nodes are often rate-limited while datanodes are faster; naive even
     * splitting lets a slow node bottleneck the whole file, so we weight by speed.
     */
    private suspend fun rankHosts(resolvedHosts: List<String>): List<String> = withContext(Dispatchers.IO) {
        if (resolvedHosts.size <= 1) return@withContext resolvedHosts
        data class Probe(val url: String, val bytesPerMs: Double)
        val probes = coroutineScope {
            resolvedHosts.map { host ->
                async {
                    try {
                        val req = Request.Builder().url(host)
                            .header("User-Agent", USER_AGENT)
                            .header("Accept-Encoding", "identity")
                            .header("Range", "bytes=0-262143") // 256 KB
                            .header("Referer", "https://archive.org/")
                            .build()
                        val t0 = System.currentTimeMillis()
                        okHttpClient.newCall(req).execute().use { resp ->
                            if (resp.code != 206) return@async Probe(host, 0.0)
                            val bytes = resp.body?.bytes()?.size ?: 0
                            val ms = (System.currentTimeMillis() - t0).coerceAtLeast(1)
                            // Reject rate-limit stubs (tiny bodies for a 256KB request).
                            if (bytes < 100_000) Probe(host, 0.0) else Probe(host, bytes.toDouble() / ms)
                        }
                    } catch (e: Exception) {
                        Probe(host, 0.0)
                    }
                }
            }.awaitAll()
        }
        val good = probes.filter { it.bytesPerMs > 0.0 }.sortedByDescending { it.bytesPerMs }
        if (good.isEmpty()) return@withContext resolvedHosts
        val best = good.first().bytesPerMs
        // Keep hosts within 4x of the best; weight ~ relative speed (1..4 slots each).
        val pool = ArrayList<String>()
        for (p in good) {
            if (p.bytesPerMs * 4 < best) continue // drop nodes >4x slower (rate-limited)
            val weight = ((p.bytesPerMs / best) * 4).toInt().coerceIn(1, 4)
            repeat(weight) { pool.add(p.url) }
        }
        Log.i("EmuHelper", "rankHosts: " + good.joinToString { "${it.url.substringAfter("//").substringBefore('/')}=${"%.0f".format(it.bytesPerMs)}B/ms (ranked)" })
        pool.ifEmpty { listOf(good.first().url) }
    }

    suspend fun downloadFileSegmented(
        candidateUrls: List<String>,
        expectedSize: Long,
        destFile: File,
        segments: Int,
        onProgress: suspend (Long, Double) -> Unit,
        isCancelled: () -> Boolean
    ): Long = withContext(Dispatchers.IO) {
        // Resolve each candidate's redirect once to its real node, dedup.
        val resolvedHosts = candidateUrls.map { resolveFinalUrl(it) ?: it }.distinct()
        // Probe + rank: weighted pool favouring the fastest nodes, slow nodes dropped.
        val hosts = rankHosts(resolvedHosts).ifEmpty { resolvedHosts }
        val rangeOk = segments > 1 && expectedSize > 0 && hosts.isNotEmpty() && supportsRange(hosts.first())
        Log.i("EmuHelper", "segDL: size=$expectedSize segs=$segments poolSize=${hosts.size} distinct=${hosts.distinct().size} rangeOk=$rangeOk")
        if (!rangeOk) {
            Log.i("EmuHelper", "segDL: FALLBACK single-stream")
            return@withContext singleStreamTo((hosts.firstOrNull() ?: resolvedHosts.first()), expectedSize, destFile, onProgress, isCancelled)
        }
        // Distinct fast hosts, fastest-first, for per-segment failover.
        val distinctHosts = hosts.distinct()

        destFile.parentFile?.mkdirs()
        // Pre-size the file so each segment can seek to its offset.
        RandomAccessFile(destFile, "rw").use { it.setLength(expectedSize) }

        val segSize = expectedSize / segments
        val ranges = (0 until segments).map { i ->
            val start = i * segSize
            val end = if (i == segments - 1) expectedSize - 1 else (start + segSize - 1)
            start to end
        }

        val downloadedTotal = java.util.concurrent.atomic.AtomicLong(0)
        val startTime = System.currentTimeMillis()
        var lastReport = startTime
        val reportLock = Any()

        // One coroutine per range. Initial host comes from the weighted pool (fast
        // nodes get more segments); on failure the segment migrates to the next
        // distinct host (fastest-first) so a degrading node can't stall the file.
        coroutineScope {
            ranges.mapIndexed { idx, (start, end) ->
                async {
                    var attempt = 0
                    while (attempt < MAX_ATTEMPTS) {
                        val host = if (attempt == 0) hosts[idx % hosts.size]
                            else distinctHosts[attempt % distinctHosts.size]
                        try {
                            val req = Request.Builder().url(host)
                                .header("User-Agent", USER_AGENT)
                                .header("Accept-Encoding", "identity")
                                .header("Accept", "*/*")
                                .header("Referer", "https://archive.org/")
                                .header("Range", "bytes=$start-$end")
                                .build()
                            okHttpClient.newCall(req).execute().use { resp ->
                                if (resp.code != 206) throw IOException("No range (HTTP ${resp.code})")
                                // Guard against stale metadata: the file was pre-sized to
                                // expectedSize and segment offsets were sliced from it. If the
                                // server's real total differs, those offsets are wrong and we'd
                                // silently write corrupt data (the caller's >=99% check can still
                                // pass). Content-Range looks like "bytes start-end/total". Only
                                // abort on a CLEAR, parseable mismatch — many servers omit the
                                // header, so absent/unparseable means fall back to old behavior.
                                resp.header("Content-Range")?.let { cr ->
                                    val serverTotal = cr.substringAfterLast('/', "").trim().toLongOrNull()
                                    if (serverTotal != null && serverTotal > 0 && serverTotal != expectedSize) {
                                        throw IOException(
                                            "Server file size changed; expected $expectedSize but server reports $serverTotal"
                                        )
                                    }
                                }
                                val body = resp.body ?: throw IOException("empty body")
                                // Each coroutine opens its own handle and seeks to its slice.
                                RandomAccessFile(destFile, "rw").use { raf ->
                                    raf.seek(start)
                                    body.byteStream().use { input ->
                                        val buf = ByteArray(256 * 1024)
                                        var read: Int
                                        while (input.read(buf).also { read = it } != -1) {
                                            if (isCancelled()) throw CancellationException()
                                            raf.write(buf, 0, read)
                                            val tot = downloadedTotal.addAndGet(read.toLong())
                                            val now = System.currentTimeMillis()
                                            // One coarse aggregate report ~3x/sec.
                                            var doReport = false
                                            synchronized(reportLock) {
                                                if (now - lastReport >= 350) { lastReport = now; doReport = true }
                                            }
                                            if (doReport) {
                                                val secs = (now - startTime).coerceAtLeast(1) / 1000.0
                                                onProgress(tot, tot / secs)
                                            }
                                        }
                                    }
                                }
                            }
                            return@async
                        } catch (c: CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            attempt++
                            if (attempt >= MAX_ATTEMPTS) throw e
                            kotlinx.coroutines.delay(min(1000L * (1L shl (attempt - 1)), 8000L))
                        }
                    }
                }
            }.awaitAll()
        }
        val total = downloadedTotal.get()
        onProgress(total, 0.0)
        total
    }

    /** Follow a redirect once and return the final CDN URL (or null on failure). */
    private fun resolveFinalUrl(url: String): String? = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=0-0") // tiny request; we only want the final URL
            .header("Referer", "https://archive.org/")
            .build()
        okHttpClient.newCall(req).execute().use { resp ->
            resp.request.url.toString()
        }
    } catch (e: Exception) {
        Log.w("EmuHelper", "resolveFinalUrl failed for ${url.take(80)}", e)
        null
    }

    /** Does the server return 206 for a tiny Range probe? */
    private fun supportsRange(url: String): Boolean = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=0-0")
            .header("Referer", "https://archive.org/")
            .build()
        okHttpClient.newCall(req).execute().use { it.code == 206 }
    } catch (e: Exception) {
        Log.w("EmuHelper", "range probe failed for ${url.take(80)}", e)
        false
    }

    /** Single-connection fallback writing sequentially to a local file. */
    private suspend fun singleStreamTo(
        url: String,
        expectedSize: Long,
        destFile: File,
        onProgress: suspend (Long, Double) -> Unit,
        isCancelled: () -> Boolean
    ): Long {
        destFile.parentFile?.mkdirs()
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .header("Accept", "*/*")
            .header("Referer", "https://archive.org/")
            .build()
        var downloaded = 0L
        val start = System.currentTimeMillis()
        var lastReport = start
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty body")
            java.io.BufferedOutputStream(java.io.FileOutputStream(destFile), 256 * 1024).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        if (isCancelled()) throw CancellationException()
                        out.write(buf, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 350) {
                            lastReport = now
                            val secs = (now - start).coerceAtLeast(1) / 1000.0
                            onProgress(downloaded, downloaded / secs)
                        }
                    }
                    out.flush()
                }
            }
        }
        onProgress(downloaded, 0.0)
        return downloaded
    }

    suspend fun fetchFileSize(identifier: String, filename: String): Long = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("https://archive.org/metadata/$identifier").build()
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@withContext 0L
                val data = json.parseToJsonElement(body).jsonObject
                val files = data["files"]?.jsonArray ?: return@withContext 0L
                val target = File(filename).name.lowercase()
                for (f in files) {
                    val obj = f.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.content ?: continue
                    val cand = File(name).name.lowercase()
                    if (cand == target) {
                        return@withContext obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    }
                }
                0L
            }
        } catch (e: Exception) {
            Log.w("EmuHelper", "fetchFileSize($identifier/$filename) failed", e)
            0L
        }
    }
}

data class DownloadFileProgress(val bytesDownloaded: Long, val bytesPerSecond: Double, val complete: Boolean = false)

sealed class LoginResult {
    data object Success : LoginResult()
    data class Failed(val message: String) : LoginResult()
}
