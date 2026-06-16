package io.github.mayusi.emuhelper.data.source

import android.util.Log
import io.github.mayusi.emuhelper.BuildConfig
import io.github.mayusi.emuhelper.data.model.GameFile
import io.github.mayusi.emuhelper.di.PersistentCookieJar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
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

    /**
     * Bounded cache of parsed metadata JSON roots, keyed by identifier.
     * Eliminates redundant network + parse work when mirrorUrls(), fetchFileList(), and
     * fetchFileSize() are called for the same identifier during a batch (e.g. mirrorUrls
     * is called once per file, so a 30-file batch would otherwise hit the endpoint 30×).
     *
     * FIX #12: this previously used a ConcurrentHashMap that grew unbounded for the whole
     * process lifetime — every distinct item the user ever scanned kept its full metadata
     * JSON (potentially MBs each) pinned in memory. Now it's an access-order LinkedHashMap
     * capped at [METADATA_CACHE_MAX] entries with LRU eviction, wrapped in a tiny
     * thread-safe accessor that preserves the get / putIfAbsent semantics callers rely on.
     */
    private val metadataCache = BoundedMetadataCache(METADATA_CACHE_MAX)

    /**
     * Synchronized LRU cache. accessOrder=true means each get()/put() moves the entry to
     * the most-recently-used end, so the eldest (least-recently-used) entry is evicted once
     * size exceeds [maxEntries]. All access is synchronized on the instance — the maps are
     * tiny (<=20 small references) and lookups are O(1), so contention is negligible.
     */
    private class BoundedMetadataCache(private val maxEntries: Int) {
        private val map = object :
            LinkedHashMap<String, kotlinx.serialization.json.JsonObject>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, kotlinx.serialization.json.JsonObject>?
            ): Boolean = size > maxEntries
        }

        @Synchronized
        operator fun get(key: String): kotlinx.serialization.json.JsonObject? = map[key]

        /**
         * Mirrors ConcurrentHashMap.putIfAbsent: stores [value] only if [key] is absent and
         * returns the PREVIOUS value (non-null when another caller already cached one), or
         * null if this call inserted. Callers use `putIfAbsent(k, v) ?: v` to always end up
         * with the winning instance.
         */
        @Synchronized
        fun putIfAbsent(
            key: String,
            value: kotlinx.serialization.json.JsonObject
        ): kotlinx.serialization.json.JsonObject? {
            val existing = map[key]
            if (existing != null) return existing
            map[key] = value
            return null
        }
    }

    companion object {
        private val SKIP_EXTS = setOf(".xml", ".json", ".sqlite", ".txt", ".md", ".torrent", ".log", ".csv", ".pdf", ".jpg", ".png", ".gif", ".ico")
        private const val MIN_FILE_SIZE = 102400L
        private const val MAX_ATTEMPTS = 5
        /** FIX #12: hard cap on cached metadata roots (LRU eviction beyond this). */
        private const val METADATA_CACHE_MAX = 20
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
                if (BuildConfig.DEBUG) { Log.i("EmuHelper", "login attempt $attempt: code=$code hasCookies=${cookieJar.hasCookies()}") }

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
     * Fetch (or return cached) parsed metadata JSON root for [identifier].
     * The metadata endpoint is public and the payload is stable within a process lifetime,
     * so caching aggressively eliminates repeated network + parse overhead during a batch
     * where mirrorUrls() is called once per file for the same identifier.
     */
    private suspend fun fetchMetadata(identifier: String): kotlinx.serialization.json.JsonObject? {
        metadataCache[identifier]?.let { return it }
        return try {
            val req = Request.Builder().url("https://archive.org/metadata/$identifier")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return null
                val root = json.parseToJsonElement(body).jsonObject
                // putIfAbsent so a concurrent caller's result wins safely; either is fine.
                metadataCache.putIfAbsent(identifier, root) ?: root
            }
        } catch (e: Exception) {
            Log.w("EmuHelper", "fetchMetadata failed for $identifier", e)
            null
        }
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
            val root = fetchMetadata(identifier) ?: return@withContext result.toList()
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
        // Use the shared metadata cache to avoid re-fetching when multiple sources share
        // the same identifier, and so subsequent mirrorUrls/fetchFileSize calls are free.
        val root = metadataCache[identifier] ?: run {
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
                val parsed = json.parseToJsonElement(body).jsonObject
                metadataCache.putIfAbsent(identifier, parsed) ?: parsed
            }
        }

        val files = root["files"]?.jsonArray
            ?: throw IOException("Item '$identifier' has no files (may be dead or restricted)")

        val kept = files.mapNotNull { f ->
            val obj = f.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (name.endsWith("/")) return@mapNotNull null
            val ext = File(name).extension.lowercase()
            if (".$ext" in SKIP_EXTS) return@mapNotNull null
            val size = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            if (size < MIN_FILE_SIZE) return@mapNotNull null
            // Source metadata exposes a per-file md5 hex string; carry it through so the
            // download engine can verify integrity after a multi-segment download. Absent
            // or blank means "unverified" downstream (back-compat, no behaviour change).
            val md5 = obj["md5"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: ""
            GameFile(
                name = name,
                filename = File(name).name,
                size = size,
                identifier = identifier,
                sourceUrl = iaUrl,
                md5 = md5
            )
        }
        Log.i("EmuHelper", "scan/$identifier  rawFiles=${files.size} kept=${kept.size}")
        kept
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
    private suspend fun rankHosts(
        resolvedHosts: List<String>,
        // FIX B: optional sink for the REAL measured probe rate (bytes/ms) per host. The adaptive
        // engine uses this to seed its EWMA scoreboard in CORRECT units (bytes/ms) instead of the
        // old bogus "slot count × 100" seed. Default null -> the static path is unaffected and the
        // ranking output is byte-for-byte identical to before.
        probeRatesOut: MutableMap<String, Double>? = null
    ): List<String> = withContext(Dispatchers.IO) {
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
        // FIX B: surface the real measured rates (bytes/ms) for the adaptive seed, if requested.
        if (probeRatesOut != null) {
            for (p in probes) if (p.bytesPerMs > 0.0) probeRatesOut[p.url] = p.bytesPerMs
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
        isCancelled: () -> Boolean,
        // ADAPTIVE ENGINE (default-OFF). When false the EXACT static path below runs unchanged.
        adaptive: Boolean = false,
        // Shared global connection budget (the DownloadManager's Semaphore(24)). When null we
        // fall back to a per-file internal Semaphore(segments) for back-compat / unit tests.
        connectionBudget: Semaphore? = null
    ): Long = withContext(Dispatchers.IO) {
        // Resolve each candidate's redirect once to its real node, dedup.
        val resolvedHosts = candidateUrls.map { resolveFinalUrl(it) ?: it }.distinct()
        // FIX B: when adaptive, capture the REAL probe rates (bytes/ms) to seed the EWMA in correct
        // units. For the static path probeRates stays null and ranking is byte-for-byte unchanged.
        val probeRates: MutableMap<String, Double>? = if (adaptive) HashMap() else null
        // Probe + rank: weighted pool favouring the fastest nodes, slow nodes dropped.
        val hosts = rankHosts(resolvedHosts, probeRates).ifEmpty { resolvedHosts }
        // SMALL-FILE FAST PATH (shared by both engines): no range support, single connection,
        // or <= 1 segment / <1 chunk -> stream straight through. Don't rank, don't chunk.
        val rangeOk = segments > 1 && expectedSize > 0 && hosts.isNotEmpty() && supportsRange(hosts.first())
        Log.i("EmuHelper", "segDL: size=$expectedSize segs=$segments poolSize=${hosts.size} distinct=${hosts.distinct().size} rangeOk=$rangeOk adaptive=$adaptive")
        if (!rangeOk) {
            Log.i("EmuHelper", "segDL: FALLBACK single-stream")
            return@withContext singleStreamTo((hosts.firstOrNull() ?: resolvedHosts.first()), expectedSize, destFile, onProgress, isCancelled)
        }

        // ====================================================================
        // ADAPTIVE PATH (chunk-queue work-stealing). Wholly additive and gated:
        // only runs when the feature flag is on. The static path below is byte-
        // for-byte the original behaviour and is the zero-risk fallback.
        // ====================================================================
        if (adaptive) {
            // Small file ≈ 1 chunk -> reuse the single-stream fast path (don't chunk/rank).
            if (expectedSize < AdaptiveEngine.CHUNK_SIZE) {
                Log.i("EmuHelper", "segDL: adaptive small-file fast path")
                return@withContext singleStreamTo(hosts.first(), expectedSize, destFile, onProgress, isCancelled)
            }
            return@withContext downloadAdaptive(
                hosts = hosts,
                expectedSize = expectedSize,
                destFile = destFile,
                workerHint = segments,
                connectionBudget = connectionBudget ?: Semaphore(segments.coerceAtLeast(1)),
                probeRates = probeRates ?: emptyMap(),
                onProgress = onProgress,
                isCancelled = isCancelled
            )
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
                    // Allocate the read buffer once per segment, OUTSIDE the retry loop,
                    // so it is reused across attempts rather than re-allocated each time.
                    val buf = ByteArray(256 * 1024)
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

    /**
     * ADAPTIVE (chunk-queue work-stealing) download path. Only reached when the feature flag
     * is on AND the file is big enough to chunk. Splits [0, expectedSize) into 8 MB chunks held
     * in a [ChunkQueue]; spins up [workerHint] worker coroutines that each loop poll -> pick host
     * -> ranged GET -> write at absolute offset -> markDone, requeuing the whole chunk on
     * failure/stall. Structured concurrency tears down all workers/Calls on cancellation.
     *
     * Correctness contract (identical guarantees to the static path):
     *  - The chunk partition covers [0,size) with no gaps/overlaps; the file is pre-sized.
     *  - A chunk is marked DONE only after its full range is written and the received byte count
     *    matches its length; a short read requeues the WHOLE chunk (idempotent overwrite).
     *  - Every worker acquires the shared [connectionBudget] before opening a socket and releases
     *    it in a finally, so the global 24-connection thermal cap holds for any worker count.
     *  - On exit we assert every chunk is done; a chunk that exhausts its REAL-error budget
     *    (MAX_ERROR_ATTEMPTS) enters the queue's terminal FAILED state and the download throws
     *    (Fix D). Stalls (soft/hard) never fail the download — they only migrate the chunk.
     */
    private suspend fun downloadAdaptive(
        hosts: List<String>,
        expectedSize: Long,
        destFile: File,
        workerHint: Int,
        connectionBudget: Semaphore,
        // FIX B: real measured probe rates (bytes/ms) per host, used to seed the EWMA in correct
        // units. May be empty (single host, probe failed, or unit test) -> falls back to the
        // scoreboard's small default prior, which is safe because soft-stall is independently
        // gated on the RecentRateWindow's min-sample guardrail, not on the seed.
        probeRates: Map<String, Double>,
        onProgress: suspend (Long, Double) -> Unit,
        isCancelled: () -> Boolean
    ): Long {
        val distinctHosts = hosts.distinct()
        destFile.parentFile?.mkdirs()
        // Pre-size the .part so each worker can seek to any absolute offset (kept from static path).
        RandomAccessFile(destFile, "rw").use { it.setLength(expectedSize) }

        val chunks = partitionChunks(expectedSize)
        val queue = ChunkQueue(chunks)

        // FIX B: seed EWMA in CORRECT units (bytes/ms) from the rankHosts() probe's actual measured
        // rate per host. The OLD seed was "slot-count × 100" (range 100-400) which is NOT bytes/ms
        // — recordSuccess feeds bytesThisChunk/ms (thousands), so the EWMA climbed into the
        // thousands while the seed stayed tiny, making the old soft-stall baseline wrong-scaled.
        // Unseeded hosts fall back to HostScoreboard's small default prior. The soft-stall baseline
        // no longer depends on this seed at all (it uses the RecentRateWindow), so an absent seed
        // is fully safe — it only biases initial host PREFERENCE, not the stall decision.
        val seed: Map<String, Double> = distinctHosts.mapNotNull { h ->
            probeRates[h]?.takeIf { it > 0.0 }?.let { h to it }
        }.toMap()
        val scoreboard = HostScoreboard(distinctHosts, seedEwma = seed)

        // FIX B: the CURRENT, decaying soft-stall baseline. A ring buffer of the last N
        // completed-chunk throughputs across ALL hosts. The soft-stall decision compares a chunk's
        // sustained rate against the MEDIAN of these real recent rates (not a never-decaying peak),
        // and is DISABLED until enough samples exist (the min-sample guardrail).
        val recentRates = RecentRateWindow()

        // Progress: confirmedBytes counts only fully-completed chunks (monotonic — a requeue can
        // never make it go backwards or over-count). It is what we REPORT, keeping the progress
        // bar monotonic. A separate liveBytes feeds the windowed speed number only.
        val confirmedBytes = java.util.concurrent.atomic.AtomicLong(0)
        val liveBytes = java.util.concurrent.atomic.AtomicLong(0)
        val startTime = System.currentTimeMillis()
        var lastReport = startTime
        val reportLock = Any()

        // v2 OVER-PROVISION the worker pool. v1 capped workers at `segments` (workerHint), so a
        // single big file never opened more than `segments` connections even when the global
        // 24-permit connectionBudget was free — leaving IA's other fast nodes idle. Now we scale
        // UP toward [AdaptiveEngine.MAX_ADAPTIVE_WORKERS] (the global ceiling), still bounded by
        // the chunk count (no point exceeding chunks). The SAFETY INVARIANT is unchanged and
        // enforced at runtime, NOT here: every worker (and every racer) acquire()s a permit from
        // the shared connectionBudget before opening a socket and releases it in a finally, so the
        // total concurrent permits across ALL files+workers+racers is <= the Semaphore's capacity
        // (24) no matter how many workers are spawned. Spawning more workers than free permits is
        // safe: the extras simply block on acquire() until a permit frees, and yield to other
        // concurrent files that share the same Semaphore. workerHint stays the FLOOR.
        val workerCount = workerHint
            .coerceAtLeast(1)
            .coerceAtLeast(minOf(chunks.size, AdaptiveEngine.MAX_ADAPTIVE_WORKERS))
            .coerceIn(1, chunks.size)

        // v2 BENCH stats (cheap, aggregated at completion — no per-chunk spam). Concurrent permit
        // tracking lets us log the PEAK simultaneous connections actually used; race counters let
        // the maintainer see whether tail-racing fired and won on a real IA download.
        val liveConns = java.util.concurrent.atomic.AtomicInteger(0)
        val peakConns = java.util.concurrent.atomic.AtomicInteger(0)
        val racesStarted = java.util.concurrent.atomic.AtomicInteger(0)
        val racesWon = java.util.concurrent.atomic.AtomicInteger(0)
        val onConnAcquired: () -> Unit = {
            val n = liveConns.incrementAndGet()
            // Lock-free monotonic max.
            var peak = peakConns.get()
            while (n > peak && !peakConns.compareAndSet(peak, n)) peak = peakConns.get()
        }
        val onConnReleased: () -> Unit = { liveConns.decrementAndGet() }

        val onReport: suspend () -> Unit = {
            // ~3x/sec aggregate report, identical shape & cadence to the static path.
            val now = System.currentTimeMillis()
            var doReport = false
            synchronized(reportLock) {
                if (now - lastReport >= 350) { lastReport = now; doReport = true }
            }
            if (doReport) {
                val secs = (now - startTime).coerceAtLeast(1) / 1000.0
                // Report the MONOTONIC confirmedBytes total; speed from live bytes.
                onProgress(confirmedBytes.get(), liveBytes.get() / secs)
            }
        }

        coroutineScope {
            (0 until workerCount).map {
                async {
                    val buf = ByteArray(256 * 1024)
                    // Each worker loops, stealing chunks until the queue is drained AND nothing
                    // is in flight (so a requeued chunk from a stalled peer is still picked up).
                    while (queue.shouldKeepWorking()) {
                        if (isCancelled()) throw CancellationException()
                        val chunk = queue.poll()
                        if (chunk == null) {
                            // No fresh chunk to start. v2: in the TAIL (queue drained, only a few
                            // chunks still in-flight) an idle worker RACES a slow in-flight chunk on
                            // a SECOND, different host instead of busy-waiting — first to finish the
                            // range wins, the loser bails (see downloadChunkAttempt's done-check).
                            // pickRaceTarget returns null unless the strict tail-gate holds, so this
                            // never fires early or on small files.
                            val raceChunk = queue.pickRaceTarget()
                            if (raceChunk != null) {
                                racesStarted.incrementAndGet()
                                downloadChunkAttempt(
                                    chunk = raceChunk,
                                    queue = queue,
                                    scoreboard = scoreboard,
                                    recentRates = recentRates,
                                    distinctHosts = distinctHosts,
                                    destFile = destFile,
                                    expectedSize = expectedSize,
                                    buf = buf,
                                    connectionBudget = connectionBudget,
                                    confirmedBytes = confirmedBytes,
                                    liveBytes = liveBytes,
                                    isCancelled = isCancelled,
                                    onReport = onReport,
                                    isRace = true,
                                    onConnAcquired = onConnAcquired,
                                    onConnReleased = onConnReleased,
                                    onRaceWon = { racesWon.incrementAndGet() }
                                )
                            } else {
                                // Nothing to claim right now but peers hold in-flight chunks that
                                // may be requeued; yield briefly rather than busy-spinning.
                                kotlinx.coroutines.delay(25)
                            }
                            continue
                        }
                        downloadChunkAttempt(
                            chunk = chunk,
                            queue = queue,
                            scoreboard = scoreboard,
                            recentRates = recentRates,
                            distinctHosts = distinctHosts,
                            destFile = destFile,
                            expectedSize = expectedSize,
                            buf = buf,
                            connectionBudget = connectionBudget,
                            confirmedBytes = confirmedBytes,
                            liveBytes = liveBytes,
                            isCancelled = isCancelled,
                            onReport = onReport,
                            isRace = false,
                            onConnAcquired = onConnAcquired,
                            onConnReleased = onConnReleased,
                            onRaceWon = { /* primary path is never a race win */ }
                        )
                    }
                }
            }.awaitAll()
        }

        // COMPLETION GATE (Fix D — self-healing, definite terminal conditions):
        // Workers exit only when no chunk is queued and none is in flight, so by here every chunk
        // is in a TERMINAL state: done or failed. The download fails iff any chunk hit the terminal
        // FAILED state (exhausted its real-error budget) — a definite condition, not a race on a
        // thrown exception. Otherwise every chunk is done and we confirm the byte total.
        if (queue.anyFailed()) {
            throw IOException(
                "Adaptive download failed: ${queue.failedIndices().size} chunk(s) exhausted the " +
                    "error budget (indices ${queue.failedIndices().sorted()})"
            )
        }
        if (!queue.allDone()) {
            // Should be impossible given the worker exit condition, but keep the hard guard.
            throw IOException("Adaptive download incomplete: ${queue.remaining()} chunk(s) unfinished")
        }
        val total = confirmedBytes.get()
        // Sanity: confirmedBytes must equal expectedSize on success (sum of all chunk lengths ==
        // expectedSize), so the caller's `total >= expected*99/100` completion check passes.
        if (total != expectedSize) {
            throw IOException("Adaptive download size mismatch: confirmed $total != expected $expectedSize")
        }
        // v2 HONEST BENCH LOG (aggregate, once at completion — cheap, no per-chunk spam). Lets the
        // maintainer compare adaptive-on vs adaptive-off MB/s on a real device from logcat, and see
        // whether over-provisioning actually opened more connections and whether tail-racing fired
        // and won. Tag is grep-friendly. wall-clock includes warm-up so it's the user-visible time.
        val wallMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
        val mb = total.toDouble() / (1024.0 * 1024.0)
        val mbps = mb / (wallMs / 1000.0)
        Log.i(
            "EmuHelper-ADAPTIVE-BENCH",
            "adaptive: ${"%.1f".format(mb)}MB in ${"%.1f".format(wallMs / 1000.0)}s = " +
                "${"%.2f".format(mbps)}MB/s, workers=$workerCount peakConns=${peakConns.get()} " +
                "races=${racesStarted.get()}/${racesWon.get()} (started/won)"
        )
        onProgress(total, 0.0)
        return total
    }

    /**
     * Download ONE chunk attempt: pick a host, ranged GET, write at absolute offset, verify the
     * full byte count, and mark the chunk DONE. The work-stealing model is "requeue whole on
     * stall": a single failed attempt does NOT loop here — instead the WHOLE chunk is handed back
     * to the queue so ANY worker can re-claim it, and this returns normally so the current worker
     * immediately pulls its next chunk.
     *
     * BUDGET SEPARATION (Fix A): a STALL (soft or hard) is NOT an error. Stalls go through
     * [ChunkQueue.requeueStall] (own large budget) and can NEVER fail the download — they just
     * migrate the chunk to a different worker/host. Only REAL transport errors (non-206, short
     * read, IOException, timeout) go through [ChunkQueue.recordErrorOrFail], which consumes the
     * per-chunk error budget ([AdaptiveEngine.MAX_ERROR_ATTEMPTS]) and, when exhausted, moves the
     * chunk to the terminal FAILED state and rethrows so the whole download fails.
     *
     * SELF-HEALING (Fix D): a `reconciled` guard ensures the polled chunk is reconciled EXACTLY
     * ONCE on every exit path. If we somehow leave without having marked it done or requeued it,
     * the finally force-requeues it so it can never be orphaned (which would leak an in-flight slot
     * and wedge the workers forever).
     *
     * Correctness: writes are idempotent absolute-offset overwrites, so a requeued chunk simply
     * overwrites the same range. The chunk is marked DONE only after the received byte count
     * equals the chunk length exactly (a short read requeues — never a partial done).
     */
    private suspend fun downloadChunkAttempt(
        chunk: Chunk,
        queue: ChunkQueue,
        scoreboard: HostScoreboard,
        recentRates: RecentRateWindow,
        distinctHosts: List<String>,
        destFile: File,
        expectedSize: Long,
        buf: ByteArray,
        connectionBudget: Semaphore,
        confirmedBytes: java.util.concurrent.atomic.AtomicLong,
        liveBytes: java.util.concurrent.atomic.AtomicLong,
        isCancelled: () -> Boolean,
        onReport: suspend () -> Unit,
        // v2 TAIL-RACING: when true this is a RACER on an already-in-flight tail chunk, not the
        // primary owner. A racer (a) acquires the global permit non-blockingly (tryAcquire — never
        // block a permit a fresh chunk could use; bails if none free), (b) checks queue.isDone
        // before every write and bails the instant the primary (or another racer) finishes the
        // range, (c) NEVER touches the error/stall budgets or requeues/force-requeues the chunk —
        // it is purely additive, so a racer failure can never fail the download (the primary still
        // owns the chunk), and (d) pairs pickRaceTarget with endRace in finally. Race correctness:
        // both the primary and the racer write the SAME absolute [start,end] range with IDENTICAL
        // server bytes, so last-write-wins is byte-safe and whoever markDone()s first wins.
        isRace: Boolean = false,
        // v2 bench hooks: count a connection permit while held (for peak-connections), fired only
        // once the permit is actually acquired and once on release.
        onConnAcquired: () -> Unit = {},
        onConnReleased: () -> Unit = {},
        // v2: invoked iff THIS attempt was a race that WON (its markDone transitioned the chunk).
        onRaceWon: () -> Unit = {}
    ) {
        if (isCancelled()) throw CancellationException()
        // v2 racer: if the chunk already finished between pickRaceTarget and here, there's nothing
        // to race — release the racer slot and bail (no permit acquired, no budget touched).
        if (isRace && queue.isDone(chunk.index)) {
            queue.endRace(chunk)
            return
        }
        // How many real errors this chunk has already taken — used to prefer a DIFFERENT host on
        // retries (fastest-first failover), matching the static path's per-attempt host rotation.
        val priorTries = queue.errorAttemptsOf(chunk.index)
        val host = scoreboard.pickHost()
            ?: distinctHosts[priorTries % distinctHosts.size]
        var hostReleased = false
        // Fix D: set true once the chunk is reconciled (markDone OR requeued/failed). If still
        // false in the finally, the chunk leaked in-flight and is force-requeued. A RACER never
        // owns reconciliation (the primary does), so it starts and stays "reconciled" — its finally
        // must never force-requeue a chunk the primary still owns.
        var reconciled = isRace
        var call: okhttp3.Call? = null
        var connAcquired = false
        var bytesThisChunk = 0L
        val chunkStart = System.currentTimeMillis()
        var lastReadAt = chunkStart
        // Soft-stall sustain counter: how many consecutive measurement windows this chunk has read
        // below the baseline bar. Resets to 0 whenever it reads at/above the bar (Fix B — sustained).
        var consecutiveSlowWindows = 0
        var lastSoftCheck = chunkStart
        // Acquire a GLOBAL connection slot BEFORE opening the socket; release in finally. This is
        // what makes the 24-connection thermal cap robust to any (variable) worker count. A RACER
        // uses tryAcquire so it never blocks a permit a fresh chunk could use and never pushes the
        // total over the cap; if no permit is free it abandons the race immediately.
        if (isRace) {
            if (!connectionBudget.tryAcquire()) {
                // No free permit — don't block. The primary still owns the chunk; just give up the
                // race. (host was picked but never used; release its scoreboard load slot.)
                scoreboard.releaseHost(host)
                queue.endRace(chunk)
                return
            }
        } else {
            connectionBudget.acquire()
        }
        connAcquired = true
        onConnAcquired()
        try {
            val req = Request.Builder().url(host)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "identity")
                .header("Accept", "*/*")
                .header("Referer", "https://archive.org/")
                .header("Range", "bytes=${chunk.start}-${chunk.end}")
                .build()
            call = okHttpClient.newCall(req)
            call.execute().use { resp ->
                if (resp.code != 206) throw IOException("No range (HTTP ${resp.code})")
                // Guard against stale metadata: if the server's real total differs from the size
                // we pre-sized/partitioned against, our offsets are wrong — abort. Same
                // CLEAR-mismatch-only policy as the static path (absent header => proceed).
                resp.header("Content-Range")?.let { cr ->
                    val serverTotal = cr.substringAfterLast('/', "").trim().toLongOrNull()
                    if (serverTotal != null && serverTotal > 0 && serverTotal != expectedSize) {
                        throw IOException(
                            "Server file size changed; expected $expectedSize but server reports $serverTotal"
                        )
                    }
                }
                val body = resp.body ?: throw IOException("empty body")
                // Open our own handle and seek to this chunk's absolute offset (idempotent).
                RandomAccessFile(destFile, "rw").use { raf ->
                    raf.seek(chunk.start)
                    body.byteStream().use { input ->
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            if (isCancelled()) throw CancellationException()
                            // v2 RACE done-check: if the chunk has already been completed (by the
                            // primary, or by another racer) bail BEFORE writing — avoid wasted
                            // writes and let the loser stop promptly. Both attempts write identical
                            // bytes to the same offset, so a stray late write would be harmless
                            // (last-write-wins), but checking `done` first avoids the work entirely.
                            // Only racers check this: the primary owns the chunk and must run to its
                            // own markDone (its own markDone is the no-op if a racer beat it).
                            if (isRace && queue.isDone(chunk.index)) {
                                hostReleased = true
                                scoreboard.releaseHost(host)
                                return  // finally cancels the Call, releases permit + race slot
                            }
                            if (read > 0) {
                                raf.write(buf, 0, read)
                                bytesThisChunk += read
                                liveBytes.addAndGet(read.toLong())
                                lastReadAt = System.currentTimeMillis()
                            }
                            onReport()

                            val now = System.currentTimeMillis()
                            // HARD STALL (unchanged, unambiguous, safe): zero bytes for
                            // STALL_TIMEOUT_MS -> bail and requeue AS A STALL (Fix A), not an error.
                            if (now - lastReadAt >= AdaptiveEngine.STALL_TIMEOUT_MS) {
                                throw StallException("hard stall on ${host.hostLabel()}", soft = false)
                            }
                            // SOFT STALL (Fix B — real, sustained, guarded). Evaluate at most once
                            // per measurement window (~350ms) so "consecutive windows" is meaningful
                            // and cheap. A chunk migrates only when ALL hold:
                            //   - past TCP warm-up (MIN_SAMPLE_MS),
                            //   - its rate is below SOFT_STALL_FRACTION × the CURRENT recent-rate
                            //     baseline (median of recent completed chunks, not a stale peak),
                            //   - it has stayed below that bar for SOFT_STALL_WINDOWS consecutive
                            //     windows (not a single jittery sample),
                            //   - the baseline is trustworthy (>= SOFT_STALL_MIN_SAMPLES; otherwise
                            //     soft-stall is DISABLED and we never migrate — the safe fallback),
                            //   - there is unclaimed work to migrate to (else migration is pointless).
                            val elapsed = now - chunkStart
                            if (elapsed >= AdaptiveEngine.MIN_SAMPLE_MS &&
                                now - lastSoftCheck >= 350L
                            ) {
                                lastSoftCheck = now
                                val rate = bytesThisChunk.toDouble() / elapsed.coerceAtLeast(1)
                                // Count consecutive sub-bar windows (no-op when soft-stall disabled
                                // by the min-sample guardrail — isBelowBar returns false then).
                                consecutiveSlowWindows =
                                    if (recentRates.isBelowBar(rate)) consecutiveSlowWindows + 1 else 0
                                if (queue.unclaimed() > 0 &&
                                    recentRates.shouldSoftStall(rate, elapsed, consecutiveSlowWindows)
                                ) {
                                    throw StallException("soft stall on ${host.hostLabel()}", soft = true)
                                }
                            }
                        }
                    }
                }
            }
            // FULL-RANGE VERIFICATION: only mark done when the received byte count matches the
            // chunk length EXACTLY. A short read requeues the WHOLE chunk (no partial done).
            // (A racer that read its full range but lost the race to the primary still lands here
            // and its markDone is simply a no-op — last-write-wins, idempotent.)
            if (bytesThisChunk != chunk.length) {
                if (isRace) {
                    // A short-read RACER never errors/requeues the chunk (the primary owns it). Just
                    // give up the race; the primary (or another racer) will complete the range.
                    scoreboard.releaseHost(host)
                    hostReleased = true
                    return
                }
                throw IOException("Short chunk ${chunk.index}: got $bytesThisChunk of ${chunk.length}")
            }
            // Success: record the observed rate (releases the host slot), mark done, count bytes.
            val ms = (System.currentTimeMillis() - chunkStart).coerceAtLeast(1)
            val observedRate = bytesThisChunk.toDouble() / ms
            scoreboard.recordSuccess(host, observedRate)
            hostReleased = true
            // Fix B: feed the CURRENT recent-rate baseline with this real completed-chunk rate.
            recentRates.record(observedRate)
            // markDone is the single atomic winner-decider under the chunk's lock: it returns true
            // for EXACTLY ONE caller (the first to finish the range — primary or racer), false for
            // the loser. Only the winner counts the bytes, so confirmedBytes can never double-count
            // a raced chunk.
            if (queue.markDone(chunk)) {
                confirmedBytes.addAndGet(chunk.length)
                if (isRace) onRaceWon()   // v2: this racer beat the primary — count the win.
            }
            reconciled = true  // Fix D: chunk reached a terminal-done state on this path.
            onReport()
        } catch (c: CancellationException) {
            // Structured cancellation: release the host slot and requeue is unnecessary (the whole
            // scope is being torn down). Re-throw so the caller deletes the .part. The finally must
            // NOT force-requeue here, so mark reconciled — the scope teardown is authoritative.
            scoreboard.releaseHost(host)
            hostReleased = true
            reconciled = true
            throw c
        } catch (s: StallException) {
            // STALL (Fix A + C): NOT an error. It never consumes the error budget and never fails
            // the download — the chunk is always requeued for another worker/host.
            if (s.soft) {
                // Soft stall (Fix C): the host is just slower than the current baseline, not broken.
                // DEMOTE it (no cooldown) so we don't funnel all workers onto one host.
                scoreboard.recordSoftStall(host)
            } else {
                // Hard stall: zero bytes for the timeout is an unambiguous bad-host signal -> cool it.
                scoreboard.recordStall(host)
            }
            hostReleased = true
            // v2: a RACER never requeues — the primary owns the chunk and is still running. A racer
            // stalling on its second host just abandons the race (the primary completes the chunk).
            if (!isRace) {
                queue.requeueStall(chunk)        // own large budget; cannot fail the download
                reconciled = true                // Fix D: chunk reconciled (requeued).
            }
            // No exponential backoff for a stall — the point is to migrate PROMPTLY to a peer/host.
        } catch (e: Exception) {
            // REAL transport error (non-206, short read, IOException, timeout): cool the host
            // (releases its slot) and consume the per-chunk ERROR budget. When exhausted, the chunk
            // enters the terminal FAILED state and we rethrow so the whole download fails — same
            // terminal semantics as the static per-segment loop, but ONLY for genuine errors.
            scoreboard.recordStall(host)
            hostReleased = true
            // v2: a RACER never consumes the error budget and never fails the download — it is
            // purely additive. On any racer error we just abandon the race; the primary still owns
            // the chunk and its own error budget governs whether the download ultimately fails.
            if (isRace) {
                // reconciled stays true (a racer never owns reconciliation) — nothing to requeue.
                return
            }
            val exhausted = queue.recordErrorOrFail(chunk, AdaptiveEngine.MAX_ERROR_ATTEMPTS)
            reconciled = true                    // Fix D: chunk reconciled (requeued or failed).
            if (exhausted) throw e
            // Backoff before the chunk is retried by some worker (same constants as static path).
            val nextTry = queue.errorAttemptsOf(chunk.index)
            kotlinx.coroutines.delay(min(1000L * (1L shl ((nextTry - 1).coerceAtLeast(0))), 8000L))
        } finally {
            // Cancel the in-flight Call so a stalled socket is torn down promptly. For a losing
            // racer this tears down the duplicate connection so it stops pulling bytes immediately.
            runCatching { call?.cancel() }
            // Always release the global connection slot we actually acquired (a racer that failed
            // tryAcquire returned before this point and never reaches here). Pairing acquire with
            // release in finally is THE invariant that keeps total permits <= the cap (24) across
            // all workers AND racers.
            if (connAcquired) {
                connectionBudget.release()
                onConnReleased()
            }
            // Defensive: if neither success nor failure released the host (shouldn't happen),
            // release it so the scoreboard's load count stays accurate.
            if (!hostReleased) scoreboard.releaseHost(host)
            // v2: release the racer slot on EVERY racer exit path (won, lost, errored, cancelled).
            // A no-op for the primary. Pairs with pickRaceTarget's racer-count increment so racer
            // accounting can never leak.
            if (isRace) queue.endRace(chunk)
            // Fix D — SELF-HEALING completion gate: if the chunk was neither marked done nor
            // requeued/failed on ANY exit path (e.g. an unexpected throw before reconciliation),
            // force it back onto the queue so it can never be orphaned and leak an in-flight slot.
            // A racer never owns reconciliation (reconciled starts true for racers), so this never
            // force-requeues a chunk the primary still owns.
            if (!reconciled) queue.forceRequeue(chunk)
        }
    }

    /** Compact host label for logs (scheme/host only). */
    private fun String.hostLabel(): String = substringAfter("//").substringBefore('/')

    /**
     * Internal marker for a detected STALL (vs. a real transport error). A stall is always
     * recoverable and NEVER consumes the per-chunk error budget (Fix A): it only migrates the
     * chunk. [soft] distinguishes a soft stall (slower than the current baseline — host is merely
     * de-preferred, NOT cooled; Fix C) from a hard stall (zero bytes for the timeout — host cooled).
     */
    private class StallException(message: String, val soft: Boolean) : IOException(message)

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
            // Use cached metadata to avoid a redundant network round-trip.
            val data = fetchMetadata(identifier) ?: return@withContext 0L
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
        } catch (e: Exception) {
            Log.w("EmuHelper", "fetchFileSize($identifier/$filename) failed", e)
            0L
        }
    }
}

sealed class LoginResult {
    data object Success : LoginResult()
    data class Failed(val message: String) : LoginResult()
}
