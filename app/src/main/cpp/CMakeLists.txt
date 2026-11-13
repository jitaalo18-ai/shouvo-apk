# Speech recognition for Auto Captions: whisper.cpp (v1.8.7, MIT) with the ggml CPU backend.
# Only the CPU backend is vendored in whisper.cpp/; the other ggml backends were left out.
cmake_minimum_required(VERSION 3.22.1)
# speech recognition is far too slow unoptimised: always build it as Release (debug APK too)
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
project(nockwhisper C CXX)
set(CMAKE_CXX_STANDARD 17)

set(W ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

set(BUILD_SHARED_LIBS   OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE         OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP         OFF CACHE BOOL "" FORCE)   # ggml's own thread pool
set(GGML_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE      OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # fp16 + dot-product instructions: every phone CPU since ~2018 (S25 Ultra included)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
elseif (ANDROID_ABI STREQUAL "x86_64")
    # only used by the emulator in CI
    set(GGML_SSE42 ON CACHE BOOL "" FORCE)
    set(GGML_AVX   ON CACHE BOOL "" FORCE)
    set(GGML_AVX2  ON CACHE BOOL "" FORCE)
    set(GGML_FMA   ON CACHE BOOL "" FORCE)
    set(GGML_F16C  ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(${W}/ggml ggml)

add_library(nockwhisper SHARED ${W}/src/whisper.cpp whisper_jni.cpp)
target_include_directories(nockwhisper PRIVATE ${W}/include ${W}/src ${W}/ggml/include)
target_compile_definitions(nockwhisper PRIVATE WHISPER_VERSION="1.8.7")
target_link_libraries(nockwhisper ggml log android)
if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(nockwhisper PRIVATE -O3)
endif()
# ggml is always optimised, also in debug builds (otherwise recognition is far too slow)
foreach(t ggml ggml-base ggml-cpu)
    if (TARGET ${t})
        target_compile_options(${t} PRIVATE -O3)
    endif()
endforeach()
