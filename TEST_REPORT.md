# V0.19 validation (2026-11-15)

Executed (GitHub Actions):
- core JVM tests: 104 passed. New: the learned noise of steady hiss is right to 0.1 dB (also
  with a voice 80 % of the time, stereo, other sample rates); noise reduction with the learned
  noise from the very first second: -27 dB at 100 %, -20 dB at 70 %, voice -0.3 dB; without a
  profile nothing is changed; profile file format; silence gives no profile.
- Android 12 emulator, 24/24 instrumented tests passed, zero player errors in the whole run:
  - VoiceProcessingUiTest (720x1280, 48 kHz phone-like video with hiss, the same file twice):
    noise reduction switched on WHILE PLAYING (20 small slider steps like a finger) -> the
    Processing popup and the dots appear, the video keeps playing; volume and voice changed while
    it processes; Minimize -> popup gone, dots and progress line stay; 33 position checks while
    processing: the video always moved on (smallest step 360 ms per ~450 ms); when done the dots
    and "Processing…" are gone from the screen, 70 % shown; the preview takes the learned noise
    (-18 dB in a pause); the second clip of the same file and a restart (memory cleared, read
    from disk) need no processing again (file decoded exactly once).
  - VoiceSinkContractTest: 3,000 sound pieces, effects switched on/off 7,782 times at random
    moments (also half way through a piece): no error, 15,223,204 of 15,223,204 bytes passed on.
    The 0.18 code fails this (it is the "video pauses" crash, also seen in a 0.19 test run's log
    before the fix: IllegalArgumentException at DefaultAudioSink.handleBuffer).
  - Export of the noisy clip: hiss -41.2 -> -68.5 dB (100 %), -61.0 dB (70 %); the first second
    -42.4 -> -70.2 dB (no learning time); voice -24.8 -> -25.1 dB; overlay sound -41.5 -> -68.6 dB.
  - Preview: Reduce noise 100 % -26.9 dB in a pause; CPU for the sound 1.1 %.
  - All earlier tests (V0.18 panel, captions, compound, alignment...) pass.

Not executed / needs a real phone: listening by ear; a long real phone video (minutes) to see how
long the one-time processing takes on the S25 Ultra (on the emulator about 1-2 s per 20 s of
sound at full speed).

---
# V0.18 validation (2026-11-14)

Executed (GitHub Actions):
- core JVM tests: 102 passed (20 new): with nothing set the sound comes back sample-exact
  (also in uneven blocks, also after a reset); volume, fade in / out ramps; volume 200 % and
  Enhance never pass full scale; noise reduction on a voice + hiss test signal: hiss -20.9 dB at
  100 %, -17.8 dB at 70 %, voice changed by only -0.17 dB; Enhance: +11 dB at 3 kHz vs +4 dB at
  150 Hz, 30 Hz rumble cut; settings per clip follow the positions to the sample; live mode
  delay; noisy / clean / steady-tone detection; auto volume; the preview's clip finder (also
  after an image, for two clips cut from the same part of a file, and a queue it was not told
  about); Voice enhance timeline edits (only clips with sound, ranges, apply to all, remove-noise
  toggle, auto adjust, split keeps the fades at the outer ends); database text form.
