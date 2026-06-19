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

    /**
     * Soft stall (Fix B): a chunk reading below this fraction of the CURRENT baseline is "slow".
     * Deliberately conservative — on a shared pipe most chunks legitimately run at 40-70% of the
     * baseline, so the fraction is well below that band. Only a genuinely dead/throttled chunk
     * (sustained, see [SOFT_STALL_WINDOWS]) trips it.
     */
    const val SOFT_STALL_FRACTION: Double = 0.15

    /**
     * Soft stall must be SUSTAINED (Fix B): a chunk must read below the threshold for this many
     * CONSECUTIVE measurement windows before it migrates. A single slow instantaneous sample
     * (normal contention jitter) is ignored.
     */
    const val SOFT_STALL_WINDOWS: Int = 3

    /**
     * Soft-stall guardrail (Fix B): the recent-rate baseline is only trustworthy once at least
     * this many completed-chunk samples exist. Below this, soft-stall is DISABLED (hard-stall
     * only) — the dangerous heuristic stays inert until it has real data.
     */
    const val SOFT_STALL_MIN_SAMPLES: Int = 4

    /** Ring-buffer capacity for the recent completed-chunk throughput baseline (Fix B). */
    const val RECENT_RATE_WINDOW: Int = 16

    /** A host that HARD-stalls/errors is on cooldown (skipped during selection) for this long. */
    const val HOST_COOLDOWN_MS: Long = 15_000L

    /**
     * Soft-stall host demotion (Fix C): a soft stall does NOT cool the host. Instead its EWMA is
     * scaled by this factor so it is mildly de-preferred without the all-cooled funnel a 15s
     * cooldown would cause. > 0 and < 1.
     */
    const val SOFT_DEMOTE_FACTOR: Double = 0.85

    /** EWMA smoothing: new = old*EWMA_KEEP + observed*(1-EWMA_KEEP). */
    const val EWMA_KEEP: Double = 0.7

    /** Real transport-error budget per chunk (Fix A): non-206, short read, IOException, timeout. */
    const val MAX_ERROR_ATTEMPTS: Int = 5

    /**
     * Stall-requeue budget per chunk (Fix A): soft/hard stall migrations. Much larger than the
     * error budget — a stall never fails the download by itself, it just re-queues the chunk for
     * another worker/host. The cap is a sanity bound against a pathological infinite-migration
     * loop, not an error threshold.
     */
    const val MAX_STALL_REQUEUES: Int = 50

    // ===================================================================================
    // v2 SPEED LEVERS — OVER-PROVISIONING + TAIL-CHUNK RACING.
    //
    // v1 kept exactly `segments` workers busy and so never opened MORE connections than the
    // nominal segment count, even when the global 24-connection budget was free. On the Internet
    // Archive a single connection to one node is throttled, but pulling from MANY fast nodes at
    // once is not — so the real speedup is to (1) open more connections from more nodes when the
    // budget allows, and (2) kill the "waiting on 1-2 slow chunks at 99%" tail by racing the last
    // few in-flight chunks on a second, different host. BOTH stay strictly under the global
    // connectionBudget Semaphore (the #1 safety invariant: total acquired permits <= 24 always).
    // ===================================================================================

    /**
     * OVER-PROVISION cap (v2). The adaptive worker pool may spin up to this many workers for a
     * single file, regardless of the nominal `segments` hint — bounded additionally by the chunk
     * count (no point exceeding chunks) and, at runtime, by the shared connectionBudget Semaphore
     * (every worker acquire()s a permit, so the GLOBAL 24-cap is never exceeded no matter how many
     * workers exist). This lets ONE big file pull from more nodes in parallel when the budget is
     * free, while still yielding permits to other concurrent files via the same Semaphore. Set to
     * the global connection ceiling so a single file can, when alone, use the whole budget.
     */
    const val MAX_ADAPTIVE_WORKERS: Int = 24

    /**
     * TAIL-RACING gate (v2): racing only kicks in for the long TAIL of a file — when there is
     * NOTHING left to start fresh (queue.unclaimed() == 0) AND only this few chunks are still
     * in-flight. Early in a download there's plenty of fresh work, so an idle worker should claim
     * a new chunk, never race. Small so we don't waste a connection racing when real work remains.
     */
    const val RACE_TAIL_THRESHOLD: Int = 3

    /**
     * Minimum wall-clock a tail chunk must have been in-flight before it is eligible to be RACED
     * (v2). A chunk that just started is not the "slow tail" — give it a beat to make progress on
     * its own before a second worker dials a duplicate. Keeps racing targeted at genuinely-slow
     * tail chunks, not freshly-claimed ones.
     */
    const val RACE_MIN_INFLIGHT_MS: Long = 1500L

    /**
     * Max simultaneous EXTRA racers per in-flight tail chunk (v2). 1 means a raced chunk runs on at
     * most TWO connections total (the original worker + one racer) — enough to dodge a single slow
     * node without multiplying connection pressure. The global Semaphore caps the absolute total
     * regardless; this just bounds per-chunk duplication.
     */
    const val MAX_RACERS_PER_CHUNK: Int = 1

    // ===================================================================================
    // v3 LANED ENGINE — HOST-PINNED WARM-CONNECTION LANES.
    //
    // MEASURED on a real on-device IA connection sweep:
    //   - IA serves HTTP/2 end-to-end; OkHttp 4.12 negotiates h2.
    //   - Each item is served from 2 GENUINELY INDEPENDENT mirror datacenters (ia6xxxxx +
    //     ia8xxxxx; sometimes a 3rd dn7xxxxx.ca node). The live list comes from metadata's
    //     workable_servers / alternate_locations.workable.
    //   - Per-connection throttle ~1.2 MB/s; per-host ceiling ~2 MB/s. Past ~4 connections to ONE
    //     host, IA SHEDS LOAD (we saw 3/8 conns fail). So never exceed ~4 streams/host.
    //   - THE KEY LEVER: spreading across the 2 independent datacenters nearly DOUBLES throughput
    //     (4 conns one host = 2.00 MB/s; 2+2 split across 2 hosts = 3.86 MB/s, +93% for the SAME
    //     connection count). SPREAD is the lever, not raw count.
    //   - The v1/v2 adaptive engine re-picked the host per 8 MB chunk, so OkHttp could not reuse a
    //     warm h2 connection — every chunk paid the TCP/TLS handshake + slow-start tax (measured 8x
    //     median-vs-peak gap). The laned engine PINS each lane runner to one host for the file's
    //     whole duration, so OkHttp reuses the SAME warm h2 connection for every chunk on that lane
    //     (the connection pool keep-alive must outlive the brief gaps between chunks — see
    //     AppModule's widened ConnectionPool). This kills slow-start.
    // ===================================================================================

    /**
     * PREFERRED streams (warm h2 lane runners) per mirror host (v3). The measured stable sweet spot
     * is ~2/host: two connections nearly saturate a host's ~2 MB/s ceiling, and the throughput win
     * comes from SPREADING these pairs across the independent datacenters, not from piling more onto
     * one host. [planLanes] aims for this per host, shrinking only if the global budget can't fund it.
     */
    const val PREFERRED_STREAMS_PER_HOST: Int = 2

    /**
     * HARD cap on streams to a SINGLE host (v3). IA sheds load past ~4 connections to one host
     * (measured: 3/8 failed). [planLanes] never assigns more than this to any one host even when
     * there is only one mirror and budget to spare — going past it COSTS throughput, it doesn't add.
     */
    const val MAX_STREAMS_PER_HOST: Int = 4
}

