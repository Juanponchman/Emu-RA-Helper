package io.github.mayusi.emuhelper.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SESSION-EXPIRY RECOVERY — pure tests for [ReauthCoordinator], the single-flight silent re-auth unit
 * that lets a long batch survive an IA session expiry (the on-device bug: two downloads 401'd mid-batch
 * and failed with no recovery). The coordinator is exercised with NO network — the login and the
 * "have we got saved creds" check are injected lambdas.
 *
 * What each test proves:
 *  - SINGLE-FLIGHT: N concurrent 401 callers trigger EXACTLY ONE re-login; all then proceed.
 *  - GENERATION / no-double-trigger: a caller that already retried in the current generation does not
 *    re-trigger another login.
 *  - RETRY-ONCE: 401 -> re-auth -> success proceeds; 401 AGAIN under the same generation fails (no
 *    infinite loop).
 *  - NO SAVED CREDS: a 401 with blank creds fails fast with NO login attempt.
 *  - isAuthExpiry policy: 401 always; 403 only with the auth-expiry hint.
 */
class ReauthCoordinatorTest {

    @Test
    fun `N concurrent re-auth callers trigger exactly one login`() = runBlocking {
        val logins = AtomicInteger(0)
        val coord = ReauthCoordinator(
            doLogin = {
                // Simulate a real login taking a moment so all callers pile up behind the single flight.
                logins.incrementAndGet()
                delay(50)
                true
            },
            credentialsAvailable = { true }
        )

        val startGen = coord.currentGeneration()
        val outcomes = coordinateConcurrent(coord, callers = 24, observedGen = startGen)

        assertEquals(1, logins.get(), "all 24 concurrent 401s must collapse to exactly ONE login")
        assertTrue(
            outcomes.all { it is ReauthCoordinator.Outcome.Retry },
            "every concurrent caller must proceed (Retry) once the single login succeeds"
        )
        // The generation advanced by exactly one (one successful login), and every caller now sees it.
        assertEquals(startGen + 1, coord.currentGeneration(), "exactly one generation bump for one login")
        outcomes.forEach {
            assertEquals(
                startGen + 1, (it as ReauthCoordinator.Outcome.Retry).generation,
                "every waiter retries under the single new generation"
            )
        }
    }

    @Test
    fun `a caller that already retried in the current generation does not re-trigger a login`() =
        runBlocking {
            val logins = AtomicInteger(0)
            val coord = ReauthCoordinator(
                doLogin = { logins.incrementAndGet(); true },
                credentialsAvailable = { true }
            )

            // First 401 at gen 0 -> one login, generation becomes 1, caller retries under gen 1.
            val first = coord.requestReauth(observedGeneration = 0L)
            assertTrue(first is ReauthCoordinator.Outcome.Retry)
            val genAfterFirst = (first as ReauthCoordinator.Outcome.Retry).generation
            assertEquals(1L, genAfterFirst)
            assertEquals(1, logins.get())

            // This caller has now retried in generation 1. The retry-once gate must say "no more" for
            // a fresh 401 while the generation is still 1.
            assertFalse(
                coord.shouldAttemptReauth(lastRetriedGeneration = genAfterFirst),
                "a caller that already retried in the current generation must NOT re-trigger re-auth"
            )
            assertEquals(1, logins.get(), "no additional login fired for the already-retried caller")
        }

    @Test
    fun `401 then success then a second login covers a later caller without a redundant login`() =
        runBlocking {
            val logins = AtomicInteger(0)
            val coord = ReauthCoordinator(
                doLogin = { logins.incrementAndGet(); true },
                credentialsAvailable = { true }
            )
            // Caller A: 401 at gen 0 -> login #1 -> gen 1.
            val a = coord.requestReauth(0L)
            assertEquals(1L, (a as ReauthCoordinator.Outcome.Retry).generation)
            // Caller B started at gen 0 too, but A already re-authenticated. B must reuse A's session
            // (no login #2): the current gen (1) is newer than B's observed gen (0).
            val b = coord.requestReauth(0L)
            assertTrue(b is ReauthCoordinator.Outcome.Retry)
            assertEquals(1L, (b as ReauthCoordinator.Outcome.Retry).generation)
            assertEquals(1, logins.get(), "B reuses A's fresh session — no redundant second login")
        }

