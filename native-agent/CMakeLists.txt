# CMakeLists.txt — JVM Observatory native agent build file
# ─────────────────────────────────────────────────────────────────────────────
# QUICK-START (Windows, x64 Native Tools Command Prompt for VS 2025):
#
#   set JAVA_HOME=C:\Program Files\Java\jdk-22
#   cd C:\path\to\native-agent
#   cmake -B build -G "Visual Studio 18 2025" -A x64
#   cmake --build build --config Release
#   dumpbin /exports build\Release\jvm_observatory_agent.dll
#   java -agentpath:build\Release\jvm_observatory_agent.dll -version
#
# Expected last-command output:
#   [jvmobs] Agent loaded on Windows. Buffer: 4096 slots.
#   openjdk version "22.x.x" ...
# ─────────────────────────────────────────────────────────────────────────────

# 3.18+ is required for reliable FindJNI support on Windows.
# Older versions had a bug where JAVA_INCLUDE_PATH2 (needed for jni_md.h, the
# platform-specific JNI type definitions) was not always populated correctly.
cmake_minimum_required(VERSION 3.18)

project(jvm_observatory_native_agent CXX)

# C++17 gives us std::atomic with guaranteed lock-free behavior for small types,
# std::array, constexpr if, and structured bindings. Required, not optional.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ─── Locate the JDK ──────────────────────────────────────────────────────────
# find_package(JNI) reads the JAVA_HOME environment variable and populates:
#   JAVA_INCLUDE_PATH  → .../jdk-22/include          (contains jni.h, jvmti.h)
#   JAVA_INCLUDE_PATH2 → .../jdk-22/include/win32    (contains jni_md.h on Windows)
#                      → .../jdk-22/include/linux    (contains jni_md.h on Linux)
#
# jni_md.h defines platform-specific integer types like jint, jlong.
# Both paths are required — the build will fail without them.
#
# REQUIRED: abort with a clear error if JAVA_HOME is not set, rather than
# silently producing a broken build that gives cryptic "jni.h not found" errors.
find_package(JNI REQUIRED)

# ─── Source files ─────────────────────────────────────────────────────────────
set(AGENT_SOURCES
    src/agent.cpp           # Agent_OnLoad, Agent_OnUnload, drainEvents JNI
    src/jvmti_events.cpp    # JVMTI callback implementations
    src/ring_buffer.cpp     # ring_buffer.h template — see file for explanation
)

# SHARED builds a .dll on Windows or .so on Linux.
# The JVM loads it via -agentpath: which requires a shared library.
add_library(jvm_observatory_agent SHARED ${AGENT_SOURCES})

# ─── Include directories ──────────────────────────────────────────────────────
# PRIVATE means these paths are only needed to compile this target, not by
# any other target that links against us.
target_include_directories(jvm_observatory_agent PRIVATE
    include                  # our own headers: agent.h, ring_buffer.h
    ${JAVA_INCLUDE_PATH}     # jni.h, jvmti.h
    ${JAVA_INCLUDE_PATH2}    # jni_md.h (platform-specific JNI integer types)
)

