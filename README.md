# Nock Nock (Android video editor) - v0.17 source

Native Android video editor: Kotlin, Jetpack Compose, Media3 (ExoPlayer + Transformer), Room.
Read PROGRESS.md first - it is the current checkpoint. V0.17: smart alignment guides and auto-snapping (video, overlays, texts) in 9:16, 16:9, 1:1 and 4:5; V0.16: Auto Captions. Device tests run in CI (see TEST_REPORT.md).

## Get an APK

**Option A - GitHub (no PC tools needed)**
1. Create a new private repository on github.com.
2. Upload the contents of this folder (keep the `.github` folder).
3. Open the repository's **Actions** tab -> "Build debug APK" -> wait for it to finish.
4. Download the APK from the repository's Releases page and install the APK on your phone.
If the run fails, copy the red error lines and send them back for a fix.

**Option B - Android Studio (Windows PC)**
1. Install Android Studio (Ladybug 2024.2 or newer).
2. File -> Open -> select this folder. Let Gradle sync finish.
3. Build -> Build APK(s). The APK appears in `app/build/outputs/apk/debug/`.

**Command line:** `./gradlew :core:test :app:assembleDebug`

## Layout

- `core/` - pure Kotlin: timeline model, Split, AutoCut (voice detection + planning). Has unit tests.
- `app/`  - Android app: `data` (Room, libraries), `media` (import, AutoCut decode, playback, export), `ui`.

Requires Android 10 or newer on the phone.


## Third-party code and models
- whisper.cpp v1.8.7 and ggml (MIT) - app/src/main/cpp/whisper.cpp (LICENSE inside).
- OpenAI Whisper (MIT) - the speech-recognition models.
- mozilla-ai/whisper-large-v3-turbo-bn (Apache-2.0) - the Bangla caption model, converted to ggml.
- Google FLEURS (CC-BY-4.0) - Bangla speech used only in the CI tests.
