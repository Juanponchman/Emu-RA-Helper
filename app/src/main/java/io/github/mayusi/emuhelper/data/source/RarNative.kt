package io.github.mayusi.emuhelper.data.source

import android.util.Log

/**
 * Thin Kotlin binding to the native unrar (RAR4 + RAR5) extraction engine.
 *
 * The native library (libemuhelper_unrar.so) wraps the OFFICIAL RARLAB unrar
 * source via a JNI shim (cpp/rar_extract_jni.cpp). All extraction goes through
 * [nativeExtractRarCancellable]: the C++ side sandboxes the write to the
 * destination directory WE hand it, enforces its own path-traversal guard, and
 * returns an ERAR_* code (0 == success) — it never crashes on malformed input.
 *
 * SAFETY: this object only EXPOSES the native calls. The sandbox-then-publish
 * orchestration (extract to an app-private temp dir, verify rc==0, copy into the
 * SAF/app-private destination, clean up) lives in [RarExtractor].
 */
object RarNative {

    /** True only if the native lib loaded. Every call site checks this first so a
     *  build/ABI mishap degrades gracefully (caller falls back to copying the .rar). */
    @JvmStatic
    val available: Boolean

    init {
        available = try {
            System.loadLibrary("emuhelper_unrar")
            true
        } catch (t: Throwable) {
            // Missing .so (unexpected ABI, stripped build) — never crash; the
            // download path falls back to saving the .rar verbatim.
            Log.w("EmuHelper", "Native unrar library unavailable; RAR extraction disabled", t)
            false
        }
    }

    // ---- ERAR_* result codes (mirror unrar/dll.hpp) -------------------------
    const val ERAR_SUCCESS = 0
    const val ERAR_END_ARCHIVE = 10
    const val ERAR_NO_MEMORY = 11
    const val ERAR_BAD_DATA = 12
    const val ERAR_BAD_ARCHIVE = 13
    const val ERAR_UNKNOWN_FORMAT = 14
    const val ERAR_MISSING_PASSWORD = 22
    const val ERAR_BAD_PASSWORD = 24
    /** Our own sentinel (see rar_extract_jni.cpp): the extract was cancelled by request. */
    const val RC_CANCELLED = -100
    const val RC_BAD_ARGS = -101

    /**
     * Extract [archivePath] into [destDir] (a REAL filesystem path — unrar cannot
     * write to a content:// SAF tree). Returns [ERAR_SUCCESS] (0) on success or a
     * non-zero error code on any failure. Synchronous; call off the main thread.
     *
     * @param password reserved for future use; currently ignored by the engine
     *  (encrypted entries abort and the caller falls back to copying the .rar).
     */
    @JvmStatic
    external fun nativeExtractRar(archivePath: String, destDir: String, password: String?): Int

    /**
     * Cancellable extract. [cancelHandle] is a handle from [nativeNewCancelFlag];
     * flipping it via [nativeSetCancel] from another thread aborts a long extract
     * promptly (checked between files and during each file's data callback).
     */
    @JvmStatic
    external fun nativeExtractRarCancellable(
        archivePath: String, destDir: String, password: String?, cancelHandle: Long
    ): Int

    /** Allocate a native cancel flag. Returns a handle; 0 on failure. MUST be paired
     *  with [nativeFreeCancelFlag] to avoid leaking the heap atomic. */
    @JvmStatic
    external fun nativeNewCancelFlag(): Long

    /** Signal cancellation on a flag obtained from [nativeNewCancelFlag]. */
    @JvmStatic
    external fun nativeSetCancel(handle: Long)

    /** Free a flag obtained from [nativeNewCancelFlag]. */
    @JvmStatic
    external fun nativeFreeCancelFlag(handle: Long)
}
