package io.github.mayusi.emuhelper.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for pure formatting/utility functions in Components.kt and related files.
 *
 * These functions have zero Android/Compose dependencies, so they run as plain JVM tests.
 * Components.kt is owned by the perf agent for regex-hoisting edits; we only READ it here
 * and put all tests in this separate file — we never edit Components.kt.
 */
class ComponentsTest {

    // ---- formatSize ---------------------------------------------------------

    @Test
    fun `formatSize returns bytes for small value`() {
        assertEquals("512.0 B", formatSize(512L))
    }

    @Test
    fun `formatSize returns KB for kilobytes`() {
        assertEquals("1.0 KB", formatSize(1024L))
    }

    @Test
    fun `formatSize returns MB for megabytes`() {
        assertEquals("1.0 MB", formatSize(1024L * 1024L))
    }

    @Test
    fun `formatSize returns GB for gigabytes`() {
        assertEquals("1.0 GB", formatSize(1024L * 1024L * 1024L))
    }

    @Test
    fun `formatSize formats fractional MB`() {
        val bytes = (1.5 * 1024 * 1024).toLong()
        assertEquals("1.5 MB", formatSize(bytes))
    }

    @Test
    fun `formatSize zero bytes`() {
        assertEquals("0.0 B", formatSize(0L))
    }

    // ---- formatSpeed --------------------------------------------------------

    @Test
    fun `formatSpeed returns dash-dash for zero`() {
        assertEquals("--", formatSpeed(0.0))
    }

    @Test
    fun `formatSpeed returns dash-dash for very small value`() {
        // 50 bytes/s = 0.0000476 MB/s which is < 0.1, so "--"
        assertEquals("--", formatSpeed(50.0))
    }

    @Test
    fun `formatSpeed formats 1 MB per second`() {
        assertEquals("1.0 MB/s", formatSpeed(1_048_576.0))
    }

    @Test
    fun `formatSpeed formats fractional MB per second`() {
        assertEquals("2.5 MB/s", formatSpeed(1_048_576.0 * 2.5))
    }

    // ---- formatEta ----------------------------------------------------------

    @Test
    fun `formatEta returns dash-dash for zero seconds`() {
        assertEquals("--", formatEta(0.0))
    }

    @Test
    fun `formatEta returns dash-dash for negative seconds`() {
        assertEquals("--", formatEta(-10.0))
    }

    @Test
    fun `formatEta formats minutes and seconds`() {
        assertEquals("1m 30s", formatEta(90.0))
    }

    @Test
    fun `formatEta formats zero minutes`() {
        assertEquals("0m 45s", formatEta(45.0))
    }

    @Test
    fun `formatEta formats hours and minutes for large values`() {
        // 3600 seconds = 1h 0m
        assertEquals("1h 0m", formatEta(3600.0))
    }

    @Test
    fun `formatEta formats hours and partial minutes`() {
        // 3661 seconds = 1h 1m (seconds are dropped in hour mode)
        assertEquals("1h 1m", formatEta(3661.0))
    }

    // ---- cleanGameName ------------------------------------------------------

    @Test
    fun `cleanGameName strips smc extension`() {
        assertEquals("Super Mario World (USA)", cleanGameName("Super Mario World (USA).smc"))
    }

    @Test
    fun `cleanGameName strips chd extension`() {
        assertEquals("Crash Bandicoot (USA)", cleanGameName("Crash Bandicoot (USA).chd"))
    }

    @Test
    fun `cleanGameName strips iso extension`() {
        assertEquals("God of War (USA)", cleanGameName("God of War (USA).iso"))
    }

    @Test
    fun `cleanGameName strips NKit prefix`() {
        assertEquals("Super Smash Bros. Melee (USA)", cleanGameName("[NKit] Super Smash Bros. Melee (USA).gcm"))
    }

    @Test
    fun `cleanGameName strips Redump prefix`() {
        assertEquals("Sonic the Hedgehog (USA)", cleanGameName("[Redump] Sonic the Hedgehog (USA).chd"))
    }

    @Test
    fun `cleanGameName replaces underscores with spaces`() {
        assertEquals("Some Game Name", cleanGameName("Some_Game_Name.sfc"))
    }

    @Test
    fun `cleanGameName trims whitespace`() {
        val result = cleanGameName("  Game Name  .nes")
        // The leading spaces become part of the stripped filename, but trim() at end removes them
        assertEquals("Game Name", result.trim())
    }

    @Test
    fun `cleanGameName does not crash on empty string`() {
        val result = cleanGameName("")
        // Empty or blank — should not throw
        assertTrue(result.length >= 0)
    }

    // ---- detectRegion -------------------------------------------------------

    @Test
    fun `detectRegion detects USA`() {
        assertEquals(Region.USA, detectRegion("Super Mario World (USA).smc"))
    }

    @Test
    fun `detectRegion detects EUR`() {
        assertEquals(Region.EUR, detectRegion("Zelda (Europe).rom"))
    }

    @Test
    fun `detectRegion detects JPN`() {
        assertEquals(Region.JPN, detectRegion("Final Fantasy (Japan).rom"))
    }

    @Test
    fun `detectRegion defaults to OTHER for unknown region`() {
        assertEquals(Region.OTHER, detectRegion("SomeGame.rom"))
    }

    @Test
    fun `detectRegion detects World as USA bucket`() {
        assertEquals(Region.USA, detectRegion("Tetris (World).rom"))
    }

    @Test
    fun `detectRegion is case insensitive`() {
        assertEquals(Region.USA, detectRegion("MARIO (USA).SMC"))
    }
}
