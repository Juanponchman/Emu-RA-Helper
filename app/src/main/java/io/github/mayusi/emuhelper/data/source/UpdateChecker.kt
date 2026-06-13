package io.github.mayusi.emuhelper.data.source

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateChecker @Inject constructor(private val okHttpClient: OkHttpClient) {

    /**
     * @param notes      Release body text (may be empty if the release has no notes).
     * @param apkUrl     Direct download URL for the APK asset, or null if no APK is attached.
     * @param apkSize    Size in bytes of the APK asset, or 0 if unknown.
     * @param apkSha256  SHA-256 hex digest parsed from the release notes, or null if not published.
     * @param sha256Url  URL of a sidecar .sha256 asset if present in the release (informational only;
     *                   not fetched eagerly — see AppUpdater for verification logic).
     */
    data class UpdateInfo(
        val latestTag: String,
        val htmlUrl: String,
        val isNewer: Boolean,
        val notes: String = "",
        val apkUrl: String? = null,
        val apkSize: Long = 0L,
        val apkSha256: String? = null,
        val sha256Url: String? = null
    )

    @Serializable
    private data class GithubAsset(
        @SerialName("name") val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
        @SerialName("size") val size: Long = 0L
    )

    @Serializable
    private data class GithubRelease(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String,
        @SerialName("body") val body: String? = null,
        @SerialName("assets") val assets: List<GithubAsset> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Regex matching a standalone 64-hex-char SHA-256 digest, optionally on a line that
     * also contains the word "sha256" (case-insensitive). Captures the digest in group 1.
     */
    private val sha256Regex = Regex(
        """(?i)(?:sha-?256[^\n]*?)?([0-9a-f]{64})""",
        setOf(RegexOption.IGNORE_CASE)
    )

    /**
     * A stripped OkHttpClient that shares the connection pool of the injected client but
     * carries NO cookie jar. GitHub API/download requests must never have archive.org
     * session cookies attached to them.
     */
    private val noCookieClient: OkHttpClient by lazy {
        okHttpClient.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
    }

    /**
     * Validate that [url] uses https and its host is within the GitHub allowed set.
     * Returns the url unchanged if valid, or null if it fails the check.
     */
    private fun validateGitHubUrl(url: String): String? {
        return try {
            val parsed = url.toHttpUrlOrNull() ?: return null
            if (parsed.scheme != "https") return null
            val host = parsed.host
            val allowed = host == "github.com" ||
                host.endsWith(".github.com") ||
                host == "githubusercontent.com" ||
                host.endsWith(".githubusercontent.com")
            if (allowed) url else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/mayusi/EmuHelper/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "EmuHelper")
                .build()

            val response = noCookieClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d("UpdateChecker", "Non-2xx response: ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val release = json.decodeFromString<GithubRelease>(body)
            val isNewer = isNewerVersion(release.tagName, currentVersion)

            // Find first .apk asset; gate its URL through the host allowlist.
            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            val rawApkUrl = apkAsset?.browserDownloadUrl
            val apkUrl = rawApkUrl?.let { validateGitHubUrl(it) }.also {
                if (rawApkUrl != null && it == null) {
                    Log.w("UpdateChecker", "APK URL failed host validation: $rawApkUrl")
                }
            }

            // Find a sidecar .sha256 asset (note URL only — not fetched eagerly).
            val sha256Asset = release.assets.firstOrNull { a ->
                a.name.endsWith(".sha256", ignoreCase = true) ||
                a.name.endsWith(".apk.sha256", ignoreCase = true)
            }
            val sha256Url = sha256Asset?.browserDownloadUrl?.let { validateGitHubUrl(it) }

            // Parse a SHA-256 hex digest from the release body.
            val releaseNotes = release.body ?: ""
            val apkSha256 = sha256Regex.find(releaseNotes)?.groupValues?.get(1)?.lowercase()

            UpdateInfo(
                latestTag = release.tagName,
                htmlUrl = release.htmlUrl,
                isNewer = isNewer,
                notes = releaseNotes,
                apkUrl = apkUrl,
                apkSize = apkAsset?.size ?: 0L,
                apkSha256 = apkSha256,
                sha256Url = sha256Url
            )
        } catch (e: Exception) {
            Log.d("UpdateChecker", "Update check failed", e)
            null
        }
    }

    /**
     * Strips a leading 'v', splits by '.', compares numerically component-by-component.
     * Returns true if [tag] represents a version strictly newer than [current].
     */
    fun isNewerVersion(tag: String, current: String): Boolean {
        // B5: Strip pre-release suffixes (e.g. "-rc1", "+build") before splitting so that
        // "v0.1.8-rc1" yields [0,1,8] instead of [0,1] (the old code dropped the last
        // component because "8-rc1".toIntOrNull() == null). Applied symmetrically to both
        // tag and current so v0.1.10 > v0.1.9 still holds (numeric comparison).
        fun parse(v: String) = v.trimStart('v')
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
            .mapNotNull { it.toIntOrNull() }
        val tagParts = parse(tag)
        val currentParts = parse(current)
        val len = maxOf(tagParts.size, currentParts.size)
        for (i in 0 until len) {
            val t = tagParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (t > c) return true
            if (t < c) return false
        }
        return false // equal
    }
}