/**
 * One LANE in the laned engine (v3): a host plus the number of warm, host-PINNED stream runners to
 * open against it. Each runner reuses ONE warm h2 connection for every chunk it pulls (no per-chunk
 * host re-pick), so OkHttp's connection pool serves all of that runner's chunks off the same socket.
 *
 * [streams] is how many concurrent runners pin THIS host. The sum of [streams] across all lanes is
 * the file's nominal warm-stream count; at runtime every runner still acquires a shared
 * connectionBudget permit before opening its socket, so the global 24-cap holds regardless.
 */
internal data class Lane(val host: String, val streams: Int)

/**
 * Plan the laned topology for a SINGLE file (v3) — PURE, Android-free, unit-testable.
 *
 * Given the distinct workable mirror hosts and the global connection [budget], return one [Lane] per
 * host with a streams-per-host count that:
 *   - prefers [AdaptiveEngine.PREFERRED_STREAMS_PER_HOST] (~2) per host — the measured sweet spot,
 *   - never exceeds [AdaptiveEngine.MAX_STREAMS_PER_HOST] (~4) per host — past that IA sheds load,
 *   - keeps Σ streams ≤ [budget] (the global 24-cap, shared across files via the Semaphore),
 *   - SPREADS across ALL available mirrors before deepening any one (never piles all streams on a
 *     single host when >1 mirror exists — spread is the throughput lever),
 *   - returns at least 1 stream total whenever ≥1 host and budget ≥1 exist.
 *
 * Allocation is breadth-first by rounds: round 1 gives every host its 1st stream, round 2 its 2nd,
 * etc., stopping at the per-host PREFERENCE and at the global budget. Breadth-first is what
 * guarantees the spread invariant — with 2 hosts and budget 4 you get 2+2, never 4+0.
 *
 * For the MULTI-mirror case the preference (~2/host) IS the target — spread across the independent
 * datacenters is the throughput lever, NOT raw depth, and going past ~2/host adds connections that
 * just split a host's ~2 MB/s ceiling. Only the SINGLE-mirror case deepens past the preference (up
 * to the per-host hard cap) so a lone-mirror file still gets a few warm streams instead of one.
 *
 * @param allowDeepen when true AND there is exactly one mirror, that lone lane may grow past the
 *   preference up to [maxPerHost]. For the multi-mirror case the preference is always the target
 *   (deepening is suppressed) — spread, don't pile on. Default true.
 */
