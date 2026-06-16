package io.github.mayusi.emuhelper.data.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [partitionChunks] and [ChunkQueue] — the pure, thread-safe core of the
 * adaptive (chunk-queue work-stealing) download engine.
 *
 * These assert the immutable-partition contract (no gaps/overlaps, full coverage of [0,size))
 * and the queue's done / in-flight accounting that the byte-coverage guarantee relies on.
 */
class ChunkQueueTest {

    // ---- partitionChunks: the immutable-partition contract ------------------

    /**
     * Helper: assert the partition for [size] covers [0,size) with no gaps, no overlaps, the
     * last chunk ends at size-1, indices are 0..count-1, and the lengths sum to size.
     */
    private fun assertValidPartition(size: Long, chunkSize: Long = AdaptiveEngine.CHUNK_SIZE) {
        val chunks = partitionChunks(size, chunkSize)
        assertTrue(chunks.isNotEmpty(), "size=$size should produce at least one chunk")

        // Indices are 0..count-1 contiguous.
        chunks.forEachIndexed { i, c -> assertEquals(i, c.index, "chunk $i has wrong index") }

        // First starts at 0, last ends at size-1.
        assertEquals(0L, chunks.first().start, "first chunk must start at 0")
        assertEquals(size - 1, chunks.last().end, "last chunk must end at size-1")

        // No gaps, no overlaps: each chunk starts exactly where the previous ended + 1.
        for (i in 1 until chunks.size) {
            assertEquals(
                chunks[i - 1].end + 1, chunks[i].start,
                "gap/overlap between chunk ${i - 1} and $i"
            )
        }

        // Sum of lengths == size; every length is positive.
        var sum = 0L
        for (c in chunks) {
            assertTrue(c.length > 0, "chunk ${c.index} has non-positive length ${c.length}")
            assertEquals(c.end - c.start + 1, c.length, "length mismatch on chunk ${c.index}")
            sum += c.length
        }
        assertEquals(size, sum, "sum of chunk lengths must equal size")

        // Every non-final chunk is exactly chunkSize; the last absorbs the remainder.
        for (i in 0 until chunks.size - 1) {
            assertEquals(chunkSize, chunks[i].length, "non-final chunk $i must be exactly chunkSize")
        }
        assertTrue(chunks.last().length <= chunkSize, "last chunk must not exceed chunkSize")
    }

    @Test
    fun `partition covers full range for many size and chunkSize combos`() {
        // A spread of sizes: exact multiples, +1, -1, primes, tiny, and the real 8 MB chunk.
        val chunk = 8L * 1024 * 1024
        assertValidPartition(1, chunk)
        assertValidPartition(chunk - 1, chunk)
        assertValidPartition(chunk, chunk)
        assertValidPartition(chunk + 1, chunk)
        assertValidPartition(2 * chunk, chunk)
        assertValidPartition(2 * chunk + 12345, chunk)
        assertValidPartition(10L * chunk + 1, chunk)
        assertValidPartition(1_073_741_824L, chunk)        // 1 GiB
        assertValidPartition(3_221_225_473L, chunk)        // 3 GiB + 1

        // Small synthetic chunk sizes exercise the boundary math independent of 8 MB.
        for (cs in listOf(1L, 2L, 3L, 7L, 16L, 100L)) {
            for (sz in listOf(1L, 2L, 5L, 7L, 8L, 16L, 17L, 99L, 100L, 101L, 1000L)) {
                assertValidPartition(sz, cs)
            }
        }
    }

    @Test
    fun `partition of zero or negative size is empty`() {
        assertTrue(partitionChunks(0L).isEmpty())
        assertTrue(partitionChunks(-5L).isEmpty())
    }

    @Test
    fun `single-chunk file when size at or below chunkSize`() {
        val chunks = partitionChunks(500L, 1000L)
        assertEquals(1, chunks.size)
        assertEquals(Chunk(0, 0, 499), chunks[0])
        assertEquals(500L, chunks[0].length)
    }

    // ---- ChunkQueue: claim / requeue / done accounting ----------------------

    private fun queueFor(size: Long, chunkSize: Long): ChunkQueue =
        ChunkQueue(partitionChunks(size, chunkSize))

    @Test
    fun `all chunks start queued and pollable`() {
        val q = queueFor(100L, 10L) // 10 chunks
        assertEquals(10, q.count)
        assertEquals(10, q.remaining())
        assertEquals(10, q.unclaimed())
        assertEquals(0, q.inFlightCount())
        assertFalse(q.allDone())
    }

    @Test
    fun `poll moves a chunk to in-flight and decrements unclaimed`() {
        val q = queueFor(100L, 10L)
        val c = q.poll()
        assertNotNull(c)
        assertEquals(9, q.unclaimed())
        assertEquals(1, q.inFlightCount())
        assertEquals(10, q.remaining()) // not done yet
    }

    @Test
    fun `markDone removes from in-flight and advances done`() {
        val q = queueFor(30L, 10L) // 3 chunks
        val c = q.poll()!!
        assertTrue(q.markDone(c))
        assertEquals(0, q.inFlightCount())
        assertEquals(2, q.remaining())
        assertTrue(q.isDone(c.index))
    }

