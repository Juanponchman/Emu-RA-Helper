package io.github.mayusi.emuhelper.ui.download

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import io.github.mayusi.emuhelper.data.model.CuratedGame
import io.github.mayusi.emuhelper.data.source.LoginResult
import io.github.mayusi.emuhelper.data.source.RemoteSource
import io.github.mayusi.emuhelper.data.storage.AuthStore
import io.github.mayusi.emuhelper.data.storage.SettingsStore
import io.github.mayusi.emuhelper.di.PersistentCookieJar
import io.github.mayusi.emuhelper.ui.browse.ScanStateHolder
import io.github.mayusi.emuhelper.ui.common.Dimens
import io.github.mayusi.emuhelper.ui.common.cleanGameName
import io.github.mayusi.emuhelper.ui.common.formatSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DownloadPreviewViewModel @Inject constructor(
    private val scanState: ScanStateHolder,
    private val source: RemoteSource,
    private val settings: SettingsStore,
    private val authStore: AuthStore,
    private val cookieJar: PersistentCookieJar,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /** The games loaded from the chosen saved list. */
    val games: StateFlow<List<CuratedGame>> = scanState.downloadQueue

    private val _checked = MutableStateFlow<Set<String>>(emptySet())
    val checked: StateFlow<Set<String>> = _checked

    private var seeded = false
    /** First time we see the list, check everything by default. */
    fun seedIfNeeded(list: List<CuratedGame>) {
        if (!seeded && list.isNotEmpty()) {
            _checked.value = list.map { it.key }.toSet()
            seeded = true
        }
    }

    fun toggle(g: CuratedGame) {
        val k = g.key
        val cur = _checked.value.toMutableSet()
        if (k in cur) cur.remove(k) else cur.add(k)
        _checked.value = cur
    }

    fun isChecked(g: CuratedGame) = g.key in _checked.value

    fun setAll(list: List<CuratedGame>, value: Boolean) {
        _checked.value = if (value) list.map { it.key }.toSet() else emptySet()
    }

    fun isLoggedIn(): Boolean = source.isLoggedIn()

    /**
     * FIX 3 + FIX 4: the download gate's reliable "am I logged in?" path.
     *
     * Returns true if the user is (or can be silently made) logged in, false ONLY when the
     * login screen is genuinely required. Steps:
     *   (a) awaitRestored() so a cold-start disk restore can't be lost to a race, then re-check
     *       the real persisted session;
     *   (b) if still logged out but rememberMe + saved creds exist, attempt a SILENT
     *       source.login() (no full login screen — the caller shows a small inline spinner);
     *   (c) return false (→ login screen needed) only if there are no saved creds OR the silent
     *       login DEFINITIVELY fails (bad creds). A network failure also returns false, but the
     *       saved creds remain so the NEXT tap retries (one in-flight attempt at a time below).
     *
     * This is the on-demand path: even if the startup auto-relogin failed (e.g. no network at
     * boot), tapping Download here silently re-auths.
     */
    suspend fun ensureLoggedIn(): Boolean {
        // (a) Don't trust a synchronous isLoggedIn() on a cold start — wait for the restore.
        cookieJar.awaitRestored()
        if (source.isLoggedIn()) return true

        // (b) Try a silent re-login from saved credentials.
        val remember = authStore.rememberMe.first()
        val email = authStore.savedEmail.first()
        if (!remember || email.isBlank()) return false // no saved creds → login screen needed
        val pwd = authStore.getSavedPassword()
        if (pwd.isBlank()) return false

        return when (source.login(email, pwd)) {
            is LoginResult.Success -> true
            is LoginResult.Failed -> source.isLoggedIn() // tolerate a benign race; else login needed
        }
    }

    // FIX 4: guard against spamming the network with concurrent silent-login attempts — only one
    // ensureLoggedIn() runs at a time. A second tap while one is in flight just awaits its result.
    private val _signingIn = MutableStateFlow(false)
    val signingIn: StateFlow<Boolean> = _signingIn
    private var inFlight: kotlinx.coroutines.Deferred<Boolean>? = null

    /** Single-flight wrapper around [ensureLoggedIn] used by the screen's Download tap. */
    suspend fun ensureLoggedInOnce(): Boolean {
        inFlight?.let { return it.await() }
        _signingIn.value = true
        val job = viewModelScope.async { ensureLoggedIn() }
        inFlight = job
        return try {
            job.await()
        } finally {
            inFlight = null
            _signingIn.value = false
        }
    }

    // ---- RAR-extraction prompt (per-batch) --------------------------------
    // When the batch contains any .rar file we surface a checkbox letting the
    // user opt in/out of extracting it after download. The choice is stashed in
    // [ScanStateHolder.pendingExtractRar] and threaded through to
    // DownloadManager.start(extractRar = ...). Default seeds from the global
    // Extract setting so behaviour is unsurprising.

    /** True if ANY currently-checked file is a .rar (decides whether to show the prompt). */
    val hasRarSelected: StateFlow<Boolean> =
        combine(scanState.downloadQueue, _checked) { games, checked ->
            games.any { it.key in checked && it.filename.lowercase().trim().endsWith(".rar") }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _extractRar = MutableStateFlow<Boolean?>(null)
    /** The user's RAR-extraction choice for this batch (null until seeded). */
    val extractRar: StateFlow<Boolean?> = _extractRar

    private var extractRarSeeded = false
    /** Seed the toggle from the global Extract setting the first time a .rar appears. */
    fun seedExtractRarIfNeeded() {
        if (extractRarSeeded) return
        extractRarSeeded = true
        viewModelScope.launch {
            val global = settings.extractArchives.first()
            if (_extractRar.value == null) _extractRar.value = global
        }
    }

    fun setExtractRar(value: Boolean) {
        _extractRar.value = value
    }

    /** Narrow the download queue to only the checked games. Returns how many remain. */
    fun confirmSelection(): Int {
        val keep = scanState.downloadQueue.value.filter { it.key in _checked.value }
        scanState.downloadQueue.value = keep
        // Commit the RAR-extraction choice for this batch. Only set a non-null value
        // when the batch actually contains a .rar AND the user has a seeded choice;
        // otherwise leave it null so DownloadManager falls back to the global setting.
        val batchHasRar = keep.any { it.filename.lowercase().trim().endsWith(".rar") }
        scanState.pendingExtractRar.value = if (batchHasRar) _extractRar.value else null
        return keep.size
    }

    /**
     * Available free bytes on the download destination.
     * Uses StatFs on the external storage volume as a best-effort approximation for both
     * app-private and SAF destinations (SAF doesn't expose a direct path, so we query
     * the primary external volume which is typically where both destinations live).
     * Returns null if unavailable.
     */
    val freeBytes: StateFlow<Long?> = settings.downloadFolder.map { uri ->
        try {
            val path = if (uri == null) {
                // App-private dir — use its actual path
                context.getExternalFilesDir(null)?.absolutePath
                    ?: Environment.getExternalStorageDirectory().absolutePath
            } else {
                // SAF URI — approximate with primary external storage volume
                Environment.getExternalStorageDirectory().absolutePath
            }
            StatFs(path).availableBytes
        } catch (_: Exception) {
            null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadPreviewScreen(
    onConfirm: (needsLogin: Boolean) -> Unit,
    onBack: () -> Unit,
    viewModel: DownloadPreviewViewModel = hiltViewModel()
) {
    val games by viewModel.games.collectAsState()
    val checked by viewModel.checked.collectAsState()
    val freeBytes by viewModel.freeBytes.collectAsState()
    // RAR-extraction prompt: shown only when a .rar is in the selection.
    val hasRarSelected by viewModel.hasRarSelected.collectAsState()
    val extractRarChoice by viewModel.extractRar.collectAsState()
    LaunchedEffect(hasRarSelected) { if (hasRarSelected) viewModel.seedExtractRarIfNeeded() }
    // FIX 3: inline "Signing in…" state for the silent relogin attempt — shown on the Download
    // button instead of bouncing the user to the full login screen.
    val signingIn by viewModel.signingIn.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(games) {
        if (games.isEmpty()) onBack() else viewModel.seedIfNeeded(games)
    }

    val checkedGames = remember(games, checked) { games.filter { it.key in checked } }
    val selCount = checkedGames.size
    val selSize = remember(checkedGames) { checkedGames.sumOf { it.size } }
    // Warn if selection is > 95% of free space (best-effort, may be null)
    val spaceWarning = remember(selSize, freeBytes) {
        val free = freeBytes
        free != null && free > 0 && selSize > (free * 0.95).toLong()
    }

    // U-C: confirmation dialog when tapping Download with a space warning
    var showSpaceConfirm by remember { mutableStateOf(false) }
    var pendingConfirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    if (showSpaceConfirm) {
        AlertDialog(
            onDismissRequest = { showSpaceConfirm = false; pendingConfirmAction = null },
            title = { Text("Limited storage space") },
            text = { Text("This may not fit in the available space. Download anyway?") },
            confirmButton = {
                TextButton(onClick = {
                    showSpaceConfirm = false
                    pendingConfirmAction?.invoke()
                    pendingConfirmAction = null
                }) { Text("Download anyway") }
            },
            dismissButton = {
                TextButton(onClick = { showSpaceConfirm = false; pendingConfirmAction = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review download", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenHorizontal, vertical = Dimens.ItemGap)) {
                    // RAR-extraction prompt — only when the selection contains a .rar.
                    if (hasRarSelected) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setExtractRar(!(extractRarChoice ?: false)) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = extractRarChoice ?: false,
                                onCheckedChange = { viewModel.setExtractRar(it) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Extract RAR after downloading",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "Unpack .rar archives into the folder. If it fails, the .rar is kept.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    // Free-space row (hidden if unavailable)
                    freeBytes?.let { free ->
                        val freeColor = if (spaceWarning) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (spaceWarning) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = "Storage warning",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                "Free: ${formatSize(free)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = freeColor
                            )
                            if (spaceWarning) {
                                Text(
                                    "· May not fit",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { viewModel.setAll(games, true) }) { Text("All") }
                            TextButton(onClick = { viewModel.setAll(games, false) }) { Text("None") }
                        }
                        Button(
                            onClick = {
                                // FIX 3: silently re-login BEFORE ever signalling needsLogin. We
                                // await the cookie restore + attempt a saved-credential login with
                                // an inline spinner; only if that DEFINITIVELY fails do we tell the
                                // nav lane (onConfirm(needsLogin = true)) to route to the login
                                // screen. The actual navigate-to-login hop lives in EmuHelperApp
                                // (nav lane, not this lane) — we only flip the boolean it consumes.
                                val doConfirm = {
                                    val remaining = viewModel.confirmSelection()
                                    if (remaining > 0) {
                                        scope.launch {
                                            // ensureLoggedInOnce() == true → already/now logged in,
                                            // proceed with no login screen. false → genuinely needs
                                            // the login screen (no creds, or bad creds).
                                            val ok = viewModel.ensureLoggedInOnce()
                                            onConfirm(!ok)
                                        }
                                    }
                                }
                                if (spaceWarning) {
                                    pendingConfirmAction = doConfirm
                                    showSpaceConfirm = true
                                } else {
                                    doConfirm()
                                }
                            },
                            // Disable while a silent relogin is in flight so taps can't stack.
                            enabled = selCount > 0 && !signingIn,
                            modifier = Modifier.height(Dimens.ButtonMinHeight),
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            if (signingIn) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Signing in…", style = MaterialTheme.typography.titleMedium)
                            } else {
                                Text("Download $selCount  ·  ${formatSize(selSize)}", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(horizontal = Dimens.ScreenHorizontal, vertical = 4.dp)
        ) {
            item {
                Text(
                    "Uncheck anything you don't want to download right now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            items(games, key = { it.key }) { game ->
                // Read from the tracked `checked` set (collected above) so each row
                // recomposes when selection changes. Calling viewModel.isChecked()
                // here would read state OUTSIDE Compose tracking → rows never update
                // → "can't deselect".
                val isOn = game.key in checked
                val backgroundColor by animateColorAsState(
                    targetValue = if (isOn) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
                    animationSpec = tween(durationMillis = 150)
                )
                Surface(
                    modifier = Modifier.fillMaxWidth().animateItem().clickable { viewModel.toggle(game) },
                    color = backgroundColor,
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isOn, onCheckedChange = { viewModel.toggle(game) }, modifier = Modifier.padding(end = 6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cleanGameName(game.name.ifBlank { game.filename }), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(game.filename, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(formatSize(game.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}
