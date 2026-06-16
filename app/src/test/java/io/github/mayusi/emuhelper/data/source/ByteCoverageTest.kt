package io.github.mayusi.emuhelper.data.source

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE corruption guard for the adaptive (chunk-queue work-stealing) engine.
 *
 * It models exactly what the real worker loop does — minus the network — against an in-memory
 * "source" byte array of known content. N concurrent worker THREADS drain a shared [ChunkQueue];
 * "downloading" a chunk means copying that absolute byte range from the source into a shared
 * output array via idempotent absolute-offset writes, with INJECTED random failures that abort a
 * chunk MID-WAY and requeue the WHOLE chunk (the same idempotent-overwrite property the engine
 * relies on). A chunk is marked DONE only after its FULL range is copied.
 *
 * The invariant proven here: no matter how the work is stolen, retried, or partially written and
 * then requeued, the reconstructed output equals the source at EVERY offset. If the work-stealing
 * model could ever corrupt bytes (a gap, an overlap onto a neighbour, a partial-then-done), this
 * test would catch it.
 */
class ByteCoverageTest {

    /**
     * Run the simulation once for a given (size, chunkSize, workerCount, failureRate, seed) and
     * return the reconstructed output array. The harness mirrors the engine's correctness rules:
     *   - writes are absolute-offset (raf.seek(start)) idempotent overwrites,
     *   - a chunk is DONE only when its full range was written (short copy -> requeue),
     *   - on injected failure the WHOLE chunk is requeued for any worker,
     *   - workers exit only when the queue is drained AND nothing is in flight.
     */
    private fun simulate(
        size: Int,
        chunkSize: Long,
        workerCount: Int,
        failureRate: Double,
        seed: Long
    ): ByteArray {
        // Deterministic "source" content: each byte is a function of its absolute offset, so a
        // misplaced byte (wrong offset) is detectable, not just a missing one.
        val source = ByteArray(size) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        val output = ByteArray(size) { 0 }

        val queue = ChunkQueue(partitionChunks(size.toLong(), chunkSize))
        val failure = AtomicReference<Throwable?>(null)
        val ready = CountDownLatch(workerCount)
        val go = CountDownLatch(1)
        // Each worker gets its own RNG derived from the shared seed for reproducibility.
        val workerSeed = AtomicInteger(0)

        val threads = (0 until workerCount).map {
            Thread {
                val rnd = Random(seed + workerSeed.getAndIncrement() * 1009L)
                ready.countDown()
                go.await()
                try {
                    while (queue.shouldKeepWorking()) {
                        val chunk = queue.poll()
                        if (chunk == null) {
                            // No claimable work right now but peers may requeue; yield and retry.
                            Thread.yield()
                            continue
                        }
                        val start = chunk.start.toInt()
                        val len = chunk.length.toInt()

                        // Decide up front whether this attempt will fail partway through.
                        val willFail = rnd.nextDouble() < failureRate
                        // If failing, abort after copying a random prefix (0..len-1 bytes).
                        val abortAfter = if (willFail) rnd.nextInt(len) else len

                        var written = 0
                        var i = 0
                        while (i < len) {
                            if (willFail && written >= abortAfter) break
                            // Idempotent absolute-offset write: output[start + i] = source[start + i].
                            // Copy in small bursts to interleave with other threads.
                            val burst = minOf(1 + rnd.nextInt(64), len - i)
                            System.arraycopy(source, start + i, output, start + i, burst)
                            i += burst
                            written += burst
                        }

                        // DONE only if the FULL range was written; otherwise requeue the WHOLE chunk.
                        if (written == len) {
                            queue.markDone(chunk)
                        } else {
                            // requeueOrFail with a generous cap so the sim always converges; the
                            // point is correctness under churn, not exercising the failure throw.
                            val exhausted = queue.requeueOrFail(chunk, maxAttempts = 10_000)
                            if (exhausted) error("chunk ${chunk.index} unexpectedly exhausted")
                        }
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }

        threads.forEach { it.isDaemon = true; it.start() }
        // Wait until all workers are parked at the gate, then release them simultaneously to
        // maximise real contention on the shared queue and output array.
        assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS), "workers failed to start")
        go.countDown()
        threads.forEach { it.join(30_000) }

        failure.get()?.let { throw AssertionError("worker threw", it) }
        assertTrue(queue.allDone(), "queue did not fully complete (size=$size, workers=$workerCount)")
        assertEquals(0, queue.inFlightCount(), "chunks left in flight after completion")
        return output
    }

    /** Assert the reconstructed output equals the source at EVERY offset. */
    private fun assertReconstructs(size: Int, chunkSize: Long, workers: Int, failureRate: Double, seed: Long) {
        val expected = ByteArray(size) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        val output = simulate(size, chunkSize, workers, failureRate, seed)
        assertEquals(size, output.size)
        // Compare every byte; report the first mismatch precisely if any.
        for (i in 0 until size) {
            if (output[i] != expected[i]) {
                throw AssertionError(
                    "byte mismatch at offset $i: got ${output[i]} expected ${expected[i]} " +
                        "(size=$size chunkSize=$chunkSize workers=$workers fail=$failureRate seed=$seed)"
                )
            }
        }
    }

    @Test
    fun `reconstruction is exact with no injected failures`() {
        assertReconstructs(size = 100_000, chunkSize = 1024, workers = 8, failureRate = 0.0, seed = 1)
    }

    @Test
    fun `reconstruction is exact under heavy random mid-chunk failures`() {
        // ~40% of attempts abort partway and requeue; output must still match byte-for-byte.
        assertReconstructs(size = 250_000, chunkSize = 997, workers = 12, failureRate = 0.4, seed = 42)
    }

    @Test
    fun `reconstruction is exact across many size and worker combos`() {
        val combos = listOf(
            // size, chunkSize, workers, failureRate, seed
            Triple(1, 1L, 1), // tiny single byte
            Triple(63, 8L, 4),
            Triple(1024, 16L, 6),
            Triple(50_000, 333L, 16),
            Triple(123_457, 2048L, 24) // prime-ish size, max worker pool
        )
        var seed = 100L
        for ((size, cs, workers) in combos) {
            assertReconstructs(size, cs, workers, failureRate = 0.25, seed = seed++)
            // Also run each with zero failures to isolate pure coverage from retry churn.
            assertReconstructs(size, cs, workers, failureRate = 0.0, seed = seed++)
        }
    }

    @Test
    fun `single worker also reconstructs exactly with failures`() {
        assertReconstructs(size = 40_000, chunkSize = 512, workers = 1, failureRate = 0.5, seed = 7)
    }

    @Test
    fun `no chunk is left claimable or in-flight after completion`() {
        // A direct structural check independent of byte content: after a churny run the queue
        // must be fully drained — nothing pollable, nothing in flight, everything done.
        val queue = ChunkQueue(partitionChunks(20_000L, 256L))
        // Drive it single-threaded with forced failures, then completion.
        var c = queue.poll()
        var loops = 0
        while (c != null && loops < 1_000_000) {
            loops++
            // Fail the first time we see each chunk, succeed thereafter.
            if (queue.attemptsOf(c.index) == 0) {
                queue.requeueOrFail(c, maxAttempts = 5)
            } else {
                queue.markDone(c)
            }
            c = queue.poll()
        }
        assertTrue(queue.allDone())
        assertEquals(0, queue.inFlightCount())
        assertNull(queue.poll())
    }
}
