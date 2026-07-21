cmake_minimum_required(VERSION 3.22.1)
project("whisperjni")

# whisper.cpp/ggml are third-party compute kernels we never step through in a
# debugger, and the NDK's debug default (-O0) makes them ~25x slower — unusably
# slow for real-time captions. Force full optimization (and drop asserts) for the
# native code in EVERY variant. This must precede add_subdirectory/add_library so
# it propagates to the whisper + ggml targets and the JNI wrapper below.
add_compile_options(-O3 -DNDEBUG)

# Build the core whisper.cpp library as a static library
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "")
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "")
add_subdirectory(whisperjni/whisper)

# Build the JNI wrapper that gives us io.github.givimad.whisperjni
add_library(whisperjni SHARED
    whisperjni/io_github_givimad_whisperjni_WhisperJNI.cpp
    whisperjni/whisper/examples/grammar-parser.cpp
)

target_include_directories(whisperjni PRIVATE
    whisperjni/whisper/include
    whisperjni/whisper/examples
    whisperjni/whisper/ggml/include
)

target_link_libraries(whisperjni
    whisper
    log
)
