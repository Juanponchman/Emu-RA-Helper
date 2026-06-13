package io.github.mayusi.emuhelper.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// ---------------------------------------------------------------------------
// App version helper
// ---------------------------------------------------------------------------

/**
 * Returns a human-readable version string, e.g. "1.2.3 (42)" when
 * [includeCode] is true, or just "1.2.3" otherwise.
 *
 * Call sites to adopt (currently inline):
 *   - HomeScreen.kt ~422       (uses includeCode = true)
 *   - SettingsScreen.kt ~89-95 (uses includeCode = true)
 *   - AboutScreen.kt ~128,135  (two separate calls — name + code separately)
 */
fun Context.appVersionString(includeCode: Boolean = false): String = try {
    val pi = packageManager.getPackageInfo(packageName, 0)
    val code = if (android.os.Build.VERSION.SDK_INT >= 28)
        pi.longVersionCode
    else
        @Suppress("DEPRECATION") pi.versionCode.toLong()
    if (includeCode) "${pi.versionName} ($code)" else (pi.versionName ?: "—")
} catch (e: Exception) {
    "—"
}

// ---------------------------------------------------------------------------
// URL launcher helper
// ---------------------------------------------------------------------------

/**
 * Opens [url] in the system browser (or any registered handler). Silently
 * ignores [ActivityNotFoundException] so call sites need no try/catch.
 *
 * Call sites to adopt (currently inline):
 *   - AboutScreen.kt ~315  ("View release" TextButton)
 *   - AboutScreen.kt ~348  (AboutLinkRow Surface.onClick)
 *   - UpdateDialog.kt ~137, ~156, ~171
 *
 * Note: EmulatorSetupInstructionsScreen opens a *file URI* via an intent
 * with extra flags, so the pattern differs — do NOT adopt there.
 */
fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Log.w("EmuHelper", "openUrl: no handler for $url", e)
    }
}

// ---------------------------------------------------------------------------
// Folder picker composable
// ---------------------------------------------------------------------------

/**
 * Wraps [ActivityResultContracts.OpenDocumentTree] with automatic
 * [takePersistableUriPermission] so every adoption site gets persistence for
 * free. Calls [onPicked] with the chosen URI on success; does nothing on cancel.
 *
 * Usage:
 * ```kotlin
 * val folderPicker = rememberFolderPicker { uri -> viewModel.setFolder(uri) }
 * Button(onClick = { folderPicker.launch(null) }) { Text("Pick folder") }
 * ```
 *
 * Call sites to adopt (currently inline — all do the same takePersistable dance):
 *   - HomeScreen.kt ~212-228
 *   - DownloadScreen.kt ~114-128
 *   - OnboardingScreen.kt ~68-82
 *   - EmulatorSetupFirmwareScreen.kt ~163-175 (also has separate OpenDocument
 *     launchers for individual files — review before adopting)
 *   - EmulatorSetupKeysScreen.kt ~164-176 (same caveat)
 */
@Composable
fun rememberFolderPicker(onPicked: (Uri) -> Unit): ActivityResultLauncher<Uri?> {
    val context = LocalContext.current
    return rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w("EmuHelper", "rememberFolderPicker: persisting URI permission failed", e)
        }
        onPicked(uri)
    }
}
