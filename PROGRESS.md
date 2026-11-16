# V0.28 — Export sheet (resolution, frame rate) and Export video for one clip (2026-11-17)

Current version: 0.28 (versionCode 28), label V0.28.

- Export sheet (like CapCut's), opened by the Export button and by the badge next to it (SD /
  HD / FHD / 2K / 4K): Resolution 480p, 720p, 1080p, 2K (1440p), 4K (2160p); Frame rate Auto,
  24, 25, 30, 50, 60; the frame size it will give; then Export. The choice is remembered
  (SharedPreferences "editor").
- "Export video" in the toolbar of a selected main clip: the same sheet, titled "Export this
  clip"; only the part of the project under that clip is rendered and saved to the Gallery
  (Movies/VideoEditor) - with everything seen and heard there (overlays, texts / captions,
  music, each cut to that part; core ExportRange). The project is not changed.
- Frame rate: a chosen rate leaves frames out (Media3 FrameDropEffect); it never invents
  frames, so a 30 fps video exported at 50 / 60 stays 30. Auto = as the videos are (as before).
- 2K / 4K: the frame is made bigger; a video is not sharper than its original. If the phone's
  H.264 encoder cannot make the size / rate, the export is refused with a message instead of
  silently coming out smaller. Overlay pictures are drawn at the frame size, so 4K with many
  overlays needs much memory.
- With a chosen resolution / frame rate the picture is always made anew (no stream copy).

---
# V0.27 — Split scenes (like CapCut's) (2026-11-17)

Version 0.27 (versionCode 27), label V0.27.

- What the user had asked for in V0.26 was CapCut's "Split scenes" (right-click menu on PC), not
  a split screen: the video is looked through, and wherever the shot changes the clip is cut, so
  one long clip becomes one clip per scene. (Split screen from V0.26 stays as its own button.)
- Button "Split scenes" for a selected video clip (main track, next to AutoCut; also overlay
  videos). A progress box with Cancel shows while the video is read; then the clip is split at
  every cut in one undo step ("Split into N scenes"), or "No scene changes were found".
  Nothing is removed or moved: picture and sound of each piece continue exactly where the piece
  before ended.
- How: media/SceneSplitter.kt decodes the clip's part of the file (MediaCodec, every frame,
  as fast as the decoder goes) and shrinks each frame to a 32 x 32 brightness picture;
  core/SceneDetect.kt calls a frame a cut when both its brightness histogram and its pixels
  differ from the frame before, clearly more than the frames just before differed from each
  other (so motion and pans are not cuts), the next frame confirms it (a single flash frame is
  not a cut), and shots under 0.4 s are not split off.
- Not found by design: slow fades / dissolves between shots (only hard cuts).

---
# V0.26 — Split screen; Compound picking by taps only (2026-11-16)

Version 0.26 (versionCode 26), label V0.26.

- Compound: holding a clip no longer selects anything (timeline chips, main clips, preview,
  captions). Picking starts only with the Compound button; then one tap per clip (tap again =
  out), "All" takes everything, the blue Compound button makes the compound. Holding a chip on
  an upper track still drags it.
- Split screen (toolbar button; also for a selected main / overlay clip), like CapCut on PC: the
  videos / pictures on screen at the playhead (the main clip + overlays above it, up to 9) each
  get their own part of the frame. A panel offers layouts for that number (2: side by side,
  top and bottom; 3: columns, rows, 1 + 2, 1 over 2; 4: 2 x 2, rows, columns, 1 over 3; more:
  grids); a tap applies it at once, another can be tried, "Reset" gives whole pictures back.
  Each apply is one undo step.
- Every picture fills its part (scaled up to cover, the overhang cropped evenly, never
  squeezed). New clip fields cropX / cropY (shown part of width / height, centred; DB version
  8, children JSON). Preview: cropped clips are drawn whole inside a clipping box (CropBox);
  export: main clips get a Media3 Crop before the fit (zoom corrected), overlays are cropped
  bitmaps. Clips can still be moved / zoomed by hand afterwards; they keep their crop.
- core/SplitScreen.kt (clipsAt, layouts, fit, apply, reset, shownBox).
- Not included: compound clips and texts are not arranged by Split screen; gaps / borders
  between the parts; dragging the picture inside its part to choose what is cropped.

