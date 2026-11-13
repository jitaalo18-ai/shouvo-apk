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
