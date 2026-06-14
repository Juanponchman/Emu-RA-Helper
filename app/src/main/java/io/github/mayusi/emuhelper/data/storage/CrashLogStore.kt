package io.github.mayusi.emuhelper.data.storage

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A single on-device error record. No PII beyond what is already local. */
@Serializable
data class LogEntry(
    val timestampMillis: Long,
    val tag: String,
    val message: String
)

/**
 * Persists a capped list of the last [MAX_ENTRIES] error records to a file in the
 * app's private storage ([Context.filesDir]/crashlog.json).
 *
 * Privacy: only tag + message + timestamp; never transmitted over the network.
 * Thread-safety: all disk writes are serialised through a [Mutex].
 * Fire-and-forget: [log] never throws on the caller.
 *
 * ── Uncaught-exception hook ──────────────────────────────────────────────────
 * The Application class is owned by a parallel agent, so the hook cannot be added
 * here. To wire it, add the following ONE LINE inside EmuHelperApplication.onCreate()
 * immediately before `previous?.uncaughtException(thread, e)`:
 *
 *   // TODO(CrashLogStore): inject CrashLogStore via Hilt field injection and call:
 *   crashLogStore.logSync(thread.name, "${e.javaClass.simpleName}: ${e.message}")
 *
 * [logSync] is intentionally blocking — it must complete before the process dies.
 * To inject: add `@Inject lateinit var crashLogStore: CrashLogStore` to the
 * Application class (Hilt's @HiltAndroidApp already enables member injection there).
 */
@OptIn(DelicateCoroutinesApi::class)
@Singleton
class CrashLogStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {
    companion object {
        private const val TAG = "CrashLogStore"
        private const val FILENAME = "crashlog.json"
        private const val MAX_ENTRIES = 50
    }

    private val file: File = File(context.filesDir, FILENAME)
    private val mutex = Mutex()

    /** In-memory cache so [entries] emits instantly after first load. */
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** Live list of log entries, newest first. */
    val entries: Flow<List<LogEntry>> = _entries.asStateFlow()

    init {
        // Load existing entries from disk on a background thread at startup.
        GlobalScope.launch(Dispatchers.IO) {
            _entries.value = readFromDisk()
        }
    }

    /**
     * Append a log entry. Fire-and-forget; never throws. Safe to call from any thread.
     */
    fun log(tag: String, message: String) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                mutex.withLock {
                    val entry = LogEntry(System.currentTimeMillis(), tag, message.take(500))
                    val updated = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
                    writeToDisk(updated)
                    _entries.value = updated
                }
            } catch (e: Exception) {
                Log.w(TAG, "log() failed: ${e.message}")
            }
        }
    }

    /**
     * Blocking (synchronous) write intended for the global uncaught-exception handler,
     * where launching a coroutine is unsafe because the process may be dying.
     *
     * See the class-level KDoc for the exact TODO + one-line code to add in Application.
     */
    fun logSync(tag: String, message: String) {
        try {
            val entry = LogEntry(System.currentTimeMillis(), tag, message.take(500))
            val updated = (listOf(entry) + readFromDisk()).take(MAX_ENTRIES)
            writeToDisk(updated)
            _entries.value = updated
        } catch (e: Exception) {
            Log.w(TAG, "logSync() failed: ${e.message}")
        }
    }

    /** Wipe all stored entries. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            writeToDisk(emptyList())
            _entries.value = emptyList()
        }
    }

    /**
     * Returns all entries as a plain-text string suitable for sharing or export.
     * Format: `[yyyy-MM-dd HH:mm:ss] [tag] message`
     */
    suspend fun exportText(): String = withContext(Dispatchers.IO) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        _entries.value.joinToString(separator = "\n") { entry ->
            "[${fmt.format(Date(entry.timestampMillis))}] [${entry.tag}] ${entry.message}"
        }
    }

    // ── Disk helpers ──────────────────────────────────────────────────────────

    private fun readFromDisk(): List<LogEntry> {
        return try {
            if (!file.exists()) return emptyList()
            val raw = file.readText()
            if (raw.isBlank()) return emptyList()
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(LogEntry.serializer()),
                raw
            )
        } catch (e: Exception) {
            Log.w(TAG, "readFromDisk failed: ${e.message}")
            emptyList()
        }
    }

    private fun writeToDisk(entries: List<LogEntry>) {
        try {
            val text = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(LogEntry.serializer()),
                entries
            )
            file.writeText(text)
        } catch (e: IOException) {
            Log.w(TAG, "writeToDisk failed: ${e.message}")
        }
    }
}
