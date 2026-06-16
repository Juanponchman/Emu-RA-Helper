package io.github.mayusi.emuhelper.data.source

/**
 * Pure, Android-free logic for the ADAPTIVE DOWNLOAD ENGINE (v1: chunk-queue work-stealing).
 *
 * This file holds the correctness-critical, UNIT-TESTABLE building blocks of the adaptive
 * downloader so they can be exercised without a network, a device, or coroutines:
 *
 *  - [Chunk]          — an immutable byte range [start, end] of the file.
 *  - [ChunkQueue]     — a thread-safe work queue of chunks with done / in-flight accounting.
 *  - [HostScoreboard] — a thread-safe EWMA scoreboard for live host selection + cooldown.
 *
 * The model: instead of statically slicing a file into N fixed segments, [partitionChunks]
 * splits [0, size) into many small fixed CHUNKS held in a [ChunkQueue]. A fixed pool of worker
 * connections each loop: poll a chunk -> pick a host -> ranged GET -> write at absolute offset
 * -> markDone; on failure/stall -> requeue the WHOLE chunk -> continue. Adaptivity is emergent:
 * fast nodes pull more chunks; a stalled node costs only its single in-flight chunk.
 *
 * Nothing here touches OkHttp, files, or Android — that lives in RemoteSource's worker loop.
 */

/** Engine-wide constants for the adaptive (chunk-queue) path. */
internal object AdaptiveEngine {
    /** Fixed chunk size: 8 MB. The last chunk absorbs the remainder. */
    const val CHUNK_SIZE: Long = 8L * 1024 * 1024

    /** Hard stall: zero bytes for this long (well under OkHttp's 180s readTimeout) -> requeue. */
    const val STALL_TIMEOUT_MS: Long = 8000L

    /** A chunk must run at least this long before soft-stall logic applies (past TCP ramp-up). */
    const val MIN_SAMPLE_MS: Long = 2000L

    /** Soft stall: chunk throughput below this fraction of the best-live EWMA is "slow". */
    const val SOFT_STALL_FRACTION: Double = 0.25

    /** A host that stalls/errors is on cooldown (skipped during selection) for this long. */
    const val HOST_COOLDOWN_MS: Long = 15_000L

    /** EWMA smoothing: new = old*EWMA_KEEP + observed*(1-EWMA_KEEP). */
    const val EWMA_KEEP: Double = 0.7
}

/**
 * An immutable chunk of the file: the absolute byte range [start, end] INCLUSIVE.
 * length == end - start + 1. [index] is its position in the partition (0-based, contiguous).
 */
internal data class Chunk(val index: Int, val start: Long, val end: Long) {
    val length: Long get() = end - start + 1
}

/**
 * Partition [0, size) into contiguous 8 MB chunks (the last absorbs the remainder).
 *
 * INVARIANTS (the byte-coverage contract every test asserts):
 *  - chunks cover [0, size) with NO gaps and NO overlaps,
 *  - chunk[0].start == 0, chunk[last].end == size - 1,
 *  - sum of lengths == size,
 *  - indices are 0..count-1 contiguous.
 *
 * For size <= 0 returns an empty list (the small-file fast path never calls this).
 */
internal fun partitionChunks(size: Long, chunkSize: Long = AdaptiveEngine.CHUNK_SIZE): List<Chunk> {
    if (size <= 0L) return emptyList()
    val effChunk = chunkSize.coerceAtLeast(1L)
    val chunks = ArrayList<Chunk>()
    var start = 0L
    var index = 0
    while (start < size) {
        // The last chunk absorbs any remainder: its end is always size - 1.
        val end = minOf(start + effChunk - 1, size - 1)
        chunks.add(Chunk(index, start, end))
        start = end + 1
        index++
    }
    return chunks
}

