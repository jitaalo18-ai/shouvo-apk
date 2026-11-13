# Nock Nock (Android video editor) - v0.13 source

Native Android video editor: Kotlin, Jetpack Compose, Media3 (ExoPlayer + Transformer), Room.
Read PROGRESS.md first - it is the current checkpoint. V0.13 adds Extract Audio for selected main/overlay videos; V0.12 timeline zoom is preserved. V0.11 passed GitHub Actions tests and APK compilation. See PROGRESS.md for device checks.

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
