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

    // ---- GameList.customFolderUri -------------------------------------------

    @Test
    fun `GameList customFolderUri defaults to null`() {
        val list = GameList(id = "1", name = "Test", createdAt = 0L, games = emptyList())
        assertEquals(null, list.customFolderUri)
    }

    @Test
    fun `GameList customFolderUri can be set to a URI string`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AROMs%2FPS1"
        val list = GameList(
            id = "2",
            name = "PS1 games",
            createdAt = 1234L,
            games = emptyList(),
            customFolderUri = uri
        )
        assertEquals(uri, list.customFolderUri)
    }

    @Test
    fun `GameList copy preserves customFolderUri when not overridden`() {
        val uri = "content://example.authority/tree/primary%3ATest"
        val original = GameList(id = "3", name = "Original", createdAt = 0L, games = emptyList(), customFolderUri = uri)
        val copy = original.copy(name = "Renamed")
        assertEquals(uri, copy.customFolderUri)
    }

    @Test
    fun `GameList copy can clear customFolderUri to null`() {
        val original = GameList(
            id = "4",
            name = "With folder",
            createdAt = 0L,
            games = emptyList(),
            customFolderUri = "content://example.authority/tree/primary%3ATest"
        )
        val cleared = original.copy(customFolderUri = null)
        assertEquals(null, cleared.customFolderUri)
    }

    @Test
    fun `GameList with customFolderUri still computes totalSize correctly`() {
        val games = listOf(
            CuratedGame(name = "A", size = 100L),
            CuratedGame(name = "B", size = 200L)
        )
        val list = GameList(
            id = "5",
            name = "With folder",
            createdAt = 0L,
            games = games,
            customFolderUri = "content://example/tree/root"
        )
        assertEquals(300L, list.totalSize)
        assertEquals(2, list.count)
    }
}
