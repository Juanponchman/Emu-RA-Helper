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
        isCancelled: () -> Boolean,
        // ADAPTIVE ENGINE (default-OFF). When false the EXACT static path below runs unchanged.
        adaptive: Boolean = false,
        // Shared global connection budget (the DownloadManager's Semaphore(24)). When null we
        // fall back to a per-file internal Semaphore(segments) for back-compat / unit tests.
        connectionBudget: Semaphore? = null
    ): Long = withContext(Dispatchers.IO) {
        // Resolve each candidate's redirect once to its real node, dedup.
        val resolvedHosts = candidateUrls.map { resolveFinalUrl(it) ?: it }.distinct()
        // Probe + rank: weighted pool favouring the fastest nodes, slow nodes dropped.
        val hosts = rankHosts(resolvedHosts).ifEmpty { resolvedHosts }
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
     *  - On exit we assert every chunk is done; a chunk that exhausts MAX_ATTEMPTS on all hosts
     *    throws, failing the whole download (same as today).
     */
    private suspend fun downloadAdaptive(
        hosts: List<String>,
        expectedSize: Long,
        destFile: File,
        workerHint: Int,
        connectionBudget: Semaphore,
        onProgress: suspend (Long, Double) -> Unit,
        isCancelled: () -> Boolean
    ): Long {
        val distinctHosts = hosts.distinct()
        destFile.parentFile?.mkdirs()
        // Pre-size the .part so each worker can seek to any absolute offset (kept from static path).
        RandomAccessFile(destFile, "rw").use { it.setLength(expectedSize) }

        val chunks = partitionChunks(expectedSize)
        val queue = ChunkQueue(chunks)

        // Seed EWMA from the one-time rankHosts() probe: a host's frequency in the weighted pool
        // (1..4 slots) encodes its relative speed, so use that count as the seed. rankHosts stays
        // unchanged; we only read its output here.
        val seed: Map<String, Double> = hosts.groupingBy { it }.eachCount()
            .mapValues { (_, slots) -> slots.toDouble() * 100.0 } // arbitrary positive scale; only ratios matter
        val scoreboard = HostScoreboard(distinctHosts, seedEwma = seed)

        // Progress: confirmedBytes counts only fully-completed chunks (monotonic — a requeue can
        // never make it go backwards or over-count). It is what we REPORT, keeping the progress
        // bar monotonic. A separate liveBytes feeds the windowed speed number only.
        val confirmedBytes = java.util.concurrent.atomic.AtomicLong(0)
        val liveBytes = java.util.concurrent.atomic.AtomicLong(0)
        val startTime = System.currentTimeMillis()
        var lastReport = startTime
        val reportLock = Any()

        // The number of worker connections for this file. Bounded by the worker hint and the
        // number of chunks (no point spinning up more workers than chunks).
        val workerCount = workerHint.coerceIn(1, chunks.size)

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
                            // Nothing to claim right now but peers hold in-flight chunks that may
                            // be requeued; yield briefly and re-check rather than busy-spinning.
                            kotlinx.coroutines.delay(25)
                            continue
                        }
                        downloadChunkAttempt(
                            chunk = chunk,
                            queue = queue,
                            scoreboard = scoreboard,
                            distinctHosts = distinctHosts,
                            destFile = destFile,
                            expectedSize = expectedSize,
                            buf = buf,
                            connectionBudget = connectionBudget,
                            confirmedBytes = confirmedBytes,
                            liveBytes = liveBytes,
                            isCancelled = isCancelled,
                            onReport = {
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
                        )
                    }
                }
            }.awaitAll()
        }

        // COMPLETION GATE: every chunk index 0..count-1 must be done before we return.
        if (!queue.allDone()) {
            throw IOException("Adaptive download incomplete: ${queue.remaining()} chunk(s) unfinished")
        }
        val total = confirmedBytes.get()
        onProgress(total, 0.0)
        return total
    }

    /**
     * Download ONE chunk attempt: pick a host, ranged GET, write at absolute offset, verify the
     * full byte count, and mark the chunk DONE. The work-stealing model is "requeue whole on
     * stall": a single failed attempt does NOT loop here — instead the WHOLE chunk is handed back
     * to [ChunkQueue.requeueOrFail] (with backoff) so ANY worker can re-claim it, and this returns
     * normally so the current worker immediately pulls its next chunk. A chunk only fails the
     * whole download once it has exhausted [MAX_ATTEMPTS] across all hosts (tracked on the queue),
     * at which point this throws.
     *
     * Correctness: writes are idempotent absolute-offset overwrites, so a requeued chunk simply
     * overwrites the same range. The chunk is marked DONE only after the received byte count
     * equals the chunk length exactly (a short read requeues — never a partial done).
     */
    private suspend fun downloadChunkAttempt(
        chunk: Chunk,
        queue: ChunkQueue,
        scoreboard: HostScoreboard,
        distinctHosts: List<String>,
        destFile: File,
        expectedSize: Long,
        buf: ByteArray,
        connectionBudget: Semaphore,
        confirmedBytes: java.util.concurrent.atomic.AtomicLong,
        liveBytes: java.util.concurrent.atomic.AtomicLong,
        isCancelled: () -> Boolean,
        onReport: suspend () -> Unit
    ) {
        if (isCancelled()) throw CancellationException()
        // How many times this chunk has already failed — used to prefer a DIFFERENT host on
        // retries (fastest-first failover), matching the static path's per-attempt host rotation.
        val priorTries = queue.attemptsOf(chunk.index)
        val host = scoreboard.pickHost()
            ?: distinctHosts[priorTries % distinctHosts.size]
        var hostReleased = false
        var call: okhttp3.Call? = null
        var bytesThisChunk = 0L
        val chunkStart = System.currentTimeMillis()
        var lastReadAt = chunkStart
        // Acquire a GLOBAL connection slot BEFORE opening the socket; release in finally. This is
        // what makes the 24-connection thermal cap robust to any (variable) worker count.
        connectionBudget.acquire()
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
                            if (read > 0) {
                                raf.write(buf, 0, read)
                                bytesThisChunk += read
                                liveBytes.addAndGet(read.toLong())
                                lastReadAt = System.currentTimeMillis()
                            }
                            onReport()

                            // HARD STALL: zero bytes for STALL_TIMEOUT_MS -> bail and requeue.
                            val now = System.currentTimeMillis()
                            if (now - lastReadAt >= AdaptiveEngine.STALL_TIMEOUT_MS) {
                                throw StallException("hard stall on ${host.hostLabel()}")
                            }
                            // SOFT STALL: past TCP ramp-up, this chunk is far slower than the best
                            // live host AND there is unclaimed work to redistribute to a peer.
                            val elapsed = now - chunkStart
                            if (elapsed >= AdaptiveEngine.MIN_SAMPLE_MS && queue.unclaimed() > 0) {
                                val rate = bytesThisChunk.toDouble() / elapsed.coerceAtLeast(1)
                                val best = scoreboard.bestLiveEwma()
                                if (best > 0.0 && rate < AdaptiveEngine.SOFT_STALL_FRACTION * best) {
                                    throw StallException("soft stall on ${host.hostLabel()}")
                                }
                            }
                        }
                    }
                }
            }
            // FULL-RANGE VERIFICATION: only mark done when the received byte count matches the
            // chunk length EXACTLY. A short read requeues the WHOLE chunk (no partial done).
            if (bytesThisChunk != chunk.length) {
                throw IOException("Short chunk ${chunk.index}: got $bytesThisChunk of ${chunk.length}")
            }
            // Success: record the observed rate (releases the host slot), mark done, count bytes.
            val ms = (System.currentTimeMillis() - chunkStart).coerceAtLeast(1)
            scoreboard.recordSuccess(host, bytesThisChunk.toDouble() / ms)
            hostReleased = true
            if (queue.markDone(chunk)) {
                confirmedBytes.addAndGet(chunk.length)
            }
            onReport()
        } catch (c: CancellationException) {
            // Structured cancellation: release the host slot and requeue is unnecessary (the whole
            // scope is being torn down). Re-throw so the caller deletes the .part.
            scoreboard.releaseHost(host)
            hostReleased = true
            throw c
        } catch (e: Exception) {
            // Stall or transport error: cool the host (releases its slot), record one attempt and
            // requeue the WHOLE chunk — UNLESS it has now exhausted MAX_ATTEMPTS, in which case the
            // whole download fails (same terminal semantics as the static per-segment loop).
            scoreboard.recordStall(host)
            hostReleased = true
            val exhausted = queue.requeueOrFail(chunk, MAX_ATTEMPTS)
            if (exhausted) throw e
            // Backoff before the chunk is retried by some worker (same constants as static path).
            val nextTry = queue.attemptsOf(chunk.index)
            kotlinx.coroutines.delay(min(1000L * (1L shl ((nextTry - 1).coerceAtLeast(0))), 8000L))
        } finally {
            // Cancel the in-flight Call so a stalled socket is torn down promptly.
            runCatching { call?.cancel() }
            // Always release the global connection slot.
            connectionBudget.release()
            // Defensive: if neither success nor failure released the host (shouldn't happen),
            // release it so the scoreboard's load count stays accurate.
            if (!hostReleased) scoreboard.releaseHost(host)
        }
    }

    /** Compact host label for logs (scheme/host only). */
    private fun String.hostLabel(): String = substringAfter("//").substringBefore('/')

    /** Internal marker for a detected stall (vs. a transport error) — both are recoverable. */
    private class StallException(message: String) : IOException(message)

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