/**
 * Thread-safe work queue of [Chunk]s for the work-stealing pool.
 *
 * Lifecycle of a chunk: it starts QUEUED. A worker [poll]s it (-> IN-FLIGHT), then either
 * [markDone]s it (-> DONE) or [requeue]s the WHOLE chunk back (-> QUEUED) on failure/stall.
 * Re-downloading the whole chunk is an absolute-offset idempotent overwrite, so a requeue can
 * never corrupt: the worst case is redundant bytes written to the same range.
 *
 * Accounting identity (always holds): claims - requeues - dones == inFlight.
 * A chunk can never be simultaneously done AND queued/in-flight: [markDone] for an unknown or
 * already-done index is rejected, and a done index is never re-enqueued.
 *
 * All state is guarded by an intrinsic lock — operations are O(1)/O(count) over a tiny set, so
 * contention is negligible even with 24 workers. No coroutines: pure, synchronous, testable.
 */
internal class ChunkQueue(chunks: List<Chunk>) {
    private val lock = Any()

    /** Immutable partition, indexed by chunk.index for O(1) lookups. */
    private val all: List<Chunk> = chunks.sortedBy { it.index }

    /** FIFO of chunk indices currently QUEUED (available to poll). */
    private val queued = ArrayDeque<Int>()

    /** Indices fully written and confirmed. */
    private val done = HashSet<Int>()

    /** Count of chunks currently held by a worker (polled, not yet done/requeued). */
    private var inFlight = 0

    /**
     * Per-chunk failed-attempt counter. Persists across requeues so a chunk that bounces
     * between workers/hosts still fails the whole download once it exhausts MAX_ATTEMPTS — the
     * same failure semantics as the static per-segment loop, just tracked on the queue so a
     * requeued chunk carries its tally instead of resetting.
     */
    private val attempts = HashMap<Int, Int>()

    // ---- diagnostic / test counters (monotonic) -----------------------------
    private var claims = 0L
    private var requeues = 0L
    private var dones = 0L

    init {
        // Every chunk starts queued, in index order.
        for (c in all) queued.add(c.index)
    }

    val count: Int get() = all.size

    /**
     * Claim the next queued chunk, or null if none is currently available. A null return does
     * NOT mean "done" — chunks may still be in flight and could be requeued; callers must check
     * [allDone]/[shouldKeepWorking]. Increments inFlight and the claims counter.
     */
    fun poll(): Chunk? = synchronized(lock) {
        val idx = queued.removeFirstOrNull() ?: return null
        inFlight++
        claims++
        all[idx]
    }

    /**
     * Return the WHOLE chunk to the queue after a failure/stall. Idempotent re-download:
     * the worker will overwrite the same absolute range. Rejected (no-op) if the chunk is
     * already done — a done chunk must never re-enter the queue (the done+queued invariant).
     */
    fun requeue(chunk: Chunk): Unit = synchronized(lock) {
        if (chunk.index in done) return  // never resurrect a completed chunk
        // Only a chunk that was in flight can be requeued; guard the counter.
        if (inFlight > 0) inFlight--
        requeues++
        // Re-add to the tail so other workers can steal it (work-stealing fairness).
        if (chunk.index !in queued) queued.add(chunk.index)
    }

    /**
     * Record one failed attempt on [chunk] and decide its fate. If the chunk has now exhausted
     * [maxAttempts] it is NOT requeued and this returns true (the caller should fail the whole
     * download). Otherwise the WHOLE chunk is requeued for any worker and this returns false.
     * Already-done chunks are never requeued and never report exhaustion.
     */
    fun requeueOrFail(chunk: Chunk, maxAttempts: Int): Boolean = synchronized(lock) {
        if (chunk.index in done) {
            if (inFlight > 0) inFlight--
            return false
        }
        val tries = (attempts[chunk.index] ?: 0) + 1
        attempts[chunk.index] = tries
        if (inFlight > 0) inFlight--
        if (tries >= maxAttempts) {
            // Exhausted: leave it OUT of the queue; the caller throws and the whole download fails.
            return true
        }
        requeues++
        if (chunk.index !in queued) queued.add(chunk.index)
        return false
    }

    /** Failed attempts recorded for [index] so far (test/diagnostic). */
    fun attemptsOf(index: Int): Int = synchronized(lock) { attempts[index] ?: 0 }

    /**
     * Mark a chunk DONE after its FULL range was written and verified. Returns true if this
     * call transitioned the chunk to done; false if the index is unknown or was already done
     * (in which case in-flight accounting is left untouched). Decrements inFlight on success.
     */
    fun markDone(chunk: Chunk): Boolean = synchronized(lock) {
        if (chunk.index < 0 || chunk.index >= all.size) return false
        if (chunk.index in done) return false
        // A done chunk must not also be queued.
        queued.remove(chunk.index)
        done.add(chunk.index)
        if (inFlight > 0) inFlight--
        dones++
        true
    }

