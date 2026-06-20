# =============================================================================
# EmuHelper native RAR extraction — builds the OFFICIAL RARLAB unrar engine
# (RAR4 + RAR5) as a static lib and our thin JNI shim as a shared lib.
#
# The unrar source is vendored unmodified at cpp/unrar/ (RARLAB 7.21, UnRAR
# license — see unrar/license.txt). We compile the makefile's `lib:` target
# (the unrar.dll / C API path), NOT the CLI. __ANDROID__ is auto-defined by
# the NDK, so unrar/raros.hpp self-selects _UNIX + _ANDROID with no edits.
# =============================================================================
cmake_minimum_required(VERSION 3.22.1)
project(emuhelper_unrar LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(UNRAR_DIR ${CMAKE_CURRENT_SOURCE_DIR}/unrar)

# -----------------------------------------------------------------------------
# unrar source set — derived EXACTLY from unrar/makefile `lib:` target:
#   lib: $(OBJECTS) $(LIB_OBJ)
#   OBJECTS = rar strlist strfn pathfn smallfn global file filefn filcreat
#             archive arcread unicode system crypt crc rawread encname resource
#             match timefn rdwrfn consio options errhnd rarvm secpassword
#             rijndael getbits sha1 sha256 blake2s hash extinfo extract volume
#             list find unpack headers threadpool rs16 cmddata ui largepage
#   LIB_OBJ = filestr scantree dll qopen
#
# NOTE: recvol/rs/rs16... recvol + rs belong ONLY to the CLI (UNRAR_OBJ), so
# they are EXCLUDED. rs16 IS in OBJECTS, so it is INCLUDED.
#
# Helper .cpp files that unrar #includes directly into a parent translation
# unit are deliberately OMITTED here (listing them would duplicate symbols):
#   crypt1/2/3/5.cpp        -> #included by crypt.cpp
#   blake2s_sse.cpp,
#   blake2sp.cpp            -> #included by blake2s.cpp
#   threadmisc.cpp          -> #included by threadpool.cpp
#   cmdfilter.cpp,
#   cmdmix.cpp              -> #included by cmddata.cpp
#   unpackinline.cpp,
#   unpack15/20/30/50/
#   50frag/50mt.cpp         -> #included by unpack.cpp (full RAR4 + RAR5 decode)
#
# rar.cpp's main() is guarded by `#if !defined(RARDLL)`, so under -DRARDLL it
# compiles to an empty unit (no CLI main) — exactly the lib target's behavior.
# -----------------------------------------------------------------------------
set(UNRAR_SOURCES
    ${UNRAR_DIR}/rar.cpp
    ${UNRAR_DIR}/strlist.cpp
    ${UNRAR_DIR}/strfn.cpp
    ${UNRAR_DIR}/pathfn.cpp
    ${UNRAR_DIR}/smallfn.cpp
    ${UNRAR_DIR}/global.cpp
    ${UNRAR_DIR}/file.cpp
    ${UNRAR_DIR}/filefn.cpp
    ${UNRAR_DIR}/filcreat.cpp
    ${UNRAR_DIR}/archive.cpp
    ${UNRAR_DIR}/arcread.cpp
    ${UNRAR_DIR}/unicode.cpp
    ${UNRAR_DIR}/system.cpp
    ${UNRAR_DIR}/crypt.cpp
    ${UNRAR_DIR}/crc.cpp
    ${UNRAR_DIR}/rawread.cpp
    ${UNRAR_DIR}/encname.cpp
    ${UNRAR_DIR}/resource.cpp
    ${UNRAR_DIR}/match.cpp
    ${UNRAR_DIR}/timefn.cpp
    ${UNRAR_DIR}/rdwrfn.cpp
    ${UNRAR_DIR}/consio.cpp
    ${UNRAR_DIR}/options.cpp
    ${UNRAR_DIR}/errhnd.cpp
    ${UNRAR_DIR}/rarvm.cpp
    ${UNRAR_DIR}/secpassword.cpp
    ${UNRAR_DIR}/rijndael.cpp
    ${UNRAR_DIR}/getbits.cpp
    ${UNRAR_DIR}/sha1.cpp
    ${UNRAR_DIR}/sha256.cpp
    ${UNRAR_DIR}/blake2s.cpp
    ${UNRAR_DIR}/hash.cpp
    ${UNRAR_DIR}/extinfo.cpp
    ${UNRAR_DIR}/extract.cpp
    ${UNRAR_DIR}/volume.cpp
    ${UNRAR_DIR}/list.cpp
    ${UNRAR_DIR}/find.cpp
    ${UNRAR_DIR}/unpack.cpp
    ${UNRAR_DIR}/headers.cpp
    ${UNRAR_DIR}/threadpool.cpp
    ${UNRAR_DIR}/rs16.cpp
    ${UNRAR_DIR}/cmddata.cpp
    ${UNRAR_DIR}/ui.cpp
    ${UNRAR_DIR}/largepage.cpp
    # ---- LIB_OBJ (the C API / unrar.dll path) ----
    ${UNRAR_DIR}/filestr.cpp
    ${UNRAR_DIR}/scantree.cpp
    ${UNRAR_DIR}/dll.cpp
    ${UNRAR_DIR}/qopen.cpp
)

add_library(unrar_static STATIC ${UNRAR_SOURCES})

target_include_directories(unrar_static PUBLIC ${UNRAR_DIR})

# Build defines mirror the makefile `lib:` recipe + a few NDK-portability flags.
#   RARDLL            -> select the C API, drop the CLI main
#   _FILE_OFFSET_BITS / _LARGEFILE_SOURCE -> 64-bit file offsets (multi-GB ROMs)
#   RAR_SMP           -> enable the thread pool (multi-core unpack on handhelds)
#   LITTLE_ENDIAN     -> arm64 + x86_64 are LE; assert it so os.hpp is happy
target_compile_definitions(unrar_static PRIVATE
    RARDLL
    _FILE_OFFSET_BITS=64
    _LARGEFILE_SOURCE
    RAR_SMP
    LITTLE_ENDIAN
)

# -O2 + -fPIC + -fexceptions match the lib recipe; the -Wno-* quiet unrar's own
# (benign, upstream) warnings so they don't drown a real diagnostic.
target_compile_options(unrar_static PRIVATE
    -O2
    -fPIC
    -fexceptions
    -Wno-switch
    -Wno-dangling-else
    -Wno-logical-op-parentheses
    -Wno-unknown-pragmas
    -Wno-unused-variable
    -Wno-unused-function
)

# -----------------------------------------------------------------------------
# Our JNI shim — the ONLY surface Kotlin talks to. Links the static unrar lib
# and Android's log lib. --exclude-libs,ALL hides every unrar symbol from the
# .so's dynamic table so nothing but our JNI entry points is exported.
# -----------------------------------------------------------------------------
add_library(emuhelper_unrar SHARED rar_extract_jni.cpp)

target_include_directories(emuhelper_unrar PRIVATE ${UNRAR_DIR})

# Match the unrar build's portability defines so the shared shim and the static
# lib agree on platform/ABI macros (64-bit offsets, endianness).
target_compile_definitions(emuhelper_unrar PRIVATE
    _FILE_OFFSET_BITS=64
    _LARGEFILE_SOURCE
    LITTLE_ENDIAN
)

target_compile_options(emuhelper_unrar PRIVATE
    -fexceptions
    -Wno-unknown-pragmas
)

find_library(log-lib log)

target_link_libraries(emuhelper_unrar
    unrar_static
    ${log-lib}
)

target_link_options(emuhelper_unrar PRIVATE "-Wl,--exclude-libs,ALL")