- Android 12 emulator, 22/22 instrumented tests passed (4 new in VoiceEnhanceTest; DB upgrade
  test now 3 -> 6):
  - Export of a noisy 8 s clip (hiss -41 dB): Reduce noise 100 % -> pauses at -61.7 dB (-20.5 dB),
    70 % -> -58.9 dB, voice -24.8 -> -27.2 dB. Volume 50 % -> -6.2 dB; during a 3 s fade in -14 dB.
  - Sound on another track (video overlay, main muted): its noise reduction works in the export
    (-41.5 -> -62.1 dB).
  - Preview player: after seeks to 2.0 / 9.0 / 6.0 s the processed sound was mapped to 2.020 /
    9.002 / 6.013 s; playing across a cut, the clip at 50 % measured -6.0 dB and the clip at
    100 % 0.0 dB; muting a clip while the project is open -> -149 dB (heard right away).
  - Panel through real touch: Voice enhance button -> panel; Auto finds exactly 1 noisy clip
    (the steady tone clip is not called noise) -> "Remove noise on 1 clip" -> 70 % on that clip
    -> "Noise removed on 1 clip"; Auto adjust audio sets both volumes, undo takes it back;
    sliders (Reduce noise, Enhance voice, Volume, Fade in, Fade out) change the clip, "Apply
    enhancement to all clips" copies to the other clip; one drag = one undo step; ✓ closes and
    everything is in the database; the panel closes when the clip is deselected. Screenshots
    70-74 checked.

Not executed / needs a real phone: listening. The emulator has no speaker, so the sound was
judged by measurements only. How natural the noise reduction sounds on real recordings (wind,
street, music under the voice) should be checked by ear on the S25 Ultra.

---
# V0.17 validation (2026-11-14)

Executed (GitHub Actions):
- core JVM tests: 82 passed, incl. 8 new alignment tests: close to the centre -> exactly centred
  with both guides; only the close axis snaps; outside the tolerance it moves freely; edges snap
  to the frame edges; the same pixel tolerance in 9:16, 16:9, 1:1 and 4:5; bounding box of turned
  elements; video framing with the given tolerance.
- Android 12 emulator, 18/18 instrumented tests passed, incl. AlignmentUiTest through real touch
  input, in 9:16 (643x1143 preview), 16:9 (1080x607), 1:1 (1080x1080) and 4:5 (914x1143):
  overlay dragged to 6 / 5 px from the centre -> exactly 0.5 / 0.5, both guides drawn while the
  finger is down, gone after it lifts; dragged 70 / 90 px away -> follows the finger, no guide;
  only left-right close -> only the vertical guide; a text and the main video snap the same way.
  Screenshots checked (yellow dotted guides, yellow selection frame).

Not executed / needs a real phone: the feel of the haptic tick and the snap distance on the S25
Ultra (8 dp; easy to change in ALIGN_TOLERANCE).

---
# V0.16 validation (2026-11-14)

Executed (GitHub Actions on the merged code):
- core JVM tests: 74 passed, incl. captions from speech (no overlaps, min / max time, noise
  dropped), replacing auto captions keeps typed text, text clips trim / split, captions inside
  compounds follow the framing, timing moved onto detected speech, sound cut into parts at pauses.
