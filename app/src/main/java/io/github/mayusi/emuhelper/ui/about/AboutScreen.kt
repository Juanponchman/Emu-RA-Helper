package io.github.mayusi.emuhelper.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emuhelper.BuildConfig
import io.github.mayusi.emuhelper.data.source.AppUpdater
import io.github.mayusi.emuhelper.data.source.UpdateChecker
import io.github.mayusi.emuhelper.ui.common.UpdateDialog
import io.github.mayusi.emuhelper.ui.common.UpdateFlowState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val updateChecker: UpdateChecker,
    private val appUpdater: AppUpdater
) : ViewModel() {

    sealed class UpdateState {
        object Idle : UpdateState()
        object Checking : UpdateState()
        data class UpToDate(val version: String) : UpdateState()
        data class UpdateAvailable(val info: UpdateChecker.UpdateInfo) : UpdateState()
        object Error : UpdateState()
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _updateFlow = MutableStateFlow<UpdateFlowState>(UpdateFlowState.Idle)
    val updateFlow: StateFlow<UpdateFlowState> = _updateFlow.asStateFlow()

    private var downloadedApkFile: File? = null

    fun checkForUpdates(currentVersion: String) {
        if (_updateState.value is UpdateState.Checking) return
        _updateState.value = UpdateState.Checking
        viewModelScope.launch {
            val info = updateChecker.check(currentVersion)
            _updateState.value = when {
                info == null -> UpdateState.Error
                info.isNewer -> UpdateState.UpdateAvailable(info)
                else -> UpdateState.UpToDate(info.latestTag)
            }
        }
    }

    fun startDownload(apkUrl: String, apkSize: Long) {
        if (_updateFlow.value is UpdateFlowState.Downloading) return
        _updateFlow.value = UpdateFlowState.Downloading(0f)
        downloadedApkFile = null
        viewModelScope.launch {
            val file = appUpdater.downloadApk(apkUrl, apkSize) { progress ->
                _updateFlow.value = UpdateFlowState.Downloading(progress)
            }
            if (file != null) {
                downloadedApkFile = file
                _updateFlow.value = UpdateFlowState.Installing
            } else {
                _updateFlow.value = UpdateFlowState.Error("Download failed. Check your connection and try again.")
            }
        }
    }

    fun installDownloadedApk() {
        val file = downloadedApkFile ?: run {
            _updateFlow.value = UpdateFlowState.Error("APK not found. Please download again.")
            return
        }
        when (val result = appUpdater.installApk(file)) {
            is AppUpdater.InstallResult.Launched -> { /* system installer launched */ }
            is AppUpdater.InstallResult.NeedsPermission -> {
                _updateFlow.value = UpdateFlowState.NeedsPermission
            }
            is AppUpdater.InstallResult.Error -> {
                _updateFlow.value = UpdateFlowState.Error(
                    "${result.message}\n\nIf installation keeps failing, try downloading the APK from the release page."
                )
            }
        }
    }

    fun resetUpdateFlow() {
        _updateFlow.value = UpdateFlowState.Idle
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    viewModel: AboutViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val updateState by viewModel.updateState.collectAsState()
    val updateFlow by viewModel.updateFlow.collectAsState()
    var showUpdateDialog by remember { mutableStateOf(false) }

    val appVersion = remember {
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "v${pi.versionName} (${pi.longVersionCode})"
        } catch (e: Exception) { "v${BuildConfig.VERSION_NAME}" }
    }

    val currentVersionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                ?: BuildConfig.VERSION_NAME
        } catch (e: Exception) { BuildConfig.VERSION_NAME }
    }

    // Show the update dialog when UpdateAvailable is active and user tapped the button.
    val availableState = updateState as? AboutViewModel.UpdateState.UpdateAvailable
    if (showUpdateDialog && availableState != null) {
        UpdateDialog(
            info = availableState.info,
            flowState = updateFlow,
            onDownload = {
                val url = availableState.info.apkUrl ?: return@UpdateDialog
                viewModel.startDownload(url, availableState.info.apkSize)
            },
            onInstall = { viewModel.installDownloadedApk() },
            onDismiss = {
                showUpdateDialog = false
                viewModel.resetUpdateFlow()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App icon in a tinted circle
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(80.dp)
            ) {
                Icon(
                    Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(18.dp)
                        .fillMaxSize()
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "EmuHelper",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                appVersion,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "A configurable download manager.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // Links
            AboutLinkRow(
                icon = Icons.Default.Code,
                label = "View on GitHub",
                url = "https://github.com/mayusi/EmuHelper",
                context = context
            )
            AboutLinkRow(
                icon = Icons.Default.NewReleases,
                label = "Releases / Changelog",
                url = "https://github.com/mayusi/EmuHelper/releases",
                context = context
            )
            AboutLinkRow(
                icon = Icons.Default.BugReport,
                label = "Report an issue",
                url = "https://github.com/mayusi/EmuHelper/issues",
                context = context
            )
            AboutLinkRow(
                icon = Icons.Default.Description,
                label = "MIT License",
                url = "https://github.com/mayusi/EmuHelper/blob/main/LICENSE",
                context = context
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            // Update check button + result
            Button(
                onClick = { viewModel.checkForUpdates(currentVersionName) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = MaterialTheme.shapes.small,
                enabled = updateState !is AboutViewModel.UpdateState.Checking
            ) {
                if (updateState is AboutViewModel.UpdateState.Checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Checking…")
                } else {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Check for updates")
                }
            }

            Spacer(Modifier.height(12.dp))

            when (val state = updateState) {
                is AboutViewModel.UpdateState.UpToDate -> {
                    Text(
                        "You're up to date (${state.version})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        textAlign = TextAlign.Center
                    )
                }
                is AboutViewModel.UpdateState.UpdateAvailable -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Update available: ${state.info.latestTag}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Show "Update now" only if an APK asset is available.
                                if (state.info.apkUrl != null) {
                                    Button(
                                        onClick = { showUpdateDialog = true },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Text("Update now")
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(state.info.htmlUrl))
                                        )
                                    }
                                ) {
                                    Text("View release", color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                    }
                }
                is AboutViewModel.UpdateState.Error -> {
                    Text(
                        "Could not check for updates. Please try again later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
                else -> {} // Idle or Checking — nothing extra shown
            }
        }
    }
}

@Composable
private fun AboutLinkRow(
    icon: ImageVector,
    label: String,
    url: String,
    context: android.content.Context
) {
    Surface(
        onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        },
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(16.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
