#!/bin/bash
# Builds libdf.so (DeepFilterNet3 C API, for Isolate voice) for the app's ABIs into
# third_party/deepfilternet/android-libs/<abi>/ (CMake links it and Gradle packages it). Needs: Rust (rustup targets aarch64-linux-android and
# x86_64-linux-android), cargo-ndk, and ANDROID_NDK_HOME pointing at NDK 27.2.12479018.
set -euo pipefail
cd "$(dirname "$0")"
OUT=android-libs
# a SONAME, so libnockwhisper.so finds "libdf.so" at run time (not the build machine's path)
export RUSTFLAGS="-C link-arg=-Wl,-soname,libdf.so"
cargo ndk -t arm64-v8a -t x86_64 --platform 29 -o "$OUT" build --release --features capi
for abi in arm64-v8a x86_64; do
  ls -la "$OUT/$abi/libdf.so"
done
