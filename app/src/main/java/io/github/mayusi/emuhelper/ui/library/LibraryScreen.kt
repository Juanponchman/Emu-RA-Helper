package io.github.mayusi.emuhelper.ui.library

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.emuhelper.data.storage.HistoryStore
import io.github.mayusi.emuhelper.data.storage.SettingsStore
import io.github.mayusi.emuhelper.ui.common.Dimens
import io.github.mayusi.emuhelper.ui.common.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

// ---------------------------------------------------------------------------
// Model types for the on-disk library
// ---------------------------------------------------------------------------

/** Outcome of an integrity re-check for a single file. */
enum class VerifyState {
    /** Not yet verified (default). */
    NONE,

    /** Hashing in progress. */
    RUNNING,

    /** Recomputed digest equals the recorded reference hash. */
    MATCH,

    /** Recomputed digest differs from the reference hash — file may be corrupt. */
    MISMATCH,

    /** File was hashed but history holds no reference hash to compare against. */
    NO_REFERENCE,

    /** Hashing failed (I/O error, file unreadable). */
    ERROR
}

/** A single file found on disk inside a category subfolder. */
data class LibraryFile(
    val name: String,
    val sizeBytes: Long,
    /** Opaque locator used to reopen the file for hashing. For a SAF tree this is the
     *  DocumentFile URI string; for the app-private default dir this is the absolute path. */
    val locator: String,
    /** True when a [HistoryEntry] with this filename exists — i.e. this app recorded it. */
    val tracked: Boolean,
    /** Reference MD5 from history (lowercase hex), or "" when unknown. */
    val referenceMd5: String,
    val verifyState: VerifyState = VerifyState.NONE,
    /** Lowercase hex digest computed on disk, shown after a verify completes. */
    val computedMd5: String = ""
)

/** A category subfolder (e.g. "SNES") and the files inside it. */
data class LibraryFolder(
    val name: String,
    val files: List<LibraryFile>
) {
    val totalSize: Long get() = files.sumOf { it.sizeBytes }
    val count: Int get() = files.size
}

