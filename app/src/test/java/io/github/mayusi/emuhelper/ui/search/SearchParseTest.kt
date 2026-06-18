package io.github.mayusi.emuhelper.ui.search

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the pure search helpers in [SearchViewModel.Companion]:
 *  - [SearchViewModel.buildSearchUrl] (URL construction / encoding)
 *  - [SearchViewModel.parseSearchResults] (advancedsearch JSON -> [IaSearchResult])
 *
 * Both are pure (no network / no Android), so they're exercised directly here.
 */
class SearchParseTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- buildSearchUrl -----------------------------------------------------

    @Test
    fun `url targets the public advancedsearch endpoint with json output`() {
        val url = SearchViewModel.buildSearchUrl("mario")
        assertTrue(url.startsWith("https://archive.org/advancedsearch.php?q="))
        assertTrue(url.contains("q=mario"))
        assertTrue(url.contains("output=json"))
        assertTrue(url.contains("rows=50"))
    }

    @Test
    fun `url requests the fields the ui shows`() {
        val url = SearchViewModel.buildSearchUrl("anything")
        assertTrue(url.contains("fl[]=identifier"))
        assertTrue(url.contains("fl[]=title"))
        assertTrue(url.contains("fl[]=mediatype"))
        assertTrue(url.contains("fl[]=downloads"))
    }

    @Test
    fun `query with spaces and specials is percent-encoded`() {
        val url = SearchViewModel.buildSearchUrl("super mario & friends")
        // No raw spaces or ampersand-from-query leak into the q parameter.
        assertTrue(url.contains("q=super+mario") || url.contains("q=super%20mario"))
        assertTrue(!url.contains("q=super mario"))
    }

    // ---- parseSearchResults -------------------------------------------------

    @Test
    fun `parses identifier title mediatype and downloads`() {
        val body = """
            {"response":{"numFound":1,"docs":[
              {"identifier":"abc123","title":"Some Item","mediatype":"software","downloads":42}
            ]}}
        """.trimIndent()
        val results = SearchViewModel.parseSearchResults(body, json)
        assertEquals(1, results.size)
        assertEquals("abc123", results[0].identifier)
        assertEquals("Some Item", results[0].title)
        assertEquals("software", results[0].mediatype)
        assertEquals(42, results[0].downloads)
    }

    @Test
    fun `row missing identifier is skipped`() {
        val body = """
            {"response":{"docs":[
              {"title":"No id here","mediatype":"texts"},
              {"identifier":"keep","title":"Kept","mediatype":"movies","downloads":3}
            ]}}
        """.trimIndent()
        val results = SearchViewModel.parseSearchResults(body, json)
        assertEquals(1, results.size)
        assertEquals("keep", results[0].identifier)
    }

    @Test
    fun `missing title falls back to identifier`() {
        val body = """{"response":{"docs":[{"identifier":"only_id","mediatype":"audio"}]}}"""
        val results = SearchViewModel.parseSearchResults(body, json)
        assertEquals(1, results.size)
        assertEquals("only_id", results[0].title)
        assertNull(results[0].downloads)
    }

    @Test
    fun `empty docs yields empty list`() {
        val body = """{"response":{"numFound":0,"docs":[]}}"""
        assertTrue(SearchViewModel.parseSearchResults(body, json).isEmpty())
    }

    @Test
    fun `missing response object yields empty list`() {
        val body = """{"responseHeader":{"status":0}}"""
        assertTrue(SearchViewModel.parseSearchResults(body, json).isEmpty())
    }

    @Test
    fun `title may be a list as IA sometimes returns`() {
        // IA occasionally returns title as an array; our parser reads it as a primitive and
        // should not crash — it falls back to the identifier when the primitive read fails.
        val body = """{"response":{"docs":[{"identifier":"arr","title":["A","B"],"mediatype":"texts"}]}}"""
        val results = SearchViewModel.parseSearchResults(body, json)
        assertEquals(1, results.size)
        assertEquals("arr", results[0].identifier)
        // title falls back to identifier because the array isn't a string primitive
        assertEquals("arr", results[0].title)
    }
}
