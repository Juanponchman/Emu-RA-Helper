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

// Compiled once at class-load time; cleanGameName() is called per-row in lists so
// constructing these Regex objects inline would allocate 8 objects per call.
private val RE_EXT     = Regex("\\.(chd|iso|rvz|nsp|xci|cia|nds|z64|zip|smc|sfc|gba|gbc|nes|gen|md|bin|cue|pbp|cso|wbfs|wad|3ds|rom|7z|wud|wux|gcm|gcz)\$", RegexOption.IGNORE_CASE)
private val RE_NKIT    = Regex("^\\[NKit\\]\\s*", RegexOption.IGNORE_CASE)
private val RE_RVZ     = Regex("^\\[RVZ\\]\\s*", RegexOption.IGNORE_CASE)
private val RE_CHD     = Regex("^\\[CHD\\]\\s*", RegexOption.IGNORE_CASE)
private val RE_REDUMP  = Regex("^\\[Redump\\]\\s*", RegexOption.IGNORE_CASE)
private val RE_NOINTRO = Regex("^\\[No-Intro\\]\\s*", RegexOption.IGNORE_CASE)
private val RE_TOSEC   = Regex("^\\[TOSEC\\]\\s*", RegexOption.IGNORE_CASE)
private val RE_UNDER   = Regex("[_]+")
private val RE_SPACES  = Regex("\\s+")

// detectRegion() regexes — hoisted so they are not reallocated on every call.
private val RE_REGION_USA = Regex("\\b(usa|\\(u\\)|world|ntsc-u)\\b")
private val RE_REGION_EUR = Regex("\\b(europe|eur|\\(e\\)|pal|uk|australia)\\b")
private val RE_REGION_JPN = Regex("\\b(japan|jpn|jap|\\(j\\)|ntsc-j)\\b")

fun cleanGameName(filename: String): String {
    var name = File(filename).name
    name = RE_EXT.replace(name, "")
    name = RE_NKIT.replace(name, "")
    name = RE_RVZ.replace(name, "")
    name = RE_CHD.replace(name, "")
    name = RE_REDUMP.replace(name, "")
    name = RE_NOINTRO.replace(name, "")
    name = RE_TOSEC.replace(name, "")
    name = RE_UNDER.replace(name, " ")
    name = RE_SPACES.replace(name, " ")
    return name.trim()
}

/** Coarse region bucket parsed from a filename's tags. */
enum class Region { USA, EUR, JPN, OTHER }

fun detectRegion(filename: String): Region {
    val s = filename.lowercase()
    return when {
        RE_REGION_USA.containsMatchIn(s) -> Region.USA
        RE_REGION_EUR.containsMatchIn(s) -> Region.EUR
        RE_REGION_JPN.containsMatchIn(s) -> Region.JPN
        else -> Region.OTHER
    }
}

