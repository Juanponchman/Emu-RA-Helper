package io.github.mayusi.emuhelper.data.storage

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.emuhelper.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "emuhelper_settings")

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        private val KEY_DOWNLOAD_FOLDER = stringPreferencesKey("download_folder_uri")
        private val KEY_HAS_ONBOARDED = booleanPreferencesKey("has_onboarded")
        private val KEY_SEEN_COACH = booleanPreferencesKey("has_seen_coach")
        private val KEY_CONCURRENCY = intPreferencesKey("download_concurrency")
        private val KEY_SEGMENTS = intPreferencesKey("download_segments")
        private val KEY_EXTRACT = booleanPreferencesKey("extract_archives")
        private val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only_downloads")
        private val KEY_ADAPTIVE_ENGINE = booleanPreferencesKey("adaptive_download_engine")
        private val KEY_SEEN_SETUP_DISCLAIMER = booleanPreferencesKey("seen_setup_disclaimer")
        private val KEY_SETUP_STAGING_FOLDER = stringPreferencesKey("setup_staging_folder_uri")
        val KEY_LAST_UPDATE_CHECK = longPreferencesKey("last_update_check_ts")
        val KEY_DISMISSED_UPDATE_TAG = stringPreferencesKey("dismissed_update_tag")
        val KEY_LAST_CONSOLES = stringSetPreferencesKey("last_selected_consoles")
        val KEY_FAVORITE_CONSOLES = stringSetPreferencesKey("favorite_consoles")
        // ---- Theme mode (Feature 1) -------------------------------------------
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        // ---- Remote catalog cache --------------------------------------------
        val KEY_LAST_CATALOG_FETCH = longPreferencesKey("last_catalog_fetch_ts")
        val KEY_CACHED_REMOTE_CATALOG = stringPreferencesKey("cached_remote_catalog_json")
        // ---- Discord community prompt ----------------------------------------
        private val KEY_SEEN_DISCORD_PROMPT = booleanPreferencesKey("seen_discord_prompt")
    }

    /** Persisted SAF URI for the user-chosen download folder, or null if using app-private dir. */
    val downloadFolder: Flow<Uri?> = context.settingsStore.data.map {
        it[KEY_DOWNLOAD_FOLDER]?.let { s -> Uri.parse(s) }
    }

    val hasOnboarded: Flow<Boolean> = context.settingsStore.data.map { it[KEY_HAS_ONBOARDED] ?: false }

    val hasSeenCoach: Flow<Boolean> = context.settingsStore.data.map { it[KEY_SEEN_COACH] ?: false }

    val concurrency: Flow<Int> = context.settingsStore.data.map { it[KEY_CONCURRENCY] ?: 2 }

    /** Parallel HTTP connections per file. The source host may throttle each connection;
     *  spreading load across multiple connections to fast nodes maximises throughput.
     *  Default 16; allow up to 32 (max throughput). */
    val segments: Flow<Int> = context.settingsStore.data.map { it[KEY_SEGMENTS] ?: 16 }

    val extractArchives: Flow<Boolean> = context.settingsStore.data.map { it[KEY_EXTRACT] ?: false }

    /** When true, downloads are only allowed on an unmetered (Wi-Fi) network. Default off. */
    val wifiOnly: Flow<Boolean> = context.settingsStore.data.map { it[KEY_WIFI_ONLY] ?: false }

    /** When true, downloads use the LANED chunk-queue engine that opens a few warm, host-pinned
     *  HTTP/2 connections per Internet Archive mirror and reuses them across chunks (killing
     *  per-chunk slow-start), spreading work across the independent mirror datacenters.
     *  Default TRUE as of v0.7.0 — on-device A/B benchmarking confirmed it matches or beats the
     *  old static path while running cooler (warm connections instead of socket churn). Users can
     *  still turn it OFF in Settings to fall back to the proven static path. The preference KEY is
     *  unchanged ("adaptive_download_engine") so existing explicit opt-ins/opt-outs are preserved. */
    val adaptiveEngine: Flow<Boolean> = context.settingsStore.data.map { it[KEY_ADAPTIVE_ENGINE] ?: true }

    suspend fun setDownloadFolder(uri: Uri?) {
        context.settingsStore.edit {
            if (uri != null) it[KEY_DOWNLOAD_FOLDER] = uri.toString()
            else it.remove(KEY_DOWNLOAD_FOLDER)
        }
    }

    suspend fun setOnboarded(value: Boolean) {
        context.settingsStore.edit { it[KEY_HAS_ONBOARDED] = value }
    }

    suspend fun setSeenCoach(value: Boolean) {
        context.settingsStore.edit { it[KEY_SEEN_COACH] = value }
    }

    suspend fun setConcurrency(value: Int) {
        context.settingsStore.edit { it[KEY_CONCURRENCY] = value.coerceIn(1, 4) }
    }

    suspend fun setSegments(value: Int) {
        context.settingsStore.edit { it[KEY_SEGMENTS] = value.coerceIn(1, 16) }
    }

    suspend fun setExtractArchives(value: Boolean) {
        context.settingsStore.edit { it[KEY_EXTRACT] = value }
    }

    suspend fun setWifiOnly(value: Boolean) {
        context.settingsStore.edit { it[KEY_WIFI_ONLY] = value }
    }

    suspend fun setAdaptiveEngine(value: Boolean) {
        context.settingsStore.edit { it[KEY_ADAPTIVE_ENGINE] = value }
    }

    val seenSetupDisclaimer: Flow<Boolean> = context.settingsStore.data.map { it[KEY_SEEN_SETUP_DISCLAIMER] ?: false }

    val setupStagingFolder: Flow<Uri?> = context.settingsStore.data.map {
        it[KEY_SETUP_STAGING_FOLDER]?.let { s -> Uri.parse(s) }
    }

    suspend fun setSeenSetupDisclaimer(value: Boolean) {
        context.settingsStore.edit { it[KEY_SEEN_SETUP_DISCLAIMER] = value }
    }

    suspend fun setSetupStagingFolder(uri: Uri?) {
        context.settingsStore.edit {
            if (uri != null) it[KEY_SETUP_STAGING_FOLDER] = uri.toString()
            else it.remove(KEY_SETUP_STAGING_FOLDER)
        }
    }

    // ---- Update check timestamp -------------------------------------------

    val lastUpdateCheck: Flow<Long> = context.settingsStore.data.map { it[KEY_LAST_UPDATE_CHECK] ?: 0L }

    suspend fun setLastUpdateCheck(timestamp: Long) {
        context.settingsStore.edit { it[KEY_LAST_UPDATE_CHECK] = timestamp }
    }

    // ---- Dismissed update tag (persist dismiss-per-version) ---------------

    val dismissedUpdateTag: Flow<String> = context.settingsStore.data.map { it[KEY_DISMISSED_UPDATE_TAG] ?: "" }

    suspend fun setDismissedUpdateTag(tag: String) {
        context.settingsStore.edit { it[KEY_DISMISSED_UPDATE_TAG] = tag }
    }

    // ---- Last selected consoles -------------------------------------------

    val lastSelectedConsoles: Flow<Set<String>> = context.settingsStore.data.map { it[KEY_LAST_CONSOLES] ?: emptySet() }

    suspend fun setLastSelectedConsoles(consoles: Set<String>) {
        context.settingsStore.edit { it[KEY_LAST_CONSOLES] = consoles }
    }

    // ---- Favorite consoles -----------------------------------------------

    val favoriteConsoles: Flow<Set<String>> = context.settingsStore.data.map { it[KEY_FAVORITE_CONSOLES] ?: emptySet() }

    suspend fun toggleFavoriteConsole(key: String) {
        context.settingsStore.edit { prefs ->
            val current = prefs[KEY_FAVORITE_CONSOLES] ?: emptySet()
            prefs[KEY_FAVORITE_CONSOLES] = if (key in current) current - key else current + key
        }
    }

    // ---- Theme mode (Feature 1) -------------------------------------------

    val themeMode: Flow<ThemeMode> = context.settingsStore.data.map { prefs ->
        prefs[KEY_THEME_MODE]?.let { s ->
            try { ThemeMode.valueOf(s) } catch (_: IllegalArgumentException) { ThemeMode.SYSTEM }
        } ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    // ---- Remote catalog cache --------------------------------------------

    val lastCatalogFetch: Flow<Long> = context.settingsStore.data.map { it[KEY_LAST_CATALOG_FETCH] ?: 0L }

    suspend fun setLastCatalogFetch(timestamp: Long) {
        context.settingsStore.edit { it[KEY_LAST_CATALOG_FETCH] = timestamp }
    }

    val cachedRemoteCatalog: Flow<String> = context.settingsStore.data.map { it[KEY_CACHED_REMOTE_CATALOG] ?: "" }

    suspend fun setCachedRemoteCatalog(json: String) {
        context.settingsStore.edit { it[KEY_CACHED_REMOTE_CATALOG] = json }
    }

    // ---- Discord community prompt ----------------------------------------

    val seenDiscordPrompt: Flow<Boolean> = context.settingsStore.data.map { it[KEY_SEEN_DISCORD_PROMPT] ?: false }

    suspend fun setSeenDiscordPrompt(value: Boolean) {
        context.settingsStore.edit { it[KEY_SEEN_DISCORD_PROMPT] = value }
    }
}
