package io.github.mayusi.emuhelper.ui.browse

import android.app.Application
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emuhelper.data.config.Catalog
import io.github.mayusi.emuhelper.data.model.GameFile
import io.github.mayusi.emuhelper.data.source.RemoteSource
import io.github.mayusi.emuhelper.data.storage.HistoryStore
import io.github.mayusi.emuhelper.ui.common.Dimens
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanStateHolder @Inject constructor() {
    val scannedFiles = MutableStateFlow<Map<String, List<GameFile>>>(emptyMap())
    val selectedGames = MutableStateFlow<Map<String, MutableSet<String>>>(emptyMap())
    val uiState = MutableStateFlow(ScanUiState())
    /** Games queued for the Download screen — replaces savedStateHandle round-tripping. */
    val downloadQueue = MutableStateFlow<List<io.github.mayusi.emuhelper.data.model.CuratedGame>>(emptyList())
    /** Games handed from the picker (BUILD mode) to the Save-list screen. */
    val pendingListGames = MutableStateFlow<List<io.github.mayusi.emuhelper.data.model.CuratedGame>>(emptyList())

    /**
     * Release the large scannedFiles map (can be 10s of MB) once the user has committed
     * their selection and the chosen games have been copied into downloadQueue / pendingListGames.
     * Does NOT touch selectedGames, downloadQueue, or pendingListGames — those are still
     * needed downstream. scannedFiles is the only scan-only payload worth freeing.
     */
    fun clearScan() {
        scannedFiles.value = emptyMap()
        selectedGames.value = emptyMap()
    }
}

data class ScanFailure(val url: String, val reason: String)

data class ScanUiState(
    val isScanning: Boolean = false,
    val scanComplete: Boolean = false,
    val totalSources: Int = 0,
    val completedSources: Int = 0,
    val totalFiles: Int = 0,
    val currentSource: String = "",
    val emptyMessage: String = "",
    val error: String = "",
    val failures: List<ScanFailure> = emptyList()
)