---
# V0.25 — Transparent image (background removal), square image preview (2026-11-16)

Version 0.25 (versionCode 25), label V0.25.

- Overlay Folder, every picture row: a "Transparent image" button (checkerboard icon, before +).
  It makes a copy of the picture without its background - a PNG named "<name> transparent",
  at most 2048 px on the longer side - and keeps the original. A progress box shows while it
  works; a message tells the outcome (also "No subject was found", download / Play services
  problems). media/BackgroundRemover.kt.
- The subject is found by Google ML Kit Subject Segmentation (on the phone; free; people,
  animals, things). Google Play services downloads its model the first time (internet needed
  once). Open-source models were tried first on a real portrait (u2netp, silueta,
  isnet-general-use, u2net_human_seg via ONNX): the small ones removed the dark dress with the
  background, the usable one is 176 MB and people-only - not shipped.
- Image preview in the Overlay Folder: a 1:1 square box with the whole picture inside and
  "Insert overlay" right under it (before, the picture took the whole screen height and pushed
  the button off the screen). Transparent parts show a checkerboard (also in the thumbnails).
- Pictures with see-through parts were already drawn correctly as overlays; now covered by a
  test on the exported video.

---
# V0.24 — native Image Search (2026-11-16)

Version 0.24 (versionCode 24), label V0.24.

- Image Search no longer shows a web page. Results are a 3-column picture grid inside the app
  (more load by themselves on scrolling); tapping a picture opens it large with its title,
  size and source link, an icon row (Download to Pictures/NockNock, Share, menu: open source
  page / copy links), "Save to Overlay Folder", and "Related Images" (a search for the picture's
  title; the other results of the query as a fallback).
- Results come from Bing's public image results page, read by the app (media/ImageSearch.kt).
  No API key. This depends on that page's format: if Bing changes it or blocks requests, search
  shows "unavailable" until the parser is updated. Only https pictures are listed.
- Downloaded pictures belong to their owners; the app only saves a copy the user asked for.

---
# V0.23 — Overlay Folder and Image Search (2026-11-16)

- Overlay Folder is separate from Video FX / Sound FX / BG MUSIC and accepts images and videos.
- Gallery and Files imports copy media into persistent private storage. Regular Overlay imports now also keep an owned library copy.
- Library supports image previews, video playback, All/Images/Videos filters, names, rename, remove and explicit insert controls.
- Deleting originals does not affect imported files. Removing used items hides them from the library while protecting saved-project references until unused.
- Image Search opens live Google/Bing image results inside the app, supports search history and long-press/download-to-library with a preview confirmation.
- Image downloads stream to temporary files, validate real image bytes, reject unsupported links and oversized files, preserve original bytes/transparency, then copy into the library. Internet required; websites may limit downloads or show consent/CAPTCHA. No private image-search API key or scraped search-index endpoint is used.
- Five new Android checks cover 50 images + video, deletion of originals, project-safe removal, transparent image downloading, image preview/insert and search-screen interactions.
- Final validation: core suite (118 tests) and 36/36 Android tests passed in CI run 43799406161, tested code 8901f46b31c42c928bde87e1c8b36be7ab23939f. Signed V0.23 APK verified; same update certificate. Screenshots reviewed; live image results rendered. Prior V0.22 caption and V0.21 large-export regression tests passed. See TEST_REPORT.md for provider/physical-device limitations.

# V0.22 — dynamic captions (2026-11-15)

- Default Auto Captions style is Dynamic: bold white type, yellow spoken word, dark translucent box.
- Licensed Lilita One and Noto Sans Bengali are bundled offline with their OFL licenses.
- Whisper subword token timestamps are merged using UTF-8 byte ranges, preserving Bengali text.
- Caption word timings persist in Room schema 7 and nested clip JSON. An additive migration keeps older projects.
- Moving, splitting, trimming and compound crops preserve source-relative word timing. Word corrections with the same word count preserve time slots; adding/removing words clears them and requires regeneration.
- Preview and export use the same caption renderer. Export composites all captions into one reusable texture; this removes the previous 15-caption shader limit. Other overlays use ordered batches of at most 15.
- Bitmap cache is bounded to 8 MiB. Full-frame export caption texture is allocated once, updated only when visible text/highlight changes.
- V0.21 isolated-video export, preview suspension, storage checks and cancellation changes remain.
- Four timing unit tests and three Android regression tests added. Android test exports 41 captions and checks highlighted-word pixels at two timestamps.

