package io.github.mayusi.emuhelper.data.source

import android.util.Log
import java.io.File

/**
 * Orchestrates in-app RAR extraction safely on top of [RarNative].
 *
 * SANDBOX MODEL: unrar writes to a REAL filesystem path (it cannot target a
 * content:// SAF tree), so we extract into an app-private temp dir, verify the
 * engine returned success (rc == 0), and only THEN let the caller publish the
 * extracted files into the user's chosen folder via the existing SAF /
 * app-private logic. On ANY failure the caller falls back to saving the .rar
 * verbatim — the download is never lost and a half-extracted mess is never
 * published (we publish only on rc == 0).
 *
 * MULTI-VOLUME: a split set looks like name.part1.rar / name.part01.rar (RAR5
 * new-style) or name.rar / name.r00 / name.r01 (RAR4 old-style). unrar follows
 * the chain itself once opened on the FIRST volume; we must only ever START on
 * the first volume and never on a trailing part. [isFirstVolume] enforces that.
 */
object RarExtractor {

    /**
     * Result of a [extractToTempDir] attempt.
     *  - [success] true only when the native engine returned 0 AND at least one
     *    file landed in the temp dir.
     *  - [tempDir] the sandboxed dir the files were written to (caller publishes
     *    from here, then deletes it). Null when extraction did not run.
     *  - [files] the extracted regular files (flattened — we pass safe basenames
     *    to the engine, so there are no nested dirs to worry about).
     *  - [code] the raw ERAR_* / sentinel code for logging.
     */
    data class Result(
        val success: Boolean,
        val tempDir: File?,
        val files: List<File>,
        val code: Int
    )

    /**
     * Is [filename] a RAR archive we should START extraction from?
     *
     * Accepts:
     *   - a plain ".rar"            (single-volume, or the first volume of an
     *                                old-style RAR4 .rar/.r00/.r01 set)
     *   - ".part1.rar" / ".part01.rar" / ".part001.rar"  (RAR5 first volume)
     * Rejects:
     *   - ".part2.rar"+ / ".part02.rar"+ (trailing volumes — unrar opens these
     *                                     via the chain, never directly)
     *   - ".r00", ".r01", ... (old-style trailing parts; the .rar is the entry)
     *   - anything not ending in .rar / .partN.rar
     *
     * Case-insensitive.
     */
    fun isFirstVolume(filename: String): Boolean {
        val lower = filename.lowercase().trim()
        if (!lower.endsWith(".rar")) return false
        // RAR5 multi-volume: <name>.partNNN.rar
        val partMatch = Regex(""".*\.part(\d+)\.rar$""").find(lower)
        if (partMatch != null) {
            val n = partMatch.groupValues[1].toIntOrNull() ?: return false
            return n == 1   // only the first part starts an extraction
        }
        // Plain .rar (covers single-volume AND the first vol of old-style sets).
        return true
    }

    /** Convenience: any .rar at all (used to decide whether to PROMPT the user). */
    fun isRar(filename: String): Boolean = filename.lowercase().trim().endsWith(".rar")

    /**
     * Extract [archive] into a fresh sandboxed temp dir under [cacheRoot].
     * Returns a [Result]; on failure [Result.success] is false and the caller
     * should save the .rar verbatim instead.
     *
     * @param cancelHandle optional native cancel-flag handle (see [RarNative]).
     *  When non-zero, flipping it from another thread aborts the extract.
     */
    fun extractToTempDir(archive: File, cacheRoot: File, cancelHandle: Long = 0L): Result {
        if (!RarNative.available) {
            Log.w("EmuHelper", "RAR extract skipped: native lib unavailable")
            return Result(false, null, emptyList(), RarNative.RC_BAD_ARGS)
        }
        if (!archive.exists() || archive.length() == 0L) {
            return Result(false, null, emptyList(), RarNative.RC_BAD_ARGS)
        }

        // Unique, app-private sandbox dir. Cleaned by the caller after publish.
        val tempDir = File(cacheRoot, "rar_out_${System.nanoTime()}").apply {
            deleteRecursively()  // belt & suspenders if a previous run left junk
            mkdirs()
        }

        val code = try {
            if (cancelHandle != 0L) {
                RarNative.nativeExtractRarCancellable(
                    archive.absolutePath, tempDir.absolutePath, null, cancelHandle
                )
            } else {
                RarNative.nativeExtractRar(archive.absolutePath, tempDir.absolutePath, null)
            }
        } catch (t: Throwable) {
            // Never let a native/JNI hiccup crash the download.
            Log.w("EmuHelper", "Native RAR extract threw for ${archive.name}", t)
            tempDir.deleteRecursively()
            return Result(false, null, emptyList(), RarNative.RC_BAD_ARGS)
        }

        if (code != RarNative.ERAR_SUCCESS) {
            Log.w("EmuHelper", "RAR extract failed for ${archive.name}, rc=$code")
            tempDir.deleteRecursively()
            return Result(false, null, emptyList(), code)
        }

        // Collect the extracted regular files (defensive recursive walk in case a
        // future engine change preserves a subdir; we still flatten on publish).
        val files = tempDir.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) {
            // rc==0 but nothing written -> treat as failure so we don't "succeed"
            // with an empty publish and then delete the .rar.
            Log.w("EmuHelper", "RAR extract produced no files for ${archive.name}")
            tempDir.deleteRecursively()
            return Result(false, null, emptyList(), RarNative.ERAR_BAD_ARCHIVE)
        }

        return Result(true, tempDir, files, code)
    }
}