@HiltViewModel
class BrowseViewModel @Inject constructor(
    application: Application,
    private val source: RemoteSource,
    private val stateHolder: ScanStateHolder,
    historyStore: HistoryStore
) : AndroidViewModel(application) {

    val scannedFiles: StateFlow<Map<String, List<GameFile>>> = stateHolder.scannedFiles
    val selectedGames: StateFlow<Map<String, MutableSet<String>>> = stateHolder.selectedGames
    val uiState: StateFlow<ScanUiState> = stateHolder.uiState
    val downloadQueue: StateFlow<List<io.github.mayusi.emuhelper.data.model.CuratedGame>> = stateHolder.downloadQueue

    /**
     * Set of filenames the user has already downloaded (status == "DONE" in history).
     * Used by [GamePickerScreen] to show a subtle "Already downloaded" badge per row.
     */
    val downloadedFilenames: StateFlow<Set<String>> = historyStore.entries
        .map { entries -> entries.filter { it.status == "DONE" }.map { it.filename }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun queueDownloads(games: List<io.github.mayusi.emuhelper.data.model.CuratedGame>) {
        stateHolder.downloadQueue.value = games
        // NOTE: clearScan() is intentionally NOT called here. Freeing scannedFiles while
        // GamePickerScreen is still composed (mid-transition) causes a flash of the
        // "No files found" empty state. Instead, GamePickerScreen calls clearScan() from
        // its DisposableEffect.onDispose so memory is freed only after the screen leaves
        // composition — after the nav animation is fully done.
    }

    fun clearQueue() {
        stateHolder.downloadQueue.value = emptyList()
    }

    /**
     * Release the large scannedFiles map. Called from GamePickerScreen's
     * DisposableEffect.onDispose so memory is freed only after the picker fully
     * leaves composition (post-nav-animation), not mid-transition.
     */
    fun clearScan() {
        stateHolder.clearScan()
    }

    /** Hand the picker's current selection to the Save-list screen (BUILD mode). */
    fun stageForSaving(games: List<io.github.mayusi.emuhelper.data.model.CuratedGame>) {
        stateHolder.pendingListGames.value = games
        // NOTE: clearScan() is intentionally NOT called here — same reasoning as
        // queueDownloads(). GamePickerScreen's DisposableEffect.onDispose calls it
        // after the screen fully leaves composition.
    }

    /** Tracks the running scan so a new scan can cancel a stale one (prevents the
     *  "isScanning stuck true / only 1 source scanned on the 2nd run" bug). */
    private var scanJob: kotlinx.coroutines.Job? = null
    private var previousScanKeys: Set<String> = emptySet()

    fun startScan(consoles: Set<String>) {
        if (consoles.isEmpty()) {
            // Defensive: an empty set means we were re-invoked after navigation
            // cleared the savedStateHandle. Do nothing — never wipe existing scan results.
            Log.i("EmuHelper", "scan start: IGNORED (empty consoles set)")
            return
        }
        // If we already have COMPLETE results for the exact same selection, reuse them.
        val previousKeys = stateHolder.scannedFiles.value.keys
        if (previousKeys.isNotEmpty() && previousKeys == consoles &&
            stateHolder.uiState.value.scanComplete && !stateHolder.uiState.value.isScanning
        ) {
            Log.i("EmuHelper", "scan start: SKIP (already have results for $consoles)")
            return
        }
        // A scan for THIS selection is already in flight — don't double-start.
        if (scanJob?.isActive == true && previousScanKeys == consoles) {
            Log.i("EmuHelper", "scan start: in-flight for $consoles, ignoring")
            return
        }
        // Cancel any stale/different in-flight scan so it can't leave isScanning stuck
        // or partially overwrite results.
        scanJob?.cancel()
        previousScanKeys = consoles
        // Reset state SYNCHRONOUSLY so any in-flight recomposition cannot see a
        // stale scanComplete=true and auto-navigate before this scan starts.
        stateHolder.scannedFiles.value = emptyMap()
        stateHolder.selectedGames.value = emptyMap()
        stateHolder.uiState.value = ScanUiState(isScanning = true)
        Log.i("EmuHelper", "scan start: consoles=$consoles")
        scanJob = viewModelScope.launch {
            val failures = java.util.Collections.synchronizedList(mutableListOf<ScanFailure>())
            try {
                // Build the full work list (console -> its source URLs).
                val work = consoles.sorted().mapNotNull { c ->
                    Catalog.IA_LINKS[c]?.let { links -> c to links }
                }
                val total = work.sumOf { it.second.size }
                val done = java.util.concurrent.atomic.AtomicInteger(0)
                val fileCount = java.util.concurrent.atomic.AtomicInteger(0)
                // Per-console accumulators (thread-safe).
                val perConsole = java.util.concurrent.ConcurrentHashMap<String, MutableList<GameFile>>()
                work.forEach { perConsole[it.first] = java.util.Collections.synchronizedList(mutableListOf()) }

                fun publishProgress(current: String) {
                    stateHolder.uiState.value = ScanUiState(
                        isScanning = true,
                        totalSources = total,
                        completedSources = done.get(),
                        totalFiles = fileCount.get(),
                        currentSource = current,
                        failures = failures.toList()
                    )
                }
                publishProgress("Starting…")

                // Fetch ALL sources CONCURRENTLY (bounded) instead of one-at-a-time.
                // PS2 has 32 sources — sequential took ~30s; concurrent takes a few.
                val sem = kotlinx.coroutines.sync.Semaphore(6)
                coroutineScope {
                    for ((console, links) in work) {
                        val d = Catalog.CONSOLES[console]?.display ?: console
                        for (url in links) {
                            launch {
                                sem.withPermit {
                                    if (!isActive) return@withPermit
                                    try {
                                        val files = source.fetchFileList(url)
                                        perConsole[console]?.addAll(files)
                                        fileCount.addAndGet(files.size)
                                    } catch (e: Exception) {
                                        Log.w("EmuHelper", "Scan failed for $url", e)
                                        failures.add(ScanFailure(url, "${e.javaClass.simpleName}: ${e.message ?: "unknown"}"))
                                    }
                                    done.incrementAndGet()
                                    publishProgress("$d: ${url.substringAfterLast("/").take(40)}")
                                }
                            }
                        }
                    }
                }

                // Assemble final results.
                val allFiles = mutableMapOf<String, List<GameFile>>()
                val selMap = mutableMapOf<String, MutableSet<String>>()
                for ((console, list) in perConsole) {
                    val snapshot = synchronized(list) { list.toList() }
                    if (snapshot.isNotEmpty()) {
                        allFiles[console] = snapshot
                        selMap[console] = mutableSetOf()
                    }
                    Log.i("EmuHelper", "scan/$console: ${snapshot.size} files")
                }
                stateHolder.scannedFiles.value = allFiles
                stateHolder.selectedGames.value = selMap
                val emptyMsg = if (allFiles.isEmpty()) {
                    if (failures.isEmpty()) "No files matched the filter for the selected platforms."
                    else "No files found — every source failed. See details below."
                } else ""
                stateHolder.uiState.value = ScanUiState(
                    scanComplete = true,
                    totalSources = total,
                    completedSources = done.get(),
                    totalFiles = allFiles.values.sumOf { it.size },
                    emptyMessage = emptyMsg,
                    failures = failures.toList()
                )
                Log.i("EmuHelper", "scan DONE: ${allFiles.values.sumOf { it.size }} files, ${failures.size} failures")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("EmuHelper", "Scan crashed", e)
                stateHolder.uiState.value = ScanUiState(
                    scanComplete = true,
                    error = "${e.javaClass.simpleName}: ${e.message}",
                    failures = failures.toList()
                )
            }
        }
    }

    fun toggleGame(console: String, gameId: String) {
        // Build a NEW map with a NEW set for the affected console so StateFlow
        // sees a value that doesn't equal() the previous one and triggers recomposition.
        val current = selectedGames.value
        val newSet = (current[console]?.toMutableSet() ?: mutableSetOf())
        if (gameId in newSet) newSet.remove(gameId) else newSet.add(gameId)
        val newMap = current.toMutableMap()
        newMap[console] = newSet
        stateHolder.selectedGames.value = newMap
    }

    fun selectAll(console: String, games: List<GameFile>) {
        val newMap = selectedGames.value.toMutableMap()
        newMap[console] = games.map { it.filename }.toMutableSet()
        stateHolder.selectedGames.value = newMap
    }

    fun selectNone(console: String) {
        val newMap = selectedGames.value.toMutableMap()
        newMap[console] = mutableSetOf()
        stateHolder.selectedGames.value = newMap
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanProgressScreen(
    selectedConsoles: Set<String>,
    instantInstall: Boolean = false,
    onScanComplete: () -> Unit,
    onBack: () -> Unit,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    // `instantInstall` is forwarded to PICK as a nav argument by the caller, so the
    // picker is self-contained — no shared/singleton flag to get out of sync.
    val state by viewModel.uiState.collectAsState()
    // Trigger a fresh scan whenever the screen is reached with a non-empty selection.
    // Empty selections are ignored to avoid wiping state after onScanComplete()
    // triggers a final recomposition of this screen with cleared savedStateHandle.
    val selectionKey = remember(selectedConsoles) { selectedConsoles.toSortedSet().joinToString(",") }

    // Session-aware auto-nav latch. The bug: when re-entering Scan, collectAsState()
    // first yields the PREVIOUS run's leftover `scanComplete=true`, and the auto-nav
    // effect can fire on that stale snapshot before startScan resets state — landing
    // you on Pick (often "No files found") while the new scan is still running.
    // We arm the latch only AFTER startScan has run for the current selection, so a
    // pre-existing completion can never trigger navigation.
    var armed by remember(selectionKey) { mutableStateOf(false) }

    LaunchedEffect(selectionKey) {
        if (selectedConsoles.isNotEmpty()) {
            viewModel.startScan(selectedConsoles)
            // startScan resets state synchronously (isScanning=true) unless it chose
            // to reuse cached results for the identical selection. Either way it's now
            // safe to honour a completion for THIS selection.
            armed = true
        }
    }

    // Guard against navigating twice (which could pop SCAN then land on a half-state).
    var navigated by remember(selectionKey) { mutableStateOf(false) }
    LaunchedEffect(armed, state.scanComplete, state.isScanning, state.totalFiles, state.failures) {
        // Auto-navigate to Pick ONLY once this session's scan is genuinely complete
        // with some files AND no failures. `armed` ensures we ignore a previous run's
        // leftover scanComplete; `navigated` ensures we fire exactly once.
        // When failures.isNotEmpty(), we hold on the scan screen so the user can see
        // which sources failed and choose to continue or not (see "Continue" button below).
        if (armed && !navigated && state.scanComplete && !state.isScanning &&
            state.error.isBlank() && state.totalFiles > 0 && state.failures.isEmpty()
        ) {
            navigated = true
            onScanComplete()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scanning", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Dimens.ScreenHorizontal * 2)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))
            if (state.isScanning) {
                CircularProgressIndicator(Modifier.size(56.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 4.dp)
                Spacer(Modifier.height(Dimens.SectionGap))
            }
            Text(
                if (state.isScanning) "Scanning..." else "Complete!",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
            if (state.totalSources > 0) {
                Spacer(Modifier.height(Dimens.SectionGap))
                val scanProgress = if (state.totalSources > 0) state.completedSources.toFloat() / state.totalSources else 0f
                val animatedScanProgress by animateFloatAsState(targetValue = scanProgress, label = "scanProgress")
                LinearProgressIndicator(
                    progress = { animatedScanProgress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.height(Dimens.ItemGap))
                Text(
                    "${state.completedSources}/${state.totalSources} sources · ${state.totalFiles} files",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.emptyMessage.isNotBlank()) {
                Spacer(Modifier.height(Dimens.ItemGap))
                Text(
                    state.emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.error.isNotBlank()) {
                Spacer(Modifier.height(Dimens.ItemGap))
                Text(
                    state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(Dimens.ItemGap))
            Text(
                state.currentSource,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            if (state.failures.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.SectionGap))
                Text(
                    "${state.failures.size} source${if (state.failures.size != 1) "s" else ""} failed",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(Dimens.ItemGap))
                state.failures.take(5).forEach { f ->
                    Text(
                        "• ${f.url.substringAfterLast("/").take(48)} — ${f.reason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.failures.size > 5) {
                    Text(
                        "(and ${state.failures.size - 5} more sources unavailable)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            if (state.scanComplete && state.totalFiles > 0 && state.failures.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.SectionGap))
                val unavailableCount = state.failures.size
                val totalSourcesLabel = if (state.totalSources > 0) " of ${state.totalSources}" else ""
                Text(
                    "$unavailableCount$totalSourcesLabel sources unavailable — some files may be missing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Dimens.ItemGap))
                Button(
                    onClick = {
                        // Set navigated so the LaunchedEffect doesn't also fire.
                        navigated = true
                        onScanComplete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("Continue with ${state.totalFiles} files") }
            }
            // U4: All-fail dead-end: show back and retry actions when no files were found.
            if (state.scanComplete && !state.isScanning && state.totalFiles == 0 && state.emptyMessage.isNotBlank()) {
                Spacer(Modifier.height(Dimens.SectionGap))
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Go back to select platforms") }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.startScan(selectedConsoles) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("Retry scan") }
            }
        }
    }
}
