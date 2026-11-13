# V0.15 — multi-select on every track, compound clips with overlays / sounds / music (2026-11-14)

Current version: 0.15 (versionCode 15), label V0.15. Branch v0.15-multitrack-compound merged to main.

What changed
- Multi-select like CapCut (mobile): long-press any clip - main video, overlay, Sound FX, BG music,
  also an overlay or the video in the preview - to start; then tap (or long-press) more clips to
  add / remove them. Picked clips get a blue tick. Bar: "N selected", All / None, Delete (all
  picked, one undo step), Compound. On overlay / sound chips: hold + let go = pick, hold + drag =
  move (as before). The "Compound" button on every toolbar starts the same mode.
- Compound with clips of any track (core/Compound.kt):
  - with main clips: compound on the main track; picked overlay / sound / music clips go inside;
    the part of such a clip outside the compound's time is split off and stays on its track, so
    the project plays exactly as before. Overlays inside move / zoom / turn with the compound's
    framing (Compound.placeOverlay) and are drawn below the project's own overlays.
  - only overlays / sounds: a compound on the overlay track (or SFX / music track if all audio),
    first start to last end; move, trim, split, copy as one clip.
  - picking an existing compound plus new clips adds them to it (it is opened first, so the
    result is one compound, never nested).
  - Uncompound puts every clip back on its own track (overlay order kept).
  - flatten() covers all tracks: player (aux players for overlay / sound pieces), preview overlays
    and export.
- Overlay / sound toolbars got Compound and (for a compound) Uncompound.
- No database change (children were already stored as JSON in V0.14; free-track children use the
  same fields). Text clips do not exist yet - when Text is built it will work with this too.

---
# V0.14 — compound clips, Copy / Paste / Duplicate, project delete (2026-11-14)

Current version: 0.14 (versionCode 14), label V0.14. Branch v0.14-compound-clip merged to main.
(An earlier, unreleased attempt at the same request is kept untouched on branch v0.14-compound:
it used a shared groupId instead of a real compound clip. Not merged.)

What changed
- Compound clip (core/Compound.kt): MediaKind.COMPOUND clip with `children` (inner timeline from
  0) and a source window into it, so split / trim / move / delete / copy work unchanged.
  Compound.flatten() turns compounds into playable pieces for the player, the exporter and the
  preview; the compound's framing (position / zoom / rotation / stretch) is applied to every piece
  (Compound.place). Nested compounds work. Uncompound gives back the clips (new ids), keeping how
  they look and only what is inside the window.
- UI: "Compound" button (no selection and main-clip bar) -> pick mode: tap clips to tick them,
  "All", everything between the first and last tick joins; "Compound" makes it. Compound clips
  show the thumbnails / waveform of their pieces and a purple badge with the clip count.
  Selected compound: Split, Uncompound, Copy, Paste, Duplicate, Compound, Delete, Move.
  AutoCut on a compound asks to Uncompound first.
- Copy / Paste / Duplicate now work for main clips (and all tracks). Copy keeps the clip while the
  app runs, so it can be pasted into another project. Paste goes to the playhead (a main clip
  under the playhead is split). Duplicate puts the copy right after the clip.
- Projects screen: ⋮ menu -> Delete project (confirmation; clips deleted first, then the project;
  library files that were only kept for it are cleaned up; gallery files untouched).
- Database version 4: clips.childrenJson (inner clips as JSON, data/ClipJson.kt) and
  clips.childLibraryIds (",3,7,") so library items used inside compounds count as in use.
  Migration 3->4 only adds the two nullable columns.
- Long toolbar labels use a smaller font.

---
# V0.13 — dynamic timeline, AutoCut review, on-device tests (2026-11-13)

Current version: 0.13 (versionCode 13), label V0.13. Branch v0.13-dynamic-timeline merged to main.
Standing rules (unchanged): APK + source ZIP of the same version as direct links every time;
Instagram Edits is the behaviour/design reference (original design); honest test reporting;
reply to the owner in Bangla; versions 0.14, 0.15 ...

What changed
- Thumbnails (app/media/Thumbnails.kt): own engine, 3 workers, newest-first queue, in-flight
  dedup, 40 MB memory LRU + JPEG disk cache (cacheDir/thumbs), nearest-frame placeholder so a
  tile is never empty while zooming/scrolling, per-uri MediaMetadataRetriever pool.
- Preview player (PlaybackController): playlist updated incrementally (only changed clips are
  replaced), drags apply 220 ms after the last move and immediately on finger-up (flush);
  scrubbing uses fast key-frame seeks, latest target wins, exact seek on release.
- Live trim frame: while a main clip edge is dragged the preview shows that exact frame
  (LiveFrames); on release the playhead jumps to the trimmed edge.
