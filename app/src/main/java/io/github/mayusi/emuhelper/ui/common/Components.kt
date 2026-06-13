package io.github.mayusi.emuhelper.ui.common

import java.io.File

fun formatSize(bytes: Long): String {
    var n = bytes.toDouble()
    for (unit in listOf("B", "KB", "MB", "GB")) {
        if (n < 1024.0) return "%.1f %s".format(n, unit)
        n /= 1024.0
    }
    return "%.1f TB".format(n)
}

fun formatSpeed(bytesPerSec: Double): String {
    val mbps = bytesPerSec / 1048576.0
    return if (mbps > 0.1) "%.1f MB/s".format(mbps) else "--"
}

fun formatEta(seconds: Double): String {
    if (seconds <= 0) return "--"
    val s = seconds.toLong()
    return if (s < 3600) "%dm %ds".format(s / 60, s % 60)
    else "%dh %dm".format(s / 3600, (s % 3600) / 60)
}

fun cleanGameName(filename: String): String {
    var name = File(filename).name
    name = Regex("\\.(chd|iso|rvz|nsp|xci|cia|nds|z64|zip|smc|sfc|gba|gbc|nes|gen|md|bin|cue|pbp|cso|wbfs|wad|3ds|rom|7z|wud|wux|gcm|gcz)\$", RegexOption.IGNORE_CASE).replace(name, "")
    name = Regex("^\\[NKit\\]\\s*", RegexOption.IGNORE_CASE).replace(name, "")
    name = Regex("^\\[RVZ\\]\\s*", RegexOption.IGNORE_CASE).replace(name, "")
    name = Regex("^\\[CHD\\]\\s*", RegexOption.IGNORE_CASE).replace(name, "")
    name = Regex("^\\[Redump\\]\\s*", RegexOption.IGNORE_CASE).replace(name, "")
    name = Regex("^\\[No-Intro\\]\\s*", RegexOption.IGNORE_CASE).replace(name, "")
    name = Regex("^\\[TOSEC\\]\\s*", RegexOption.IGNORE_CASE).replace(name, "")
    name = Regex("[_]+").replace(name, " ")
    name = Regex("\\s+").replace(name, " ")
    return name.trim()
}

/** Coarse region bucket parsed from a filename's tags. */
enum class Region { USA, EUR, JPN, OTHER }

fun detectRegion(filename: String): Region {
    val s = filename.lowercase()
    return when {
        Regex("\\b(usa|\\(u\\)|world|ntsc-u)\\b").containsMatchIn(s) -> Region.USA
        Regex("\\b(europe|eur|\\(e\\)|pal|uk|australia)\\b").containsMatchIn(s) -> Region.EUR
        Regex("\\b(japan|jpn|jap|\\(j\\)|ntsc-j)\\b").containsMatchIn(s) -> Region.JPN
        else -> Region.OTHER
    }
}

