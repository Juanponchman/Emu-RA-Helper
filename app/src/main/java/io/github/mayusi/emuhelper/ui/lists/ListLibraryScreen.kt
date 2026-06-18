package io.github.mayusi.emuhelper.ui.lists

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.mayusi.emuhelper.data.model.GameList
import io.github.mayusi.emuhelper.ui.common.Dimens
import io.github.mayusi.emuhelper.ui.common.formatSize
import io.github.mayusi.emuhelper.ui.common.rememberFolderPicker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ListSortMode { NEWEST, NAME, LARGEST }

private val listDateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListLibraryScreen(
    onOpen: (GameList) -> Unit,
    onBack: () -> Unit,
    onMakeList: () -> Unit,
    viewModel: ListViewModel = hiltViewModel()
) {
    val lists by viewModel.lists.collectAsState()
    val decodeError by viewModel.decodeError.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(ListSortMode.NEWEST) }

    val displayedLists = remember(lists, searchQuery, sortMode) {
        val filtered = if (searchQuery.isBlank()) lists
        else lists.filter { it.name.contains(searchQuery, ignoreCase = true) }
        when (sortMode) {
            ListSortMode.NEWEST  -> filtered.sortedByDescending { it.createdAt }
            ListSortMode.NAME    -> filtered.sortedBy { it.name.lowercase() }
            ListSortMode.LARGEST -> filtered.sortedByDescending { it.totalSize }
        }
    }

    LaunchedEffect(message) {
        if (message.isNotBlank()) {
            snackbar.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    val importer = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                viewModel.importFromText(text)
            } catch (e: Exception) {
                Log.w("EmuHelper", "Import read failed", e)
                scope.launch { snackbar.showSnackbar("Couldn't open that file.") }
            }
        }
    }

    // Controls the "Import from URL" dialog.
    var showUrlImportDialog by remember { mutableStateOf(false) }
    var urlImportText by remember { mutableStateOf("") }
    var urlImporting by remember { mutableStateOf(false) }

    // Holds the list pending deletion; non-null shows the confirmation dialog.
    var deleteTarget by remember { mutableStateOf<GameList?>(null) }

    // Holds the list pending rename; non-null shows the rename dialog.
    var renameTarget by remember { mutableStateOf<GameList?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Export: remember which list, then write its JSON to the chosen file.
    var exportTarget by remember { mutableStateOf<GameList?>(null) }

    // Per-list folder picker: track which list we're picking a folder for.
    var folderPickTarget by remember { mutableStateOf<GameList?>(null) }
    val folderPicker = rememberFolderPicker { uri ->
        val target = folderPickTarget
        if (target != null) {
            viewModel.setListFolder(target.id, uri.toString())
            scope.launch { snackbar.showSnackbar("Download folder set for \"${target.name}\".") }
        }
        folderPickTarget = null
    }
    val exporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val target = exportTarget
        if (uri != null && target != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(viewModel.encodeForExport(target).toByteArray())
                }
                scope.launch { snackbar.showSnackbar("Exported \"${target.name}\".") }
            } catch (e: Exception) {
                Log.w("EmuHelper", "Export failed", e)
                scope.launch { snackbar.showSnackbar("Export failed.") }
            }
        }
        exportTarget = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your lists", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { importer.launch(arrayOf("application/json", "text/*", "*/*")) }) {
                        Icon(Icons.Default.FileUpload, contentDescription = "Import list", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Import")
                    }
                    TextButton(onClick = { urlImportText = ""; showUrlImportDialog = true }) {
                        Text("URL")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (decodeError) {
                Text(
                    text = "Some saved lists couldn't be read (the saved data may be corrupted).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.ScreenHorizontal, vertical = 8.dp)
                )
            }
        if (lists.isEmpty()) {
            Box(modifier = Modifier.weight(1f).padding(48.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No saved lists yet", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Make a list of items first, or import one from a .json file.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onMakeList,
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.weight(1f).height(Dimens.ButtonMinHeight)
                        ) { Text("Make a list") }
                        OutlinedButton(
                            onClick = { importer.launch(arrayOf("application/json", "text/*", "*/*")) },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f).height(Dimens.ButtonMinHeight)
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = "Import list", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Import")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = Dimens.ScreenHorizontal, vertical = Dimens.ItemGap)
            ) {
                // ---- Search + sort controls ----
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Search lists") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Sort:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilterChip(
                                selected = sortMode == ListSortMode.NEWEST,
                                onClick = { sortMode = ListSortMode.NEWEST },
                                label = { Text("Newest") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                            FilterChip(
                                selected = sortMode == ListSortMode.NAME,
                                onClick = { sortMode = ListSortMode.NAME },
                                label = { Text("Name") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                            FilterChip(
                                selected = sortMode == ListSortMode.LARGEST,
                                onClick = { sortMode = ListSortMode.LARGEST },
                                label = { Text("Largest") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                        if (displayedLists.isEmpty()) {
                            Text(
                                "No lists match \"$searchQuery\".",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                }

                items(displayedLists, key = { it.id }) { list ->
                    var menuOpen by remember { mutableStateOf(false) }
                    Card(
                        modifier = Modifier.fillMaxWidth().animateItem().clickable {
                            // Load this list's games into the download queue, then open the preview.
                            viewModel.loadForDownload(list)
                            onOpen(list)
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.CardPadding, horizontal = Dimens.CardPadding + 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(list.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${list.count} items  ·  ${formatSize(list.totalSize)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    listDateFmt.format(Date(list.createdAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                // Show per-list folder override when set.
                                if (list.customFolderUri != null) {
                                    val folderLabel = android.net.Uri.parse(list.customFolderUri)
                                        .lastPathSegment ?: "custom folder"
                                    Text(
                                        "Folder: $folderLabel",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "List actions")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                                        onClick = {
                                            menuOpen = false
                                            renameText = list.name
                                            renameTarget = list
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Set download folder") },
                                        leadingIcon = { Icon(Icons.Default.Folder, null) },
                                        onClick = {
                                            menuOpen = false
                                            folderPickTarget = list
                                            folderPicker.launch(null)
                                        }
                                    )
                                    if (list.customFolderUri != null) {
                                        DropdownMenuItem(
                                            text = { Text("Use default folder") },
                                            leadingIcon = { Icon(Icons.Default.FolderOff, null) },
                                            onClick = {
                                                menuOpen = false
                                                viewModel.setListFolder(list.id, null)
                                                scope.launch { snackbar.showSnackbar("Reverted to default folder for \"${list.name}\".") }
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Export to file") },
                                        leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                                        onClick = {
                                            menuOpen = false
                                            exportTarget = list
                                            exporter.launch("${list.name.replace(Regex("[^A-Za-z0-9 _-]"), "")}.json")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete") },
                                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                                        onClick = { menuOpen = false; deleteTarget = list }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        } // end outer Column
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete this list?") },
            text = { Text("\"${target.name}\" will be removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(target.id); deleteTarget = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    renameTarget?.let { target ->
        val focusRequester = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename list") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("List name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.rename(target.id, renameText)
                        renameTarget = null
                    },
                    enabled = renameText.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (showUrlImportDialog) {
        val focusRequester = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { if (!urlImporting) showUrlImportDialog = false },
            title = { Text("Import from URL") },
            text = {
                Column {
                    Text(
                        "Paste a direct link to a .json list file (https://…).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = urlImportText,
                        onValueChange = { urlImportText = it },
                        label = { Text("URL") },
                        placeholder = { Text("https://example.com/mylist.json") },
                        singleLine = true,
                        enabled = !urlImporting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                    if (urlImporting) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        urlImporting = true
                        viewModel.importFromUrl(urlImportText.trim()) { success, msg ->
                            urlImporting = false
                            showUrlImportDialog = false
                            scope.launch { snackbar.showSnackbar(msg) }
                        }
                    },
                    enabled = !urlImporting && urlImportText.trim().let {
                        it.startsWith("http://") || it.startsWith("https://")
                    }
                ) { Text("Import") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUrlImportDialog = false },
                    enabled = !urlImporting
                ) { Text("Cancel") }
            }
        )
    }
}