- Magnet snapping (core/Snap.kt) to 0, playhead and clip edges with a yellow guide + haptic
  tick. Main-track right edge ignores clips that ripple with it (was sticky before).
- Trim handles of the selected main clip sit OUTSIDE the clip (Edits style), so short clips
  keep two separate handles; handle gesture always reports its end (try/finally).
- "+ Add audio" row above the main track when there is no music; opens the BG MUSIC library.
- Status messages: small pill over the preview, hides after 2.5 s, never covers the timeline
  (the old snackbar sat on the timeline and swallowed pinch/trim touches).
- AutoCut: strengths Gentle / Normal / Tight (min silence 700/400/250 ms), review sheet with a
  kept(blue)/removed(red) bar and summary before applying; word-edge protection in the VAD
  (soft s/f sounds and word tails are not cut); still non-destructive.
- Fixes: save() before the project finished loading could overwrite it with an empty
  timeline; editor coroutine scope pinned to the main thread (ExoPlayer wrong-thread crash).
- Not done: "online render" (no server; preview/export stay on-device). The other account's
  branch feature/v0.13-extract-audio was not merged.

Testing infrastructure
- core JVM tests: 47 (run_core_tests.sh locally / gradle :core:test in CI).
- app/src/androidTest: MediaPipelineTest (7) + EditorUiTest (1 end-to-end) on an Android 12
  x86_64 emulator in GitHub Actions (job "emulator"). Screenshots, report.txt, instrumentation
  output and logcat are pushed to branch ci-results (artifact download is blocked here).
- Release job (main only) needs build + emulator to pass.

Next ideas: remember zoom per project, handles outside for chips too, captions, real-phone
checks of feel (haptics, 120 Hz scrolling, long 4K files).

---
# V0.12 — two-finger timeline zoom

Spread two fingers over the timeline to zoom in; pinch to zoom out. One-finger scrolling,
selection and trimming retain their handlers. A two-finger gesture takes priority over
child handlers and consumes input until both fingers lift. Playback pauses and the time
under the central playhead is retained. Ruler, thumbnails, waveforms and trim/move mapping
all use the same scale. Zoom is saved across configuration changes per open project.
Scale is bounded to 0.25–16 times the default, additionally limited by the existing
200,000-pixel content-width cap for long projects. No media or database edits for zoom.

Validation: GitHub Actions build/tests to be checked for this commit. Device pinch,
remaining-finger behavior, long projects and edge positions still need phone testing.

---
# V0.11 update — 2026-11-13

Current version: 0.11 (versionCode 11). Prior checkpoint retained below as history.

Requested changes implemented in source:
- Compact reference-based timeline: wider time overview (12 dp/second), stacked thumbnail
  tracks, magenta centred audio waveforms, thin white fixed playhead, white trim handles.
- Height adapts to tracks (164–248 dp), with vertical scrolling for additional lanes.
- Main-track add button follows the main filmstrip vertically.
- BG MUSIC appears in the bottom bar with and without clip selection.
- BG MUSIC uses the same LibraryDialog and audio-row interface as Sound FX: import,
  name search, clear query, play/stop preview, rename, remove and insert at playhead.
- Search remains visible even when a library is empty.
- Existing tools, data schema, signing key and media processing preserved.
- Captions shown in the reference are not implemented; no fake caption track was added.

Validation: source/diff and XML/archive checks only. Gradle distribution is not installed
and download is blocked in this environment. No APK build, JVM tests, emulator run,
or screenshot comparison of the actual Android UI was possible for V0.11.
Next: run ./gradlew :core:test :app:assembleDebug, then the V0.11 device checklist.
Prior review findings (save-before-load, Undo media cleanup, buffering sync) are unchanged
and outside this UI update.

---
# Historical V0.10 checkpoint (build/test claims below apply to V0.10 only)

# Android Video Editor - Progress Checkpoint

Self-contained handoff. Another developer / AI account can continue from this file plus the source ZIP.
Master spec: "ANDROID VIDEO EDITOR - DEVELOPMENT MASTER PROMPT V1" (Steps 1-12).

PROJECT VERSION: 0.10 (versionCode 10), app name "Nock Nock", APK label "Nock Nock V0.10" - checkpoint 10, 2026-11-13
VERSION RULE (set by the owner): versions go 0.2, 0.3, 0.4, 0.5, 0.6 ... (never 3, 4, 5).
For each new version change `versionName`/`versionCode` in app/build.gradle.kts and the label in
release_label.txt (V0.11, V0.12 ...). History: 1.0.0 (first build) -> 0.2 "V2" -> 0.3 -> 0.4 -> 0.5 -> 0.6 -> 0.7 -> 0.8 -> 0.9
-> 0.10 (zero point ten, the version after 0.9).

