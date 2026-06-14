package io.github.mayusi.emuhelper.data.source

import android.util.Log
import io.github.mayusi.emuhelper.data.config.Catalog
import io.github.mayusi.emuhelper.data.config.CatalogData
import io.github.mayusi.emuhelper.data.storage.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

// ---------------------------------------------------------------------------
// Remote JSON schema
// ---------------------------------------------------------------------------

/** Top-level remote catalog JSON. All fields default so partial responses parse cleanly. */
@Serializable
data class RemoteCatalog(
    val version: Int = 1,
    val iaLinks: Map<String, List<String>> = emptyMap(),
    val consoles: Map<String, RemoteConsole> = emptyMap(),
    val consoleColors: Map<String, Long> = emptyMap(),
    val displayOrder: List<String>? = null
)

/** Per-console entry in the remote JSON. All fields default. */
@Serializable
data class RemoteConsole(
    val display: String = "",
    val folder: String = "",
    val emulator: String = ""
)

// ---------------------------------------------------------------------------
// Result type
// ---------------------------------------------------------------------------

sealed class RefreshResult {
    /** Remote data was fetched and the catalog was updated. */
    data object Updated : RefreshResult()
    /** Staleness check passed; cache/baked-in is current (no fetch was made). */
    data object UpToDate : RefreshResult()
    /** Feature is dormant (placeholder URL) — no action taken. */
    data object Disabled : RefreshResult()
    /** Fetch or parse failed; catalog was NOT changed. */
    data class Failed(val msg: String) : RefreshResult()
}

// ---------------------------------------------------------------------------
// Repository
// ---------------------------------------------------------------------------