Validation completed on 2026-11-16 (Bangladesh): 118 JVM tests and all 31 Android 12 emulator tests passed in GitHub Actions run 43746147908, code commit 349d46b0527f737b95fa4c40508f71d3c17b4c26. Includes old-project migration, 41-caption dynamic export and the 1 GiB isolated-video export regression. No real-phone validation claimed.

# V0.21 — large isolated-video export (2026-11-15)

Reported: a 1 GB video isolates successfully but the app exits on Export. No device crash log
or the original video was available; resource pressure is a suspected cause, not a confirmed trace.

Changes:
- Stop/clear preview sources and release all auxiliary players during export, then restore the
  paused timeline and playhead. Preview buffering is capped at 8 MiB per player / 5 seconds.
- For a single, uncut, untransformed isolated video with matching aspect and no other tracks:
  encode the isolated WAV to AAC, then stream the ORIGINAL encoded video samples into an MP4
  with that audio. No video decoding/encoding, no full-video byte array; rotation is retained.
  This path preserves source resolution/frame rate and picture quality. Visual edits, trims,
  overlays, captions, and extra tracks continue through Media3's compositing exporter.
- Explicit AAC/H.264 in the compositing path; shut down the image-loader executor after export.
- Require the isolated file to exist instead of silently exporting original noisy audio.
- Space preflight for the copy path includes BOTH temporary output and Gallery copy; monitor
  space while muxing, clean temporary files, support cancellation including the Gallery copy.
- Keep the display awake during export, and log recoverable failures with tag NockExport.

Validation additions: three JVM policy/space tests and LargeExportTest, with a generated
1,121,420,784-byte, 66-second CBR H.264/AAC fixture. The latter checks isolation, export audio
presence/duration, bit-exact picture sample SHA-256, and preview suspend/restore. The fixture
uses 640x360 frames with valid CBR filler; it tests large encoded size, not real-phone 4K/HDR.
Existing isolation tests also check measured noise removal and audio alignment.
See the fix branch's GitHub Actions results for execution status. Real S25 Ultra reproduction,
background process termination and a long 4K/HDR workload still need device testing. This is
an Activity-bound exporter, not a background export service.

---
# V0.20 — Isolate voice (like the iPhone's) (2026-11-15)

Current version: 0.20 (versionCode 20), label V0.20. Branch v0.20-isolate-voice merged to main.

- Button "Isolate voice" (toolbar, with nothing selected, and next to Voice enhance for a main
  clip): turns it on for every voice of the main timeline (compound clips included); the button
  lights up; tap again = off; undo works. In the Voice enhance panel (Enhance tab) a switch does
  it per clip ("Apply enhancement to all clips" copies it).
- How: DeepFilterNet3 (MIT/Apache-2.0, a neural network for speech: keeps the voice, removes
  traffic, music, fans, clatter...), running on the phone through its C API (Rust library
  libdf.so built for arm64-v8a and x86_64 from third_party/deepfilternet by cargo-ndk in CI;
  JNI in app/src/main/cpp/isolate_jni.cpp; the network file is the asset df/DeepFilterNet3_onnx.dfn,
  checked by SHA-256 before use).
- Once per file, like the noise learning (same Processing popup, sparkles, Minimize, video keeps
  playing): the whole sound is decoded, mixed to mono, converted to 48 kHz (core Resampler,
  windowed sinc, no delay), run through the network, its 30 ms delay taken out, and written as a
  48 kHz mono WAV (filesDir/isolated_voice, about 5.8 MB per minute) whose sample 0 is time 0 of
  the file and that is as long as the video - so the voice stays exactly with the lips.
- Preview and export: an isolated clip plays its own picture (its own sound left out,
  FilteringMediaSource) together with the voice file, cut to the same part (MergingMediaSource,
  through VoiceMediaSourceFactory for ExoPlayer and Transformer). Volume, fades, noise and voice
  settings still apply on top.
- Not removed: other people talking (to the network that is also a voice).

---
# V0.19 — Voice enhance processes once, like Edits (2026-11-15)

