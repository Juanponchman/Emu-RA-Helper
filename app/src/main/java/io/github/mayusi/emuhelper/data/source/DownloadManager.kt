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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RESUME (v0.8): a DISTINCT exception for "the assembled file failed its MD5 verify". It is the ONLY
 * failure that discards the cache .part + resume manifest — a genuinely corrupt file must be
 * re-fetched from scratch, never resumed. Every OTHER (retryable) failure keeps the .part for resume.
 * IOException so it still flows through the existing IOException-aware error messaging.
 */
private class CorruptDownloadException(message: String) : IOException(message)

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

    /**
     * FIX #1: the ALWAYS-CURRENT source of truth for the task list.
     *
     * [updateTask] is called ~69×/sec across up to 24 segment callbacks. Previously every
     * call assigned `_tasks.value`, which made the StateFlow emit at that full rate and
     * forced Compose to re-diff the whole LazyColumn each time -> jank on low-RAM handhelds.
     *
     * We now decouple "source of truth" from "UI emission": this field is updated on EVERY
     * call (so internal readers and the next updateTask always see the latest state), while
     * `_tasks.value` (the StateFlow the UI collects) is only re-assigned on terminal status,
     * task add/remove, or once the throttle window elapses. All access is under updateTask's
     * @Synchronized lock (or the other writers below, which also keep this field in lockstep
     * with `_tasks.value`). @Volatile guards the few non-locked reads on other coroutines.
     */
    @Volatile private var latestTasks: List<DownloadTask> = emptyList()
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
    /** EXPERIMENTAL adaptive (chunk-queue work-stealing) engine. Default OFF; loaded from
     *  settings on each start()/retry(). When false the proven static path runs unchanged. */
    @Volatile private var adaptiveEngine = false

    /** Per-list destination override for the CURRENT batch. Set by start(folderOverride),
     *  null = use the global Settings download folder. Held on the manager (not just read
     *  inside start) so a retry() of a task from a custom-folder list re-targets the SAME
     *  folder instead of silently falling back to the global one. Cleared on clear(). */
    @Volatile private var activeFolderOverride: Uri? = null

    // HARD SAFETY CEILING on simultaneous HTTP connections across ALL files+segments.
    // Without this, concurrentFiles(16) × segmentsPerFile(32) = 512 sockets + 128 MB of
    // buffers could peg the CPU/network and thermally force-shutdown a handheld (which
    // is exactly what happened). 24 simultaneous connections is plenty for typical use.
    private val MAX_TOTAL_CONNECTIONS = 24
    private val connectionBudget = kotlinx.coroutines.sync.Semaphore(MAX_TOTAL_CONNECTIONS)

    // MULTI-FILE BATCH SCHEDULER. One instance for the whole manager (stateless between calls, so it
    // is safe to reuse across batches). It assigns DISTINCT mirrors to concurrently-downloading files
    // so two files don't both pin the same datacenter and split its ~2 MB/s ceiling — file A runs on
    // ia6, file B on ia8, each at full speed. It is ONLY a host-assignment layer: the global
    // 24-connection cap stays enforced by [connectionBudget] (the scheduler never mints permits).
    private val mirrorScheduler = MirrorScheduler(globalBudget = MAX_TOTAL_CONNECTIONS)

    // Live demand registry: the resolved mirrors + size of every file CURRENTLY downloading, keyed by
    // taskId. The adaptive engine's runners read a snapshot of this (via the liveDemands supplier) to
    // re-consult the scheduler between chunks, so a freed mirror (peer file finished) flows to the
    // remaining files. Entries are added once a file's mirrors resolve and removed on completion.
    private val activeDemands =
        java.util.concurrent.ConcurrentHashMap<String, FileDemand>()

    // ---- OVERNIGHT GOVERNORS (v0.8) ----------------------------------------------------------
    // (3a) WI-FI-ONLY: when SettingsStore.wifiOnly is ON and the active network becomes metered
    // (cellular), pause the batch; auto-resume when unmetered Wi-Fi returns. We only pause/resume
    // what the governor itself paused — never a batch the user manually paused.
    @Volatile private var userPaused = false       // a USER-initiated pause is in effect
    @Volatile private var governorPaused = false   // the Wi-Fi governor paused the batch itself
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    // (3b) THERMAL BACKOFF: on SEVERE+ thermal status, shrink the live connection cap toward ~8 by
    // HOLDING permits from the shared budget Semaphore (fewer permits = fewer connections = always
    // cap-safe). Restore on cool-down. The governor only ever REDUCES the live cap, never raises it
    // past 24.
    private val thermalGovernor = ThermalPermitGovernor(connectionBudget, MAX_TOTAL_CONNECTIONS)
    private var thermalListener: android.os.PowerManager.OnThermalStatusChangedListener? = null

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

    /** @param folderOverride when non-null, this batch downloads into THIS SAF tree instead of
     *  the global Settings download folder. Used by per-list custom destinations (a saved list
     *  can pin its own folder, e.g. PS1 on internal, N64 on the SD card). */
    fun start(games: List<CuratedGame>, folderOverride: Uri? = null) {
        // B2: Atomic check-and-set prevents two concurrent callers (e.g. LaunchedEffect
        // refiring) from both passing the guard and spinning up duplicate batch jobs.
        synchronized(this) {
            if (_isRunning.value) return
            if (games.isEmpty()) return
            _isRunning.value = true
        }
        // Remember the override for the whole batch so retry() of any task lands in the same place.
        activeFolderOverride = folderOverride
        _isPaused.value = false
        userPaused = false
        governorPaused = false
        cancelRequested = false
        batchHistoryRecorded.set(false)
        DownloadService.start(appContext)
        // OVERNIGHT GOVERNORS: start the Wi-Fi-only + thermal listeners for this batch's lifetime.
        startGovernors()

        batchJob = scope.launch {
            // Persist the queue immediately so a force-close/crash/reboot doesn't lose it.
            // Direct suspend call (NOT a nested launch) so the queue is committed to DataStore
            // BEFORE any download starts. A fire-and-forget launch raced the batch start: if the
            // app was killed in that window, the queue wasn't persisted and the resume banner
            // never appeared. The resume guarantee requires this to complete first.
            queueStore.save(games)
            // Per-list override wins over the global folder when one is pinned.
            val chosenUri = activeFolderOverride ?: settings.downloadFolder.first()
            // Clamp to SAFE ceilings. Files-at-once is the dangerous multiplier, so cap
            // it low (downloads are network-bound; 2-3 files saturates the source host).
            concurrentFiles = settings.concurrency.first().coerceIn(1, 4)
            segmentsPerFile = settings.segments.first().coerceIn(1, 16)
            // Ensure files × segments can never exceed the global connection budget.
            if (concurrentFiles * segmentsPerFile > MAX_TOTAL_CONNECTIONS) {
                segmentsPerFile = (MAX_TOTAL_CONNECTIONS / concurrentFiles).coerceAtLeast(1)
            }
            extractArchives = settings.extractArchives.first()
            // EXPERIMENTAL adaptive engine (default OFF). When false, downloadOne() passes
            // adaptive=false and the EXACT current static download path runs unchanged.
            adaptiveEngine = settings.adaptiveEngine.first()

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
                    subfolder = subfolder, console = consoleKey, name = g.name,
                    // Carry the source checksum so the completion path can verify integrity.
                    md5 = g.md5
                )
            }
            val sorted = taskList.sortedBy { it.filename }
            latestTasks = sorted          // keep source of truth in lockstep with the StateFlow
            _tasks.value = sorted
            _statusText.value = "Downloading ${taskList.size} files..."
            recomputeAggregates(sorted)

            val sem = Semaphore(concurrentFiles)
            sorted.map { it.id }.map { id ->
                launch {
                    sem.withPermit {
                        if (!cancelRequested) downloadOne(id, customRoot, defaultRoot)
                    }
                }
            }.forEach { it.join() }

            // Read the source of truth (all tasks are terminal here, so it already equals
            // the last emitted _tasks.value — but latestTasks is the canonical snapshot).
            val finalTasks = latestTasks
            val done = finalTasks.count { it.status == DownloadStatus.DONE }
            val failed = finalTasks.count { it.status == DownloadStatus.FAILED }
            _statusText.value = when {
                failed == 0 -> "Complete: $done done"
                done == 0 -> "All $failed downloads failed"
                else -> "Done: $done · Failed: $failed"
            }
            recordBatchHistory(finalTasks)
            // Clear the persisted queue — batch reached its terminal state successfully,
            // so there is nothing to resume. An interrupted batch (app killed mid-batch)
            // leaves the queue set because this line is never reached.
            queueStore.clear()
            _isRunning.value = false
            stopGovernors()
            DownloadService.stop(appContext)
        }
    }

    fun retry(taskId: String) {
        // B1: Snapshot status BEFORE mutating, then guard on the original value.
        // Wrap the whole check-and-launch block in synchronized so rapid double-taps
        // can't both pass the guard and spawn duplicate coroutines.
        val shouldLaunch: Boolean
        synchronized(this) {
            val originalStatus = latestTasks.firstOrNull { it.id == taskId }?.status ?: return
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
        if (shouldLaunch) {
            DownloadService.start(appContext)
            // OVERNIGHT GOVERNORS: a retry that (re)starts the batch gets the listeners too.
            startGovernors()
        }
        scope.launch {
            // Honour the active per-list override so a retry re-targets the same custom folder.
            val chosenUri = activeFolderOverride ?: settings.downloadFolder.first()
            // B11: Re-run the SAF folder-permission check before re-downloading.
            val permError = checkFolderPermission(chosenUri)
            if (permError != null) {
                updateTask(taskId) { it.copy(status = DownloadStatus.FAILED, error = permError) }
                synchronized(this@DownloadManager) {
                    if (latestTasks.none {
                            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
                        }
                    ) {
                        _isRunning.value = false
                        stopGovernors()
                        DownloadService.stop(appContext)
                    }
                }
                return@launch
            }
            // Re-read the experimental flag so a retry honours the current setting (default OFF).
            adaptiveEngine = settings.adaptiveEngine.first()
            val customRoot = chosenUri?.let { DocumentFile.fromTreeUri(appContext, it) }
            val defaultRoot = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ROMs").apply { mkdirs() }
            try {
                downloadOne(taskId, customRoot, defaultRoot)
            } finally {
                synchronized(this@DownloadManager) {
                    if (latestTasks.none {
                            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
                        }
                    ) {
                        _isRunning.value = false
                        stopGovernors()
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
        // USER pause. Records userPaused so the Wi-Fi governor never auto-resumes a batch the user
        // deliberately paused (the governor only ever resumes a pause IT caused).
        userPaused = true
        _isPaused.value = true
        if (_isRunning.value) DownloadService.updatePausedState(appContext, paused = true)
    }
    fun resume() {
        // USER resume. Clears BOTH the user-pause and governor-pause flags: an explicit user resume
        // overrides any governor pause currently in effect.
        userPaused = false
        governorPaused = false
        _isPaused.value = false
        if (_isRunning.value) DownloadService.updatePausedState(appContext, paused = false)
    }
    fun cancelAll() {
        cancelRequested = true
        batchJob?.cancel()
        userPaused = false
        governorPaused = false
        _isPaused.value = false
        _isRunning.value = false
        stopGovernors()
        DownloadService.stop(appContext)
    }

    // ---- OVERNIGHT GOVERNORS: thin Android-framework wiring ----------------------------------

    /**
     * (3a) Apply the Wi-Fi-only DECISION (computed by the pure [decideWifiGovernorAction]) given the
     * current network's connected + metered state. Pauses/resumes ONLY the governor's own pause —
     * never a user pause. Reads the live [wifiOnly] setting each call so toggling the setting takes
     * effect immediately.
     */
    private fun applyWifiGovernor(isConnected: Boolean, isMetered: Boolean) {
        scope.launch {
            val wifiOnly = settings.wifiOnly.first()
            val action = decideWifiGovernorAction(
                wifiOnly = wifiOnly,
                isConnected = isConnected,
                isMetered = isMetered,
                governorPaused = governorPaused
            )
            when (action) {
                WifiGovernorAction.PAUSE -> {
                    // Don't override a manual user pause; just record that the governor wants it
                    // paused so it knows to auto-resume later.
                    if (!userPaused) {
                        governorPaused = true
                        _isPaused.value = true
                        if (_isRunning.value) DownloadService.updatePausedState(appContext, paused = true)
                        Log.i("EmuHelper", "wifi-only governor: PAUSED (metered network, wifiOnly on)")
                    } else {
                        governorPaused = true  // remember our intent even while user-paused
                    }
                }
                WifiGovernorAction.RESUME -> {
                    governorPaused = false
                    // Only actually un-pause if the user hasn't manually paused.
                    if (!userPaused) {
                        _isPaused.value = false
                        if (_isRunning.value) DownloadService.updatePausedState(appContext, paused = false)
                        Log.i("EmuHelper", "wifi-only governor: RESUMED (unmetered Wi-Fi returned)")
                    }
                }
                WifiGovernorAction.NONE -> { /* no change */ }
            }
        }
    }

    /** Register the connectivity + thermal listeners for the lifetime of a batch. Idempotent. */
    private fun startGovernors() {
        // (3a) Connectivity callback.
        if (networkCallback == null) {
            runCatching {
                val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager
                if (cm != null) {
                    val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                        override fun onCapabilitiesChanged(
                            network: android.net.Network,
                            caps: android.net.NetworkCapabilities
                        ) {
                            val metered = !caps.hasCapability(
                                android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
                            )
                            val connected = caps.hasCapability(
                                android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
                            )
                            applyWifiGovernor(isConnected = connected, isMetered = metered)
                        }

                        override fun onLost(network: android.net.Network) {
                            // Lost the network entirely -> treat as not-connected (governor pauses if
                            // wifiOnly is on).
                            applyWifiGovernor(isConnected = false, isMetered = true)
                        }
                    }
                    cm.registerDefaultNetworkCallback(cb)
                    networkCallback = cb
                }
            }
        }
        // (3b) Thermal status listener (API 29+, minSdk is 29).
        if (thermalListener == null) {
            runCatching {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                if (pm != null) {
                    val listener = android.os.PowerManager.OnThermalStatusChangedListener { status ->
                        Log.i(
                            "EmuHelper",
                            "thermal governor: status=$status -> holding " +
                                "${thermalPermitsToHold(status)} permits (liveCap " +
                                "${MAX_TOTAL_CONNECTIONS - thermalPermitsToHold(status)})"
                        )
                        scope.launch { runCatching { thermalGovernor.applyThermalStatus(status) } }
                    }
                    pm.addThermalStatusListener(listener)
                    thermalListener = listener
                    // Apply the CURRENT status immediately so a batch started while already hot backs
                    // off without waiting for the next transition.
                    val current = runCatching { pm.currentThermalStatus }.getOrDefault(0)
                    scope.launch { runCatching { thermalGovernor.applyThermalStatus(current) } }
                }
            }
        }
    }

    /** Unregister listeners and release any held thermal permits. Called at batch end / cancel. */
    private fun stopGovernors() {
        networkCallback?.let { cb ->
            runCatching {
                val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager
                cm?.unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
        thermalListener?.let { l ->
            runCatching {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                pm?.removeThermalStatusListener(l)
            }
        }
        thermalListener = null
        // Release any permits the thermal governor is still holding so the next batch starts with the
        // full budget. governorPaused is reset so a new batch isn't born paused.
        governorPaused = false
        scope.launch { runCatching { thermalGovernor.release() } }
    }

    /** Reset visible state when the user leaves a finished batch. */
    fun clear() {
        if (_isRunning.value) return
        // Drop the per-list folder override so the next non-list batch uses the global folder.
        activeFolderOverride = null
        // B4: Attempt to record history via the same CAS guard used at batch-end.
        // If batch-end already recorded, the CAS in recordBatchHistory returns early (no-op).
        // Reset the AtomicBoolean BEFORE launching so the next batch starts clean,
        // but only after the snapshot is captured.
        val snapshot = latestTasks
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
        latestTasks = emptyList()     // reset source of truth alongside the StateFlow
        _tasks.value = emptyList()
        _statusText.value = "Preparing..."
        _totalProgress.value = 0f
        _totalSpeed.value = 0.0
        _eta.value = "--"
        smoothedSpeed = 0.0
        // FIX 3: clear the SAF subfolder cache after each batch so DocumentFile objects
        // (which hold a Uri + ContentResolver chain) don't accumulate across sessions.
        // Folders are cheap to re-resolve, so clearing here is the correct trade-off.
        safCache.clear()
    }

    // ---- one file ---------------------------------------------------------

    private suspend fun downloadOne(taskId: String, customRootBase: DocumentFile?, defaultRootBase: File) {
        // FIX 2: Early cancellation guard — stop wasting HTTP probes (resolveFinalUrl +
        // supportsRange = 2 round-trips) for files that haven't started yet when a batch cancel
        // was requested. Without this, queued files still fired both blocking probes before
        // noticing the cancellation flag at the isCancelled() check inside downloadFileSegmented.
        if (cancelRequested) {
            updateTask(taskId) { it.copy(status = DownloadStatus.CANCELLED, speed = 0.0) }
            return
        }
        val base = latestTasks.firstOrNull { it.id == taskId } ?: return
        val filename = base.filename
        val expected = base.size
        val url = base.url
        val subfolder = base.subfolder
        val expectedMd5 = base.md5  // empty => no checksum known => verification skipped

        updateTask(taskId) { it.copy(status = DownloadStatus.DOWNLOADING, error = "", speed = 0.0) }

        // If the final file already exists at the destination and looks complete, skip.
        if (destinationHasComplete(customRootBase, defaultRootBase, subfolder, filename, expected)) {
            updateTask(taskId) { it.copy(downloaded = expected, status = DownloadStatus.DONE, speed = 0.0) }
            return
        }

        // Download into app cache (real File, supports random-access multi-connection).
        val cacheDir = File(appContext.cacheDir, "dl").apply { mkdirs() }
        val cacheFile = File(cacheDir, "${taskId.hashCode()}_$filename.part")
        // RESUME (v0.8): the adaptive engine writes a sidecar manifest of completed chunk indices
        // next to the .part. When we discard the .part we must drop its manifest too, else a stale
        // "all done" manifest could mislead a future resume. delete both via [discardPartAndManifest].
        val cacheManifest = File(cacheDir, cacheFile.name + ".manifest")
        fun discardPartAndManifest() {
            cacheFile.delete()
            cacheManifest.delete()
        }

        // Gather mirror hosts for this file (primary + d1/d2 + CDN nodes) so the
        // segmented downloader can spread load and fail over between them.
        val mirrors = runCatching { source.mirrorUrls(base.identifier, base.relativeName) }
            .getOrDefault(listOf(url))
            .ifEmpty { listOf(url) }

        // Register this file's demand so the batch MirrorScheduler can assign it a distinct mirror
        // away from other concurrent files (and so peers re-consult the scheduler between chunks).
        // Removed in the finally below on EVERY exit path (done/failed/cancelled) so a finished file
        // frees its mirror for the survivors. Only meaningful for the adaptive engine; harmless for
        // the static path (which ignores the scheduler args).
        activeDemands[taskId] = FileDemand(fileId = taskId, mirrors = mirrors, sizeBytes = expected)

        try {
            val total = source.downloadFileSegmented(
                candidateUrls = mirrors,
                expectedSize = expected,
                destFile = cacheFile,
                segments = segmentsPerFile,
                // EXPERIMENTAL: gate the LANED chunk-queue path behind the default-OFF flag. When
                // false, the static path runs UNCHANGED. The shared 24-connection budget is passed
                // through so the thermal cap holds across files regardless of engine.
                //
                // MULTI-FILE BATCH SCHEDULER: the batch-level [mirrorScheduler] assigns DISTINCT
                // mirrors to concurrent files (file A on datacenter-6, file B on datacenter-8) so two
                // files no longer both pin the same mirror and split its ~2 MB/s ceiling. The
                // [liveDemands] supplier hands the engine a snapshot of every CURRENTLY-active file's
                // mirrors so runners re-consult the scheduler between chunks (a finished peer frees
                // its mirror for the survivors). The scheduler only decides WHICH host each runner
                // pins — the global 24-connection cap is still enforced by [connectionBudget].
                adaptive = adaptiveEngine,
                connectionBudget = connectionBudget,
                scheduler = mirrorScheduler,
                fileId = taskId,
                liveDemands = { activeDemands.values.toList() },
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

            // Integrity check: a multi-GB, multi-segment download can silently corrupt
            // (a stale byte range, a flaky node) yet still pass the 1%-tolerance size check.
            // When the source published an md5 for this file, hash the freshly downloaded
            // bytes (the cache .part, which is exactly what the server served — verbatim for
            // copies, and the .zip itself when extraction is enabled) and compare. On a clear
            // mismatch we FAIL the task and do NOT publish the corrupt file. If the md5 is
            // unknown or hashing itself errors, we proceed exactly as before (unverified).
            if (expectedMd5.isNotBlank()) {
                val actualMd5 = computeMd5OrNull(cacheFile)  // off-main, cancellation-safe
                if (actualMd5 != null && !actualMd5.equals(expectedMd5, ignoreCase = true)) {
                    // Don't rename/copy: throwing CorruptDownloadException lands in the catch below,
                    // which DELETES the .part + manifest (a genuinely corrupt file must NOT be
                    // resumed — every byte is suspect) and marks the task FAILED. This is the ONLY
                    // failure path that discards the .part; all other (retryable) failures keep it.
                    throw CorruptDownloadException("File corrupt - retry")
                }
            }

            _statusText.value = "Saving $filename…"
            if (extractArchives && filename.lowercase().endsWith(".zip")) {
                extractZipToDestination(cacheFile, customRootBase, defaultRootBase, subfolder)
            } else {
                copyToDestination(cacheFile, customRootBase, defaultRootBase, subfolder, filename)
            }
            // Published successfully — the .part + manifest have done their job; drop both.
            discardPartAndManifest()
            updateTask(taskId) { it.copy(downloaded = if (expected > 0) expected else total, status = DownloadStatus.DONE, speed = 0.0) }
        } catch (c: CancellationException) {
            // The user explicitly cancelled (or paused-then-cancelled): there is nothing to resume,
            // so drop the .part + manifest. (A process FORCE-KILL never reaches this catch — the
            // coroutine is killed outright — so the .part + manifest survive on disk for resume,
            // which is exactly the overnight-resume win.)
            discardPartAndManifest()
            updateTask(taskId) { it.copy(status = DownloadStatus.CANCELLED, speed = 0.0) }
        } catch (e: CorruptDownloadException) {
            // MD5 mismatch — the assembled bytes are genuinely corrupt. DELETE the .part + manifest
            // so the next attempt re-fetches from scratch (a resume would just re-assemble the same
            // bad bytes). This is the final correctness gate the resume feature relies on.
            Log.w("EmuHelper", "Download corrupt (md5 mismatch): $filename", e)
            discardPartAndManifest()
            updateTask(taskId) { it.copy(status = DownloadStatus.FAILED, speed = 0.0, error = e.message ?: "File corrupt - retry") }
        } catch (e: Exception) {
            // RETRYABLE failure (network error, incomplete, host blip). KEEP the .part + manifest so
            // a retry / next-launch resume continues from the chunks already done instead of
            // restarting a multi-GB download from zero. The end-of-download MD5 verify still runs on
            // the next completed attempt, so a kept-but-bad .part can never publish a corrupt file.
            Log.w("EmuHelper", "Download failed (retryable, keeping .part for resume): $filename", e)
            // B11: Detect out-of-disk-space and surface a clear message.
            val errorMessage = if (e is IOException) {
                val msg = e.message ?: ""
                if (msg.contains("ENOSPC", ignoreCase = true) || msg.contains("No space", ignoreCase = true))
                    "Storage full — free some space and retry"
                else msg.ifBlank { e.javaClass.simpleName }
            } else e.message ?: e.javaClass.simpleName
            updateTask(taskId) { it.copy(status = DownloadStatus.FAILED, speed = 0.0, error = errorMessage) }
        } finally {
            // Free this file's mirror demand on EVERY exit path (done/failed/cancelled) so the batch
            // scheduler reassigns its datacenter to the remaining files on their next rebalance.
            activeDemands.remove(taskId)
        }
    }

    /**
     * Stream the file through MD5 and return the lowercase hex digest, or null if hashing
     * could not complete (I/O error, MessageDigest unavailable, etc.) — callers treat null
     * as "unverified, proceed" rather than a failure.
     *
     * Runs on [Dispatchers.IO] so it never touches the main thread, reads in 1 MB chunks so a
     * multi-GB file streams with a bounded buffer (no slowdown worth noticing on small files),
     * and checks for cancellation each chunk. A genuine coroutine cancellation is re-thrown so
     * a cancelled download is recorded as CANCELLED rather than silently "proceeding".
     */
    private suspend fun computeMd5OrNull(file: File): String? = withContext(Dispatchers.IO) {
        try {
            val digest = java.security.MessageDigest.getInstance("MD5")
            file.inputStream().buffered().use { input ->
                val buf = ByteArray(1 shl 20) // 1 MB
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    // Cooperative cancellation: bail promptly if the batch was cancelled or the
                    // coroutine itself is cancelled, so we don't keep hashing a dead download.
                    if (cancelRequested) throw CancellationException()
                    ensureActive()
                    digest.update(buf, 0, read)
                }
            }
            val sb = StringBuilder(32)
            for (b in digest.digest()) {
                val v = b.toInt() and 0xFF
                sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0x0F])
            }
            sb.toString()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            // Never crash the download over a hashing failure — log and let the caller proceed.
            Log.w("EmuHelper", "MD5 verify failed for ${file.name}; proceeding unverified", e)
            null
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
        // FIX 4: give each entry a unique timestamp (now + index offset) so that a 30-game
        // batch doesn't stamp 30 entries with the exact same millisecond. Without this, the
        // HistoryScreen LazyColumn key "${timestamp}_${filename}" collides when the same game
        // appears twice (retry), silently dropping one entry. The +index offset also preserves
        // the relative order within a batch for the newest-first display.
        val entries = terminal.mapIndexed { index, task ->
            HistoryEntry(
                filename = task.filename,
                subfolder = task.subfolder,
                sizeBytes = task.size,
                status = task.status.name,
                timestampMillis = now + index,
                identifier = task.identifier,
                console = task.console,
                name = task.name,
                // Carry the source checksum into history so the on-disk Library view can
                // integrity-verify a file later against the hash it was downloaded with.
                md5 = task.md5
            )
        }
        historyStore.addAll(entries)
    }

    // ---- state plumbing ---------------------------------------------------

    @Synchronized
    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        // Read from the always-current source of truth (not _tasks.value, which may now
        // lag the UI by one throttled progress update — see FIX #1 below).
        val all = latestTasks
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return
        val oldStatus = all[idx].status
        val updated = all.toMutableList().apply { this[idx] = transform(this[idx]) }
        val newStatus = updated[idx].status
        // Always advance the source of truth so the NEXT updateTask (and any internal
        // reader) sees this mutation even if we don't emit to the UI this time.
        latestTasks = updated

        // FIX #1: decouple emission from the segment-callback rate.
        // updateTask fires ~69×/sec across up to 24 segments; assigning _tasks.value on
        // every call made the StateFlow emit at that rate and forced Compose to re-diff the
        // whole LazyColumn each time -> jank on 2-3GB handhelds. Gate the EMISSION behind the
        // same throttle that already guarded recomputeAggregates, with two correctness
        // carve-outs that must ALWAYS emit so the UI never shows a wrong final/paused state:
        //   - terminal status (DONE/FAILED/CANCELLED): the last word on a task.
        //   - any status TRANSITION (e.g. -> DOWNLOADING/PAUSED): once paused, progress
        //     callbacks stop, so a throttled-away PAUSED would otherwise never reach the UI.
        // Pure progress ticks within the throttle window are coalesced (still recorded in
        // latestTasks; the next emission carries the freshest bytes/speed).
        val isTerminal = newStatus == DownloadStatus.DONE ||
                         newStatus == DownloadStatus.FAILED ||
                         newStatus == DownloadStatus.CANCELLED
        val statusChanged = newStatus != oldStatus
        val now = System.currentTimeMillis()
        val throttleElapsed = now - lastAggregateMs >= AGGREGATE_INTERVAL_MS

        // Aggregate recompute keeps its ORIGINAL gate (terminal || throttle window) so
        // totals/ETA/notification timing is byte-for-byte unchanged from before FIX #1.
        if (isTerminal || throttleElapsed) {
            lastAggregateMs = now
            recomputeAggregates(updated)
        }
        // Emit to the UI on the same window, PLUS on any status transition. The transition
        // carve-out is essential: when a task flips to PAUSED its progress callbacks stop,
        // so a throttled-away PAUSED would never reach the UI; likewise terminal states are
        // status changes and so always emit. Pure progress ticks inside the window are
        // coalesced — latestTasks already holds them and the next emission carries them.
        if (isTerminal || statusChanged || throttleElapsed) {
            _tasks.value = updated
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