internal fun planLanes(
    mirrors: List<String>,
    budget: Int,
    preferredPerHost: Int = AdaptiveEngine.PREFERRED_STREAMS_PER_HOST,
    maxPerHost: Int = AdaptiveEngine.MAX_STREAMS_PER_HOST,
    allowDeepen: Boolean = true
): List<Lane> {
    val distinct = mirrors.distinct().filter { it.isNotBlank() }
    if (distinct.isEmpty() || budget <= 0) return emptyList()
    val cap = budget.coerceAtLeast(1)
    val pref = preferredPerHost.coerceIn(1, maxPerHost)
    val hardPerHost = maxPerHost.coerceAtLeast(1)

    // Streams assigned to each host, indexed alongside `distinct`. Start at 0; fill breadth-first.
    val streams = IntArray(distinct.size)
    var assigned = 0

    // PHASE 1: breadth-first up to the PREFERRED per-host count. Each round adds one stream to every
    // host that is still under the preference, so spread always wins over depth (2 hosts, budget 4
    // -> 2+2). Stop when the budget is exhausted or every host reached the preference.
    var round = 0
    while (assigned < cap && round < pref) {
        var progressed = false
        for (i in distinct.indices) {
            if (assigned >= cap) break
            if (streams[i] < pref) {
                streams[i]++
                assigned++
                progressed = true
            }
        }
        if (!progressed) break
        round++
    }

    // PHASE 2 (deepen) — SINGLE-MIRROR ONLY. With just one workable mirror, spreading isn't possible,
    // so let that lone lane grow past the preference up to the per-host HARD cap (~4) — but NEVER
    // beyond it (past ~4/host IA sheds load). For the multi-mirror case we deliberately STOP at the
    // preference: more depth on an already-spread plan just splits a host's ceiling, it doesn't add
    // throughput (the measured lever is spread across datacenters, not raw count).
    if (allowDeepen && distinct.size == 1) {
        while (assigned < cap && streams[0] < hardPerHost) {
            streams[0]++
            assigned++
        }
    }

    val lanes = ArrayList<Lane>(distinct.size)
    for (i in distinct.indices) {
        if (streams[i] > 0) lanes.add(Lane(distinct[i], streams[i]))
    }
    return lanes
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
internal class ChunkQueue(
    chunks: List<Chunk>,
    /** Injected clock so tail-race timing is deterministic in tests. */
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()

    /** Immutable partition, indexed by chunk.index for O(1) lookups. */
    private val all: List<Chunk> = chunks.sortedBy { it.index }

    /** FIFO of chunk indices currently QUEUED (available to poll). */
    private val queued = ArrayDeque<Int>()

    /** Indices fully written and confirmed. */
    private val done = HashSet<Int>()

    /**
     * v2 TAIL-RACING bookkeeping. For each chunk index that has a PRIMARY worker in flight, when
     * it was claimed (millis) and how many EXTRA racers are currently dialled on it. Used by
     * [pickRaceTarget] to choose the longest-running tail chunk on a DIFFERENT host and to cap the
     * per-chunk racer count. A racer does NOT create an entry here — only the primary [poll] does;
     * racers bump [racers] on the existing entry. The entry is cleared on done/requeue/fail/heal.
     *
     * This is pure bookkeeping layered on top of the existing inFlight counter: [inFlight] still
     * counts PRIMARY claims only (so the accounting identity claims-requeues-dones == inFlight is
     * preserved — racers are tracked separately and never touch that identity).
     */
    private class InFlightInfo(val claimedAtMs: Long, var racers: Int)
    private val inFlightInfo = HashMap<Int, InFlightInfo>()

    /**
     * Terminal FAILED state (Fix D): indices that exhausted [AdaptiveEngine.MAX_ERROR_ATTEMPTS]
     * real transport errors. An explicit terminal condition so [allFailed]/[shouldKeepWorking]
     * have a definite answer instead of relying on a thrown exception racing other workers. The
     * download throws iff this set is non-empty; it completes iff [done] covers every index.
     */
    private val failed = HashSet<Int>()

    /** Count of chunks currently held by a worker (polled, not yet done/requeued). */
    private var inFlight = 0

    /**
     * Per-chunk REAL-error counter (Fix A). Only genuine transport failures (non-206, short read,
     * IOException, timeout) increment this. Persists across requeues so a chunk that bounces
     * between workers/hosts still fails once it exhausts [AdaptiveEngine.MAX_ERROR_ATTEMPTS] —
     * the same terminal semantics as the static per-segment loop.
     */
    private val errorAttempts = HashMap<Int, Int>()

    /**
     * Per-chunk STALL-requeue counter (Fix A). Soft/hard stall migrations increment this, NOT
     * [errorAttempts]. A stall never fails the whole download — it just re-queues the chunk for
     * another worker/host. Capped only by [AdaptiveEngine.MAX_STALL_REQUEUES] as a sanity bound.
     */
    private val stallRequeues = HashMap<Int, Int>()

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
        // v2: record when this PRIMARY claim went in-flight so [pickRaceTarget] can find the
        // longest-running tail chunk. A requeued chunk re-polled later gets a fresh timestamp.
        inFlightInfo[idx] = InFlightInfo(claimedAtMs = nowProvider(), racers = 0)
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
        inFlightInfo.remove(chunk.index)  // v2: clear tail-race tracking for this primary
        requeues++
        // Re-add to the tail so other workers can steal it (work-stealing fairness).
        if (chunk.index !in queued) queued.add(chunk.index)
    }

    /**
     * Record one REAL transport error on [chunk] and decide its fate (Fix A — error budget).
     *
     * Increments ONLY the [errorAttempts] counter. If the chunk has now exhausted [maxAttempts]
     * (default [AdaptiveEngine.MAX_ERROR_ATTEMPTS]) it is moved to the terminal [failed] state,
     * left OUT of the queue, and this returns true — the caller throws and the whole download
     * fails. Otherwise the WHOLE chunk is requeued for any worker and this returns false.
     * Already-done chunks are never requeued and never report exhaustion.
     *
     * Stalls must NOT call this — they go through [requeueStall], which uses a separate, far
     * larger budget and can never fail the download.
     */
    fun recordErrorOrFail(
        chunk: Chunk,
        maxAttempts: Int = AdaptiveEngine.MAX_ERROR_ATTEMPTS
    ): Boolean = synchronized(lock) {
        if (chunk.index in done) {
            if (inFlight > 0) inFlight--
            inFlightInfo.remove(chunk.index)
            return false
        }
        val tries = (errorAttempts[chunk.index] ?: 0) + 1
        errorAttempts[chunk.index] = tries
        if (inFlight > 0) inFlight--
        inFlightInfo.remove(chunk.index)  // v2: clear tail-race tracking for this primary
        if (tries >= maxAttempts) {
            // Exhausted real-error budget: mark terminal FAILED and leave it OUT of the queue.
            // shouldKeepWorking()/allFailed() now report a definite condition; the caller throws.
            failed.add(chunk.index)
            return true
        }
        requeues++
        if (chunk.index !in queued) queued.add(chunk.index)
        return false
    }

    /**
     * Requeue [chunk] after a SOFT or HARD stall (Fix A — stall budget). Increments only the
     * [stallRequeues] counter, NEVER [errorAttempts], so a stall can NEVER exhaust the error
     * budget and fail the download. The chunk is always re-queued for another worker/host (so it
     * can eventually complete on a faster node), except:
     *  - an already-done chunk is a harmless no-op, and
     *  - if [stallRequeues] somehow exceeds [maxStallRequeues] (a pathological infinite-migration
     *    loop) the chunk is still requeued — a stall must never fail the download — but the cap is
     *    surfaced via [stallRequeuesOf] for diagnostics.
     *
     * Always returns false: a stall never signals download failure. (Boolean kept for symmetry /
     * call-site readability.)
     */
    fun requeueStall(
        chunk: Chunk,
        @Suppress("UNUSED_PARAMETER") maxStallRequeues: Int = AdaptiveEngine.MAX_STALL_REQUEUES
    ): Boolean = synchronized(lock) {
        if (chunk.index in done) {
            if (inFlight > 0) inFlight--
            inFlightInfo.remove(chunk.index)
            return false
        }
        stallRequeues[chunk.index] = (stallRequeues[chunk.index] ?: 0) + 1
        if (inFlight > 0) inFlight--
        inFlightInfo.remove(chunk.index)  // v2: clear tail-race tracking for this primary
        requeues++
        // A stalled chunk ALWAYS goes back to the queue so a different worker/host can complete it.
        if (chunk.index !in queued) queued.add(chunk.index)
        return false
    }

    /** Real transport-error attempts recorded for [index] so far (test/diagnostic). */
    fun errorAttemptsOf(index: Int): Int = synchronized(lock) { errorAttempts[index] ?: 0 }

    /** Stall requeues recorded for [index] so far (test/diagnostic). */
    fun stallRequeuesOf(index: Int): Int = synchronized(lock) { stallRequeues[index] ?: 0 }

    /** True iff [index] reached the terminal FAILED state (exhausted the real-error budget). */
    fun isFailed(index: Int): Boolean = synchronized(lock) { index in failed }

    /**
     * Self-healing reconciliation (Fix D). If [chunk] is still IN-FLIGHT (not done, not failed,
     * not queued) on some exit path that neither marked it done nor requeued it, force it back
     * onto the queue so it can never be orphaned / leak an in-flight slot (which would wedge
     * shouldKeepWorking() true forever and hang the download). A no-op if the chunk is already
     * done, failed, or already queued. Touches NEITHER budget — pure bookkeeping. Returns true
     * iff it actually reconciled a leaked in-flight chunk.
     */
    fun forceRequeue(chunk: Chunk): Boolean = synchronized(lock) {
        if (chunk.index in done) return false
        if (chunk.index in failed) return false
        if (chunk.index in queued) return false
        if (inFlight > 0) inFlight--
        inFlightInfo.remove(chunk.index)  // v2: clear tail-race tracking for this primary
        requeues++
        queued.add(chunk.index)
        return true
    }

    // ---- v2 TAIL-CHUNK RACING ------------------------------------------------

    /**
     * Is the download in its TAIL right now (v2 tail-gating predicate)? True iff there is NOTHING
     * left to start fresh (no queued chunks) AND a small number of chunks are still in-flight
     * (<= [RACE_TAIL_THRESHOLD]) and not yet all done. This is the ONLY window in which racing is
     * allowed: early on there's fresh work to claim, so an idle worker should never race. Pulled
     * out as a pure predicate so it's directly unit-testable.
     */
    fun isInTail(
        raceTailThreshold: Int = AdaptiveEngine.RACE_TAIL_THRESHOLD
    ): Boolean = synchronized(lock) {
        if (done.size == all.size) return false      // already complete — nothing to race
        if (queued.isNotEmpty()) return false        // fresh work exists — claim it, don't race
        if (inFlight <= 0) return false              // nothing in flight to race
        inFlight <= raceTailThreshold
    }

    /**
     * Pick an in-flight TAIL chunk for an idle worker to RACE on a second connection, or null if
     * none is eligible right now. A chunk is eligible iff ALL hold (v2):
     *   - we are in the TAIL ([isInTail]) — no fresh work remains and only a few chunks are left,
     *   - it has been in-flight at least [RACE_MIN_INFLIGHT_MS] (it's a genuinely-slow tail chunk,
     *     not one that was just claimed),
     *   - it has fewer than [maxRacersPerChunk] extra racers already (cap per-chunk duplication),
     *   - it is not done.
     * Among eligible chunks the LONGEST-running (oldest claim) is chosen — the most likely to be
     * the slow tail straggler. On success the chunk's racer count is incremented and the caller
     * MUST pair it with [endRace] in a finally. Returns null (no race) if nothing qualifies, in
     * which case the caller does NOT acquire a connection.
     *
     * Pure bookkeeping: this never touches the inFlight counter, the queue, or any budget — it
     * only bumps a per-chunk racer count. Racing changes nothing about correctness: both the
     * primary and the racer write the SAME absolute range with IDENTICAL server bytes, so
     * last-write-wins is byte-safe and whichever finishes first calls [markDone] (the other's
     * late markDone is a no-op).
     */
    fun pickRaceTarget(
        now: Long = nowProvider(),
        raceTailThreshold: Int = AdaptiveEngine.RACE_TAIL_THRESHOLD,
        minInFlightMs: Long = AdaptiveEngine.RACE_MIN_INFLIGHT_MS,
        maxRacersPerChunk: Int = AdaptiveEngine.MAX_RACERS_PER_CHUNK
    ): Chunk? = synchronized(lock) {
        // Tail gate, inlined (we already hold the lock).
        if (done.size == all.size) return null
        if (queued.isNotEmpty()) return null
        if (inFlight <= 0 || inFlight > raceTailThreshold) return null
        var bestIdx = -1
        var bestClaimedAt = Long.MAX_VALUE
        for ((idx, info) in inFlightInfo) {
            if (idx in done) continue
            if (info.racers >= maxRacersPerChunk) continue
            if (now - info.claimedAtMs < minInFlightMs) continue
            if (info.claimedAtMs < bestClaimedAt) {
                bestClaimedAt = info.claimedAtMs
                bestIdx = idx
            }
        }
        if (bestIdx < 0) return null
        inFlightInfo[bestIdx]!!.racers++   // reserve a racer slot; caller pairs with endRace
        all[bestIdx]
    }

    /**
     * Release a racer slot reserved by [pickRaceTarget] (v2). Called in the racer's finally on
     * EVERY exit path (won, lost, cancelled, errored). A no-op if the primary already cleared the
     * in-flight entry (chunk completed/requeued) — racer accounting can never leak. Never touches
     * the inFlight counter or any budget.
     */
    fun endRace(chunk: Chunk): Unit = synchronized(lock) {
        inFlightInfo[chunk.index]?.let { if (it.racers > 0) it.racers-- }
    }

    /** Active extra-racer count on [index] (test/diagnostic). */
    fun racersOf(index: Int): Int = synchronized(lock) { inFlightInfo[index]?.racers ?: 0 }

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
        inFlightInfo.remove(chunk.index)  // v2: clear tail-race tracking for this primary
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

    /** True iff at least one chunk reached the terminal FAILED state (Fix D). */
    fun anyFailed(): Boolean = synchronized(lock) { failed.isNotEmpty() }

    /** Indices in the terminal FAILED state (test/diagnostic). */
    fun failedIndices(): Set<Int> = synchronized(lock) { failed.toSet() }

    /**
     * True while a worker should keep looping (Fix D — definite terminal conditions): either work
     * is queued, or other workers still hold in-flight chunks that might be requeued. Workers exit
     * only when the queue is empty AND no chunks are in flight — at which point every chunk is in
     * a terminal state (done or failed). A short-circuit on [anyFailed] lets workers stop promptly
     * once the download is doomed rather than draining the rest first.
     */
    fun shouldKeepWorking(): Boolean = synchronized(lock) {
        if (failed.isNotEmpty()) return false
        queued.isNotEmpty() || inFlight > 0
    }

    /** True iff this exact index is done. */
    fun isDone(index: Int): Boolean = synchronized(lock) { index in done }

    // ---- test/diagnostic accessors -----------------------------------------
    fun claimsCount(): Long = synchronized(lock) { claims }
    fun requeuesCount(): Long = synchronized(lock) { requeues }
    fun donesCount(): Long = synchronized(lock) { dones }
}