# ─── Platform-specific configuration ─────────────────────────────────────────
if(WIN32)

    # ── Compile options ───────────────────────────────────────────────────────
    #
    # /W4    — Warning level 4. Catches most real issues without noise.
    # /WX    — Treat ALL warnings as errors. Ensures the codebase compiles
    #          clean; unexamined warnings are silently ignored bugs in disguise.
    # /O2    — Optimise for speed. Critical: JVMTI callbacks are on the hot path
    #          (every object allocation). We need them fast.
    # /EHsc  — Standard C++ exception model. Required for STL (std::array,
    #          std::atomic). Without it, destructors may not run correctly.
    # /MD    — Link against the SHARED (DLL) version of the C Runtime.
    #
    #          CRITICAL — DO NOT change to /MT:
    #          /MT embeds a PRIVATE copy of the CRT heap inside our DLL.
    #          When our DLL and the JVM exchange objects (e.g. jbyteArray),
    #          they would be allocated by different heaps. Freeing a /MT-DLL
    #          allocation from the JVM's /MD heap = immediate crash.
    #          /MD uses the shared system CRT; all DLLs in the process share
    #          one heap, which is safe.
    #
    # /wd4100 — Suppress "unreferenced formal parameter". JVMTI callback
    #           signatures are fixed by the JVMTI spec; we cannot remove unused
    #           parameters like `jthread thread` even if we don't use them.
    #
    # /wd4324 — Suppress "structure was padded due to alignment specifier".
    #           RingBuffer uses alignas(64) on head_ and tail_ intentionally:
    #           placing them on separate cache lines prevents "false sharing"
    #           (a multi-core performance problem). The padding is CORRECT and
    #           INTENTIONAL. Without /wd4324, /WX turns this into an error.
    target_compile_options(jvm_observatory_agent PRIVATE
        /W4 /WX /O2 /EHsc /MD /wd4100 /wd4324
    )

    # ── Preprocessor definitions ──────────────────────────────────────────────
    #
    # WIN32_LEAN_AND_MEAN — Omit rarely-used parts of <windows.h>. Without
    #                       this, windows.h pulls in ~100 sub-headers including
    #                       macros that clash with std:: names. Also speeds up
    #                       compilation significantly.
    #                       NOTE: agent.h also defines this with an #ifndef guard
    #                       to handle cases where windows.h is included before
    #                       agent.h. Without the guard, MSVC emits C4005
    #                       (macro redefinition), which /WX makes a fatal error.
    #
    # NOMINMAX             — Prevent windows.h from defining min() and max()
    #                       as macros. They would silently override std::min
    #                       and std::max, causing cryptic template errors.
    #                       Same #ifndef guard rationale as above.
    #
    # _WIN32_WINNT=0x0A00  — Target Windows 10 API surface (0x0A00 = 10.0).
    #                       Required for modern QueryPerformanceCounter
    #                       guarantees and other Win10 API improvements.
    #
    # _CRT_SECURE_NO_WARNINGS — Suppress MSVC deprecation warnings for POSIX
    #                           functions (strncpy, etc.). We use strncpy_s
    #                           on Windows, but this avoids noise from headers.
    target_compile_definitions(jvm_observatory_agent PRIVATE
        WIN32_LEAN_AND_MEAN
        NOMINMAX
        _WIN32_WINNT=0x0A00
        _CRT_SECURE_NO_WARNINGS
    )

    # ── .def file: explicit symbol exports ───────────────────────────────────
    # On Windows, symbols in a DLL are HIDDEN by default (opposite of Linux).
    # Two mechanisms expose them:
    #   1. __declspec(dllexport) on the function (JNIEXPORT macro does this)
    #   2. Listing the symbol in the MODULE DEFINITION (.def) file
    # We use BOTH as belt-and-suspenders. The .def file is authoritative:
    # even if JNIEXPORT is accidentally removed, the symbol stays exported.
    # Without the .def file, some linker configurations may still strip the
    # symbol, causing "Agent_OnLoad not found" at JVM startup.
    target_sources(jvm_observatory_agent PRIVATE
        jvm_observatory_agent.def
    )

    # Output filename: jvm_observatory_agent.dll
    # CMake does not add "lib" prefix on Windows by default, but we are
    # explicit to document the intent.
    set_target_properties(jvm_observatory_agent PROPERTIES
        PREFIX ""
        SUFFIX ".dll"
    )

else()
    # ── Linux / macOS ─────────────────────────────────────────────────────────

    # -Wall -Wextra  — Enable most useful warnings
    # -Werror        — Treat warnings as errors (same philosophy as /WX)
    # -O2            — Optimise for speed
    # -fPIC          — Position-Independent Code. REQUIRED for shared libraries
    #                  on Linux. Without it, the linker refuses to produce a .so
    #                  because absolute addresses would break ASLR.
    # -fvisibility=hidden — Hide all symbols by default. We use JNIEXPORT
    #                       (__attribute__((visibility("default")))) on the
    #                       three exported functions to make them visible.
    #                       This mirrors the Windows .def file approach.
    target_compile_options(jvm_observatory_agent PRIVATE
        -Wall -Wextra -Werror -O2 -fPIC -fvisibility=hidden
    )

    # Output filename: jvm_observatory_agent.so (NOT libjvm_observatory_agent.so)
    # The JVM loads the exact path given to -agentpath:. If the file is named
    # libjvm_observatory_agent.so, the -agentpath: argument must match exactly.
    # Removing the "lib" prefix is conventional for JVMTI agents.
    set_target_properties(jvm_observatory_agent PROPERTIES
        PREFIX ""
        SUFFIX ".so"
    )

endif()
