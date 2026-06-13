package io.github.mayusi.emuhelper.data.source

import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [UpdateChecker.isNewerVersion].
 *
 * The function is `internal` (widened from private) so tests in the same Gradle module can
 * call it directly without going through the network-bound [UpdateChecker.check] function.
 */
class UpdateCheckerTest {

    // Construct UpdateChecker with a bare OkHttpClient. The client is never used in these
    // tests because we only exercise the pure isNewerVersion function.
    private val checker = UpdateChecker(OkHttpClient())

    // ---- Standard newer cases -----------------------------------------------

    @Test
    fun `patch bump is newer`() {
        assertTrue(checker.isNewerVersion("0.1.9", "0.1.8"))
    }

    @Test
    fun `two-digit minor component is newer`() {
        // 0.1.10 > 0.1.9 — must compare numerically, not lexicographically
        assertTrue(checker.isNewerVersion("0.1.10", "0.1.9"))
    }

    @Test
    fun `major bump is newer`() {
        assertTrue(checker.isNewerVersion("1.0", "0.9.9"))
    }

    @Test
    fun `minor bump is newer`() {
        assertTrue(checker.isNewerVersion("0.2.0", "0.1.9"))
    }

    // ---- Equal / older cases ------------------------------------------------

    @Test
    fun `equal versions return false`() {
        assertFalse(checker.isNewerVersion("0.1.8", "0.1.8"))
    }

    @Test
    fun `older tag returns false`() {
        assertFalse(checker.isNewerVersion("0.1.7", "0.1.8"))
    }

    @Test
    fun `same major minor older patch returns false`() {
        assertFalse(checker.isNewerVersion("0.1.8", "0.1.9"))
    }

    // ---- Leading 'v' handling -----------------------------------------------

    @Test
    fun `leading v in tag is stripped`() {
        assertTrue(checker.isNewerVersion("v0.1.9", "0.1.8"))
    }

    @Test
    fun `leading v in current is stripped`() {
        assertTrue(checker.isNewerVersion("0.1.9", "v0.1.8"))
    }

    @Test
    fun `leading v in both is stripped`() {
        assertTrue(checker.isNewerVersion("v0.1.9", "v0.1.8"))
    }

    @Test
    fun `leading v equal versions return false`() {
        assertFalse(checker.isNewerVersion("v0.1.8", "v0.1.8"))
    }

    // ---- Pre-release suffix handling ----------------------------------------

    @Test
    fun `pre-release suffix rc1 is stripped before comparison`() {
        // "v0.1.8-rc1" should parse as [0,1,8] — not newer than the release 0.1.8
        assertFalse(checker.isNewerVersion("v0.1.8-rc1", "0.1.8"))
    }

    @Test
    fun `pre-release suffix on tag allows correct comparison`() {
        // v0.1.9-rc1 treated as 0.1.9 — newer than 0.1.8
        assertTrue(checker.isNewerVersion("v0.1.9-rc1", "0.1.8"))
    }

    @Test
    fun `build metadata suffix is stripped`() {
        // "0.1.9+build42" -> [0,1,9] — newer than 0.1.8
        assertTrue(checker.isNewerVersion("0.1.9+build42", "0.1.8"))
    }

    // ---- Malformed / edge cases ---------------------------------------------

    @Test
    fun `empty tag does not crash and returns false`() {
        assertFalse(checker.isNewerVersion("", "0.1.8"))
    }

    @Test
    fun `empty current does not crash`() {
        // Any real tag should be "newer" than an empty current (parses as empty list -> 0)
        assertTrue(checker.isNewerVersion("0.1.0", ""))
    }

    @Test
    fun `both empty does not crash and returns false`() {
        assertFalse(checker.isNewerVersion("", ""))
    }

    @Test
    fun `non-numeric tag segment is silently ignored`() {
        // "abc.1.2" -> mapNotNull drops "abc" -> [1,2]; compare against [0,1,8]
        // [1,2] vs [0,1,8]: 1 > 0 -> true
        assertTrue(checker.isNewerVersion("abc.1.2", "0.1.8"))
    }

    @Test
    fun `fully non-numeric tag does not crash`() {
        // "garbage" -> all components drop -> [] -> treated as [0]; 0 == 0 -> not newer
        assertFalse(checker.isNewerVersion("garbage", "0.1.8"))
    }

    @Test
    fun `three-digit major bump`() {
        assertTrue(checker.isNewerVersion("2.0.0", "1.99.99"))
    }
}
