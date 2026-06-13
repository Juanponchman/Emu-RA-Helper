package io.github.mayusi.emuhelper.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.mayusi.emuhelper.data.source.UpdateChecker

/**
 * Download / install state exposed by the ViewModel to the dialog.
 */
sealed class UpdateFlowState {
    object Idle : UpdateFlowState()
    data class Downloading(val progress: Float) : UpdateFlowState()
    object Installing : UpdateFlowState()
    object NeedsPermission : UpdateFlowState()
    data class Error(val message: String) : UpdateFlowState()
}

/**
 * Full-screen-ish AlertDialog showing patch notes and the download/install flow.
 *
 * @param info          The available update info from [UpdateChecker].
 * @param flowState     Current download/install state driven by the ViewModel.
 * @param onDownload    Called when the user taps "Update now". ViewModel should start
 *                      the download and emit [UpdateFlowState.Downloading] progress.
 * @param onInstall     Called when download finished and user taps "Install". ViewModel
 *                      should invoke [AppUpdater.installApk].
 * @param onDismiss     Called when the user taps "Later" or closes the dialog (also cancels
 *                      any in-flight download via the ViewModel before this is called).
 */
@Composable
fun UpdateDialog(
    info: UpdateChecker.UpdateInfo,
    flowState: UpdateFlowState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isDownloading = flowState is UpdateFlowState.Downloading
    val isInstalling = flowState is UpdateFlowState.Installing
    val isNeedsPermission = flowState is UpdateFlowState.NeedsPermission
    val isError = flowState is UpdateFlowState.Error
    val readyToInstall = flowState == UpdateFlowState.Installing || isInstalling

    // B3: Allow dismissal during download — onDismiss is responsible for cancelling.
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text("Update available: ${info.latestTag}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
            ) {
                // Release notes
                if (info.notes.isNotBlank()) {
                    Text(
                        text = "What's new",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = renderMarkdown(info.notes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // Progress bar (visible while downloading)
                when (val s = flowState) {
                    is UpdateFlowState.Downloading -> {
                        Text(
                            "Downloading… ${(s.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { s.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    is UpdateFlowState.Installing -> {
                        Text(
                            "Launching installer…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is UpdateFlowState.NeedsPermission -> {
                        Text(
                            "Please grant \"Install unknown apps\" permission in Settings, then tap Install again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    is UpdateFlowState.Error -> {
                        Text(
                            "Update could not be installed automatically — open the release page to download manually.\n\nDetails: ${s.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                when {
                    info.apkUrl == null -> {
                        // No APK asset — only browser fallback.
                        TextButton(onClick = { context.openUrl(info.htmlUrl) }) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("View on GitHub")
                        }
                    }
                    isDownloading -> {
                        // B3: Show an active Cancel button during download so the user isn't locked in.
                        OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    }
                    flowState is UpdateFlowState.Installing || isNeedsPermission -> {
                        Button(onClick = onInstall) { Text("Install") }
                    }
                    isError -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { context.openUrl(info.htmlUrl) }) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("View on GitHub")
                            }
                            Button(onClick = onDownload) { Text("Retry") }
                        }
                    }
                    else -> {
                        // Idle — primary "Update now" + secondary "View on GitHub".
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { context.openUrl(info.htmlUrl) }) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("GitHub")
                            }
                            Button(onClick = onDownload) { Text("Update now") }
                        }
                    }
                }
            }
        },
        dismissButton = {
            // B3: Always show a dismiss option; during download the confirm-area Cancel
            // button already handles cancellation, so hide the redundant "Later" there.
            if (!isDownloading) {
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        }
    )
}

/**
 * Lightweight inline Markdown -> [AnnotatedString] renderer.
 *
 * Handles:
 *  - `## Heading` lines -> bold + newline
 *  - `- ` / `* ` bullet lines -> "• " prefix
 *  - `**bold**` inline spans -> FontWeight.Bold
 *  - Blank lines -> extra newline for paragraph spacing
 *
 * No external dependencies; good enough for typical GitHub release notes.
 */
fun renderMarkdown(raw: String): AnnotatedString = buildAnnotatedString {
    val boldRegex = Regex("""\*\*(.+?)\*\*""")
    val lines = raw.lines()
    for ((index, line) in lines.withIndex()) {
        val trimmed = line.trimEnd()
        when {
            trimmed.startsWith("### ") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(trimmed.removePrefix("### "))
                }
                append("\n")
            }
            trimmed.startsWith("## ") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(trimmed.removePrefix("## "))
                }
                append("\n")
            }
            trimmed.startsWith("# ") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(trimmed.removePrefix("# "))
                }
                append("\n")
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                append("• ")
                appendInlineBold(trimmed.substring(2), boldRegex)
                append("\n")
            }
            trimmed.isBlank() -> {
                // Two consecutive blank lines -> single blank line to avoid excessive gaps.
                if (index > 0 && lines[index - 1].isNotBlank()) append("\n")
            }
            else -> {
                appendInlineBold(trimmed, boldRegex)
                append("\n")
            }
        }
    }
}

private fun AnnotatedString.Builder.appendInlineBold(text: String, boldRegex: Regex) {
    var cursor = 0
    boldRegex.findAll(text).forEach { match ->
        if (match.range.first > cursor) {
            append(text.substring(cursor, match.range.first))
        }
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(match.groupValues[1])
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}
