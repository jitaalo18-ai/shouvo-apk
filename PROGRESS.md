# Android Video Editor - Progress Checkpoint

Self-contained handoff. Another developer / AI account can continue from this file plus the source ZIP.
Master spec: "ANDROID VIDEO EDITOR - DEVELOPMENT MASTER PROMPT V1" (Steps 1-12).

PROJECT VERSION: 1.0.0 (versionCode 1) - checkpoint 1, 2026-11-12

COMPLETED STEPS:
- None are marked complete. Completion requires validation on an Android device, which has not happened.
- Source written for Steps 1-9. Core logic of Steps 3 and 4 is validated by JVM unit tests.

IMPLEMENTED FEATURES (source written; "JVM-tested" = unit tests really executed):
- Step 1 Projects: Projects screen, + New Project, multi-select video/image picker (Storage Access
  Framework), project list, reopen, every edit auto-saved to Room.
- Step 2 Editor: preview on top, play/pause, scrollable thumbnail timeline with fixed centre playhead,
  scrubbing, Audio/Text/Voice/Links buttons (visible only, no function), six clip options.
- Step 3 Split: non-destructive split at playhead, repeatable. JVM-tested.
- Step 4 AutoCut: on-device audio decode (MediaExtractor/MediaCodec) -> pure-Kotlin voice activity
  detector (speech-band energy vs adaptive noise floor, hysteresis) -> planner (keeps pauses < 0.6 s,
  150/220 ms margins) -> clip replaced by the speaking pieces. No audio / no speech / unreliable =>
  clip left unchanged with a message. Detector, planner and timeline application are JVM-tested on
  synthetic audio only. The Android decode path is not tested.
- Steps 5-7 Libraries: Video FX, Sound FX, Background Music. Files are copied to
  filesDir/library/{video_fx,sound_fx,music}/ (private, persistent, not cache). Rename, remove, insert
  at playhead (Video FX into main track; audio on its own track). Shared by all projects.
  Removing an item still used by a project: confirmation lists the projects; the item leaves the
  library but its file is kept until no project references it (no broken media).
- Step 8 Overlay: image/video overlay track, drag to move and pinch to resize in the preview, move /
  change start and end on the timeline, multiple overlays, remove overlay.
- Rendering: Export button -> Media3 Transformer composition (main sequence, one audio sequence per
  sound/music/overlay-audio clip, overlays as timed BitmapOverlay) -> Movies/VideoEditor.

DEVIATIONS FROM THE SPEC TO KNOW ABOUT:
- The three libraries share one Room table with a `kind` column (separate records, separate folders),
  not three tables.
- minSdk is 29 (Android 10) so export can save to the Gallery without a storage permission.
- Project and overlay media are referenced through persisted read permission, not copied (the spec
  requires copying only for the libraries). Deleting such an original from the phone breaks that clip.
- Not added because the spec does not list them: delete project, delete main/sound/music clips, undo.

FILES CREATED OR MODIFIED (all new in this version):
- settings.gradle.kts, build.gradle.kts, gradle.properties, gradlew, gradlew.bat, gradle/wrapper/*
- .github/workflows/build.yml (builds the debug APK on GitHub)
- core/build.gradle.kts
- core/src/main/kotlin/com/videoeditor/core/Model.kt           timeline data model
- core/src/main/kotlin/com/videoeditor/core/TimelineOps.kt     Split engine, insert, move, trim, lanes
- core/src/main/kotlin/com/videoeditor/core/SpeechDetector.kt  VAD + audio downsampler
- core/src/main/kotlin/com/videoeditor/core/AutoCutPlanner.kt  speech -> keep ranges
- core/src/test/kotlin/com/videoeditor/core/CoreTests.kt       20 unit tests
- app/build.gradle.kts, app/src/main/AndroidManifest.xml, app/src/main/res/*
- app/src/main/kotlin/com/videoeditor/app/App.kt, MainActivity.kt
- .../data/Db.kt              Room entities + DAOs (projects, clips, library_items)
- .../data/Repositories.kt    ProjectRepository, LibraryRepository (persistent copies)
- .../media/MediaProbe.kt     Media import probing + thumbnails
- .../media/AutoCutEngine.kt  audio decode + VAD driver
- .../media/PlaybackController.kt  preview player and audio/overlay sync
- .../media/Exporter.kt       Media Rendering Engine
- .../ui/Common.kt, ProjectsScreen.kt, EditorScreen.kt, EditorController.kt, TimelineView.kt,
  LibraryDialog.kt

TESTS ACTUALLY RUN (see TEST_REPORT.md):
- 20 JUnit tests of the core module, compiled with kotlinc 2.0.21 and run on a desktop JVM: all pass.
- Kotlin parser pass over all Android sources: 0 syntax errors (types could not be checked - no SDK).
- Media3 1.5.1 method signatures used by Exporter/PlaybackController compared with the 1.5.1 source.
- NOT run: Gradle build, APK compilation, anything on a device or emulator. All 15 Step-10 device
  checks are still open.

KNOWN BUGS / RISKS:
- The Android module has never been compiled. Expect a small number of compile errors on first build.
- Video overlays render slowly on export (one frame grab per output frame).
- Dragging a sound/overlay clip across another one can move it to a different row and end the drag.
- Preview transitions between many short AutoCut pieces may not be perfectly seamless.
- AutoCut treats loud non-speech sounds (music, clapping) as speech, so it keeps them.
- Clips on other tracks do not shift when AutoCut shortens the main track.

UNFINISHED TASKS:
- First real build; fix compile errors.
- Run every Step-10 check on a device and record results in TEST_REPORT.md.
- Audio/Text/Voice/Links functions (spec: to be provided later).

NEXT DEVELOPMENT STEP:
1. Build (`./gradlew :core:test :app:assembleDebug`, or push to GitHub and let Actions build).
2. Fix compile errors without changing behaviour; keep core tests green.
3. Device-test in Step-10 order, starting with project create/reopen, Split, AutoCut.

BUILD STATUS: NOT BUILT. No Android SDK and no access to Google Maven in the authoring environment.
No APK exists.

SOURCE ZIP / APK PATH IF AVAILABLE: VideoEditor_v1.0.0_source.zip (source only). APK: none.