/**
 * Thread-safe ring buffer of the most-recent completed-chunk throughputs (bytes/ms), and the
 * soft-stall DECISION built on top of it (Fix B).
 *
 * The old soft-stall baseline was the PEAK EWMA of the single fastest host — a number that only
 * ever climbs and never reflects that bandwidth is now shared across ~24 connections. Against a
 * peak, most chunks look "slow" and trip a false-positive storm. This window instead holds the
 * last [capacity] *actually-observed* completed-chunk rates across ALL hosts, so the baseline is
 * a CURRENT, decaying snapshot of real conditions: as the pipe gets shared the recorded rates
 * fall and the baseline falls with them.
 *
 * [baseline] is the MEDIAN of the buffered samples (robust to one fast/slow outlier). The
 * soft-stall decision ([shouldSoftStall]) is deliberately conservative and only fires when ALL
 * of these hold:
 *   - enough samples exist ([AdaptiveEngine.SOFT_STALL_MIN_SAMPLES]) — otherwise the baseline is
 *     untrustworthy and soft-stall is DISABLED (hard-stall only); this is the safety fallback,
 *   - the chunk has run past warm-up ([AdaptiveEngine.MIN_SAMPLE_MS]),
 *   - its rate is below [AdaptiveEngine.SOFT_STALL_FRACTION] × baseline,
 *   - it has been below that bar for [AdaptiveEngine.SOFT_STALL_WINDOWS] CONSECUTIVE windows
 *     (sustained, not a single jittery sample), and
 *   - there is unclaimed work to migrate to (checked by the caller).
 */
