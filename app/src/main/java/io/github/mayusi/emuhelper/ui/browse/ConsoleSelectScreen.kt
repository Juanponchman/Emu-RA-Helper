package io.github.mayusi.emuhelper.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.mayusi.emuhelper.data.config.Catalog
import io.github.mayusi.emuhelper.data.source.RefreshResult
import io.github.mayusi.emuhelper.ui.common.Dimens
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleSelectScreen(
    onStartScan: (Set<String>) -> Unit,
    onBack: () -> Unit,
    viewModel: ConsoleSelectViewModel = hiltViewModel()
) {
    // Collect the live catalog snapshot — starts as baked-in, updates if a remote fetch succeeds.
    val catalog by Catalog.catalogFlow.collectAsState()

    // Derive the list of displayable consoles from the live snapshot (mirrors original logic).
    val consoles = catalog.displayOrder.filter { it in catalog.iaLinks }

    // Favorites: collect live and partition consoles so favorites float to the top.
    val favorites by viewModel.favoriteConsoles.collectAsState()
    val sortedConsoles by remember(consoles, favorites) {
        derivedStateOf {
            val (fav, rest) = consoles.partition { it in favorites }
            fav + rest
        }
    }

    val selected = remember { mutableStateMapOf<String, Boolean>() }

    // Initialise/sync the selected map whenever the base consoles list changes (e.g. after remote update).
    LaunchedEffect(consoles) {
        // Add any new consoles (default unchecked), leave existing ones as-is.
        consoles.forEach { c -> if (c !in selected) selected[c] = false }
        // Remove entries that are no longer in the consoles list.
        selected.keys.toList().forEach { k -> if (k !in consoles) selected.remove(k) }
    }

    // B8: Apply the saved selection ONCE on first composition only, so subsequent
    // DataStore emissions don't overwrite the user's in-progress checkbox changes.
    LaunchedEffect(Unit) {
        val savedConsoles = viewModel.lastSelectedConsoles.first()
        if (savedConsoles.isNotEmpty()) {
            consoles.forEach { console ->
                selected[console] = console in savedConsoles
            }
        }
    }

    // Snackbar host for refresh result messages.
    val snackbarHostState = remember { SnackbarHostState() }
    val refreshResult by viewModel.refreshResult.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    LaunchedEffect(refreshResult) {
        val result = refreshResult ?: return@LaunchedEffect
        val msg = when (result) {
            is RefreshResult.Updated  -> "Sources updated"
            is RefreshResult.UpToDate -> "Already up to date"
            is RefreshResult.Failed   -> "Couldn't check for updates"
            is RefreshResult.Disabled -> "Sources are up to date"
        }
        snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        viewModel.clearRefreshResult()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Select Consoles", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Refresh sources action — always shown; disabled while a fetch is in progress.
                    IconButton(
                        onClick = { viewModel.refreshCatalog() },
                        enabled = !isRefreshing
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh sources")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenHorizontal, vertical = Dimens.ItemGap),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { sortedConsoles.forEach { selected[it] = true } }) { Text("Select All") }
                        TextButton(onClick = { sortedConsoles.forEach { selected[it] = false } }) { Text("Clear") }
                    }
                    val selCount = selected.count { it.value }
                    Button(
                        onClick = {
                            val picked = selected.filter { it.value }.keys.toSet()
                            viewModel.saveSelectedConsoles(picked)
                            onStartScan(picked)
                        },
                        enabled = selCount > 0,
                        modifier = Modifier.height(Dimens.ButtonMinHeight),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Scan $selCount Console${if (selCount != 1) "s" else ""}", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = Dimens.ScreenHorizontal),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = Dimens.ItemGap)
        ) {
            item {
                Text(
                    "Select which categories to scan. The app fetches live metadata to find all available files.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            items(sortedConsoles, key = { it }) { console ->
                val info = catalog.consoles[console] ?: return@items
                val count = catalog.iaLinks[console]?.size ?: 0
                val checked = selected[console] ?: true
                val isFavorite = console in favorites
                val scanHint = when {
                    count <= 3  -> "quick scan"
                    count <= 10 -> "medium scan"
                    else        -> "large scan"
                }

                Card(
                    modifier = Modifier.fillMaxWidth().animateItem(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Dimens.CardPadding)
                            .clickable { selected[console] = !checked },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Star toggle — separate click target, does not affect selection checkbox.
                        IconButton(
                            onClick = { viewModel.toggleFavorite(console) },
                            modifier = Modifier.size(Dimens.TouchTarget)
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(info.display, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "${info.emulator}  ·  $count source${if (count != 1) "s" else ""}  ·  $scanHint",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Pass a real onCheckedChange so accessibility services don't read this as disabled
                        Checkbox(checked = checked, onCheckedChange = { selected[console] = it })
                    }
                }
            }
        }
    }
}