@Singleton
class CatalogRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val settings: SettingsStore
) {
    companion object {
        private const val TAG = "CatalogRepository"

        /**
         * DORMANT PLACEHOLDER — no real server is pointed at yet.
         * The operator swaps this for a real URL when the feature goes live.
         * Any URL containing "PLACEHOLDER" triggers the dormant short-circuit.
         */
        const val REMOTE_CATALOG_URL =
            "https://raw.githubusercontent.com/PLACEHOLDER/PLACEHOLDER/main/catalog.json"

        /** Maximum allowed response size (512 KB). Larger responses are rejected. */
        private const val MAX_RESPONSE_BYTES = 512 * 1024L

        /** Minimum staleness window: 3 hours in milliseconds. */
        private const val STALENESS_MS = 3L * 60 * 60 * 1000

        /** Allowed hosts for the remote catalog (https only). */
        private val ALLOWED_HOST_SUFFIXES = listOf(
            "raw.githubusercontent.com",
            "gist.githubusercontent.com"
        )
    }

    /**
     * Whether the feature is dormant. True when the URL still contains the placeholder
     * token, meaning no operator has configured a real endpoint yet.
     */
    private val isDormant: Boolean
        get() = REMOTE_CATALOG_URL.contains("PLACEHOLDER")

    /**
     * Cookie-less OkHttpClient derived from the shared client. Remote catalog fetches
     * must NOT send archive.org session cookies.
     */
    private val noCookieClient: OkHttpClient by lazy {
        okHttpClient.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
    }

    /**
     * Called on app start (off main thread). Applies any cached remote overlay first
     * (so the user sees a previously-fetched customisation immediately), then checks
     * staleness and optionally fetches a fresh copy.
     *
     * This function NEVER throws. Any failure leaves the catalog at baked-in (or cache).
     */
    suspend fun loadOnStart() {
        try {
            withContext(Dispatchers.IO) {
                // 1. Apply any previously-cached remote JSON immediately (remerge over
                //    the current baked-in so the floor is always intact).
                val cached = settings.cachedRemoteCatalog.first()
                if (cached.isNotBlank()) {
                    tryApplyCached(cached)
                }

                // 2. If dormant, stop here — no network activity.
                if (isDormant) {
                    Log.d(TAG, "loadOnStart: feature dormant (placeholder URL), skipping fetch")
                    return@withContext
                }

                // 3. Staleness check: skip fetch if data was refreshed within the window.
                val lastFetch = settings.lastCatalogFetch.first()
                val now = System.currentTimeMillis()
                if (now - lastFetch < STALENESS_MS) {
                    Log.d(TAG, "loadOnStart: catalog fresh (age=${now - lastFetch}ms < ${STALENESS_MS}ms), skipping fetch")
                    return@withContext
                }

                // 4. Attempt remote fetch.
                fetchRemote()
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadOnStart: unexpected error (catalog unchanged)", e)
        }
    }

    /**
     * Manual refresh triggered by the user. Ignores staleness and always attempts a
     * network fetch (unless dormant). Returns a [RefreshResult] the UI can display.
     */
    suspend fun refresh(): RefreshResult = withContext(Dispatchers.IO) {
        try {
            if (isDormant) return@withContext RefreshResult.Disabled
            fetchRemote()
        } catch (e: Exception) {
            Log.w(TAG, "refresh: unexpected error", e)
            RefreshResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    /**
     * Try to apply [cachedJson] as a remote overlay. Parses defensively; if it fails
     * or yields an empty result the catalog stays at baked-in (or its current state).
     */
    private fun tryApplyCached(cachedJson: String) {
        try {
            val remote = json.decodeFromString(RemoteCatalog.serializer(), cachedJson)
            val merged = merge(Catalog.bakedIn(), remote)
            if (sanityOk(merged)) {
                Catalog.applyRemote(merged)
                Log.d(TAG, "loadOnStart: applied cached remote overlay")
            } else {
                Log.w(TAG, "loadOnStart: cached data failed sanity check, ignoring")
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadOnStart: failed to apply cached remote data", e)
        }
    }

    /**
     * Validate the URL is https and the host is within the allowed set.
     */
    private fun validateUrl(url: String): Boolean {
        return try {
            val parsed = url.toHttpUrlOrNull() ?: return false
            if (parsed.scheme != "https") return false
            val host = parsed.host
            ALLOWED_HOST_SUFFIXES.any { suffix -> host == suffix || host.endsWith(".$suffix") }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Fetch the remote catalog, merge with baked-in, sanity-check, and (if all good)
     * apply to [Catalog] and cache the raw JSON to [SettingsStore].
     *
     * On ANY failure the catalog is left unchanged. Never throws.
     */
    private suspend fun fetchRemote(): RefreshResult = withContext(Dispatchers.IO) {
        if (isDormant) return@withContext RefreshResult.Disabled

        if (!validateUrl(REMOTE_CATALOG_URL)) {
            Log.e(TAG, "fetchRemote: URL failed host/scheme validation: $REMOTE_CATALOG_URL")
            return@withContext RefreshResult.Failed("URL not allowed")
        }

        try {
            val request = Request.Builder()
                .url(REMOTE_CATALOG_URL)
                .header("User-Agent", "EmuHelper/CatalogUpdater")
                .header("Accept", "application/json")
                .build()

            val responseJson: String = noCookieClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "fetchRemote: HTTP ${resp.code}")
                    return@withContext RefreshResult.Failed("HTTP ${resp.code}")
                }
                val body = resp.body ?: return@withContext RefreshResult.Failed("Empty body")
                // Size guard: reject oversized responses before reading.
                val contentLength = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                if (contentLength > MAX_RESPONSE_BYTES) {
                    Log.w(TAG, "fetchRemote: Content-Length $contentLength exceeds max $MAX_RESPONSE_BYTES")
                    return@withContext RefreshResult.Failed("Response too large")
                }
                val bytes = body.bytes()
                if (bytes.size > MAX_RESPONSE_BYTES) {
                    Log.w(TAG, "fetchRemote: body ${bytes.size} bytes exceeds max $MAX_RESPONSE_BYTES")
                    return@withContext RefreshResult.Failed("Response too large")
                }
                String(bytes, Charsets.UTF_8)
            }

            // Parse defensively.
            val remote: RemoteCatalog = try {
                json.decodeFromString(RemoteCatalog.serializer(), responseJson)
            } catch (e: Exception) {
                Log.w(TAG, "fetchRemote: JSON parse failed", e)
                return@withContext RefreshResult.Failed("Parse error: ${e.message}")
            }

            // Merge (add/replace only — never remove baked-in entries).
            val merged = merge(Catalog.bakedIn(), remote)

            // Sanity guard: reject if merged catalog has zero consoles with non-empty IA links.
            if (!sanityOk(merged)) {
                Log.w(TAG, "fetchRemote: merged catalog failed sanity check, ignoring remote data")
                return@withContext RefreshResult.Failed("Sanity check failed")
            }

            // All good — apply and cache.
            Catalog.applyRemote(merged)
            settings.setCachedRemoteCatalog(responseJson)
            settings.setLastCatalogFetch(System.currentTimeMillis())
            Log.i(TAG, "fetchRemote: catalog updated from remote")
            RefreshResult.Updated

        } catch (e: Exception) {
            // Network or unexpected error — catalog unchanged.
            Log.w(TAG, "fetchRemote: failed, catalog unchanged", e)
            RefreshResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Merge strategy: ADD or REPLACE only — never remove baked-in entries.
     *
     * - iaLinks: remote entries replace baked-in per key, UNLESS the remote list is empty
     *   (an empty list would strip all sources for that console, which is forbidden).
     * - consoles: remote entries upsert; blank display/folder fields fall back to baked-in.
     * - consoleColors: remote entries always win (it's safe — colours are cosmetic).
     * - displayOrder: remote wins if non-empty; otherwise baked-in is kept.
     */
    internal fun merge(baked: CatalogData, remote: RemoteCatalog): CatalogData {
        // IA links: overlay but skip empty lists so remote can't blank a source.
        val ia = baked.iaLinks.toMutableMap()
        remote.iaLinks.forEach { (k, v) ->
            if (v.isNotEmpty()) ia[k] = v
        }

        // Consoles: upsert; fall back to baked-in values when remote fields are blank.
        val cons = baked.consoles.toMutableMap()
        remote.consoles.forEach { (k, rc) ->
            val existing = cons[k]
            cons[k] = Catalog.ConsoleDesc(
                key = k,
                display = rc.display.ifBlank { existing?.display ?: k },
                folder = rc.folder.ifBlank { existing?.folder ?: k },
                emulator = rc.emulator
            )
        }

        // Console colours: remote always wins (cosmetic, safe to overwrite).
        val col = baked.consoleColors.toMutableMap()
        remote.consoleColors.forEach { (k, c) -> col[k] = c }

        // Display order: remote wins only if non-empty.
        val order = remote.displayOrder?.takeIf { it.isNotEmpty() } ?: baked.displayOrder

        return CatalogData(ia, cons, col, order)
    }

    /**
     * Sanity guard: the merged result must have at least one console that has a
     * non-empty IA links list. An all-zero merged result indicates a garbage response.
     */
    private fun sanityOk(data: CatalogData): Boolean =
        data.consoles.isNotEmpty() &&
        data.iaLinks.any { (_, urls) -> urls.isNotEmpty() }
}