/** Top-level UI state for the Library screen. */
data class LibraryUiState(
    val loading: Boolean = true,
    val folders: List<LibraryFolder> = emptyList(),
    /** Non-null when the folder could not be read (permission lost / unset). */
    val error: String? = null,
    /** True when there is genuinely nothing on disk (folder readable but empty). */
    val empty: Boolean = false
) {
    val totalFiles: Int get() = folders.sumOf { it.count }
    val totalSize: Long get() = folders.sumOf { it.totalSize }
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val historyStore: HistoryStore
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    /** Active verify jobs keyed by file locator, so each can be cancelled independently. */
    private val verifyJobs = HashMap<String, Job>()

    init {
        refresh()
    }

    /**
     * Walk the download folder off the main thread and publish the grouped result.
     * DocumentFile.listFiles() is slow content-resolver IPC, so this runs on Dispatchers.IO
     * and surfaces a loading state while it works.
     */
    fun refresh() {
        viewModelScope.launch {
            _state.value = LibraryUiState(loading = true)
            val historyNames: Set<String>
            val referenceByName: Map<String, String>
            // Snapshot history once; we cross-reference by filename.
            val historyEntries = historyStore.entries.first()
            historyNames = historyEntries.map { it.filename }.toSet()
            // Prefer the newest entry's md5 when a filename appears more than once.
            referenceByName = historyEntries
                .filter { it.md5.isNotBlank() }
                .associate { it.filename to it.md5 }

            val result = withContext(Dispatchers.IO) {
                walkFolder(historyNames, referenceByName)
            }
            _state.value = result
        }
    }

    /**
     * Build the grouped folder listing. Returns a populated [LibraryUiState] describing either
     * the contents, an empty library, or an error (permission lost / unreadable).
     */
    private suspend fun walkFolder(
        historyNames: Set<String>,
        referenceByName: Map<String, String>
    ): LibraryUiState {
        val chosenUri: Uri? = settings.downloadFolder.first()
        return try {
            val folders = if (chosenUri != null) {
                walkSafTree(chosenUri, historyNames, referenceByName)
            } else {
                walkDefaultDir(historyNames, referenceByName)
            }
            when {
                folders == null -> LibraryUiState(
                    loading = false,
                    error = "Storage folder access was lost — re-pick your download folder from the menu on the home screen."
                )
                folders.isEmpty() -> LibraryUiState(loading = false, empty = true)
                else -> LibraryUiState(loading = false, folders = folders)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("EmuHelper", "Library walk failed", e)
            LibraryUiState(
                loading = false,
                error = "Couldn't read the download folder. Try re-picking it from the home menu."
            )
        }
    }

    /**
     * Walk a SAF tree. Returns null when the grant is lost/unreadable, an empty list when the
     * folder exists but holds no files, or the grouped folders otherwise.
     *
     * Files that sit directly in the root (not inside a category subfolder) are grouped under
     * a synthetic "(root)" bucket so nothing on disk is hidden from the audit.
     */
    private fun walkSafTree(
        treeUri: Uri,
        historyNames: Set<String>,
        referenceByName: Map<String, String>
    ): List<LibraryFolder>? {
        val root = DocumentFile.fromTreeUri(context, treeUri)
        if (root == null || !root.exists() || !root.canRead()) return null

        val folders = mutableListOf<LibraryFolder>()
        val rootFiles = mutableListOf<LibraryFile>()
        for (child in root.listFiles()) {
            val childName = child.name ?: continue
            if (child.isDirectory) {
                val files = child.listFiles()
                    .filter { it.isFile }
                    .mapNotNull { it.toLibraryFile(historyNames, referenceByName) }
                    .sortedBy { it.name.lowercase() }
                if (files.isNotEmpty()) folders += LibraryFolder(childName, files)
            } else if (child.isFile) {
                child.toLibraryFile(historyNames, referenceByName)?.let { rootFiles += it }
            }
        }
        if (rootFiles.isNotEmpty()) {
            folders += LibraryFolder("(root)", rootFiles.sortedBy { it.name.lowercase() })
        }
        return folders.sortedBy { it.name.lowercase() }
    }

    private fun DocumentFile.toLibraryFile(
        historyNames: Set<String>,
        referenceByName: Map<String, String>
    ): LibraryFile? {
        val n = name ?: return null
        return LibraryFile(
            name = n,
            sizeBytes = length(),
            locator = uri.toString(),
            tracked = historyNames.contains(n),
            referenceMd5 = referenceByName[n] ?: ""
        )
    }

    /**
     * Walk the app-private default download dir (the same one DownloadManager falls back to:
     * getExternalFilesDir(DIRECTORY_DOWNLOADS)/ROMs). Returns an empty list when nothing's there.
     */
    private fun walkDefaultDir(
        historyNames: Set<String>,
        referenceByName: Map<String, String>
    ): List<LibraryFolder> {
        val root = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ROMs")
        if (!root.exists() || !root.isDirectory) return emptyList()

        val folders = mutableListOf<LibraryFolder>()
        val rootFiles = mutableListOf<LibraryFile>()
        root.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val files = child.listFiles().orEmpty()
                    .filter { it.isFile }
                    .map { it.toLibraryFile(historyNames, referenceByName) }
                    .sortedBy { it.name.lowercase() }
                if (files.isNotEmpty()) folders += LibraryFolder(child.name, files)
            } else if (child.isFile) {
                rootFiles += child.toLibraryFile(historyNames, referenceByName)
            }
        }
        if (rootFiles.isNotEmpty()) {
            folders += LibraryFolder("(root)", rootFiles.sortedBy { it.name.lowercase() })
        }
        return folders.sortedBy { it.name.lowercase() }
    }

    private fun File.toLibraryFile(
        historyNames: Set<String>,
        referenceByName: Map<String, String>
    ): LibraryFile = LibraryFile(
        name = name,
        sizeBytes = length(),
        locator = absolutePath,
        tracked = historyNames.contains(name),
        referenceMd5 = referenceByName[name] ?: ""
    )

    // ---- per-file integrity verify ----------------------------------------

    /**
     * Recompute [file]'s MD5 off the main thread and compare to its reference hash. Opt-in per
     * file so we never hash a whole library at once (thermal/battery on a handheld). Cancellable
     * via [cancelVerify]. A re-tap while running is a no-op (the existing job keeps going).
     */
    fun verify(file: LibraryFile) {
        if (verifyJobs[file.locator]?.isActive == true) return
        setFileState(file.locator) { it.copy(verifyState = VerifyState.RUNNING, computedMd5 = "") }

        val job = viewModelScope.launch {
            val computed = try {
                withContext(Dispatchers.IO) { computeMd5(file.locator) }
            } catch (c: CancellationException) {
                // Cancelled by the user — reset the row to its idle state and bail.
                setFileState(file.locator) { it.copy(verifyState = VerifyState.NONE, computedMd5 = "") }
                throw c
            }
            if (computed == null) {
                setFileState(file.locator) { it.copy(verifyState = VerifyState.ERROR) }
                return@launch
            }
            // Re-read the row's current reference each time (it never changes mid-session, but
            // this keeps the comparison local and explicit).
            val ref = currentFile(file.locator)?.referenceMd5 ?: file.referenceMd5
            val newState = when {
                ref.isBlank() -> VerifyState.NO_REFERENCE
                computed.equals(ref, ignoreCase = true) -> VerifyState.MATCH
                else -> VerifyState.MISMATCH
            }
            setFileState(file.locator) { it.copy(verifyState = newState, computedMd5 = computed) }
        }
        verifyJobs[file.locator] = job
        job.invokeOnCompletion { verifyJobs.remove(file.locator) }
    }

    /** Cancel an in-flight verify for [file], if any. */
    fun cancelVerify(file: LibraryFile) {
        verifyJobs.remove(file.locator)?.cancel()
        setFileState(file.locator) { it.copy(verifyState = VerifyState.NONE, computedMd5 = "") }
    }

    override fun onCleared() {
        super.onCleared()
        verifyJobs.values.forEach { it.cancel() }
        verifyJobs.clear()
    }

    /**
     * Stream the file at [locator] through MD5 and return the lowercase hex digest, or null on
     * I/O failure. Mirrors DownloadManager.computeMd5OrNull: 1 MB chunks (bounded buffer for
     * multi-GB files), cooperative cancellation each chunk. [locator] is either a SAF content://
     * URI (opened via ContentResolver) or an absolute file path.
     */
    private suspend fun computeMd5(locator: String): String? {
        return try {
            val digest = java.security.MessageDigest.getInstance("MD5")
            val input = if (locator.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(locator))
            } else {
                File(locator).inputStream()
            } ?: return null
            input.buffered().use { stream ->
                val buf = ByteArray(1 shl 20) // 1 MB
                var read: Int
                while (stream.read(buf).also { read = it } != -1) {
                    // Cooperative cancellation; throws CancellationException if the verify was
                    // cancelled. Use the suspend fun's coroutineContext as the receiver since
                    // this lambda has no CoroutineScope of its own.
                    coroutineContext.ensureActive()
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
            Log.w("EmuHelper", "Library MD5 verify failed for $locator", e)
            null
        }
    }

    // ---- state plumbing ---------------------------------------------------

    private fun currentFile(locator: String): LibraryFile? =
        _state.value.folders.firstNotNullOfOrNull { folder ->
            folder.files.firstOrNull { it.locator == locator }
        }

    /** Replace a single file row (matched by locator) by applying [transform], preserving order. */
    private fun setFileState(locator: String, transform: (LibraryFile) -> LibraryFile) {
        _state.value = _state.value.copy(
            folders = _state.value.folders.map { folder ->
                val idx = folder.files.indexOfFirst { it.locator == locator }
                if (idx < 0) folder
                else folder.copy(
                    files = folder.files.toMutableList().apply { this[idx] = transform(this[idx]) }
                )
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("On-device library", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingState()
                state.error != null -> MessageState(
                    icon = Icons.Default.FolderOff,
                    title = "Folder unavailable",
                    message = state.error!!
                )
                state.empty -> MessageState(
                    icon = Icons.Default.Folder,
                    title = "Nothing on disk yet",
                    message = "Files you download appear here, grouped by their category folder."
                )
                else -> LibraryContent(
                    state = state,
                    onVerify = viewModel::verify,
                    onCancelVerify = viewModel::cancelVerify
                )
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            "Scanning your library…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MessageState(icon: ImageVector, title: String, message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.Large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LibraryContent(
    state: LibraryUiState,
    onVerify: (LibraryFile) -> Unit,
    onCancelVerify: (LibraryFile) -> Unit
) {
    // Collapsed-folder names. Default expanded; remember by folder name so it survives rescans.
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = Dimens.ScreenHorizontal,
            vertical = Dimens.ItemGap
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Summary header: total count + total size across the whole library.
        item(key = "summary") {
            LibrarySummary(totalFiles = state.totalFiles, totalSize = state.totalSize)
        }

        state.folders.forEach { folder ->
            val isCollapsed = collapsed[folder.name] == true
            item(key = "folder_${folder.name}") {
                FolderHeader(
                    folder = folder,
                    collapsed = isCollapsed,
                    onToggle = { collapsed[folder.name] = !isCollapsed }
                )
            }
            if (!isCollapsed) {
                items(folder.files, key = { "${folder.name}/${it.name}" }) { file ->
                    FileRow(
                        file = file,
                        onVerify = { onVerify(file) },
                        onCancelVerify = { onCancelVerify(file) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LibrarySummary(totalFiles: Int, totalSize: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Dimens.CardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.width(Dimens.ItemGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (totalFiles == 1) "1 file on disk" else "$totalFiles files on disk",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    "Total ${formatSize(totalSize)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun FolderHeader(
    folder: LibraryFolder,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        onClick = onToggle,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.CardPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                contentDescription = if (collapsed) "Expand" else "Collapse",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(Dimens.ItemGap))
            Text(
                folder.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${folder.count} · ${formatSize(folder.totalSize)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FileRow(
    file: LibraryFile,
    onVerify: () -> Unit,
    onCancelVerify: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(Dimens.CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatSize(file.sizeBytes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!file.tracked) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "· not tracked",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(Dimens.ItemGap))
                VerifyControl(
                    file = file,
                    onVerify = onVerify,
                    onCancelVerify = onCancelVerify
                )
            }
            // Verify result detail line (badge + computed hash when present).
            AnimatedVisibility(visible = file.verifyState != VerifyState.NONE) {
                VerifyResult(file = file)
            }
        }
    }
}

@Composable
private fun VerifyControl(
    file: LibraryFile,
    onVerify: () -> Unit,
    onCancelVerify: () -> Unit
) {
    when (file.verifyState) {
        VerifyState.RUNNING -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Dimens.IconSmall),
                    strokeWidth = 2.dp
                )
                IconButton(onClick = onCancelVerify) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Cancel verify",
                        modifier = Modifier.size(Dimens.IconSmall)
                    )
                }
            }
        }
        else -> {
            TextButton(onClick = onVerify) {
                Icon(Icons.Default.VerifiedUser, null, modifier = Modifier.size(Dimens.IconSmall))
                Spacer(Modifier.width(6.dp))
                Text(if (file.verifyState == VerifyState.NONE) "Verify" else "Re-verify")
            }
        }
    }
}

@Composable
private fun VerifyResult(file: LibraryFile) {
    val (icon, tint, label) = when (file.verifyState) {
        VerifyState.MATCH -> Triple(
            Icons.Default.CheckCircle,
            MaterialTheme.colorScheme.tertiary,
            "Integrity OK — matches reference"
        )
        VerifyState.MISMATCH -> Triple(
            Icons.Default.Warning,
            MaterialTheme.colorScheme.error,
            "Mismatch — file may be corrupt"
        )
        VerifyState.NO_REFERENCE -> Triple(
            Icons.Default.HelpOutline,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Hashed — no reference to compare"
        )
        VerifyState.ERROR -> Triple(
            Icons.Default.Warning,
            MaterialTheme.colorScheme.error,
            "Couldn't read the file to verify"
        )
        else -> Triple(
            Icons.Default.HelpOutline,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Verifying…"
        )
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Dimens.IconSmall))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
        }
        if (file.computedMd5.isNotBlank()) {
            Text(
                "md5: ${file.computedMd5}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