STANDING RULES FROM THE OWNER (apply to every version without being asked again):
1. Every APK delivery comes together with the project source code ZIP of the same version.
   Give BOTH as direct download links (the build publishes Nock_Nock_<label>.apk and
   Nock_Nock_<label>_source.zip on the GitHub release of each version).
2. Use the Instagram "Edits" app as the reference for how the editor looks and behaves (preview,
   overlay, gestures); keep an original design - no copied branding or assets.
3. Deliver the APK as a direct file. Report honestly what was and was not tested.

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
- NEW in 0.4 - Video format drop-down next to Play: Original, 16:9, 9:16, 1:1. Changes the preview
  canvas and the exported frame; clips are fitted inside (black bars where needed). Stored per project
  in SharedPreferences ("project_settings"), not in Room, so no database migration was needed.
- NEW in 0.4 - "Import from" chooser for New Project, Overlay, Video FX and Extract Music: Gallery
  (Android photo picker) or Files & Google Drive (Storage Access Framework). Sound FX and Music
  imports open the file picker directly (it lists phone audio and Google Drive).
- NEW in 0.5 - Pinch with two fingers on the preview to make the main video bigger / smaller and move
  it inside the frame (one finger moves it while the clip is selected). Applied in the preview and in
  the exported video. Stored in the clip's existing scale/posX/posY columns (main clips: scale 0.4 =
  zoom 1, see TimelineOps.MAIN_SCALE_UNIT), so no database migration. Undo works.
- NEW in 0.5 - Preview: the output frame is black on a lighter panel so its shape (9:16, 1:1 ...) is
  visible; the selected clip gets a border in the preview. The main video is now drawn on a
  TextureView sized by the app (PlayerView removed).
- NEW in 0.5 - Overlay tool row: Split, Duplicate, Delete work; Opacity, Replace, Mask, Filters,
  Adjust, Mirror, Cutout are placeholders. Sound/music clips: Split, Duplicate, Delete.
- NEW in 0.6 - Video FX library: thumbnails now come from the middle of the clip (exact frame, first
  clearly visible of 50/25/75/10 %), because many clips start black. Tap a thumbnail box to play the
  clip with sound inside that box; tap again to stop; one preview at a time. Tapping the rest of the
  row still inserts the clip.
- NEW in 0.7 - Framing like the reference app: two fingers on the preview move, zoom AND rotate the
  main video; one finger moves it while selected; tap selects/deselects it. A read-out (X, Y, angle,
  zoom) shows while framing; the selection border may reach outside the frame. Overlays rotate with two
  fingers too. Rotation is applied in the export (aspect-corrected matrix for the main video,
  OverlaySettings rotation for overlays).
- NEW in 0.7 - Touch handling rewritten: a dedicated touch layer above the video surface receives the
  gestures (in 0.5/0.6 they were attached to the box under the video surface).
- NEW in 0.7 - DATABASE VERSION 2: column `clips.rotation` (REAL NOT NULL DEFAULT 0) added by
  MIGRATION_1_2 in data/Db.kt. Any later schema change needs its own migration - never bump the
  version without one, or existing users lose their projects and libraries.