    @Test
    fun `markDone is idempotent and rejects unknown indices`() {
        val q = queueFor(20L, 10L) // 2 chunks
        val c = q.poll()!!
        assertTrue(q.markDone(c))
        // Second markDone for the same chunk is a no-op (returns false, accounting unchanged).
        assertFalse(q.markDone(c))
        assertEquals(0, q.inFlightCount())
        // Unknown index is rejected.
        assertFalse(q.markDone(Chunk(999, 0, 0)))
    }

    @Test
    fun `a chunk cannot be done and queued simultaneously`() {
        val q = queueFor(30L, 10L)
        val c = q.poll()!!
        q.markDone(c)
        // Requeueing a done chunk must be a no-op: it must never re-enter the queue.
        q.requeue(c)
        assertTrue(q.isDone(c.index))
        // Drain the rest; the done chunk's index must never be polled again.
        val seen = HashSet<Int>()
        var next = q.poll()
        while (next != null) {
            assertFalse(next.index == c.index, "done chunk ${c.index} was polled again")
            seen.add(next.index)
            q.markDone(next)
            next = q.poll()
        }
        assertTrue(q.allDone())
    }

    @Test
    fun `requeue puts the whole chunk back for any worker`() {
        val q = queueFor(20L, 10L) // 2 chunks
        val c = q.poll()!!
        assertEquals(1, q.inFlightCount())
        q.requeue(c)
        assertEquals(0, q.inFlightCount())
        // It is pollable again (work-stealing): both chunks remain available.
        assertEquals(2, q.unclaimed())
        assertEquals(2, q.remaining())
    }

    @Test
    fun `allDone is true iff every index is done`() {
        val q = queueFor(50L, 10L) // 5 chunks
        val claimed = ArrayList<Chunk>()
        var c = q.poll()
        while (c != null) { claimed.add(c); c = q.poll() }
        assertEquals(5, claimed.size)
        // Mark all but one done -> not allDone.
        for (i in 0 until claimed.size - 1) q.markDone(claimed[i])
        assertFalse(q.allDone())
        q.markDone(claimed.last())
        assertTrue(q.allDone())
        assertEquals(0, q.remaining())
    }

    @Test
    fun `accounting identity claims minus requeues minus dones equals inFlight`() {
        val q = queueFor(100L, 10L) // 10 chunks
        // Drive a messy sequence of polls, requeues, and dones; the identity must always hold.
        fun checkIdentity() {
            val expected = q.claimsCount() - q.requeuesCount() - q.donesCount()
            assertEquals(expected, q.inFlightCount().toLong(), "claims - requeues - dones must equal inFlight")
        }
        checkIdentity()
        val a = q.poll()!!; checkIdentity()
        val b = q.poll()!!; checkIdentity()
        q.requeue(a); checkIdentity()
        q.markDone(b); checkIdentity()
        val c = q.poll()!!; checkIdentity()
        val d = q.poll()!!; checkIdentity()
        q.markDone(c); checkIdentity()
        q.requeue(d); checkIdentity()
        // Drain everything to completion, checking the identity each step.
        var n = q.poll()
        while (n != null) { q.markDone(n); checkIdentity(); n = q.poll() }
        assertTrue(q.allDone())
        assertEquals(0, q.inFlightCount())
    }

    @Test
    fun `shouldKeepWorking is true while work queued or in-flight, false when drained`() {
        val q = queueFor(20L, 10L) // 2 chunks
        assertTrue(q.shouldKeepWorking())          // queued
        val a = q.poll()!!
        val b = q.poll()!!
        assertTrue(q.shouldKeepWorking())          // nothing queued but 2 in flight
        assertNull(q.poll())                       // queue empty right now
        q.markDone(a)
        assertTrue(q.shouldKeepWorking())          // 1 still in flight
        q.markDone(b)
        assertFalse(q.shouldKeepWorking())         // empty AND nothing in flight
        assertTrue(q.allDone())
    }

    // ---- requeueOrFail: bounded retries -------------------------------------

    @Test
    fun `requeueOrFail requeues until maxAttempts then signals failure`() {
        val q = queueFor(10L, 10L) // 1 chunk
        val c = q.poll()!!
        // attempts 1..4 requeue (false); attempt 5 hits MAX and reports exhaustion (true).
        assertFalse(q.requeueOrFail(c, maxAttempts = 5)) // try 1
        assertEquals(1, q.attemptsOf(c.index))
        val c2 = q.poll()!!                               // re-claimed after requeue
        assertFalse(q.requeueOrFail(c2, 5))               // try 2
        val c3 = q.poll()!!
        assertFalse(q.requeueOrFail(c3, 5))               // try 3
        val c4 = q.poll()!!
        assertFalse(q.requeueOrFail(c4, 5))               // try 4
        val c5 = q.poll()!!
        assertTrue(q.requeueOrFail(c5, 5))                // try 5 -> exhausted
        assertEquals(5, q.attemptsOf(c.index))
        // The exhausted chunk is NOT requeued (caller fails the whole download).
        assertNull(q.poll())
        assertFalse(q.allDone())
    }

    @Test
    fun `requeueOrFail on a done chunk is a harmless no-op`() {
        val q = queueFor(20L, 10L)
        val c = q.poll()!!
        q.markDone(c)
        // A late failure report for a chunk that already completed must not requeue or fail.
        assertFalse(q.requeueOrFail(c, maxAttempts = 5))
        assertEquals(0, q.attemptsOf(c.index))
        assertTrue(q.isDone(c.index))
    }
}
