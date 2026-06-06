package io.github.mayusi.emuhelper.data.model

import java.io.Serializable
import kotlinx.serialization.Serializable as KSerializable

data class GameFile(
    val name: String,
    val filename: String,
    val size: Long,
    val identifier: String,
    val sourceUrl: String = ""
) : Serializable

@KSerializable
data class CuratedGame(
    val name: String,
    val filename: String = "",
    val size: Long = 0,
    val identifier: String = "",
    val source: String = "built_in",
    /** Console key (e.g. "snes", "gcn") so downloads can be filed into a per-console
     *  subfolder. Defaulted for backward-compat with lists exported before this field. */
    val console: String = ""
) : Serializable

/**
 * A named, reusable selection of games the user can build once and download later.
 * Persisted as JSON via GameListStore and exportable/importable as a `.json` file.
 */
@KSerializable
data class GameList(
    val id: String,
    val name: String,
    val createdAt: Long,
    val games: List<CuratedGame>
) {
    val totalSize: Long get() = games.sumOf { it.size }
    val count: Int get() = games.size
}

enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, DONE, FAILED, CANCELLED }

/**
 * Fully immutable so Compose detects per-task changes. Progress updates produce a
 * NEW instance via copy(); the old approach mutated `var` fields that were excluded
 * from data-class equals(), so the UI never saw individual rows change.
 */
data class DownloadTask(
    val id: String = "",
    val url: String,
    val displayPath: String,
    val filename: String,
    val size: Long = 0,
    val identifier: String = "",
    /** Full relative path within the IA item (may include subdirs) — used for mirrors. */
    val relativeName: String = "",
    val region: String = "",
    /** Per-console subfolder name this file is filed under, e.g. "SNES". */
    val subfolder: String = "",
    val downloaded: Long = 0,
    val speed: Double = 0.0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val error: String = ""
) {
    /** Returns null when size is unknown so the UI can show an indeterminate bar. */
    val progressPercent: Float?
        get() = if (size > 0) (downloaded.toFloat() / size * 100f).coerceIn(0f, 100f) else null
}
