package io.github.mayusi.emuhelper.ui.home

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the pure [shouldShowGentleNag] function.
 *
 * The function has zero Android/Compose dependencies, so tests run as plain JVM tests.
 * All cases are arranged around the three conditions that must ALL be true:
 *   1. isNewer == true
 *   2. dismissedTag == latestTag  (user already dismissed this version — it's tier 2)
 *   3. (nowMs - lastNagMs) >= 24h  (cooldown has elapsed)
 */
class UpdateNagTest {

    private val tag = "v1.2.3"
    private val oneDay = 24L * 60L * 60L * 1000L   // 24 hours in millis
    private val now = 1_700_000_000_000L            // arbitrary fixed "now"

    // ---- Happy path ---------------------------------------------------------

    @Test
    fun `returns true when all conditions met`() {
        assertTrue(
            shouldShowGentleNag(
                isNewer      = true,
                latestTag    = tag,
                dismissedTag = tag,
                lastNagMs    = now - oneDay,   // exactly 24h ago
                nowMs        = now
            )
        )
    }

    @Test
    fun `returns true when nag was shown more than 24h ago`() {
        assertTrue(
            shouldShowGentleNag(
                isNewer      = true,
                latestTag    = tag,
                dismissedTag = tag,
                lastNagMs    = now - (oneDay * 2),  // 48h ago
                nowMs        = now
            )
        )
    }

    @Test
    fun `returns true when nag was never shown (lastNagMs = 0L)`() {
        // 0L means "never shown" — (now - 0) is way more than 24h
        assertTrue(
            shouldShowGentleNag(
                isNewer      = true,
                latestTag    = tag,
                dismissedTag = tag,
                lastNagMs    = 0L,
                nowMs        = now
            )
        )
    }

    // ---- Condition 1: isNewer must be true ----------------------------------

    @Test
    fun `returns false when isNewer is false`() {
        assertFalse(
            shouldShowGentleNag(
                isNewer      = false,
                latestTag    = tag,
                dismissedTag = tag,
                lastNagMs    = 0L,
                nowMs        = now
            )
        )
    }

    // ---- Condition 2: must be already-dismissed (tier-2 guard) --------------

    @Test
    fun `returns false when dismissedTag is empty (fresh version, tier 1)`() {
        // User hasn't dismissed anything yet — this is tier 1 territory.
        assertFalse(
            shouldShowGentleNag(
                isNewer      = true,
                latestTag    = tag,
                dismissedTag = "",
                lastNagMs    = 0L,
                nowMs        = now
            )
        )
    }

    @Test
    fun `returns false when dismissedTag is a different older version`() {
        // User dismissed v1.2.2; v1.2.3 is fresh — tier 1 should handle it.
        assertFalse(
            shouldShowGentleNag(
                isNewer      = true,
                latestTag    = "v1.2.3",
                dismissedTag = "v1.2.2",
                lastNagMs    = 0L,
                nowMs        = now
            )
        )
    }

    @Test
    fun `returns false when latestTag is blank`() {
        // Guard against malformed update info.
        assertFalse(
            shouldShowGentleNag(
                isNewer      = true,
                latestTag    = "",
                dismissedTag = "",
                lastNagMs    = 0L,
                nowMs        = now
            )
        )
    }

    // ---- Condition 3: 24h cooldown ------------------------------------------

    @Test
    fun `returns false when nag was shown less than 24h ago`() {
        assertFalse(
            shouldShowGentleNag(
                isNewer      = true,
                latestTag    = tag,
                dismissedTag = tag,
                lastNagMs    = now - (oneDay - 1),   // 1ms short of 24h
                nowMs        = now
            )
        )
    }

    @Test
    fun `returns false when nag was shown just moments ago`() {
        assertFalse(
            shouldShowGentleNag(
                isNewer      = true,
                latestTag    = tag,
                dismissedTag = tag,
                lastNagMs    = now - 5_000L,  // 5 seconds ago
                nowMs        = now
            )
        )
    }

    // ---- Mutual exclusion with tier-1 fresh prompt --------------------------

    @Test
    fun `returns false for a brand-new version even if cooldown has elapsed`() {
        // A new release v1.3.0 arrived after the user dismissed v1.2.3.
        // dismissedTag != latestTag => tier 1 should handle it; gentle nag must stay silent.
        assertFalse(
            shouldShowGentleNag(
                isNewer      = true,
                latestTag    = "v1.3.0",
                dismissedTag = "v1.2.3",
                lastNagMs    = 0L,
                nowMs        = now
            )
        )
    }

    // ---- Edge: exact boundary -----------------------------------------------

    @Test
    fun `returns true at exactly 24h boundary`() {
        assertTrue(
            shouldShowGentleNag(
                isNewer      = true,
                latestTag    = tag,
                dismissedTag = tag,
                lastNagMs    = now - oneDay,
                nowMs        = now
            )
        )
    }

    @Test
    fun `returns false one millisecond before 24h boundary`() {
        assertFalse(
            shouldShowGentleNag(
                isNewer      = true,
                latestTag    = tag,
                dismissedTag = tag,
                lastNagMs    = now - (oneDay - 1L),
                nowMs        = now
            )
        )
    }
}