    /** Number of chunks not yet done (queued + in-flight). */
    fun remaining(): Int = synchronized(lock) { all.size - done.size }

    /** Number of chunks available to poll RIGHT NOW (queued, not in flight). */
    fun unclaimed(): Int = synchronized(lock) { queued.size }

    /** Chunks currently held by workers. */
    fun inFlightCount(): Int = synchronized(lock) { inFlight }

    /** True iff EVERY chunk index 0..count-1 is done. The completion gate asserts this. */
    fun allDone(): Boolean = synchronized(lock) { done.size == all.size }

    /**
     * True while a worker should keep looping: either work is queued, or other workers still
     * hold in-flight chunks that might be requeued. Workers exit only when the queue is empty
     * AND no chunks are in flight (and therefore everything is done, or has failed out).
     */
    fun shouldKeepWorking(): Boolean = synchronized(lock) { queued.isNotEmpty() || inFlight > 0 }

    /** True iff this exact index is done. */
    fun isDone(index: Int): Boolean = synchronized(lock) { index in done }

    // ---- test/diagnostic accessors -----------------------------------------
    fun claimsCount(): Long = synchronized(lock) { claims }
    fun requeuesCount(): Long = synchronized(lock) { requeues }
    fun donesCount(): Long = synchronized(lock) { dones }
}

/**
 * Thread-safe live scoreboard of host throughput for adaptive host selection.
 *
 * Each host maps to {ewmaBytesPerMs, lastStallMs, inFlightCount}. On chunk completion the
 * EWMA moves toward the observed rate (ewma = ewma*0.7 + observed*0.3). On stall/error the
 * host is put on cooldown ([HOST_COOLDOWN_MS]). A free worker [pickHost]s among NON-cooled
 * hosts by EWMA-weighted choice WITH a light inFlight load penalty so all workers don't pile
 * onto the single fastest node (Internet Archive throttles that). If every host is cooled it
 * falls back to the least-recently-stalled one — it NEVER returns null when >=1 host exists.
 *
 * Pure logic: a [nowProvider] is injected so tests control time deterministically, and a
 * [rng] seam makes the weighted pick reproducible. All state under an intrinsic lock.
 */
