#!/bin/bash
# Runs the on-device tests on the emulator started by the workflow and collects screenshots.
set -u
PKG=com.videoeditor.app
OUT=emulator-results
mkdir -p "$OUT"
adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell run-as $PKG rm -rf files/shots
adb logcat -c
adb shell am instrument -w -r $PKG.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/instrumentation.txt" 2>&1
STATUS=$?
adb logcat -d -v time > "$OUT/logcat.txt" 2>&1
adb exec-out run-as $PKG tar -cf - files/shots > "$OUT/shots.tar" 2>/dev/null && tar -xf "$OUT/shots.tar" -C "$OUT" && rm "$OUT/shots.tar"
grep -E "^(OK|FAILURES|Tests run)|INSTRUMENTATION_(RESULT|CODE|STATUS: stream)|^[0-9]+\)" "$OUT/instrumentation.txt" | head -60
cat "$OUT/files/shots/report.txt" 2>/dev/null
if [ $STATUS -ne 0 ] || ! grep -q "^OK (" "$OUT/instrumentation.txt"; then
  echo "On-device tests FAILED"
  exit 1
fi
echo "On-device tests passed"
