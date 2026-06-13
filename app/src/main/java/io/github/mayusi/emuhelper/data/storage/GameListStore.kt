package io.github.mayusi.emuhelper.data.storage

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.emuhelper.data.model.GameList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.gameListStore: DataStore<Preferences> by preferencesDataStore(name = "emuhelper_lists")

/**
 * Persists the user's named game lists as a single JSON blob in DataStore.
 *
 * Lists are small (text only — names, identifiers, sizes), so storing them all under
 * one key and rewriting on each change is simpler than a database and perfectly fast
 * at this scale. The same JSON encoding is reused for file export/import.
 */
@Singleton
class GameListStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {

    companion object {
        private val KEY_LISTS = stringPreferencesKey("game_lists_v1")
    }

    /** All saved lists, newest first. */
    val lists: Flow<List<GameList>> = context.gameListStore.data.map { prefs ->
        decode(prefs[KEY_LISTS]).sortedByDescending { it.createdAt }
    }

    suspend fun save(list: GameList) {
        context.gameListStore.edit { prefs ->
            val current = decode(prefs[KEY_LISTS]).toMutableList()
            val idx = current.indexOfFirst { it.id == list.id }
            if (idx >= 0) current[idx] = list else current.add(list)
            prefs[KEY_LISTS] = encode(current)
        }
    }

    suspend fun delete(id: String) {
        context.gameListStore.edit { prefs ->
            val current = decode(prefs[KEY_LISTS]).filterNot { it.id == id }
            prefs[KEY_LISTS] = encode(current)
        }
    }

    suspend fun rename(id: String, newName: String) {
        context.gameListStore.edit { prefs ->
            val current = decode(prefs[KEY_LISTS]).map {
                if (it.id == id) it.copy(name = newName) else it
            }
            prefs[KEY_LISTS] = encode(current)
        }
    }

    // ---- JSON helpers, shared with export/import -------------------------

    /** Serialize a single list (used for file export). */
    fun encodeOne(list: GameList): String = json.encodeToString(GameList.serializer(), list)

    /** Parse a single list from file content (used for import). Returns null if invalid. */
    fun decodeOne(text: String): GameList? = try {
        json.decodeFromString(GameList.serializer(), text)
    } catch (e: Exception) {
        Log.w("EmuHelper", "decodeOne failed", e)
        null
    }

    private fun encode(lists: List<GameList>): String =
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(GameList.serializer()), lists)

    private fun decode(raw: String?): List<GameList> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GameList.serializer()), raw)
        } catch (e: Exception) {
            Log.w("EmuHelper", "Decoding saved lists failed; starting empty", e)
            emptyList()
        }
    }
}
