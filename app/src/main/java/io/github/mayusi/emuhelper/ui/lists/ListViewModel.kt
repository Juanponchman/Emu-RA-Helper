package io.github.mayusi.emuhelper.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emuhelper.data.model.CuratedGame
import io.github.mayusi.emuhelper.data.model.GameList
import io.github.mayusi.emuhelper.data.storage.GameListStore
import io.github.mayusi.emuhelper.ui.browse.ScanStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ListViewModel @Inject constructor(
    private val store: GameListStore,
    private val scanState: ScanStateHolder,
    private val okHttpClient: OkHttpClient
) : ViewModel() {

    val lists: StateFlow<List<GameList>> =
        store.lists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** True when the persisted game-list JSON was corrupt and could not be decoded. */
    val decodeError: StateFlow<Boolean> =
        store.decodeError.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Games staged by the picker (BUILD mode), shown on the Save-list screen. */
    val pendingGames: StateFlow<List<CuratedGame>> = scanState.pendingListGames

    /** One-shot UI message (import errors etc.). */
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message
    fun consumeMessage() { _message.value = "" }

    /** Build a list object (not yet persisted). */
    fun buildList(name: String, games: List<CuratedGame>): GameList = GameList(
        id = UUID.randomUUID().toString(),
        name = name.ifBlank { "Untitled list" },
        createdAt = System.currentTimeMillis(),
        games = games
    )

    /** Persist a list, clear the staging area, then run [onSaved]. */
    fun persist(list: GameList, onSaved: () -> Unit) {
        if (list.games.isEmpty()) return
        viewModelScope.launch {
            store.save(list)
            scanState.pendingListGames.value = emptyList()
            onSaved()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { store.delete(id) }
    }

    fun rename(id: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { store.rename(id, trimmed) }
    }

    /**
     * Persist the per-list folder override.
     * [uri] is the SAF tree URI as a string, or null to clear (revert to global folder).
     */
    fun setListFolder(id: String, uri: String?) {
        viewModelScope.launch { store.setListFolder(id, uri) }
    }

    /**
     * Load a saved list into the download queue (all games), then the preview screen filters.
     * Also carries the list's [GameList.customFolderUri] into [ScanStateHolder.pendingListFolderUri]
     * so the DownloadViewModel can apply the override without touching DownloadManager internals.
     */
    fun loadForDownload(list: GameList) {
        scanState.downloadQueue.value = list.games
        scanState.pendingListFolderUri.value = list.customFolderUri
    }

    /** Serialize a list for file export. */
    fun encodeForExport(list: GameList): String = store.encodeOne(list)

    /** Parse and add an imported list (fresh id). Returns success. */
    fun importFromText(text: String) {
        val parsed = store.decodeOne(text)
        if (parsed == null) {
            _message.value = "Couldn't read that list file."
            return
        }
        val copy = parsed.copy(id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis())
        viewModelScope.launch {
            store.save(copy)
            _message.value = "Imported \"${copy.name}\" (${copy.count} games)."
        }
    }

    /**
     * Fetch a JSON list from [url] and import it.
     * - Only http/https schemes are permitted.
     * - A cookie-jar-free OkHttpClient is used so session cookies are never sent to
     *   arbitrary hosts.
     * - Runs on [Dispatchers.IO]; calls [onResult] on the main thread with
     *   (true, successMsg) or (false, errorMsg).
     */
    fun importFromUrl(url: String, onResult: (Boolean, String) -> Unit) {
        val scheme = url.trim().substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") {
            onResult(false, "Only http/https URLs are supported.")
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    // Use a new client without the session cookie jar so no cookies
                    // are sent to (or stored from) arbitrary third-party hosts.
                    val safeClient = okHttpClient.newBuilder()
                        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
                        .build()
                    val request = Request.Builder().url(url.trim()).get().build()
                    safeClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            return@withContext false to "Server returned ${response.code}."
                        }
                        val body = response.body?.string()
                            ?: return@withContext false to "Empty response from server."
                        val parsed = store.decodeOne(body)
                            ?: return@withContext false to "Couldn't parse the list at that URL."
                        val copy = parsed.copy(id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis())
                        store.save(copy)
                        true to "Imported \"${copy.name}\" (${copy.count} items)."
                    }
                } catch (e: Exception) {
                    false to "Network error: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            onResult(result.first, result.second)
        }
    }
}
