package io.github.mayusi.emuhelper.data.storage

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.emuhelper.data.model.CuratedGame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.queueDataStore: DataStore<Preferences> by preferencesDataStore(name = "emuhelper_queue")

/**
 * Persists the pending download queue as a single JSON blob in DataStore,
 * mirroring [GameListStore]'s pattern exactly (same Json, same key approach,
 * same encode/decode helpers).
 *
 * Lifecycle:
 *  - [save] is called at batch START so the queue survives a force-close/crash/reboot.
 *  - [clear] is called when the batch finishes successfully OR is explicitly dismissed
 *    by the user, so a completed/cleared batch never offers a spurious "resume".
 *  - If the process is killed mid-batch, the saved queue survives in DataStore and
 *    HomeScreen can offer a "Resume" banner on the next launch.
 */
@Singleton
class QueueStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {

    companion object {
        private val KEY_QUEUE = stringPreferencesKey("pending_queue_v1")
    }

    /** The pending queue from the last interrupted batch, or empty if none. */
    val pendingQueue: Flow<List<CuratedGame>> = context.queueDataStore.data.map { prefs ->
        decode(prefs[KEY_QUEUE])
    }

    /** Persist [games] as the current pending queue (called at batch start). */
    suspend fun save(games: List<CuratedGame>) {
        context.queueDataStore.edit { prefs ->
            prefs[KEY_QUEUE] = encode(games)
        }
    }

    /** Clear the pending queue (called on successful batch completion or user dismiss). */
    suspend fun clear() {
        context.queueDataStore.edit { prefs ->
            prefs[KEY_QUEUE] = encode(emptyList())
        }
    }

    // ---- JSON helpers -------------------------------------------------------

    private fun encode(games: List<CuratedGame>): String =
        json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(CuratedGame.serializer()),
            games
        )

    private fun decode(raw: String?): List<CuratedGame> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(CuratedGame.serializer()),
                raw
            )
        } catch (e: Exception) {
            Log.w("EmuHelper", "Decoding pending queue failed; returning empty", e)
            emptyList()
        }
    }
}
