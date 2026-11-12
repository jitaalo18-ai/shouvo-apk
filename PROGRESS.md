# Android Video Editor - Progress Checkpoint

Self-contained handoff. Another developer / AI account can continue from this file plus the source ZIP.
Master spec: "ANDROID VIDEO EDITOR - DEVELOPMENT MASTER PROMPT V1" (Steps 1-12).

PROJECT VERSION: 0.3 (versionCode 3), app name "Nock Nock", APK label "Nock Nock V0.3" - checkpoint 3, 2026-11-13
VERSION RULE (set by the owner): versions go 0.2, 0.3, 0.4, 0.5 ... (never 3, 4, 5).
For each new version change `versionName`/`versionCode` in app/build.gradle.kts and the label in
release_label.txt (V0.4, V0.5 ...). History: 1.0.0 (first build) -> 0.2 "V2" -> 0.3.

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
- NEW in 0.2 - Search field in the Video FX and Sound FX libraries (filters by name).
- NEW in 0.2 - "Extract Music from Video" button on the Projects screen: pick a video, the audio is
  decoded on-device, the voice is removed (centre-channel removal, core/VocalRemover.kt), and the
  remaining music is saved as WAV into the Background Music or Sound FX library (user chooses).
  Needs stereo sound; mono sound is detected and reported, nothing is saved. JVM-tested on synthetic
  stereo audio only; not yet tried on a device.
- NEW in 0.2 - fixed debug signing key (app/debug.keystore) so later versions install as updates.
  0.2 itself has a different signature than 1.0.0, so 1.0.0 must be uninstalled once.
- NEW in 0.3 - Editor layout rearranged after the owner's reference screenshot (original colours and
  icons): top bar (close, project name, search, FHD label, Export), transport row (play, current/total
  time, undo, redo), overlay/sound/music tracks ABOVE the main video track, duration label on the
  selected clip, horizontally scrolling tool row with a back arrow.
- NEW in 0.3 - Working: Undo/Redo (50 steps, per editing session), Delete for any selected clip
  (the last main clip is protected).
- NEW in 0.3 - PLACEHOLDER buttons, shown on the owner's request, no function yet (they show a
  "not available yet" message): Search, Effects, Adjust, Filters, Crop, Transform, Mirror, Cutout,
  Mask, Opacity, Replace, Copy, Duplicate, Green screen. "FHD" is a label only.
- Rendering: Export button -> Media3 Transformer composition (main sequence, one audio sequence per
  sound/music/overlay-audio clip, overlays as timed BitmapOverlay) -> Movies/VideoEditor.

DEVIATIONS FROM THE SPEC TO KNOW ABOUT:
- The three libraries share one Room table with a `kind` column (separate records, separate folders),
  not three tables.
- minSdk is 29 (Android 10) so export can save to the Gallery without a storage permission.
- Project and overlay media are referenced through persisted read permission, not copied (the spec
  requires copying only for the libraries). Deleting such an original from the phone breaks that clip.
- Not added because nobody asked for it: delete project. (Delete clip and undo/redo arrived in 0.3.)

FILES MODIFIED IN 0.3: ui/EditorScreen.kt, ui/EditorController.kt, ui/TimelineView.kt, core tests (27),
app/build.gradle.kts, release_label.txt, docs/REFERENCE_FEATURES.md (added after 0.2).

FILES CREATED OR MODIFIED IN 0.2:
- core/.../VocalRemover.kt (new: voice removal + FFT), core tests extended (26 tests)
- app/.../media/MusicExtractor.kt (new: decode -> VocalRemover -> WAV)
- app/.../data/Repositories.kt (newFile/addFile for app-generated library audio)
- app/.../ui/ProjectsScreen.kt (Extract Music button + dialogs), ui/LibraryDialog.kt (search)
- app/build.gradle.kts (version 0.2, signing), app/debug.keystore, res/values/strings.xml (name)
- release_label.txt, .github/workflows/build.yml (APK name Nock_Nock_<label>.apk, release per version)

FILES FROM 1.0.0 (unchanged unless listed above):
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
- 27 JUnit tests of the core module (20 from 1.0.0 + 6 voice removal + 1 main-clip delete) run on a desktop JVM: all pass.
- Owner's phone, version 1.0.0 (screenshots 2026-11-13): Projects screen lists a saved project that
  was edited later; Video FX library shows 8+ imported clips with thumbnails and names. No other
  device results reported yet.
- Kotlin parser pass over all Android sources: 0 syntax errors (types could not be checked - no SDK).
- Media3 1.5.1 method signatures used by Exporter/PlaybackController compared with the 1.5.1 source.
- Gradle build on GitHub Actions: core tests pass under Gradle, debug APK compiles.
- NOT run: anything on a device or emulator. All 15 Step-10 device checks are still open.

KNOWN BUGS / RISKS:
- The app compiles but has never been launched. Expect runtime bugs in first device testing.
- Voice removal cannot work on mono sound, and it also lowers instruments mixed exactly in the centre.
- Extracted music is stored as WAV (about 10 MB per minute).
- Video overlays render slowly on export (one frame grab per output frame).
- Dragging a sound/overlay clip across another one can move it to a different row and end the drag.
- Preview transitions between many short AutoCut pieces may not be perfectly seamless.
- AutoCut treats loud non-speech sounds (music, clapping) as speech, so it keeps them.
- Clips on other tracks do not shift when AutoCut shortens the main track.

UNFINISHED TASKS:
- Run every Step-10 check on a device and record results in TEST_REPORT.md.
- Audio/Text/Voice/Links functions (spec: to be provided later).

NEXT DEVELOPMENT STEP:
1. Install the APK and device-test in Step-10 order, starting with project create/reopen, Split, AutoCut.
2. Fix what fails without changing other behaviour; keep core tests green; push to main to rebuild.

BUILD STATUS: BUILT. GitHub Actions (repo jitaalo18-ai/shouvo-apk, run 43269596434, 2026-11-12) ran
`./gradlew :core:test` and `./gradlew :app:assembleDebug` successfully with no source changes.
The debug APK has NOT been installed or run on any device yet.

SOURCE ZIP / APK PATH IF AVAILABLE: source at https://github.com/jitaalo18-ai/shouvo-apk (main).
APK: Nock_Nock_V0.3.apk on the v0.3 release of that repository (rebuilt on every push to main).
