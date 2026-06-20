// =============================================================================
// rar_extract_jni.cpp — JNI bridge to the official RARLAB unrar engine.
//
// UnRAR source code may be used in any software to handle RAR archives without
// limitations free of charge, but cannot be used to develop RAR (WinRAR)
// compatible archiver and to re-create RAR compression algorithm, which is
// proprietary. Distribution of modified UnRAR source code in separate form or
// as a part of other software is permitted, provided that full text of this
// paragraph, starting from "UnRAR source code" words, is included in license,
// or in documentation if license is not available, and in source code comments
// of resulting package.
//   (UnRAR license paragraph 2 — see app/src/main/cpp/unrar/license.txt for the
//    full license. Copyrights to RAR/UnRAR are owned by Alexander Roshal.)
//
// SAFETY MODEL (this runs native C++ on UNTRUSTED archive bytes):
//   * We control the destination directory entirely (a sandboxed app-private
//     temp dir handed in by Kotlin). unrar never sees an arbitrary path.
//   * BEFORE each file is written we sanitize the archive entry name OURSELVES
//     (strip drive letters/leading slashes, drop "."/".." components, normalize
//     separators) down to a safe basename leaf, then JOIN it onto our sandboxed
//     destDir and hand unrar the FULL absolute path as DestName, so unrar writes
//     exactly where we say — defence in depth on top of unrar's own hardening.
//     (unrar's dll path: when DestNameW is set it uses it verbatim as the whole
//     output pathname and IGNORES DestPathW — see extract.cpp ~line 657 — so the
//     leaf MUST already include destDir or it resolves against the process CWD,
//     which on Android is "/" and is read-only -> ERAR_ECREATE on every file.)
//   * Any escape attempt SKIPS that entry. Any malformed input returns an error
//     code; we never crash the process (all C-API calls are exception-isolated
//     by dll.cpp, and we additionally wrap our own logic).
//   * A cancel flag (atomic, in UserData) aborts a multi-GB extract cleanly.
// =============================================================================

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <string>
#include <vector>
#include <cstring>
#include <cwchar>

// The vendored unrar C API. dll.hpp's platform typedefs (HANDLE, PASCAL, LONG,
// LPARAM, UINT) live inside an `#ifdef _UNIX` block, so we must establish the
// platform context FIRST. unrar/raros.hpp self-selects _UNIX + _ANDROID from
// the NDK's auto-defined __ANDROID__ (no source edits) — include it before
// dll.hpp so those typedefs are visible to this translation unit too.
#include "unrar/raros.hpp"
// dll.hpp pulls in the ERAR_* codes, the RAR_OM_*/RAR_EXTRACT operations, the
// UCM_* callback messages and the function declarations.
#include "unrar/dll.hpp"

#define LOG_TAG "EmuHelperRar"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// A negative sentinel the Kotlin side maps to "we aborted it ourselves" so it is
// distinguishable from any positive ERAR_* engine code.
static const int RC_CANCELLED = -100;
static const int RC_BAD_ARGS  = -101;

// -----------------------------------------------------------------------------
// Per-extraction state, handed to the unrar callback via UserData.
// -----------------------------------------------------------------------------
struct ExtractCtx {
    std::atomic<bool>* cancel;  // set true by Kotlin (off-thread) to abort
};

// -----------------------------------------------------------------------------
// wchar conversion helpers.
//
// On Android `wchar_t` is 32-bit (UTF-32). unrar's `wchar` typedef IS `wchar_t`
// (see rartypes.hpp), and the C API takes wchar_t* for the W variants, so we
// build proper 32-bit wide strings. We do a minimal UTF-8 <-> UTF-32 conversion
// ourselves (no locale dependency, which mbstowcs would need set up).
// -----------------------------------------------------------------------------

// UTF-8 (Java modified-UTF8-safe for the BMP+ range we use) -> UTF-32 wstring.
static std::wstring utf8ToWide(const char* s) {
    std::wstring out;
    if (s == nullptr) return out;
    const unsigned char* p = reinterpret_cast<const unsigned char*>(s);
    while (*p) {
        uint32_t cp;
        unsigned char c = *p;
        if (c < 0x80) {
            cp = c; p += 1;
        } else if ((c >> 5) == 0x6 && p[1]) {
            cp = ((c & 0x1F) << 6) | (p[1] & 0x3F); p += 2;
        } else if ((c >> 4) == 0xE && p[1] && p[2]) {
            cp = ((c & 0x0F) << 12) | ((p[1] & 0x3F) << 6) | (p[2] & 0x3F); p += 3;
        } else if ((c >> 3) == 0x1E && p[1] && p[2] && p[3]) {
            cp = ((c & 0x07) << 18) | ((p[1] & 0x3F) << 12) |
                 ((p[2] & 0x3F) << 6) | (p[3] & 0x3F); p += 4;
        } else {
            // Malformed byte — replace and advance one byte to stay in sync.
            cp = 0xFFFD; p += 1;
        }
        out.push_back(static_cast<wchar_t>(cp));
    }
    return out;
}

