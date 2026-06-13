package io.github.mayusi.emuhelper.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Unit tests for pure computed properties on data model classes.
 */
class ModelsTest {

    // ---- CuratedGame.key ----------------------------------------------------

    @Test
    fun `key combines identifier and filename with slash`() {
        val game = CuratedGame(
            name = "Super Mario World",
            filename = "Super Mario World (USA).smc",
            identifier = "nointro-snes",
            console = "snes"
        )
        assertEquals("nointro-snes/Super Mario World (USA).smc", game.key)
    }

    @Test
    fun `key is unique for same filename different identifiers`() {
        val game1 = CuratedGame(name = "Game", filename = "game.chd", identifier = "chd_psx")
        val game2 = CuratedGame(name = "Game", filename = "game.chd", identifier = "chd_psx_eur")
        assertNotEquals(game1.key, game2.key)
    }

    @Test
    fun `key is unique for same identifier different filenames`() {
        val game1 = CuratedGame(name = "Game A", filename = "game_a.chd", identifier = "chd_psx")
        val game2 = CuratedGame(name = "Game B", filename = "game_b.chd", identifier = "chd_psx")
        assertNotEquals(game1.key, game2.key)
    }

    @Test
    fun `key is stable across multiple calls`() {
        val game = CuratedGame(name = "Sonic", filename = "sonic.md", identifier = "nointro.md")
        assertEquals(game.key, game.key)
    }

    @Test
    fun `key with blank identifier still doesn't crash`() {
        val game = CuratedGame(name = "Anon", filename = "anon.rom", identifier = "")
        assertEquals("/anon.rom", game.key)
    }

    // ---- DownloadTask.progressPercent ---------------------------------------

    @Test
    fun `progressPercent is null when size is zero`() {
        val task = DownloadTask(url = "", displayPath = "", filename = "f.rom", size = 0, downloaded = 0)
        assertEquals(null, task.progressPercent)
    }

    @Test
    fun `progressPercent is 50 percent when half downloaded`() {
        val task = DownloadTask(url = "", displayPath = "", filename = "f.rom", size = 1000, downloaded = 500)
        assertEquals(50f, task.progressPercent)
    }

    @Test
    fun `progressPercent is 100 at full download`() {
        val task = DownloadTask(url = "", displayPath = "", filename = "f.rom", size = 1000, downloaded = 1000)
        assertEquals(100f, task.progressPercent)
    }

    @Test
    fun `progressPercent does not exceed 100 on over-download`() {
        val task = DownloadTask(url = "", displayPath = "", filename = "f.rom", size = 1000, downloaded = 1200)
        assertEquals(100f, task.progressPercent)
    }

    @Test
    fun `progressPercent is 0 at start`() {
        val task = DownloadTask(url = "", displayPath = "", filename = "f.rom", size = 1000, downloaded = 0)
        assertEquals(0f, task.progressPercent)
    }

    // ---- GameList.totalSize and count ---------------------------------------

    @Test
    fun `GameList totalSize sums all game sizes`() {
        val games = listOf(
            CuratedGame(name = "A", size = 500_000L),
            CuratedGame(name = "B", size = 1_000_000L),
            CuratedGame(name = "C", size = 250_000L)
        )
        val list = GameList(id = "1", name = "Test", createdAt = 0L, games = games)
        assertEquals(1_750_000L, list.totalSize)
    }

    @Test
    fun `GameList count returns number of games`() {
        val games = listOf(
            CuratedGame(name = "A"),
            CuratedGame(name = "B"),
            CuratedGame(name = "C")
        )
        val list = GameList(id = "1", name = "Test", createdAt = 0L, games = games)
        assertEquals(3, list.count)
    }

    @Test
    fun `GameList totalSize is 0 for empty list`() {
        val list = GameList(id = "1", name = "Empty", createdAt = 0L, games = emptyList())
        assertEquals(0L, list.totalSize)
        assertEquals(0, list.count)
    }
}