internal class HostScoreboard(
    hosts: List<String>,
    seedEwma: Map<String, Double> = emptyMap(),
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val rng: (Double) -> Double = { bound -> Math.random() * bound }
) {
    private class HostState(
        var ewmaBytesPerMs: Double,
        var lastStallMs: Long,
        var inFlight: Int
    )

    private val lock = Any()
    private val states = LinkedHashMap<String, HostState>()

    init {
        // Seed EWMA from the one-time rankHosts() probe where available; default to a small
        // positive prior so an unseeded host is still pickable (avoids divide-by-zero weighting).
        for (h in hosts) {
            val seed = seedEwma[h]?.takeIf { it > 0.0 } ?: DEFAULT_SEED
            states[h] = HostState(ewmaBytesPerMs = seed, lastStallMs = NEVER_STALLED, inFlight = 0)
        }
    }

    /**
     * Whether [host] is currently cooling down after a recent stall/error.
     *
     * [NEVER_STALLED] (Long.MIN_VALUE) is the "fresh host" sentinel — treated as never cooled.
     * Special-casing it also avoids the `now - Long.MIN_VALUE` overflow that would otherwise make
     * a brand-new host look cooled when the clock is near zero (e.g. in unit tests).
     */
    private fun isCooled(s: HostState, now: Long): Boolean {
        if (s.lastStallMs == NEVER_STALLED) return false
        return now - s.lastStallMs < AdaptiveEngine.HOST_COOLDOWN_MS
    }

    /**
     * Pick a host for a free worker, or null only when there are NO hosts at all.
     *
     * Selection: among non-cooled hosts, weight ~ ewma / (1 + inFlight) so faster hosts are
     * preferred but a host already saturated with in-flight chunks is de-emphasised (load
     * spreading). If all hosts are cooled, return the least-recently-stalled one so progress
     * never wedges. The chosen host's inFlight is incremented; the caller MUST pair this with
     * [releaseHost] (or [recordSuccess]/[recordStall], which both release) in a finally.
     */
    fun pickHost(): String? = synchronized(lock) {
        if (states.isEmpty()) return null
        val now = nowProvider()
        val live = states.entries.filter { !isCooled(it.value, now) }
        val chosenKey: String = if (live.isNotEmpty()) {
            weightedPick(live)
        } else {
            // All cooled: least-recently-stalled (smallest lastStallMs) gets the next try.
            states.entries.minByOrNull { it.value.lastStallMs }!!.key
        }
        states[chosenKey]!!.inFlight++
        chosenKey
    }

    /** EWMA-weighted random pick with a light inFlight load penalty. */
    private fun weightedPick(live: List<Map.Entry<String, HostState>>): String {
        var totalWeight = 0.0
        val weights = DoubleArray(live.size)
        for (i in live.indices) {
            val s = live[i].value
            // ewma is bytes/ms (>0 by construction); divide by load+1 so a busy host is
            // less likely to be chosen again. Never zero, so every live host stays eligible.
            val w = (s.ewmaBytesPerMs / (1.0 + s.inFlight)).coerceAtLeast(MIN_WEIGHT)
            weights[i] = w
            totalWeight += w
        }
        var r = rng(totalWeight)
        for (i in live.indices) {
            r -= weights[i]
            if (r <= 0.0) return live[i].key
        }
        return live.last().key  // floating-point guard
    }

    /** Release a previously-picked host without recording a sample (e.g. cancellation). */
    fun releaseHost(host: String): Unit = synchronized(lock) {
        states[host]?.let { if (it.inFlight > 0) it.inFlight-- }
    }

    /**
     * Record a successful chunk completion on [host]: move its EWMA toward [observedBytesPerMs]
     * and release its in-flight slot. Observed rates <= 0 are ignored for the EWMA (but the
     * slot is still released) so a zero-time sample can't poison the score.
     */
    fun recordSuccess(host: String, observedBytesPerMs: Double): Unit = synchronized(lock) {
        val s = states[host] ?: return
        if (observedBytesPerMs > 0.0) {
            s.ewmaBytesPerMs =
                s.ewmaBytesPerMs * AdaptiveEngine.EWMA_KEEP +
                observedBytesPerMs * (1.0 - AdaptiveEngine.EWMA_KEEP)
        }
        if (s.inFlight > 0) s.inFlight--
    }

    /** Record a stall/error on [host]: start its cooldown and release its in-flight slot. */
    fun recordStall(host: String): Unit = synchronized(lock) {
        val s = states[host] ?: return
        s.lastStallMs = nowProvider()
        if (s.inFlight > 0) s.inFlight--
    }

    /** Best live (non-cooled) EWMA right now, or 0.0 if every host is cooled / none exist. */
    fun bestLiveEwma(): Double = synchronized(lock) {
        val now = nowProvider()
        states.values.filter { !isCooled(it, now) }
            .maxOfOrNull { it.ewmaBytesPerMs } ?: 0.0
    }

    // ---- test/diagnostic accessors -----------------------------------------
    fun ewmaOf(host: String): Double = synchronized(lock) { states[host]?.ewmaBytesPerMs ?: 0.0 }
    fun inFlightOf(host: String): Int = synchronized(lock) { states[host]?.inFlight ?: 0 }
    fun isCooledNow(host: String): Boolean = synchronized(lock) {
        states[host]?.let { isCooled(it, nowProvider()) } ?: false
    }

    companion object {
        /** Small positive prior for an unseeded host: ~0.05 MB/ms is meaningless in absolute
         *  terms but keeps the host pickable and lets the EWMA converge to reality quickly. */
        private const val DEFAULT_SEED = 50.0
        /** Floor so a live host's weight is never exactly zero. */
        private const val MIN_WEIGHT = 1e-6
        /** Sentinel for "this host has never stalled" — treated as not cooled (overflow-safe). */
        private const val NEVER_STALLED = Long.MIN_VALUE
    }
}
