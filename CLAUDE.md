# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

KitMux is a local music player (package `com.kitsudo.kitmux`, UI strings in Spanish): it scans the device for audio, groups songs by folder, and plays them in the background with a media notification.

## Architecture

- **Playback runs in `playback/PlaybackService`** (Media3 `MediaSessionService` + ExoPlayer, foreground type `mediaPlayback`). Media3 handles the foreground-service lifecycle and the notification/lock-screen controls automatically; there is no custom notification code. The UI never touches ExoPlayer directly — `ui/MainActivity` talks to it through a `MediaController` built in `onStart` and released in `onStop`.
- **URI hand-off gotcha:** `MediaController` strips `MediaItem.localConfiguration` when sending items to the session. The activity puts the song URI in `requestMetadata.mediaUri`, and `PlaybackService.SessionCallback.onAddMediaItems` rebuilds the playable item from it. Keep both sides in sync when changing how items are built.
- **Library scanning (`data/MusicScanner`) has two sources**, merged in `ui/MainViewModel.scan()`:
  1. MediaStore (`IS_MUSIC != 0`), needs `READ_MEDIA_AUDIO` (33+) / `READ_EXTERNAL_STORAGE` (≤32). Folder = `RELATIVE_PATH` (Q+) or derived from `DATA`.
  2. Folders picked with SAF (`OpenDocumentTree`), persisted by `data/FolderStore` via persistable URI permissions and walked recursively with `DocumentsContract` (metadata via `MediaMetadataRetriever`). Works without the audio permission.
  Duplicates across sources are removed with `LibraryUtils.dedupeKey(folder, displayName)`, so folder paths from both sources must use the same relative form (e.g. `Music/Rock`).
- `data/LibraryUtils` holds the Android-free logic and is what `app/src/test` covers; put new pure logic there so it stays JVM-testable.
- UI is Views + ViewBinding (`buildFeatures.viewBinding`), Material 3 theme without action bar (`MaterialToolbar` showing `logo_wordmark` + song count). Edge-to-edge is enforced (targetSdk 37), so insets are applied manually in `MainActivity.applyInsets()`.
- The library is rescanned on each fresh launch; nothing is cached in a database.
- Navigation is a single activity with two states driven by `LibraryState.openFolder`: `null` = home (`folderList`, `FolderAdapter` in LIST or GRID, persisted in the `ui` prefs) and a path = that folder's songs (`songList`, `SongAdapter`). Toolbar, menu and an `OnBackPressedCallback` follow that state. A folder's play button calls `onFolderPlay`: it queues the folder or toggles play/pause if the current item (looked up via `LibraryState.folderOfSong`) belongs to it.
- **Design system** (Material 3, brand colors from the logo) is documented in `docs/design-system.md`. Layouts and code must use theme roles (`?attr/colorPrimary`, `MaterialColors.getColor`) and the `space_*`/`radius_*` dimens, never hard-coded colors or dp values. Role colors live in `values/colors.xml` + `values-night/colors.xml` (same names); `values/themes.xml` maps them onto Material attributes, so there is no `values-night/themes.xml`. Pure `brand_orange` fails 4.5:1 with white text: use it only for large graphics (play button); text uses `colorPrimary`.
- Branding: launcher icon is an adaptive icon (`mipmap-anydpi/ic_launcher*.xml`) = white `@color/ic_launcher_background` + PNG wordmark foreground/monochrome per density, sized to fit the 66dp safe zone. Splash uses `androidx.core:core-splashscreen`: `MainActivity` launches with `Theme.KitMux.Starting` (`values/splash.xml`, logo `drawable-*/splash_logo.png`) and calls `installSplashScreen()` before `super.onCreate`. The 512px Play Store icon is `app/src/main/ic_launcher-playstore.png`.

### PC → phone sync over WiFi (`sync/` + `tools/kitmux_sync/`)

- `sync/SyncService` is a foreground service (`connectedDevice`) toggled from the overflow menu ("Recibir desde PC"). It only serves while on WiFi (`NetworkCallback` on `TRANSPORT_WIFI`), advertises `_kitmux._tcp` via `NsdManager` (TXT `id` = `PairingStore.deviceId`), holds wake + WiFi locks, and reuses its last port (`PairingStore.lastPort`) so clients with a saved `IP:PORT` keep working when mDNS is blocked.
- `sync/MiniHttpServer` + `HttpProtocol` are a hand-rolled HTTP/1.1 server (one request per connection, Content-Length bodies only, no chunked). `sync/SyncServer` routes the protocol documented in its KDoc (`/info`, `/pair`, `/manifest`, `PUT|DELETE /files/<path>`, `/move`). Every path goes through `SyncPaths.normalize`; only audio extensions are accepted.
- `sync/SyncStorage` writes to `Music/KitMux/`: via MediaStore on API 29+ (pending temp row → delete old → rename; owned rows need no permission), plain files + `MediaScannerConnection` on 26–28 (needs `WRITE_EXTERNAL_STORAGE`). After a reinstall MediaStore ownership is lost, so deletes/moves of old files return 409.
- `sync/SyncStatus` (process-wide StateFlow/SharedFlow) feeds the banner in `MainActivity` and triggers a debounced `MainViewModel.scan()` when files change.
- `tools/kitmux_sync/kitmux_sync.py` is the PC client (watchdog + zeroconf). It is a reconcile loop, not an event replayer: `compute_plan(local, unstable, remote, state)` is pure and covered by `test_kitmux_sync.py`, which also runs an in-process fake phone. Keep `AUDIO_EXTENSIONS` in sync with `LibraryUtils.isAudioFile`.
- The emulator's WiFi isn't reachable from the host: to test against it, `adb forward tcp:<port> tcp:<port>` and use `--host 127.0.0.1:<port>` (mDNS discovery only works with a real phone on the same LAN).

## Commands

```bash
./gradlew assembleDebug                 # build debug APK
./gradlew installDebug                  # install on connected device/emulator
./gradlew test                          # JVM unit tests (app/src/test)
./gradlew connectedAndroidTest          # instrumented tests (app/src/androidTest, needs device)
./gradlew lint                          # Android lint

# single unit test class / method
./gradlew :app:testDebugUnitTest --tests "com.kitsudo.kitmux.ExampleUnitTest"
./gradlew :app:testDebugUnitTest --tests "com.kitsudo.kitmux.ExampleUnitTest.addition_isCorrect"

# PC sync script (from tools/kitmux_sync, after creating .venv per its README)
.venv/bin/python -m unittest test_kitmux_sync

# single instrumented test class
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.kitsudo.kitmux.ExampleInstrumentedTest
```

## Build setup

- Single module `:app`. Gradle 9.5 wrapper, AGP 9.3 (built-in Kotlin — no separate `kotlin-android` plugin is applied), Gradle daemon JVM toolchain 25 (auto-provisioned via foojay).
- compileSdk/targetSdk 37, minSdk 26, Java/Kotlin bytecode target 11.
- Dependencies are managed through the version catalog `gradle/libs.versions.toml`; add libraries there and reference them as `libs.*`.
- Configuration cache is enabled (`gradle.properties`) — build logic must be configuration-cache compatible.
- R8 keep rules live in `app/src/main/keepRules/` (AGP 9 convention, not `proguard-rules.pro`); release minification is currently disabled.
