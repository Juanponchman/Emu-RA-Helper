package io.github.mayusi.emuhelper.data.source

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles in-app APK download and install.
 *
 * The APK is written to [context.cacheDir]/update/EmuHelper-update.apk so it is
 * covered by the FileProvider path "update/" declared in res/xml/file_paths.xml.
 */
@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "AppUpdater"
        private const val APK_SUBDIR = "update"
        private const val APK_FILENAME = "EmuHelper-update.apk"
        private const val FILEPROVIDER_AUTHORITY_SUFFIX = ".fileprovider"
    }

    /**
     * Download the APK from [apkUrl] into the update cache directory.
     *
     * @param apkUrl       Direct download URL for the APK.
     * @param expectedSize Expected byte count (used for progress); 0 if unknown.
     * @param onProgress   Callback with fractional progress 0f..1f.
     * @return The written [File] on success, or null on any error.
     */
    suspend fun downloadApk(
        apkUrl: String,
        expectedSize: Long,
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val updateDir = File(context.cacheDir, APK_SUBDIR).also { it.mkdirs() }
            val destFile = File(updateDir, APK_FILENAME)
            // Remove any stale partial download.
            if (destFile.exists()) destFile.delete()

            val request = Request.Builder()
                .url(apkUrl)
                .header("User-Agent", "EmuHelper")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed: HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body ?: run {
                    Log.e(TAG, "Download failed: empty body")
                    return@withContext null
                }

                // Use Content-Length from server if expectedSize is unknown.
                val totalBytes = if (expectedSize > 0) expectedSize
                    else response.header("Content-Length")?.toLongOrNull() ?: 0L

                var downloaded = 0L
                BufferedOutputStream(FileOutputStream(destFile), 256 * 1024).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                onProgress((downloaded.toFloat() / totalBytes).coerceIn(0f, 1f))
                            }
                        }
                        out.flush()
                    }
                }
                Log.i(TAG, "APK downloaded: ${destFile.length()} bytes")
                onProgress(1f)
                destFile
            }
        } catch (e: IOException) {
            Log.e(TAG, "Download IOException", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            null
        }
    }

    /**
     * Launch the system package installer for [file].
     *
     * On Android 8+ (API 26), if the user has not granted "Install unknown apps" to
     * this app, the function returns [InstallResult.NeedsPermission] and fires an
     * intent to the settings screen so the user can grant it. After granting, the
     * caller should invoke [installApk] again.
     *
     * @return [InstallResult] indicating what happened.
     */
    fun installApk(file: File): InstallResult {
        return try {
            // On Android 8+ check "Install unknown apps" permission for this source app.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    // Route the user to the per-source "install unknown apps" settings page.
                    val settingsIntent = Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(settingsIntent)
                    return InstallResult.NeedsPermission
                }
            }

            val authority = "${context.packageName}$FILEPROVIDER_AUTHORITY_SUFFIX"
            val contentUri: Uri = FileProvider.getUriForFile(context, authority, file)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            InstallResult.Launched
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            InstallResult.Error("Could not launch installer: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** State that [installApk] can return so the UI can react. */
    sealed class InstallResult {
        /** The installer activity was launched successfully. */
        object Launched : InstallResult()

        /**
         * "Install unknown apps" is not granted for this app. The user has been sent
         * to Settings — they should grant it and tap "Install" again.
         */
        object NeedsPermission : InstallResult()

        /** Something went wrong; [message] describes the failure. */
        data class Error(val message: String) : InstallResult()
    }
}