internal class RecentRateWindow(
    private val capacity: Int = AdaptiveEngine.RECENT_RATE_WINDOW
) {
    private val lock = Any()
    private val buf = DoubleArray(capacity.coerceAtLeast(1))
    private var size = 0
    private var next = 0

    /** Record one completed-chunk throughput in bytes/ms. Non-positive samples are ignored. */
    fun record(bytesPerMs: Double): Unit = synchronized(lock) {
        if (bytesPerMs <= 0.0) return
        buf[next] = bytesPerMs
        next = (next + 1) % buf.size
        if (size < buf.size) size++
    }

    /** Number of samples currently buffered (caps at [capacity]). */
    fun sampleCount(): Int = synchronized(lock) { size }

    /**
     * Bench-only: a snapshot copy of all currently-buffered per-chunk throughput samples
     * (bytes/ms) in ring-insertion order. Used at download completion to compute median
     * and peak rates for the diagnostic log line. Returns an empty array when empty.
     * Thread-safe: holds [lock] while copying, so no concurrent [record] can interleave.
     */
    fun snapshotSamples(): DoubleArray = synchronized(lock) {
        if (size == 0) return DoubleArray(0)
        DoubleArray(size) { i -> buf[i] }
    }

    /**
     * Median of the buffered completed-chunk rates (bytes/ms), or 0.0 when empty. Median (not
     * mean) so one very fast or very slow chunk can't skew the baseline.
     */
    fun baseline(): Double = synchronized(lock) {
        if (size == 0) return 0.0
        val snap = DoubleArray(size) { buf[it] }
        snap.sort()
        return if (size % 2 == 1) snap[size / 2]
        else (snap[size / 2 - 1] + snap[size / 2]) / 2.0
    }

    /**
     * Pure soft-stall decision (Fix B), excluding the "unclaimed work waiting" precondition which
     * the caller checks separately (it needs the live queue). Returns true iff a chunk reading
     * [chunkRate] bytes/ms after [elapsedMs] should be treated as soft-stalled.
     *
     * Returns false (soft-stall DISABLED) whenever the baseline isn't yet trustworthy — fewer
     * than [AdaptiveEngine.SOFT_STALL_MIN_SAMPLES] samples. This is the guardrail that makes the
     * heuristic inert until it has real data; the conservative no-migration behaviour is the
     * automatic fallback.
     */
    fun shouldSoftStall(
        chunkRate: Double,
        elapsedMs: Long,
        consecutiveSlowWindows: Int
    ): Boolean = synchronized(lock) {
        if (size < AdaptiveEngine.SOFT_STALL_MIN_SAMPLES) return false      // baseline untrustworthy
        if (elapsedMs < AdaptiveEngine.MIN_SAMPLE_MS) return false           // still warming up
        if (consecutiveSlowWindows < AdaptiveEngine.SOFT_STALL_WINDOWS) return false  // not sustained
        val base = baselineLocked()
        if (base <= 0.0) return false
        return chunkRate < AdaptiveEngine.SOFT_STALL_FRACTION * base
    }

    /** Is [chunkRate] below the soft-stall bar right now (for counting consecutive slow windows)? */
    fun isBelowBar(chunkRate: Double): Boolean = synchronized(lock) {
        if (size < AdaptiveEngine.SOFT_STALL_MIN_SAMPLES) return false
        val base = baselineLocked()
        if (base <= 0.0) return false
        return chunkRate < AdaptiveEngine.SOFT_STALL_FRACTION * base
    }

    /** Caller already holds [lock]. */
    private fun baselineLocked(): Double {
        if (size == 0) return 0.0
        val snap = DoubleArray(size) { buf[it] }
        snap.sort()
        return if (size % 2 == 1) snap[size / 2]
        else (snap[size / 2 - 1] + snap[size / 2]) / 2.0
    }
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

    /**
     * Record a HARD stall or a real transport error on [host]: start its [HOST_COOLDOWN_MS]
     * cooldown and release its in-flight slot. Only unambiguous events (zero bytes for the stall
     * timeout, non-206, short read, IOException) call this — they're real signals that the host
     * is unhealthy, so cooling it is correct.
     */
    fun recordStall(host: String): Unit = synchronized(lock) {
        val s = states[host] ?: return
        s.lastStallMs = nowProvider()
        if (s.inFlight > 0) s.inFlight--
    }

    /**
     * Record a SOFT stall on [host] (Fix C): the host is merely slower than the current baseline,
     * NOT broken. It must NOT go on cooldown — cooling every soft-stalled mirror funnels all 24
     * workers onto the one least-recently-stalled host, which IA then throttles, producing more
     * soft stalls (the positive-feedback loop). Instead we lightly DEMOTE its EWMA by
     * [SOFT_DEMOTE_FACTOR] so it's mildly de-preferred by [pickHost] but stays fully eligible,
     * and release its in-flight slot. No [lastStallMs] update -> never cooled.
     */
    fun recordSoftStall(host: String): Unit = synchronized(lock) {
        val s = states[host] ?: return
        s.ewmaBytesPerMs = (s.ewmaBytesPerMs * AdaptiveEngine.SOFT_DEMOTE_FACTOR)
            .coerceAtLeast(MIN_WEIGHT)
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