// -----------------------------------------------------------------------------
// PATH-TRAVERSAL GUARD (our own — belt & suspenders over unrar's hardening).
//
// Given an archive entry's FileNameW, produce a SAFE single-segment leaf name to
// hand back as DestName. We:
//   1. Normalize backslashes to forward slashes.
//   2. Split on '/'.
//   3. Drop empty / "." / ".." components and any Windows drive-letter prefix
//      ("C:") — this neutralises "../../etc", "/abs/path", "C:\evil", etc.
//   4. Keep ONLY the final remaining component (the basename). Since the Kotlin
//      publish step flattens entries into the destination console subfolder
//      anyway, a basename is both safe and sufficient; it can never escape the
//      destination directory.
// Returns an empty string if nothing safe remains (caller SKIPS the entry).
// -----------------------------------------------------------------------------
static std::wstring safeLeafName(const std::wstring& raw) {
    std::wstring s = raw;
    for (auto& ch : s) {
        if (ch == L'\\') ch = L'/';
    }
    // Walk components, keeping the last safe one.
    std::wstring leaf;
    size_t start = 0;
    while (start <= s.size()) {
        size_t slash = s.find(L'/', start);
        std::wstring comp = (slash == std::wstring::npos)
                                ? s.substr(start)
                                : s.substr(start, slash - start);
        // Trim trailing/leading whitespace and control chars defensively.
        // Reject unsafe components.
        bool unsafe = comp.empty() || comp == L"." || comp == L"..";
        // Reject a Windows drive-letter component like "C:" or "C:something".
        if (!unsafe && comp.size() >= 2 && comp[1] == L':' &&
            ((comp[0] >= L'A' && comp[0] <= L'Z') ||
             (comp[0] >= L'a' && comp[0] <= L'z'))) {
            unsafe = true;
        }
        if (!unsafe) {
            leaf = comp;  // last safe component wins -> basename
        }
        if (slash == std::wstring::npos) break;
        start = slash + 1;
    }
    // Final scrub: strip any NUL and path separators that somehow survived, and
    // collapse a leading dot-run so we never produce "" or "..".
    std::wstring cleaned;
    for (wchar_t ch : leaf) {
        if (ch == L'/' || ch == L'\\' || ch == L'\0') continue;
        cleaned.push_back(ch);
    }
    if (cleaned == L"." || cleaned == L"..") cleaned.clear();
    return cleaned;
}

// -----------------------------------------------------------------------------
// unrar extraction callback.
//
// UCM_PROCESSDATA   — fired periodically while a file is being written. We check
//                     the cancel flag and return -1 to abort the whole extract.
// UCM_CHANGEVOLUMEW — multi-volume: RAR_VOL_NOTIFY (the next part exists, unrar
//                     is about to open it) -> return 0 to continue; RAR_VOL_ASK
//                     (a part is MISSING) -> return -1 to abort gracefully.
// UCM_NEEDPASSWORDW — encrypted entry. We return -1 (abort) for now; game ROMs
//                     are rarely encrypted and the Kotlin fallback saves the
//                     .rar verbatim. (Future: surface a password prompt.)
// -----------------------------------------------------------------------------
static int CALLBACK unrarCallback(UINT msg, LPARAM userData, LPARAM p1, LPARAM p2) {
    ExtractCtx* ctx = reinterpret_cast<ExtractCtx*>(userData);
    switch (msg) {
        case UCM_PROCESSDATA:
            if (ctx != nullptr && ctx->cancel != nullptr &&
                ctx->cancel->load(std::memory_order_relaxed)) {
                LOGW("extract cancelled by request");
                return -1;  // abort
            }
            return 0;

        case UCM_CHANGEVOLUME:
        case UCM_CHANGEVOLUMEW:
            // p2 is the volume mode.
            if (ctx != nullptr && ctx->cancel != nullptr &&
                ctx->cancel->load(std::memory_order_relaxed)) {
                return -1;
            }
            if (p2 == RAR_VOL_ASK) {
                LOGW("multi-volume: next part missing -> aborting");
                return -1;  // a required part is absent
            }
            return 0;  // RAR_VOL_NOTIFY: next part present, continue

        case UCM_NEEDPASSWORD:
        case UCM_NEEDPASSWORDW:
            LOGW("encrypted archive: password required -> aborting (fallback saves .rar)");
            return -1;  // abort; Kotlin falls back to copying the .rar verbatim

        case UCM_LARGEDICT:
            // Dictionary larger than what we'd want to allocate on a handheld.
            // Returning -1 makes RARProcessFile fail with an error we fall back
            // on, rather than risking an OOM. (Most ROM archives are small dict.)
            return -1;

        default:
            return 0;
    }
}