Version 0.19 (versionCode 19), label V0.19. Branch v0.19-voice-processing merged to main.

What changed and why (feedback on V0.18: noise reduction started at once without looking at the
whole video, no popup, and the video paused):
- One-time processing (media/VoiceProcessing.kt): when noise reduction is turned on for a clip
  (Reduce noise slider, "Remove noise on N clips", "Apply enhancement to all clips"), the whole
  sound of its file is decoded once on a low-priority background thread and its noise is learned
  (core NoiseProfiler: band power per short frame, 10th percentile over the whole file, digital
  silence left out; checked to 0.1 dB on known noise). The result is a few KB, kept in memory and
  on disk (filesDir/voice_profiles, keyed by file + size), so a file is never processed twice -
  also not after the app restarts or for other clips of the same file.
- While it runs: the "Processing" popup (spinner, "This only needs to happen once. You can keep
  editing while audio processes.", Minimize), twinkling dots and stars over the video with
  "Enhancing audio" (preview only, never exported), a progress line in the panel after
  Minimize, "Processing…" next to Reduce noise. The video is never paused; editing goes on.
  When it is done the popup and dots go away and the noise reduction is heard right away.
- Noise reduction now uses the learned noise from the first sample (no learning while playing,
  no "easing in"): fixture with hiss -39 dB: 100 % -> -27 dB less hiss (was -20), voice -0.4 dB
  (was -2.6). Export waits for the processing if needed ("Processing audio…").
- The "video pauses" bug of 0.18, found and fixed: when an effect was switched on while the
  video played and the audio output was half way through a piece of sound, VoiceFxSink handed it
  a different piece; Media3's DefaultAudioSink rejects that (IllegalArgumentException in
  handleBuffer) and the player stopped. Now the "process or pass through" choice is made only
  between pieces (VoiceSinkContractTest switches effects 7,700 times at random moments: no
  error, every byte passed on).
- Preview player: on any player error it prepares again and carries on from the same place
  (counted; the tests require zero). Sound processing CPU in the preview: 1-4 % on the emulator.
- Compressor gain is worked out every 16 samples (cheaper).

---
# V0.18 — Voice enhance (like Instagram Edits) (2026-11-14)

Version 0.18 (versionCode 18), label V0.18. Branch v0.18-voice merged to main.

- Toolbar button "Voice enhance" for every selected clip with sound (main video, overlay video,
  sound effect, music). Opens a panel over the bottom of the editor (preview stays visible, play
  button and ✓ in the panel, Back closes it) with the Edits tabs:
  - Auto: analyses every clip's sound (16 kHz decode; noise floor = quietest 10 % of 20 ms
    frames, spectral flatness so a steady tone / music is not called noise). "Remove noise on N
    clips" turns noise reduction (70 %, like Edits) on for the noisy clips, tap again = off.
    "Auto adjust audio" sets every clip's volume so the voice is at about -20 dBFS.
  - Volume: 0-200 % for the clip (soft limiter above full scale, never crackles).
  - Enhance: "Apply enhancement to all clips", "Reduce noise" 0-100 %, "Enhance voice" 0-100 %.
  - Fade: fade in / fade out, up to 5 s (at most half the clip). Split keeps the fade in on the
    left part and the fade out on the right part.
  A slider drag is one undo step; everything is saved (DB version 6, column clips.audioFx;
  inside compounds in the children JSON).
- Sound processing (core/VoiceFx.kt, pure Kotlin, same code for preview and export):
  short-time spectrum noise reduction (1024-point FFT, minimum-statistics noise estimate per
  clip, decision-directed Wiener gain, max 28 dB), voice EQ (high-pass 80 Hz, -3 dB at 250 Hz,
  +5 dB presence at 3 kHz, air shelf) with a compressor and make-up gain, volume, fades, soft
  limiter. About 70x real time on a desktop JVM.
- Export: one VoiceFxProcessor (Media3 AudioProcessor) per clip, delay compensated (output
  sample i = input sample i).
- Preview: each player's audio sink is wrapped (VoiceFxSink via FxRenderersFactory). The main
  player finds which clip a piece of sound belongs to from the renderer time and the stream
  offset (PlayMap: renderer time - timeline time is constant for one playing queue, learned again
  after every seek; split clips of the same file are told apart by the seek target). Changes are
  heard while playing. Constant ~20 ms extra audio delay in the preview only.