- NEW in 0.8 - Alignment snapping while framing the main video (core/Framing.kt): locks onto the frame
  centre, the frame edges, straight angles (0/90/180/270) and the fit / fill sizes; dashed guide lines
  show what it locked onto and the phone vibrates once per new lock (Compose haptic feedback, follows
  the phone's touch-vibration setting). Overlays do not snap yet.
- NEW in 0.8 - W x H control (button left of Undo): Width and Height sliders, 50-200 %, with Reset, for
  the selected main/overlay clip or the clip under the playhead. Applied in preview and export.
- NEW in 0.8 - DATABASE VERSION 3: columns `clips.stretchX`, `clips.stretchY` (REAL NOT NULL DEFAULT 1)
  added by MIGRATION_2_3.
- NEW in 0.9 - Sound FX and Background Music libraries: tap the box at the left of a row to listen to
  the sound, tap again to stop.
- NEW in 0.9 - Trim handles on every selected timeline clip. Main track (incl. inserted Video FX):
  drag the left/right handle to hide or bring back the start/end; the track re-flows without gaps
  (TimelineOps.trimMain). Sound/music/overlay clips: larger handles, same behaviour as before.
- NEW in 0.9 - Moving clips. Main track: Move left / Move right buttons in the tool row
  (TimelineOps.moveMain). Sound/music/overlay: hold and drag at any time, or drag while selected.
  All clips of a free track now share one box, so a drag is no longer cut off when the clip changes
  lane. The owner had reported that sounds and Video FX could not be moved or trimmed.
- NEW in 0.10 - Timeline rebuilt after the structure of the reference app (own colours/icons): time
  ruler on top, tracks packed tightly, overlay clips shown as thumbnails, audio waveforms (under each
  main clip with sound and inside sound/music clips; media/Waveforms.kt decodes each file once in the
  background and caches the outline in cacheDir/waveforms), "+" button at the right that appends more
  videos/images to the main track, rounded preview frame. The bottom tool bar is unchanged on the
  owner's request. Not built: captions track (there is no captions feature yet).
- Rendering: Export button -> Media3 Transformer composition (main sequence, one audio sequence per
  sound/music/overlay-audio clip, overlays as timed BitmapOverlay) -> Movies/VideoEditor.

DEVIATIONS FROM THE SPEC TO KNOW ABOUT:
- The three libraries share one Room table with a `kind` column (separate records, separate folders),
  not three tables.
- minSdk is 29 (Android 10) so export can save to the Gallery without a storage permission.
- Project and overlay media are referenced through persisted read permission, not copied (the spec
  requires copying only for the libraries). Deleting such an original from the phone breaks that clip.
- Not added because nobody asked for it: delete project. (Delete clip and undo/redo arrived in 0.3.)

FILES MODIFIED IN 0.10: media/Waveforms.kt (new), ui/TimelineView.kt (rewritten), ui/EditorScreen.kt,
ui/EditorController.kt (appendMedia), build files.

FILES MODIFIED IN 0.9: core/TimelineOps.kt (trimMain, moveMain), core tests (41), ui/TimelineView.kt
(FreeTrack, ChipView, TrimHandle, main handles), ui/EditorScreen.kt, ui/EditorController.kt,
ui/LibraryDialog.kt, build files.

FILES MODIFIED IN 0.8: core/Framing.kt (new), core/Model.kt, core/TimelineOps.kt, core tests (38),
data/Db.kt (version 3 + migration), data/Repositories.kt, media/Exporter.kt, ui/EditorScreen.kt,
ui/EditorController.kt, build files.

FILES MODIFIED IN 0.7: core/Model.kt (rotation), core/TimelineOps.kt, core tests (30), data/Db.kt
(version 2 + migration), data/Repositories.kt, media/Exporter.kt, ui/EditorScreen.kt (PreviewCanvas,
OverlayView), ui/EditorController.kt, build files.

FILES MODIFIED IN 0.6: media/MediaProbe.kt (Thumbnails.representative), ui/LibraryDialog.kt,
ui/EditorScreen.kt (pause when a library opens), build files.

FILES MODIFIED IN 0.5: core/TimelineOps.kt (mainZoom, transformMain, duplicate), core tests (29),
media/Exporter.kt (zoom matrix), ui/EditorScreen.kt, ui/EditorController.kt, build files.

FILES MODIFIED IN 0.4: media/Exporter.kt (CanvasFormat), ui/EditorController.kt, ui/EditorScreen.kt,
ui/Common.kt (rememberMediaImporter), ui/ProjectsScreen.kt, app/build.gradle.kts, release_label.txt.

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
- 41 JUnit tests of the core module (timeline, AutoCut, voice removal, zoom, rotation, snapping, stretch,
  main-track trim and move) run on a desktop JVM: all pass.
- Owner's phone, version 1.0.0 (screenshots 2026-11-13): Projects screen lists a saved project that
  was edited later; Video FX library shows 8+ imported clips with thumbnails and names. No other
  device results reported yet.
- Kotlin parser pass over all Android sources: 0 syntax errors (types could not be checked - no SDK).
- Media3 1.5.1 method signatures used by Exporter/PlaybackController compared with the 1.5.1 source.
- Gradle build on GitHub Actions: core tests pass under Gradle, debug APK compiles.
- NOT run: anything on a device or emulator. All 15 Step-10 device checks are still open.

KNOWN BUGS / RISKS:
- The database migrations (1 -> 2 in 0.7, 2 -> 3 in 0.8) are untested on a device. If the app does not
  start after an update, the migrations are the first thing to check.
- Most screens have been opened on the owner's phone, but no version has been fully device-tested.
- Voice removal cannot work on mono sound, and it also lowers instruments mixed exactly in the centre.
- Extracted music is stored as WAV (about 10 MB per minute).
- Video overlays render slowly on export (one frame grab per output frame).
- Waveforms of long videos take a while to appear the first time (the whole audio is decoded once).
- Main-track clips are reordered with buttons, not by dragging (drag-to-reorder is not built).
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
APK: Nock_Nock_V0.10.apk and Nock_Nock_V0.10_source.zip on the v0.10 release of that repository (rebuilt on every push to main).
