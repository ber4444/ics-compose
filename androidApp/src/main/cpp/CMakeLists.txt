cmake_minimum_required(VERSION 3.22.1)
project("whisperjni")

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
