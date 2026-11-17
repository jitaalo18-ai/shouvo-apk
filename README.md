# Nock Nock (Android video editor) - v0.29 source

V0.23 adds Overlay Folder (owned image/video copies, preview and playback) and in-app web Image Search with save-to-library.

### Use the new tools
- **Overlay Folder → Add Images / Videos:** choose gallery or Files media. Imports are copied into the app; deleting originals leaves these copies intact.
- Tap an image thumbnail to preview, a video thumbnail to play, or **+** to insert at the playhead. Filter All / Images / Videos, search names, rename, or remove items. Saved-project references are protected when a library item is removed.
- **Image Search:** enter a keyword, choose Google or Bing, open an image, then long-press it. Preview and select **Save image** to copy it into Overlay Folder. Search history stays on the device.
- Image Search needs internet. Providers may show consent or CAPTCHA, and some source websites refuse downloads. Use full-size results where available.
- Copies survive deletion of gallery originals, but uninstalling the app or clearing its app data removes its private files.


Native Android video editor: Kotlin, Jetpack Compose, Media3 (ExoPlayer + Transformer), Room.
V0.22 adds Dynamic captions: Lilita One / bold Bengali fonts, a translucent dark panel, and word-timed yellow highlighting shared by preview and export. V0.21 large-export fixes are retained. See THIRD_PARTY_CAPTIONS.md for font licenses and timing behavior.
Read PROGRESS.md first - it is the current checkpoint. V0.20: Isolate voice (DeepFilterNet3 neural network on the phone, whole main timeline); V0.19: Voice enhance processes each file once like Edits (Processing popup, sparkles, whole-clip noise learning, video keeps playing); V0.18: Voice enhance (Auto / Volume / Enhance / Fade, noise reduction and voice EQ in preview and export); V0.17: smart alignment guides and auto-snapping (video, overlays, texts) in 9:16, 16:9, 1:1 and 4:5; V0.16: Auto Captions. Device tests run in CI (see TEST_REPORT.md).

## Get an APK

**Option A - GitHub (no PC tools needed)**
1. Create a new private repository on github.com.
2. Upload the contents of this folder (keep the `.github` folder).
3. Open the repository's **Actions** tab -> "Build debug APK" -> wait for it to finish.
4. Download the `apks` artifact from the completed Actions run; install `debug/app-debug.apk`. Main-branch builds also publish a Release.
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
- DeepFilterNet (libDF, MIT OR Apache-2.0, Hendrik Schröter) - Isolate voice: the Rust library
  in third_party/deepfilternet (licenses inside, changes in NOCKNOCK_CHANGES.md) and the
  DeepFilterNet3 network, app/src/main/assets/df/DeepFilterNet3_onnx.dfn (same license).
  Building it needs Rust + cargo-ndk (third_party/deepfilternet/build-android.sh; CI does it);
  without it the app builds and Isolate voice reports it is not available.
- Google ML Kit Subject Segmentation (com.google.android.gms:play-services-mlkit-subject-segmentation, ML Kit
  Terms of Service) - Transparent image; the model is delivered by Google Play services.
- Google FLEURS (CC-BY-4.0) - Bangla speech used only in the CI tests. Mixed with synthetic traffic and music for the Isolate voice tests.
