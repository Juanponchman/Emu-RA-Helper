package io.github.mayusi.emuhelper.data.source

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateChecker @Inject constructor(private val okHttpClient: OkHttpClient) {

    /**
     * @param notes   Release body text (may be empty if the release has no notes).
     * @param apkUrl  Direct download URL for the APK asset, or null if no APK is attached.
     * @param apkSize Size in bytes of the APK asset, or 0 if unknown.
     */
    data class UpdateInfo(
        val latestTag: String,
        val htmlUrl: String,
        val isNewer: Boolean,
        val notes: String = "",
        val apkUrl: String? = null,
        val apkSize: Long = 0L
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

    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/mayusi/EmuHelper/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "EmuHelper")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d("UpdateChecker", "Non-2xx response: ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val release = json.decodeFromString<GithubRelease>(body)
            val isNewer = isNewerVersion(release.tagName, currentVersion)

            // Find first .apk asset
            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

            UpdateInfo(
                latestTag = release.tagName,
                htmlUrl = release.htmlUrl,
                isNewer = isNewer,
                notes = release.body ?: "",
                apkUrl = apkAsset?.browserDownloadUrl,
                apkSize = apkAsset?.size ?: 0L
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
        val tagParts = tag.trimStart('v').split('.').mapNotNull { it.toIntOrNull() }
        val currentParts = current.trimStart('v').split('.').mapNotNull { it.toIntOrNull() }
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
