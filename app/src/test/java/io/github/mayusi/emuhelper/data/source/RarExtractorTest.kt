package io.github.mayusi.emuhelper.data.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure (Android-free) parts of the RAR extraction wiring:
 * the first-volume detection that decides which file in a multi-volume set we
 * are allowed to START extraction from. unrar follows the volume chain itself
 * once opened on volume 1, so starting on a trailing part is always wrong.
 */
class RarExtractorTest {

    @Test
    fun plainRar_isFirstVolume() {
        assertTrue(RarExtractor.isFirstVolume("Some Game (USA).rar"))
        assertTrue(RarExtractor.isFirstVolume("game.RAR"))
        assertTrue(RarExtractor.isFirstVolume("  spaced.rar  "))
    }

    @Test
    fun rar5_firstPart_isFirstVolume() {
        assertTrue(RarExtractor.isFirstVolume("game.part1.rar"))
        assertTrue(RarExtractor.isFirstVolume("game.part01.rar"))
        assertTrue(RarExtractor.isFirstVolume("game.part001.rar"))
        assertTrue(RarExtractor.isFirstVolume("GAME.PART1.RAR"))
    }

    @Test
    fun rar5_trailingPart_isNotFirstVolume() {
        assertFalse(RarExtractor.isFirstVolume("game.part2.rar"))
        assertFalse(RarExtractor.isFirstVolume("game.part02.rar"))
        assertFalse(RarExtractor.isFirstVolume("game.part003.rar"))
        assertFalse(RarExtractor.isFirstVolume("game.part10.rar"))
    }

    @Test
    fun nonRar_isNotFirstVolume() {
        assertFalse(RarExtractor.isFirstVolume("game.zip"))
        assertFalse(RarExtractor.isFirstVolume("game.r00"))   // old-style trailing part
        assertFalse(RarExtractor.isFirstVolume("game.r01"))
        assertFalse(RarExtractor.isFirstVolume("game.7z"))
        assertFalse(RarExtractor.isFirstVolume("readme.txt"))
        assertFalse(RarExtractor.isFirstVolume("game.rar.bak"))
    }

    @Test
    fun isRar_detectsAnyRar() {
        assertTrue(RarExtractor.isRar("game.rar"))
        assertTrue(RarExtractor.isRar("game.part2.rar"))   // still a .rar (for prompting)
        assertTrue(RarExtractor.isRar("GAME.RAR"))
        assertFalse(RarExtractor.isRar("game.zip"))
        assertFalse(RarExtractor.isRar("game.r00"))
    }
}