// -----------------------------------------------------------------------------
// The single extraction entry point. Returns 0 on success, RC_CANCELLED if the
// user aborted, RC_BAD_ARGS for bad input, else the first ERAR_* engine code.
// -----------------------------------------------------------------------------
static int doExtract(const std::wstring& archiveW,
                     const std::wstring& destDirW,
                     std::atomic<bool>* cancel) {
    ExtractCtx ctx{};
    ctx.cancel = cancel;

    // Mutable copies for the C API (it takes non-const wchar_t*).
    std::vector<wchar_t> arcBuf(archiveW.begin(), archiveW.end());
    arcBuf.push_back(0);

    RAROpenArchiveDataEx openData;
    std::memset(&openData, 0, sizeof(openData));
    openData.ArcNameW   = arcBuf.data();
    openData.OpenMode   = RAR_OM_EXTRACT;
    openData.Callback   = unrarCallback;
    openData.UserData   = reinterpret_cast<LPARAM>(&ctx);

    HANDLE h = RAROpenArchiveEx(&openData);
    if (h == nullptr || openData.OpenResult != ERAR_SUCCESS) {
        int rc = (openData.OpenResult != ERAR_SUCCESS) ? (int)openData.OpenResult
                                                       : ERAR_EOPEN;
        LOGW("RAROpenArchiveEx failed rc=%d", rc);
        if (h != nullptr) RARCloseArchive(h);
        return rc;
    }

    // Normalize the destination dir ONCE: forward-slash separators and strip any
    // trailing separator so we can cleanly join "<destDir>/<leaf>" per file.
    std::wstring destDirNorm = destDirW;
    for (auto& ch : destDirNorm) {
        if (ch == L'\\') ch = L'/';
    }
    while (!destDirNorm.empty() && destDirNorm.back() == L'/') {
        destDirNorm.pop_back();
    }

    int finalRc = ERAR_SUCCESS;

    for (;;) {
        if (cancel != nullptr && cancel->load(std::memory_order_relaxed)) {
            finalRc = RC_CANCELLED;
            break;
        }

        RARHeaderDataEx header;
        std::memset(&header, 0, sizeof(header));
        int readRc = RARReadHeaderEx(h, &header);
        if (readRc == ERAR_END_ARCHIVE) {
            break;  // clean end
        }
        if (readRc != ERAR_SUCCESS) {
            LOGW("RARReadHeaderEx failed rc=%d", readRc);
            finalRc = readRc;
            break;
        }

        // Is this entry a directory? (We flatten, so we just SKIP directory
        // entries — the files inside carry their own names.)
        bool isDir = (header.Flags & RHDF_DIRECTORY) != 0;

        // OUR path-traversal guard: derive a safe basename leaf from FileNameW.
        std::wstring rawName(header.FileNameW);
        std::wstring safeLeaf = safeLeafName(rawName);

        if (isDir || safeLeaf.empty()) {
            // Directory marker, or nothing safe survived sanitization -> SKIP
            // (RAR_SKIP advances without writing anything).
            int skipRc = RARProcessFileW(h, RAR_SKIP, nullptr, nullptr);
            if (skipRc != ERAR_SUCCESS) {
                LOGW("RAR_SKIP failed rc=%d for unsafe/dir entry", skipRc);
                finalRc = skipRc;
                break;
            }
            continue;
        }

        // Extract. unrar's dll uses DestNameW verbatim as the COMPLETE output
        // pathname and ignores DestPathW (extract.cpp ~657), so we must hand it
        // the FULL absolute path = "<destDir>/<safeLeaf>". A bare leaf would be
        // resolved against the process CWD ("/" on Android, read-only) and fail
        // with ERAR_ECREATE. The safe leaf is still a single sanitized segment,
        // so the file can never escape destDir.
        std::wstring fullDest = destDirNorm;
        fullDest.push_back(L'/');
        fullDest += safeLeaf;

        std::vector<wchar_t> nameBuf(fullDest.begin(), fullDest.end());
        nameBuf.push_back(0);

        // DestPath = nullptr (unrar ignores it when DestName is set anyway).
        int procRc = RARProcessFileW(h, RAR_EXTRACT, nullptr, nameBuf.data());
        if (procRc != ERAR_SUCCESS) {
            if (cancel != nullptr && cancel->load(std::memory_order_relaxed)) {
                finalRc = RC_CANCELLED;
            } else {
                LOGW("RARProcessFileW(EXTRACT) failed rc=%d", procRc);
                finalRc = procRc;
            }
            break;
        }
    }

    RARCloseArchive(h);
    return finalRc;
}