    @Test
    fun `retry-once - a 401 after a fresh successful re-auth fails instead of looping forever`() =
        runBlocking {
            val logins = AtomicInteger(0)
            val coord = ReauthCoordinator(
                doLogin = { logins.incrementAndGet(); true },
                credentialsAvailable = { true }
            )

            // Model the wrapper loop: 401 -> re-auth (login #1, gen 1) -> retry. Then 401 AGAIN under
            // gen 1 (the resource is genuinely restricted even with a fresh session).
            var lastRetriedGen = ReauthCoordinator.NEVER_RETRIED
            var observedGen = coord.currentGeneration()

            // First 401: allowed to attempt (never retried before).
            assertTrue(coord.shouldAttemptReauth(lastRetriedGen))
            val first = coord.requestReauth(observedGen)
            lastRetriedGen = (first as ReauthCoordinator.Outcome.Retry).generation
            observedGen = lastRetriedGen
            assertEquals(1, logins.get())

            // Second 401 in the SAME generation: the retry-once gate must refuse -> genuine failure,
            // and crucially NO second login is fired (no infinite 401 -> login -> 401 loop).
            assertFalse(
                coord.shouldAttemptReauth(lastRetriedGen),
                "after retrying once in the current generation, a repeat 401 must NOT re-auth again"
            )
            assertEquals(1, logins.get(), "no infinite loop — exactly one login across the failure")
        }

    @Test
    fun `no saved creds - a 401 fails fast with no login attempt`() = runBlocking {
        val logins = AtomicInteger(0)
        val coord = ReauthCoordinator(
            doLogin = { logins.incrementAndGet(); true },
            credentialsAvailable = { false } // blank email/password
        )
        val outcome = coord.requestReauth(observedGeneration = coord.currentGeneration())
        assertTrue(
            outcome is ReauthCoordinator.Outcome.NoCredentials,
            "with no saved creds the coordinator reports NoCredentials so the caller fails clearly"
        )
        assertEquals(0, logins.get(), "no login must be attempted when there are no saved credentials")
        assertEquals(
            0L, coord.currentGeneration(),
            "a no-creds outcome does not advance the generation (nothing was re-authenticated)"
        )
    }

    @Test
    fun `re-auth failure (bad password) reports Failed and does not advance the generation`() =
        runBlocking {
            val logins = AtomicInteger(0)
            val coord = ReauthCoordinator(
                doLogin = { logins.incrementAndGet(); false }, // login attempted but rejected
                credentialsAvailable = { true }
            )
            val outcome = coord.requestReauth(observedGeneration = coord.currentGeneration())
            assertTrue(
                outcome is ReauthCoordinator.Outcome.Failed,
                "a login that returns false must surface as Failed so the download fails clearly"
            )
            assertEquals(1, logins.get(), "the login was attempted exactly once")
            assertEquals(0L, coord.currentGeneration(), "a failed login must NOT bump the generation")
        }

    @Test
    fun `isAuthExpiry - 401 always, 403 only with the auth-expiry hint`() {
        assertTrue(ReauthCoordinator.isAuthExpiry(401, authExpiryHint = false), "401 is always auth-expiry")
        assertTrue(ReauthCoordinator.isAuthExpiry(401, authExpiryHint = true))
        assertTrue(
            ReauthCoordinator.isAuthExpiry(403, authExpiryHint = true),
            "403 counts only when an auth-expiry hint is present"
        )
        assertFalse(
            ReauthCoordinator.isAuthExpiry(403, authExpiryHint = false),
            "a plain forbidden 403 must NOT trigger re-auth (would loop on a truly restricted item)"
        )
        assertFalse(ReauthCoordinator.isAuthExpiry(206, authExpiryHint = true), "a real 206 is not auth-expiry")
        assertFalse(ReauthCoordinator.isAuthExpiry(500, authExpiryHint = true), "a 500 is a normal error")
    }

    // ---- helpers ----------------------------------------------------------

    /** Fire [callers] concurrent requestReauth() calls that all observed [observedGen] before failing. */
    private suspend fun coordinateConcurrent(
        coord: ReauthCoordinator,
        callers: Int,
        observedGen: Long
    ): List<ReauthCoordinator.Outcome> = withContext(Dispatchers.Default) {
        coroutineScope {
            (0 until callers).map {
                async { coord.requestReauth(observedGen) }
            }.awaitAll()
        }
    }
}
