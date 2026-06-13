package io.github.mayusi.emuhelper.data.source

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.emuhelper.data.config.Catalog
import io.github.mayusi.emuhelper.data.model.CuratedGame
import io.github.mayusi.emuhelper.data.model.DownloadStatus
import io.github.mayusi.emuhelper.data.model.DownloadTask
import io.github.mayusi.emuhelper.data.storage.HistoryEntry
import io.github.mayusi.emuhelper.data.storage.HistoryStore
import io.github.mayusi.emuhelper.data.storage.QueueStore
import io.github.mayusi.emuhelper.data.storage.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-scoped download engine. Lives in the Hilt SingletonComponent and runs work on
 * an application-lifetime CoroutineScope, so downloads keep running when the user
 * leaves the Download screen or backgrounds the app (the foreground DownloadService
 * keeps the process alive). They only stop if the user force-closes the app.
 *
 * Strategy for speed + correctness:
 *  - Download each file with multi-connection ranged requests into APP CACHE (a real
 *    local File supporting random-access seek), then copy the finished file into the
 *    user's chosen folder (SAF) or app-private downloads dir in one sequential pass.
 *  - Files are organised into per-platform subfolders (e.g. Downloads/SNES/).
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val source: RemoteSource,
    private val settings: SettingsStore,
    private val historyStore: HistoryStore,
    private val queueStore: QueueStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks
    private val _totalProgress = MutableStateFlow(0f)
    val totalProgress: StateFlow<Float> = _totalProgress
    private val _totalSpeed = MutableStateFlow(0.0)
    val totalSpeed: StateFlow<Double> = _totalSpeed
    private val _eta = MutableStateFlow("--")
    val eta: StateFlow<String> = _eta
    private val _statusText = MutableStateFlow("Preparing...")
    val statusText: StateFlow<String> = _statusText
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused

    @Volatile private var cancelRequested = false
    private var batchJob: Job? = null
    /** Guards against double-recording history for the same batch. AtomicBoolean + CAS so
     *  concurrent callers (batch-end path and clear()) never double-record. */
    private val batchHistoryRecorded = AtomicBoolean(false)

    /** Caller-tunable. Loaded from settings on each start(). */
    @Volatile private var segmentsPerFile = 4
    @Volatile private var concurrentFiles = 2
    @Volatile private var extractArchives = false

    // HARD SAFETY CEILING on simultaneous HTTP connections across ALL files+segments.
    // Without this, concurrentFiles(16) × segmentsPerFile(32) = 512 sockets + 128 MB of
    // buffers could peg the CPU/network and thermally force-shutdown a handheld (which
    // is exactly what happened). 24 simultaneous connections is plenty for typical use.
    private val MAX_TOTAL_CONNECTIONS = 24
    private val connectionBudget = kotlinx.coroutines.sync.Semaphore(MAX_TOTAL_CONNECTIONS)

    /** Smoothed speed for stable ETA calculations. Prevents jitter when concurrent files start/stop. */
    @Volatile private var smoothedSpeed = 0.0

    /** Timestamp of the last full recomputeAggregates() call (millis). Used to throttle
     *  aggregate recomputes to at most ~3×/sec during active progress updates. */
    @Volatile private var lastAggregateMs = 0L
    private val AGGREGATE_INTERVAL_MS = 333L

    /** Timestamp of the last notification progress push (millis). Throttled independently
     *  to ~once per 1.5 s so we don't flood the NotificationManager. */
    @Volatile private var lastNotifProgressMs = 0L
    private val NOTIF_PROGRESS_INTERVAL_MS = 1500L

    fun start(games: List<CuratedGame>) {
        // B2: Atomic check-and-set prevents two concurrent callers (e.g. LaunchedEffect
        // refiring) from both passing the guard and spinning up duplicate batch jobs.
        synchronized(this) {
            if (_isRunning.value) return
            if (games.isEmpty()) return
            _isRunning.value = true
        }
        _isPaused.value = false
        cancelRequested = false
        batchHistoryRecorded.set(false)
        DownloadService.start(appContext)

        batchJob = scope.launch {
            // Persist the queue immediately so a force-close/crash/reboot doesn't lose it.
            // Cleared only when the batch completes successfully (below) or clear() is called.
            launch { queueStore.save(games) }
            val chosenUri = settings.downloadFolder.first()
            // Clamp to SAFE ceilings. Files-at-once is the dangerous multiplier, so cap
            // it low (downloads are network-bound; 2-3 files saturates the source host).
            concurrentFiles = settings.concurrency.first().coerceIn(1, 4)
            segmentsPerFile = settings.segments.first().coerceIn(1, 16)
            // Ensure files × segments can never exceed the global connection budget.
            if (concurrentFiles * segmentsPerFile > MAX_TOTAL_CONNECTIONS) {
                segmentsPerFile = (MAX_TOTAL_CONNECTIONS / concurrentFiles).coerceAtLeast(1)
            }
            extractArchives = settings.extractArchives.first()

            val customRoot = chosenUri?.let { DocumentFile.fromTreeUri(appContext, it) }
            val defaultRoot = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ROMs")
                .apply { mkdirs() }

            // Upfront SAF permission check via shared helper (also used by retry()).
            val permError = checkFolderPermission(chosenUri)
            if (permError != null) {
                _statusText.value = permError
                _isRunning.value = false
                DownloadService.stop(appContext)
                return@launch
            }

            val taskList = games.map { g ->
                val safeName = File(g.filename).name
                val url = source.buildDownloadUrl(g.identifier, g.filename)
                val consoleKey = g.console.ifBlank { Catalog.consoleForIdentifier(g.identifier) ?: "" }
                val subfolder = Catalog.folderForConsole(consoleKey)
                val displayPath = customRoot?.uri?.toString()?.let { "$it / $subfolder / $safeName" }
                    ?: File(File(defaultRoot, subfolder), safeName).absolutePath
                DownloadTask(
                    id = "${g.identifier}/${g.filename}",
                    url = url, displayPath = displayPath, filename = safeName,
                    size = g.size, identifier = g.identifier, relativeName = g.filename,
                    subfolder = subfolder, console = consoleKey, name = g.name
                )
            }
            _tasks.value = taskList.sortedBy { it.filename }
            _statusText.value = "Downloading ${taskList.size} files..."
            recomputeAggregates()

            val sem = Semaphore(concurrentFiles)
            _tasks.value.map { it.id }.map { id ->
                launch {
                    sem.withPermit {
                        if (!cancelRequested) downloadOne(id, customRoot, defaultRoot)
                    }
                }
            }.forEach { it.join() }

            val done = _tasks.value.count { it.status == DownloadStatus.DONE }
            val failed = _tasks.value.count { it.status == DownloadStatus.FAILED }
            _statusText.value = when {
                failed == 0 -> "Complete: $done done"
                done == 0 -> "All $failed downloads failed"
                else -> "Done: $done · Failed: $failed"
            }
            recordBatchHistory(_tasks.value)
            // Clear the persisted queue — batch reached its terminal state successfully,
            // so there is nothing to resume. An interrupted batch (app killed mid-batch)
            // leaves the queue set because this line is never reached.
            queueStore.clear()
            _isRunning.value = false
            DownloadService.stop(appContext)
        }
    }

    fun retry(taskId: String) {
        // B1: Snapshot status BEFORE mutating, then guard on the original value.
        // Wrap the whole check-and-launch block in synchronized so rapid double-taps
        // can't both pass the guard and spawn duplicate coroutines.
        val shouldLaunch: Boolean
        synchronized(this) {
            val originalStatus = _tasks.value.firstOrNull { it.id == taskId }?.status ?: return
            // Only retry tasks that were genuinely terminal.
            if (originalStatus != DownloadStatus.FAILED && originalStatus != DownloadStatus.CANCELLED) return
            // Mark as queued now, while holding the lock.
            updateTask(taskId) {
                it.copy(status = DownloadStatus.QUEUED, downloaded = 0, speed = 0.0, error = "")
            }
            shouldLaunch = !_isRunning.value
            if (shouldLaunch) {
                _isRunning.value = true
                cancelRequested = false
            }
        }
        if (shouldLaunch) DownloadService.start(appContext)
        scope.launch {
            val chosenUri = settings.downloadFolder.first()
            // B11: Re-run the SAF folder-permission check before re-downloading.
            val permError = checkFolderPermission(chosenUri)
            if (permError != null) {
                updateTask(taskId) { it.copy(status = DownloadStatus.FAILED, error = permError) }
                synchronized(this@DownloadManager) {
                    if (_tasks.value.none {
                            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
                        }
                    ) {
                        _isRunning.value = false
                        DownloadService.stop(appContext)
                    }
                }
                return@launch
            }
            val customRoot = chosenUri?.let { DocumentFile.fromTreeUri(appContext, it) }
            val defaultRoot = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ROMs").apply { mkdirs() }
            try {
                downloadOne(taskId, customRoot, defaultRoot)
            } finally {
                synchronized(this@DownloadManager) {
                    if (_tasks.value.none {
                            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
                        }
                    ) {
                        _isRunning.value = false
                        DownloadService.stop(appContext)
                    }
                }
            }
        }
    }

    /**
     * B11 / B2 helper: verify that the persisted SAF folder grant is still held and the
     * folder is writable. Returns a human-readable error string if something is wrong,
     * or null if everything is fine (including when no custom folder is set).
     */
    private fun checkFolderPermission(chosenUri: android.net.Uri?): String? {
        if (chosenUri == null) return null
        val grantHeld = try {
            appContext.contentResolver.persistedUriPermissions.any {
                it.uri == chosenUri && it.isWritePermission
            }
        } catch (e: Exception) {
            Log.w("EmuHelper", "Checking persisted URI permissions failed", e)
            false
        }
        val writable = try {
            val root = DocumentFile.fromTreeUri(appContext, chosenUri)
            grantHeld && root?.canWrite() == true
        } catch (e: Exception) {
            Log.w("EmuHelper", "Checking folder writability failed", e)
            false
        }
        return if (!writable)
            "Storage folder access was lost — please re-pick your download folder in the menu."
        else null
    }

    fun pause() {
        _isPaused.value = true
        if (_isRunning.value) DownloadService.updatePausedState(appContext, paused = true)
    }
    fun resume() {
        _isPaused.value = false
        if (_isRunning.value) DownloadService.updatePausedState(appContext, paused = false)
    }
    fun cancelAll() {
        cancelRequested = true
        batchJob?.cancel()
        _isPaused.value = false
        _isRunning.value = false
        DownloadService.stop(appContext)
    }

    /** Reset visible state when the user leaves a finished batch. */
    fun clear() {
        if (_isRunning.value) return
        // B4: Attempt to record history via the same CAS guard used at batch-end.
        // If batch-end already recorded, the CAS in recordBatchHistory returns early (no-op).
        // Reset the AtomicBoolean BEFORE launching so the next batch starts clean,
        // but only after the snapshot is captured.
        val snapshot = _tasks.value
        if (snapshot.isNotEmpty()) {
            scope.launch {
                recordBatchHistory(snapshot)
                // Also clear persisted queue — if the user explicitly clears a batch
                // (even a cancelled one), there is nothing meaningful to resume.
                queueStore.clear()
            }
        }
        // Reset AFTER launch so the coroutine above can still CAS-acquire the guard.
        // The next call to start() resets the flag at its own start, so this reset is
        // a belt-and-suspenders safety net for the edge case where the app never calls
        // start() again before another clear().
        batchHistoryRecorded.set(false)
        _tasks.value = emptyList()
        _statusText.value = "Preparing..."
        _totalProgress.value = 0f
        _totalSpeed.value = 0.0
        _eta.value = "--"
        smoothedSpeed = 0.0
    }

    // ---- one file ---------------------------------------------------------

    private suspend fun downloadOne(taskId: String, customRootBase: DocumentFile?, defaultRootBase: File) {
        val base = _tasks.value.firstOrNull { it.id == taskId } ?: return
        val filename = base.filename
        val expected = base.size
        val url = base.url
        val subfolder = base.subfolder

        updateTask(taskId) { it.copy(status = DownloadStatus.DOWNLOADING, error = "", speed = 0.0) }

        // If the final file already exists at the destination and looks complete, skip.
        if (destinationHasComplete(customRootBase, defaultRootBase, subfolder, filename, expected)) {
            updateTask(taskId) { it.copy(downloaded = expected, status = DownloadStatus.DONE, speed = 0.0) }
            return
        }

        // Download into app cache (real File, supports random-access multi-connection).
        val cacheDir = File(appContext.cacheDir, "dl").apply { mkdirs() }
        val cacheFile = File(cacheDir, "${taskId.hashCode()}_$filename.part")

        // Gather mirror hosts for this file (primary + d1/d2 + CDN nodes) so the
        // segmented downloader can spread load and fail over between them.
        val mirrors = runCatching { source.mirrorUrls(base.identifier, base.relativeName) }
            .getOrDefault(listOf(url))
            .ifEmpty { listOf(url) }

        try {
            val total = source.downloadFileSegmented(
                candidateUrls = mirrors,
                expectedSize = expected,
                destFile = cacheFile,
                segments = segmentsPerFile,
                onProgress = { bytes, bps ->
                    // honour pause: suspend until unpaused instead of busy-polling every 200ms.
                    // _isPaused is a StateFlow; .first { !it } suspends cheaply with no polling
                    // and resumes promptly when resume() flips it to false. Structured cancellation
                    // (CancellationException) propagates normally when cancelAll() cancels the job.
                    if (_isPaused.value) {
                        updateTask(taskId) { it.copy(status = DownloadStatus.PAUSED, speed = 0.0) }
                        if (!cancelRequested) {
                            _isPaused.first { !it }  // suspend until unpaused; throws on cancel
                        }
                        if (!cancelRequested) updateTask(taskId) { it.copy(status = DownloadStatus.DOWNLOADING) }
                    }
                    updateTask(taskId) { it.copy(downloaded = bytes, speed = bps) }
                },
                isCancelled = { cancelRequested }
            )

            // Validate size, then publish into the destination folder.
            val ok = if (expected > 0) total >= expected * 99 / 100 else total > 0
            if (!ok) throw java.io.IOException("Incomplete: $total / $expected")

            _statusText.value = "Saving $filename…"
            if (extractArchives && filename.lowercase().endsWith(".zip")) {
                extractZipToDestination(cacheFile, customRootBase, defaultRootBase, subfolder)
            } else {
                copyToDestination(cacheFile, customRootBase, defaultRootBase, subfolder, filename)
            }
            cacheFile.delete()
            updateTask(taskId) { it.copy(downloaded = if (expected > 0) expected else total, status = DownloadStatus.DONE, speed = 0.0) }
        } catch (c: CancellationException) {
            cacheFile.delete()
            updateTask(taskId) { it.copy(status = DownloadStatus.CANCELLED, speed = 0.0) }
        } catch (e: Exception) {
            Log.w("EmuHelper", "Download failed: $filename", e)
            cacheFile.delete()
            // B11: Detect out-of-disk-space and surface a clear message.
            val errorMessage = if (e is IOException) {
                val msg = e.message ?: ""
                if (msg.contains("ENOSPC", ignoreCase = true) || msg.contains("No space", ignoreCase = true))
                    "Storage full — free some space and retry"
                else msg.ifBlank { e.javaClass.simpleName }
            } else e.message ?: e.javaClass.simpleName
            updateTask(taskId) { it.copy(status = DownloadStatus.FAILED, speed = 0.0, error = errorMessage) }
        }
    }

    private fun destinationHasComplete(
        customRootBase: DocumentFile?, defaultRootBase: File, subfolder: String, filename: String, expected: Long
    ): Boolean {
        if (expected <= 0) return false
        return try {
            if (customRootBase != null) {
                val dir = findChildCI(customRootBase, subfolder)?.takeIf { it.isDirectory }
                val f = dir?.let { findChildCI(it, filename) }
                f != null && f.length() >= expected * 99 / 100
            } else {
                val f = File(File(defaultRootBase, subfolder), filename)
                f.exists() && f.length() >= expected * 99 / 100
            }
        } catch (e: Exception) { false }
    }

    /** Copy the finished cache file into the chosen folder (SAF) or app-private dir. */
    private fun copyToDestination(
        cacheFile: File, customRootBase: DocumentFile?, defaultRootBase: File, subfolder: String, filename: String
    ) {
        if (customRootBase != null) {
            val dir = resolveSafSubdir(customRootBase, subfolder)
            findChildCI(dir, filename)?.delete()
            val out = dir.createFile("application/octet-stream", filename)
                ?: throw java.io.IOException("Could not create file in chosen folder")
            appContext.contentResolver.openOutputStream(out.uri, "w")?.use { os ->
                cacheFile.inputStream().use { it.copyTo(os, 1 shl 20) }
            } ?: throw java.io.IOException("Could not open destination stream")
        } else {
            val dir = File(defaultRootBase, subfolder).apply { mkdirs() }
            val dest = File(dir, filename)
            if (dest.exists()) dest.delete()
            cacheFile.copyTo(dest, overwrite = true)
        }
    }

    /** Extract .zip archive into destination folder. Falls back to copyToDestination on failure. */
    private fun extractZipToDestination(
        zipFile: File, customRootBase: DocumentFile?, defaultRootBase: File, subfolder: String
    ) {
        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // Sanitize entry name to prevent zip-slip and keep it safe
                        val entryName = File(entry.name).name  // Use only basename for simplicity

                        if (customRootBase != null) {
                            // SAF path: flatten all entries into the console subfolder
                            val dir = resolveSafSubdir(customRootBase, subfolder)
                            findChildCI(dir, entryName)?.delete()
                            val outFile = dir.createFile("application/octet-stream", entryName)
                                ?: throw java.io.IOException("Could not create file: $entryName")
                            appContext.contentResolver.openOutputStream(outFile.uri, "w")?.use { os ->
                                zis.copyTo(os, 1 shl 20)
                            } ?: throw java.io.IOException("Could not open output stream for: $entryName")
                        } else {
                            // App-private File path: preserve nested directories
                            val relativePath = entry.name.split('/').filter { it.isNotEmpty() && it != ".." }
                                .joinToString("/")
                            val outFile = File(File(defaultRootBase, subfolder), relativePath)
                            // Canonical-path zip-slip guard: verify the resolved path stays
                            // inside the intended destination directory before writing.
                            val canonicalOut = try { outFile.canonicalPath } catch (e: java.io.IOException) {
                                Log.w("EmuHelper", "zip: canonicalPath failed for ${entry.name}, skipping", e)
                                zis.closeEntry(); entry = zis.nextEntry; continue
                            }
                            val canonicalBase = try {
                                File(defaultRootBase, subfolder).canonicalPath + File.separator
                            } catch (e: java.io.IOException) {
                                Log.w("EmuHelper", "zip: canonicalPath for base failed, skipping entry", e)
                                zis.closeEntry(); entry = zis.nextEntry; continue
                            }
                            if (!canonicalOut.startsWith(canonicalBase)) {
                                Log.w("EmuHelper", "zip: path traversal blocked for ${entry.name}")
                                zis.closeEntry(); entry = zis.nextEntry; continue
                            }
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().buffered().use { os ->
                                zis.copyTo(os, 1 shl 20)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.w("EmuHelper", "Failed to extract zip, falling back to copy", e)
            // Fall back to copying the zip file as-is
            copyToDestination(zipFile, customRootBase, defaultRootBase, subfolder, zipFile.name)
        }
    }

    /** Case-insensitive child lookup; SAF's findFile is case-sensitive, but folders made by
     *  other tools (e.g. EmuTran) are often lowercase. Returns the first name match or null. */
    private fun findChildCI(parent: DocumentFile, displayName: String): DocumentFile? =
        parent.listFiles().firstOrNull { it.name?.equals(displayName, ignoreCase = true) == true }

    private val safCache = java.util.concurrent.ConcurrentHashMap<String, DocumentFile>()
    @Synchronized
    private fun resolveSafSubdir(parent: DocumentFile, name: String): DocumentFile {
        if (name.isBlank()) return parent
        // Case-insensitive cache key + lookup: reuse an existing folder even if its case
        // differs (tools like EmuTran create lowercase folders, e.g. "psp" vs our "PSP"),
        // so we don't create a duplicate "PSP (1)" next to it. SAF's findFile is case-sensitive.
        val key = "${parent.uri}/${name.lowercase()}"
        safCache[key]?.let { if (it.exists()) return it }
        val existing = parent.listFiles().firstOrNull { it.isDirectory && it.name?.equals(name, ignoreCase = true) == true }
        val dir = if (existing != null) existing else parent.createDirectory(name) ?: parent
        safCache[key] = dir
        return dir
    }

    // ---- history recording -----------------------------------------------

    /**
     * Snapshot terminal [DownloadTask]s into [HistoryStore]. Uses [batchHistoryRecorded] as a
     * guard so the same batch is never written twice (once at batch-complete, once at clear()).
     * Records only DONE/FAILED/CANCELLED tasks; skips QUEUED/DOWNLOADING/PAUSED.
     */
    private suspend fun recordBatchHistory(tasks: List<DownloadTask>) {
        // B4: compareAndSet is the single atomic gate — only the first caller records.
        // Subsequent calls (batch-end racing clear()) are no-ops.
        if (!batchHistoryRecorded.compareAndSet(false, true)) return
        val terminal = tasks.filter {
            it.status == DownloadStatus.DONE ||
            it.status == DownloadStatus.FAILED ||
            it.status == DownloadStatus.CANCELLED
        }
        if (terminal.isEmpty()) return
        val now = System.currentTimeMillis()
        val entries = terminal.map { task ->
            HistoryEntry(
                filename = task.filename,
                subfolder = task.subfolder,
                sizeBytes = task.size,
                status = task.status.name,
                timestampMillis = now,
                identifier = task.identifier,
                console = task.console,
                name = task.name
            )
        }
        historyStore.addAll(entries)
    }

    // ---- state plumbing ---------------------------------------------------

    @Synchronized
    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        val all = _tasks.value
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return
        val updated = all.toMutableList().apply { this[idx] = transform(this[idx]) }
        _tasks.value = updated
        // Always recompute immediately on terminal status so totals are accurate at completion.
        // During active progress, throttle to at most ~3×/sec to avoid pegging the CPU
        // across up to 24 concurrent segment coroutines each reporting every ~256 KB.
        val newStatus = updated[idx].status
        val isTerminal = newStatus == DownloadStatus.DONE ||
                         newStatus == DownloadStatus.FAILED ||
                         newStatus == DownloadStatus.CANCELLED
        val now = System.currentTimeMillis()
        if (isTerminal || now - lastAggregateMs >= AGGREGATE_INTERVAL_MS) {
            lastAggregateMs = now
            recomputeAggregates(updated)
        }
    }

    private fun recomputeAggregates(all: List<DownloadTask> = _tasks.value) {
        // Compute aggregate remaining bytes over all non-terminal tasks
        val td = all.sumOf { it.downloaded.toDouble() }
        val ts = all.sumOf { it.size.toDouble() }
        val sp = all.filter { it.status == DownloadStatus.DOWNLOADING }.sumOf { it.speed }
        val remaining = (ts - td).coerceAtLeast(0.0)

        _totalProgress.value = if (ts > 0) (td / ts).toFloat().coerceIn(0f, 1f) else 0f

        // Update smoothed speed: exponential moving average for stability
        smoothedSpeed = if (sp > 0.1) {
            if (smoothedSpeed <= 0) sp else smoothedSpeed * 0.6 + sp * 0.4
        } else {
            0.0
        }

        _totalSpeed.value = sp
        _eta.value = if (smoothedSpeed > 0.1) io.github.mayusi.emuhelper.ui.common.formatEta(remaining / smoothedSpeed) else "--"

        // Push live progress to the notification, throttled to ~once per 1.5 s.
        // Only update while the batch is actively running and not paused.
        if (_isRunning.value && !_isPaused.value) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastNotifProgressMs >= NOTIF_PROGRESS_INTERVAL_MS) {
                lastNotifProgressMs = nowMs
                val pct       = (_totalProgress.value * 100).toInt().coerceIn(0, 100)
                val speed     = io.github.mayusi.emuhelper.ui.common.formatSpeed(sp)
                val doneCount = all.count { it.status == io.github.mayusi.emuhelper.data.model.DownloadStatus.DONE }
                val total     = all.size
                DownloadService.updateProgress(appContext, pct, speed, doneCount, total)
            }
        }
    }
}