// -----------------------------------------------------------------------------
// JNI entry point.
//   nativeExtractRar(archivePath, destDir, password) : Int
// password is currently ignored (encrypted archives abort -> Kotlin fallback).
// -----------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_io_github_mayusi_emuhelper_data_source_RarNative_nativeExtractRar(
        JNIEnv* env, jobject /*thiz*/,
        jstring archivePath, jstring destDir, jstring /*password*/) {

    if (archivePath == nullptr || destDir == nullptr) {
        return RC_BAD_ARGS;
    }

    const char* arcUtf8  = env->GetStringUTFChars(archivePath, nullptr);
    const char* destUtf8 = env->GetStringUTFChars(destDir, nullptr);
    if (arcUtf8 == nullptr || destUtf8 == nullptr) {
        if (arcUtf8)  env->ReleaseStringUTFChars(archivePath, arcUtf8);
        if (destUtf8) env->ReleaseStringUTFChars(destDir, destUtf8);
        return RC_BAD_ARGS;
    }

    std::wstring archiveW = utf8ToWide(arcUtf8);
    std::wstring destDirW = utf8ToWide(destUtf8);

    env->ReleaseStringUTFChars(archivePath, arcUtf8);
    env->ReleaseStringUTFChars(destDir, destUtf8);

    if (archiveW.empty() || destDirW.empty()) {
        return RC_BAD_ARGS;
    }

    // This native extract is invoked from a worker thread (off the UI thread)
    // by the Kotlin caller, so we do not poll a cancel flag from JNI here; the
    // Kotlin layer enforces cancellation by interrupting/abandoning the worker
    // and (for cooperative aborts) we expose a cancel hook below. For this
    // synchronous call we run with a no-cancel context; cooperative cancel is
    // wired via nativeExtractRarCancellable when needed.
    std::atomic<bool> noCancel{false};

    int rc;
    try {
        rc = doExtract(archiveW, destDirW, &noCancel);
    } catch (...) {
        // Defence in depth: unrar's C API already isolates RAR_EXIT throws, but
        // we never let ANYTHING escape into the JVM.
        LOGE("doExtract threw — returning ERAR_UNKNOWN");
        rc = ERAR_UNKNOWN;
    }
    return rc;
}

// -----------------------------------------------------------------------------
// Cancellable variant: Kotlin passes a long handle to a heap atomic<bool> it can
// flip from another thread. This lets a multi-GB extract abort promptly without
// killing the worker thread. The pair of create/destroy keeps ownership clear.
// -----------------------------------------------------------------------------
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_mayusi_emuhelper_data_source_RarNative_nativeNewCancelFlag(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return reinterpret_cast<jlong>(new std::atomic<bool>(false));
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_mayusi_emuhelper_data_source_RarNative_nativeSetCancel(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle != 0) {
        reinterpret_cast<std::atomic<bool>*>(handle)->store(true, std::memory_order_relaxed);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_mayusi_emuhelper_data_source_RarNative_nativeFreeCancelFlag(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle != 0) {
        delete reinterpret_cast<std::atomic<bool>*>(handle);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_mayusi_emuhelper_data_source_RarNative_nativeExtractRarCancellable(
        JNIEnv* env, jobject /*thiz*/,
        jstring archivePath, jstring destDir, jstring /*password*/, jlong cancelHandle) {

    if (archivePath == nullptr || destDir == nullptr) {
        return RC_BAD_ARGS;
    }

    const char* arcUtf8  = env->GetStringUTFChars(archivePath, nullptr);
    const char* destUtf8 = env->GetStringUTFChars(destDir, nullptr);
    if (arcUtf8 == nullptr || destUtf8 == nullptr) {
        if (arcUtf8)  env->ReleaseStringUTFChars(archivePath, arcUtf8);
        if (destUtf8) env->ReleaseStringUTFChars(destDir, destUtf8);
        return RC_BAD_ARGS;
    }

    std::wstring archiveW = utf8ToWide(arcUtf8);
    std::wstring destDirW = utf8ToWide(destUtf8);

    env->ReleaseStringUTFChars(archivePath, arcUtf8);
    env->ReleaseStringUTFChars(destDir, destUtf8);

    if (archiveW.empty() || destDirW.empty()) {
        return RC_BAD_ARGS;
    }

    std::atomic<bool>* cancel =
        (cancelHandle != 0) ? reinterpret_cast<std::atomic<bool>*>(cancelHandle) : nullptr;
    std::atomic<bool> fallback{false};
    if (cancel == nullptr) cancel = &fallback;

    int rc;
    try {
        rc = doExtract(archiveW, destDirW, cancel);
    } catch (...) {
        LOGE("doExtract (cancellable) threw — returning ERAR_UNKNOWN");
        rc = ERAR_UNKNOWN;
    }
    return rc;
}