- Android 12 emulator (x86_64), 17/17 instrumented tests passed, incl.:
  - English speech (espeak-ng, starts after 1.5 s of silence), Small model: every word right
    ("hello, everyone. today i will show you how to make a video. first open the editor, then add
    your clips, and press export."), first caption at 1.36 s; 13 s of audio in 5-8 s.
  - Real Bangla speech (Google FLEURS), Bangla model: 7 s sentence CER 9.1% ("দ্বীপপুঞ্জ এবং
    লেগগুলোতে আবস্থিকভাবে..." vs "...লেকগুলোতে আবশ্যকভাবে..."), 35-48 s on the emulator CPU.
  - 43 s of continuous Bangla (several sentences): CER 9.4%, 102% of the text (nothing cut off),
    206 s on the emulator CPU.
  - Export: the caption is burned in where and when it should be (difference 106 inside the
    caption box, 0.1 elsewhere, 0.0 before its time).
  - UI: Captions -> English -> Create -> 4 captions on the text track and in the preview; Edit
    words; Style Box for all captions; Undo; Video FX from the library lands on the overlay
    layer at the playhead with the main track unchanged.
  - Database 3 -> 5 upgrade keeps old projects; captions (Bangla text) are saved and read back,
    also inside compounds.
- Model accuracy with the whisper.cpp command line on 10 FLEURS Bangla clips is in the
  models-v1 release notes (ACCURACY.md).
Bugs found by the tests and fixed: captions started before the words (now aligned to the voice);
long Bangla speech was cut off after ~220 tokens per 30 s (now parts at pauses + full window);
broken Bangla letters at piece borders (UTF-8 carried over); ffmpeg / float WAV issues in CI.

Not executed / needs a real phone: speed on the S25 Ultra (emulator: about 5x the audio length for
Bangla; a phone CPU should be several times faster), the 574 MB download over mobile data,
Bangla with music in the background, very long videos (memory).

---
# V0.15 validation (2026-11-14)

Executed (GitHub Actions on the merged code):
- core JVM tests: 67 passed, incl. 9 new multi-track tests: main + overlay + sound + music in one
  compound plays exactly as before (sampled every 250 ms per track, incl. overlay framing); the
  music part past the compound stays on its track; overlay-only and sound-only compounds; adding
  clips to an existing compound gives one flat compound; uncompound restores tracks and overlay
  order; splitting a compound does not play its music twice; moving a compound takes its overlays
  along; compound framing moves / zooms overlays inside; trimming an overlay compound.
- Android 12 emulator, 12/12 instrumented tests passed, incl. the new
  CompoundUiTest.multiTrackCompoundByLongPress: long-press video clip, long-press overlay chip,
  tap sound, music and second video (and tap again to untick), Compound -> one compound with
  {MAIN 2, OVERLAY 1, SFX 1, MUSIC 1}, same playback, plays; Undo / Redo; hold the compound + tap
  the other overlay -> added (6 clips, not nested); Uncompound -> all back on their tracks;
  long-press + tap + Delete removes two clips at once, Undo brings them back.
  Screenshots reviewed by eye.
Test fixes on the way (not app bugs): sampling helper on a shared time grid; on the slow emulator
playback reached the end before the test pressed Pause, so tests now stop it via the controller.

Not executed / needs a real phone: long-press feel and haptics on the S25 Ultra, very long music
inside compounds, export of a large multi-track compound.

---
# V0.14 validation (2026-11-14)

Executed (GitHub Actions, run on the merged code):
- core JVM tests: 58 passed, incl. 11 new compound tests: plays exactly like the clips it was
  made from, clips between the ticked ones are included, trim + split cut through the inner clips,
  uncompound gives the clips back with new ids, nested compounds, compound framing moves / zooms /
  turns everything inside (incl. off-centre child around the compound centre), duplicate main,
  paste at the playhead, library ids inside compounds.
- Android 12 emulator, 11/11 instrumented tests passed (8 from V0.13 + 3 new):
  - CompoundDataTest: a V0.13 (schema 3) database is upgraded to 4 and the old project is intact;
    a nested compound with framing and a library clip inside is saved and read back exactly; the
    library item counts as used; deleting the project removes its clips.
  - CompoundDataTest: export of compound clips with zoom + rotation, split and trimmed: right
    length (13.0 s), sound present, portrait output.
  - CompoundUiTest (through the real UI): Compound -> All -> Compound (4 clips -> 1, same length,
    same pieces play); playback crosses from one inner clip to the next; tick 2nd + 4th -> 3 clips
    packed; Split the compound; Uncompound restores the 4 clips; Copy, Paste at the end; Duplicate;
    close + reopen restores compounds with their clips; ⋮ -> Delete project -> confirm -> gone.
  Screenshots reviewed by eye (pick mode ticks, compound badge, split, paste, delete dialog).
Found and fixed while testing: annotation compile error; cut-off "Uncompound" label.

Not executed / needs a real phone: compound of long real projects (e.g. 9-minute AutoCut project)
for scrolling smoothness, export of a long compound, Samsung S25 Ultra feel.

---
# V0.13 validation (2026-11-13)

Executed (GitHub Actions, final run before merge):
- core JVM tests: all passed (47), incl. soft word ending outside the voice band is not cut
  (verified to fail without the fix), strength ordering, snap tests incl. ripple edge.
- Android 12 emulator (API 31, x86_64), 8/8 instrumented tests passed:
  - probe, thumbnails (12 frames ~1.4–2.3 s cold, 8–18 ms from disk cache), thumbnail work kept
    when a tile scrolls away, waveform.
  - AutoCut on a 24 s test clip with 5 speech parts: analysis ~1.1–1.4 s, NORMAL keeps exactly
    the speech ranges (±250 ms); export after AutoCut has the right length (14.16 s, audio present).
  - player: incremental playlist update, one update per drag, scrub of 40 moves lands on the last.
  - UI end-to-end: create project, open, select, scrub (18.4 s), AutoCut review -> Tight -> Apply
    (7 clips), undo/redo, pinch zoom (clip 101 -> 303 px), status message not over the timeline
    and gone on its own, trim end handle (edge follows finger, guide cleared, playhead on new
    edge), play, Add audio opens BG MUSIC, reopen project (edits saved), short clip has two
    separate handles. Screenshots reviewed by eye.
Bugs found by these tests and fixed: snackbar blocked timeline touches; status message never
hid; sticky snap on main-track trim; trim end not always reported; ExoPlayer wrong-thread after
project load; cut-off duration label on narrow clips.

Not executed / needs a real phone: feel of haptics and scrolling at 120 Hz, Samsung S25 Ultra
specifically, long or 4K/HEVC files, real speech AutoCut in the app (the VAD itself was checked
offline on the owner's two uploaded videos), Google Drive import, export of long projects.

---
# V0.12 validation

Run core unit tests and debug APK compilation using GitHub Actions for this version.
Phone testing still required: spread/pinch on ruler, filmstrip, waveform and selected
trim handles; single-finger scroll/trim; lift one finger then both; pinch during playback;
zoom at start/end; long projects and scale bounds. Check time at central playhead stays
fixed and zoom does not change clip timings or add Undo entries.

Historical reports below describe earlier versions, not V0.12 results.

---
# V0.11 validation — 2026-11-13

Performed: reviewed changed source, verified version metadata, XML parse and ZIP integrity;
confirmed the unchanged core module against V0.10. These are not Kotlin compilation tests.

Blocked: Gradle bootstrap attempts to fetch gradle-8.9-bin.zip and fails with
"Network is unreachable". No V0.11 APK or executed unit-test result is available.
No Android device/emulator UI verification has been performed.

Device checklist after building:
1. Open an existing project; verify preview, saved framing and original clips.
2. Scrub/start/pause a 17-minute project and verify fixed playhead/ruler alignment.
3. Add multiple overlay, SFX and music tracks; scroll vertically and check add button.
4. Select, trim, move, split and undo clips; verify handles and playback.
5. Open BG MUSIC with no selection, main selection, overlay and audio selection.
6. Import WAV/MP3, search mixed-case names, clear query and check no-match state.
7. Preview/stop, rename, insert at playhead, remove, restart and reopen music library.
8. Export a short project containing music; check duration, image and audio.
9. Inspect small screens and large font settings; horizontally scroll both toolbars.

---
# Historical report supplied with V0.10 (not re-executed)

# Test report - v0.10 "Nock Nock V0.10" (2026-11-13)

## Executed

**Core module unit tests - 41/41 passed** (desktop JVM 21, kotlinc 2.0.21, JUnit 4.13.2; not on Android)

| Area | Tests |
|---|---|
| Split | two independent clips; 200 random repeated splits keep total duration and exact timeline-to-source mapping; edge splits rejected; split of an already split clip |
| Timeline | insert inside / at start / at end; overlays and sounds never change the main track; move, start/end adjustment, source limit; image overlay extension; overlapping clips get separate rows; exclusive selection |
| AutoCut | speech found over background noise (not digital silence); silence removed while a 300 ms pause and word margins are kept; pieces joined in order with unchanged source mapping (A/V sync by construction); trimmed clip with decoder offset; pure silence, noise-only and no-audio leave the clip unchanged; continuous speech kept whole; empty/invalid keep list rejected; louder noise; 48 kHz stereo downsampling |
| Voice removal (0.2) | centred voice removed while side-panned music stays (residual more than 12 dB below the music); voice alone reduced by more than 15 dB; pure stereo music changes less than 1.5 dB; centred bass kept; mono source reported instead of saved; output has the same length as the input |

Audio in these tests is synthetic (harmonic "voice" plus noise). Real speech recordings have not been tested.

**Static checks**
- Kotlin parser over all 13 Android source files: no syntax errors. Type checking was not possible.
- Media3 calls checked against the 1.5.1 source (sequence builder/addGap, Composition builder, overlay classes, Transformer listener, Presentation).
- Resource and manifest XML are well-formed.

**Build (GitHub Actions, run 43269596434)**
- `./gradlew :core:test` - passed.
- `./gradlew :app:assembleDebug` - passed; debug APK produced.

**Owner's phone (version 0.9, from a screenshot)**
- 9:16 frame visible, selected main clip shows its border in preview and timeline, W x H button and
  format drop-down present, 17-minute project loads. So the app started after both database updates.
  The 0.10 timeline (ruler, waveforms, overlay thumbnails, "+" button) is untested on a device.

**Owner's phone (version 0.8, from a screenshot and report)**
- Sound FX library with 7+ saved sounds and search opens. Reported: sounds and Video FX placed on the
  timeline could not be moved or trimmed - addressed in 0.9 (handles, move buttons, sturdier drag).
  The 0.9 gestures themselves are untested on a device.

**Owner's phone (version 0.5, from a screenshot)**
- Video FX library with search field and 7+ saved clips opens; thumbnails were black because the
  clips start with a black frame - addressed in 0.6. The 0.6 thumbnail and in-box preview are untested
  on a device.

**Owner's phone (version 0.4, from a screenshot)**
- New editor layout, format drop-down set to 9:16, timeline thumbnails and a 17-minute project load.
  The owner found the 9:16 preview unclear (small video in an all-black area) - addressed in 0.5.

**Owner's phone (version 1.0.0, from screenshots)**
- Project created, saved, listed and reopened later; Video FX library holds 8+ imported clips.

The 0.3 layout, undo/redo and delete, and the 0.4 format drop-down and import chooser, have not been
fully run on a device yet. The 0.5 pinch-zoom, new preview surface and overlay tools are untested on a device.

0.7: rotation maths is unit-tested; the two-finger gestures, the rotated export and the database
migration are untested on a device.

0.8: snapping maths (centre, edges, angles, fit/fill, quarter turns) and stretch are unit-tested; the
guide lines, the vibration, the W x H sliders and the second database migration are untested on a device.

## Not executed

Every device check in Step 10 (the APK has not been installed or launched yet):

| # | Step-10 check | Status |
|---|---|---|
| 1 | New Project creation | not run |
| 2 | Multiple video/image imports | not run |
| 3 | Project save and reopen | not run |
| 4 | Timeline playback and scrubbing | not run |
| 5 | Repeated Split operations | logic passed on JVM; not run on device |
| 6 | AutoCut removing non-speaking segments | logic passed on JVM with synthetic audio; not run on device |
| 7 | A/V sync after AutoCut | mapping passed on JVM; playback not run |
| 8 | Video FX import, rename, remove | not run |
| 9 | Sound FX import, rename, remove | not run |
| 10 | Background Music import, rename, remove | not run |
| 11 | Deleting Gallery originals after library import | not run |
| 12 | App restart with saved library files | not run |
| 13 | Overlay movement, resizing, timeline positioning | timeline maths passed on JVM; gestures not run |
| 14 | Multiple overlays and sound effects | not run |
| 15 | Final rendering with all edits | not run |