---
# V0.17 — smart alignment guides and auto-snapping (2026-11-14)

Version 0.17 (versionCode 17), label V0.17. Branch v0.17-align merged to main.

- core/Align.kt: snapping of an element's centre to the frame centre and of its on-screen
  bounding box (turned elements included) to the frame edges; tolerance in screen pixels
  (ALIGN_TOLERANCE = 8 dp), so it feels the same in every frame shape. Guides as a bit mask
  (CENTER_X = vertical centre line, CENTER_Y = horizontal centre line, LEFT/RIGHT/TOP/BOTTOM).
- Used for: the main video (Framing.snap now takes per-axis tolerances from the same 8 dp),
  image / video overlays (one finger drag and two-finger resize / turn) and captions / texts.
  Gestures keep the unsnapped position, so an element leaves a guide by simply moving on (no
  sticking). Haptic tick when a guide switches on.
- Guides: thin yellow dotted lines (with a faint dark shade so they show on bright video), only
  while dragging; hidden as soon as the finger lifts (Modifier.onGestureEnd). Drawn over the
  preview only - never in the export.
- Selection frame in the preview is yellow (like Edits).
- New frame format 4:5 (1080 x 1350) next to Original, 16:9, 9:16, 1:1.

---
# V0.16 — Auto Captions (speech recognition on the phone), Text, Video FX on the overlay layer (2026-11-14)

Current version: 0.16 (versionCode 16), label V0.16. Branch v0.16-captions merged to main.

Auto Captions
- Speech recognition: whisper.cpp v1.8.7 (MIT), vendored in app/src/main/cpp/whisper.cpp (CPU
  backend only; one change, see NOCKNOCK_CHANGES.md: the full 448-token text window per 30 s
  part, because Bangla needs many tokens). JNI: whisper_jni.cpp -> captions/WhisperNative.kt.
  ABIs: arm64-v8a (armv8.2-a+fp16+dotprod) and x86_64 (emulator only). Always built -O3.
- Models (downloaded once in the app, from release "models-v1" of this repo, resumable):
  - বাংলা: mozilla-ai/whisper-large-v3-turbo-bn (Apache-2.0, Whisper Turbo fine-tuned on Bangla)
    converted to ggml and quantised q5_0 (574 MB) by .github/workflows/models.yml.
  - English / other languages: whisper Small q5_1 (190 MB).
  The regular Whisper models are poor at Bangla (see the release's ACCURACY.md), hence the
  Bangla-trained model.
- Pipeline (captions/AutoCaptioner.kt): the main track's sound as it plays (compounds flattened)
  -> 16 kHz mono (SpeechAudio) -> AutoCut voice detector -> parts of at most 15 s cut at pauses,
  silence skipped (Captions.planChunks) -> whisper (beam 3, no context, word-split pieces) ->
  times moved onto the detected speech (Captions.alignToSpeech) -> caption clips that never
  overlap (Captions.build). Silero VAD was tried and dropped (2-18x slower, no gain).
- Captions are TEXT clips on a new TEXT track (core Track.TEXT, MediaKind.TEXT, Clip.text/style).
  Drawn by captions/CaptionRenderer (preview and export use the same bitmap -> WYSIWYG); styles
  Classic, Box, Yellow, Neon, Clean; Bangla via the system font fallback.
- UI: "Captions" button -> sheet (language, model download / delete, style, Create, Remove);
  progress with Cancel. Caption chips on the timeline (yellow). Selected caption: Edit (words),
  Style (style / size / height - all auto captions together), Split, Copy, Paste, Duplicate,
  Compound, Delete, Remove all. Preview: tap selects, double tap edits, drag moves (auto captions
  together), long-press multi-select. Captions work inside compounds.
- "Text" button: a typed text at the playhead (same styles), kept when captions are re-made.
- Export burns captions / texts in (TimedText BitmapOverlay).

Other
- Video FX from the library are added on the overlay layer at the playhead (fitted to the frame);
  the main video is not changed any more.
- Database version 5: clips.text, clips.style (migration 4->5; 3->5 tested on device).
- INTERNET permission (only for the model download).

---
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
