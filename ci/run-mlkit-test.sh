#!/bin/bash
# Runs only the real ML Kit background-removal test (needs a recent Google Play services, which
# the Android 12 emulator image of the main test run does not have).
set -u
PKG=com.videoeditor.app
OUT=mlkit-results
mkdir -p "$OUT"
adb wait-for-device
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell dumpsys package com.google.android.gms | grep -m2 -E "versionName|versionCode" | tee "$OUT/gms.txt"
adb logcat -c
adb shell am instrument -w -r -e class com.videoeditor.app.TransparentImageTest#mlKitCutsAPersonOutOfAPhoto \
  $PKG.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/instrumentation.txt" 2>&1
timeout 120 adb logcat -d -v time > "$OUT/logcat.txt" 2>&1
timeout 120 adb exec-out run-as $PKG tar -cf - files/shots > "$OUT/shots.tar" 2>/dev/null && tar -xf "$OUT/shots.tar" -C "$OUT" && rm "$OUT/shots.tar"
grep -E "BackgroundRemover|MlKitException|MobileVisionBase|NockNockTest" "$OUT/logcat.txt" | cut -c1-300 | head -60
# the cut-out on a green card, small enough to read from the log
[ -f "$OUT/files/shots/transparent_mlkit.png" ] && { echo "CUTOUT_PNG_BASE64_BEGIN"; base64 -w 0 "$OUT/files/shots/transparent_mlkit.png"; echo; echo "CUTOUT_PNG_BASE64_END"; }
gzip -f "$OUT/logcat.txt"
cat "$OUT/instrumentation.txt" | tail -15
cat "$OUT/files/shots/report.txt" 2>/dev/null
# informative: the result is read from the artifact; this job never blocks a release
exit 0
