package io.github.mayusi.emuhelper.data.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Unit tests for pure functions on [Catalog].
 *
 * [Catalog.folderForConsole] and [Catalog.consoleForIdentifier] are pure functions over
 * static maps — no Android dependencies, no coroutines, no mocking needed.
 */
class CatalogTest {

    // ---- folderForConsole ---------------------------------------------------

    @Test
    fun `folderForConsole returns expected folder for snes`() {
        assertEquals("SNES", Catalog.folderForConsole("snes"))
    }

    @Test
    fun `folderForConsole returns expected folder for ps1`() {
        assertEquals("PS1", Catalog.folderForConsole("ps1"))
    }

    @Test
    fun `folderForConsole returns expected folder for ps2`() {
        assertEquals("PS2", Catalog.folderForConsole("ps2"))
    }

    @Test
    fun `folderForConsole returns expected folder for gcn`() {
        assertEquals("GameCube", Catalog.folderForConsole("gcn"))
    }

    @Test
    fun `folderForConsole returns expected folder for gba`() {
        assertEquals("GBA", Catalog.folderForConsole("gba"))
    }

    @Test
    fun `folderForConsole returns expected folder for n64`() {
        assertEquals("N64", Catalog.folderForConsole("n64"))
    }

    @Test
    fun `folderForConsole returns expected folder for wii`() {
        assertEquals("Wii", Catalog.folderForConsole("wii"))
    }

    @Test
    fun `folderForConsole returns expected folder for dreamcast`() {
        assertEquals("Dreamcast", Catalog.folderForConsole("dreamcast"))
    }

    @Test
    fun `folderForConsole unknown key uppercases the key as fallback`() {
        // Unknown key -> fallback: consoleKey.uppercase()
        assertEquals("UNKNOWN_CONSOLE", Catalog.folderForConsole("unknown_console"))
    }

    @Test
    fun `folderForConsole blank key returns Other`() {
        // Blank key -> fallback: ifBlank { "Other" }.uppercase() -> "OTHER"
        // Actually the impl: consoleKey.ifBlank { "Other" }.uppercase() -> "OTHER"
        assertEquals("OTHER", Catalog.folderForConsole(""))
    }

    // ---- consoleForIdentifier -----------------------------------------------

    @Test
    fun `consoleForIdentifier finds ps1 for known identifier`() {
        // "chd_psx" appears in https://archive.org/download/chd_psx/...
        assertEquals("ps1", Catalog.consoleForIdentifier("chd_psx"))
    }

    @Test
    fun `consoleForIdentifier finds snes for nointro-snes`() {
        // "nointro-snes" appears in https://archive.org/download/nointro-snes
        assertEquals("snes", Catalog.consoleForIdentifier("nointro-snes"))
    }

    @Test
    fun `consoleForIdentifier returns null for blank identifier`() {
        assertNull(Catalog.consoleForIdentifier(""))
    }

    @Test
    fun `consoleForIdentifier returns null for unknown identifier`() {
        assertNull(Catalog.consoleForIdentifier("totally_unknown_identifier_xyz"))
    }

    @Test
    fun `consoleForIdentifier finds gba identifier`() {
        // "ef_gba_no-intro_2024-02-21" is in the gba url list
        assertEquals("gba", Catalog.consoleForIdentifier("ef_gba_no-intro_2024-02-21"))
    }

    // ---- CONSOLES map sanity checks -----------------------------------------

    @Test
    fun `all CONSOLES keys have non-blank folder`() {
        Catalog.CONSOLES.forEach { (key, desc) ->
            assert(desc.folder.isNotBlank()) { "Console '$key' has blank folder" }
        }
    }

    @Test
    fun `DISPLAY_ORDER keys are all present in CONSOLES`() {
        Catalog.DISPLAY_ORDER.forEach { key ->
            assertNotNull(Catalog.CONSOLES[key], "DISPLAY_ORDER key '$key' missing from CONSOLES")
        }
    }

    @Test
    fun `IA_LINKS has entries for core consoles`() {
        val coreConsoles = listOf("ps1", "ps2", "snes", "gba", "n64", "gcn", "wii")
        coreConsoles.forEach { key ->
            assertNotNull(Catalog.IA_LINKS[key], "IA_LINKS missing entry for '$key'")
            assert(Catalog.IA_LINKS[key]!!.isNotEmpty()) { "IA_LINKS entry for '$key' is empty" }
        }
    }
}
