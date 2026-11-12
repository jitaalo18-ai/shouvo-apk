# Test report - v0.8 "Nock Nock V0.8" (2026-11-13)

## Executed

**Core module unit tests - 38/38 passed** (desktop JVM 21, kotlinc 2.0.21, JUnit 4.13.2; not on Android)

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
