# Changelog

All notable changes to the native Android app (`project_files/Android-native`), released as Hedge Party. Versions are the `versionName` of the debug APK; `versionCode` is in brackets. The engine changes named below are commits on this branch, `android-master` of https://github.com/Gadgit83/hw. Until the app moved into this repository its history was kept in https://github.com/Gadgit83/hedgewars-party (folder `host/`); that history was carried over, so `git log -- project_files/Android-native` shows all of it.

Only some builds were put on a phone, so a version below is a build that was made, not necessarily one that was released. Releases are tags on `android-master` with the APK attached; there is none yet.

## 0.3.0-mp1 (100): two phones play through a relay (test build)

Phase 4 of the multi-phone plan (`docs/tv-viewer-plan.md` in https://github.com/Gadgit83/hedgewars-party): any phone can host, a second phone joins by typed address, each phone runs its own engine with its own teams. Experimental; the owner's two-phone test is `docs/multiphone-test.md`. Nothing about local hotseat games changes.

### Added

- **Relay** (`app/.../net/`, plain Java, no Android classes): lobby over TCP with one JSON object per line, engine links with the engine's own length-byte framing, the Hedgewars server's message whitelist and the team-ownership check for hedgehog speech, an in-memory game log with a hash, per-recipient bounded queues with their own writer threads, and the plan's dropout rule: when a phone is lost the relay sends every other engine the unsynced `F<team>` for each of its teams, with no timestamp, never `f`. It also keeps the turn checksums (`N`), the map digest (`M`) and message counts, and sends every phone a report when the game ends. Port 46633.
- **TV viewer endpoint** in the relay: WebSocket on port 8766 with the framing and behaviour of the e2e-host bridge (tv/e2e-host in hedgewars-party), so the existing TV viewer page connects unchanged. The host screen shows the address. A one-phone host plays through its own relay and the TV can watch it.
- **Hosting**: `HostService`, a foreground service (type `connectedDevice`, notification channel, Stop action, wake lock and Wi-Fi lock) that runs the relay in the main process, so it survives the game process.
- **Network screen** (`NetLobbyActivity`, the Network button on the setup screen): Host network game, Join by address, the list of phones and teams, Ready, Start, and the report of the last game to compare between phones.
- **Launcher network mode** (`hp_ipc.c`): when `files/net.cfg` exists the launcher joins the game through the relay: the IPC reader only queues, a sender thread writes to the relay, a downstream thread writes the relay's frames to the engine. It writes `files/logs/netgame.txt` with every `N` and `M` that crosses the link in either direction and hashes of both streams. Share log includes it.
- `GameConfig.teamLines` and `GameConfig.forRecipient` (the setup split: shared lines once, per-team blocks, then `TN` and `erdriven` after the teams a phone does not own).
- Host tests: `scripts/net-test.sh [basic|dropout|stall|solo]` runs the relay on the JVM and real engines as separate phones under Xvfb (`native/host-test/NetHarness.java`).

### Changed

- Version 0.3.0-mp1, versionCode 100. New permissions: foreground service (connected device), notifications, wake lock, Wi-Fi and network state, multicast state (declared for the foreground service type, and for discovery in a later phase).
- `host-test.sh` takes `HP_TEST_DISPLAY` for the X display (default `:77`).

## 0.2.2 (8): launch crash fix

### Fixed

- **The app crashed on every launch on Android 11 and later (0.2.1).** `Ui.hideBars()` called `Window.getInsetsController()` from `onCreate`, before `setContentView`, when the window has no decor view yet (`NullPointerException: ... "this.mDecor" is null`). It now takes the controller from the decor view (`getDecorView().getWindowInsetsController()`), and when that has none yet (window not attached) it falls back to the system UI flags; `onWindowFocusChanged` applies it again once the window is attached. `edgeToEdge` can be called at any point of the life cycle. The other window calls in the setup, options and team screens and in `GameActivity` were checked and are safe at that point; the decor view access in SDL's `SDLActivity` happens after `setContentView`.

### Added

- **Launch smoke test** (`scripts/ui-smoke-test.sh`, `LaunchSmokeTest`): opens the setup screen (all tabs, with installed data and a setup saved by 0.2.0, and on first launch with no data), the options screen (all tabs) and the team page under Robolectric on Android 14 and 15 with Fairphone 4 qualifiers, and fails on any exception. `scripts/build-apk.sh` runs it before packaging and stops if it fails (`SKIP_UI_SMOKE=1` skips it). With the 0.2.1 code it fails with the crash above; with the fix it passes. `scripts/fetch-robolectric.sh` fills the offline cache of Robolectric's Android jars (Google's Maven Central mirror, repo1 as fallback). See the README, "UI smoke test".

### Changed

- The screenshot test's install marker was an empty file, which never matches the stamp `DataInstaller` checks, so the app would have unpacked the data again; it now holds the stamp. The test only runs with `-Pscreenshots`.
- Robolectric and JUnit are test dependencies of every build (they were only added with `-Pscreenshots`); `android.useAndroidX=true` for Robolectric's sake. Nothing is added to the APK.
- Version 0.2.2, versionCode 8.

## 0.2.1 (7): compact setup screens, touch fixes

### Fixed

- **Walking right, and tapping the aim marker.** In the engine's touch code (`hedgewars/uTouch.pas`) the touch areas of the aim marker and of your hedgehog were compared in a mirrored frame (up and down swapped about the middle of the screen) and ignored the zoom, so on a map where the hedgehog was high or low on the screen the "aim marker" sat over the arrows and the fire button while the real marker did not respond. The marker was also tested before the buttons, stayed active when it was not drawn, and its area grew with the whole HUD scale (about 250 px). Now the buttons come first, the marker can only be grabbed while it is drawn, its area is a finger's width (the square root of the HUD scale), and the conversions use the drawn world scale. Tapping your own hedgehog to open the weapon menu uses the same corrected test. Checked on the host test: a tap on the marker aims, a tap on the mirrored spot does nothing, the right arrow walks the hedgehog (`[Touch] +right`, `-right` in the engine log). Not yet checked on the phone.
- Turn time showed at most 300 s while the Timeless and HedgeEditor schemes use 9999: it now reads "Unlimited" at 9999 and the stepper can select it (from 300 s up).
- With a game style that fixes the scheme, the Key values panel showed the rules of the scheme you had picked instead of the ones the style makes the game use. It shows the effective values.
- The image map preview, the theme icon and the fort, grave and hat pictures are decoded off the UI thread.
- The game data folders are scanned once and kept, off the UI thread, instead of on every return to the setup screen.
- The Java package is now `org.hedgewars.android` (it changed when the app moved into this repository), so a launcher shortcut pinned before updating may need to be added again. The normal app icon is not affected.

### Changed

- **Setup, team and options screens are compact** and full screen (status and navigation bars hidden, swipe from the edge to show them; the camera cut-out and the keyboard are still avoided). Header on one row (title, tabs, a small Options button). Controls are drawn smaller (about 36 dp) and keep a 48 dp touch area. Computer teams read "CPU 1" to "CPU 5" on the button (the full text is in the list), with one-line labels that end in an ellipsis rather than wrapping. The hedgehog count is a compact stepper. Randomise and Add team sit beside the Teams heading, and team cards are in three columns on a phone held sideways, so up to six teams show without scrolling. The Customise button is called Edit. On the team page the colour is a picker row and Randomise team is in the header.
- `scripts/host-test.sh`: `HP_TEST_DISPLAY` chooses the Xvfb display number.
- Optional screenshot test: `./gradlew -Pscreenshots -Pandroid.useAndroidX=true testDebugUnitTest` is meant to render the screens with Robolectric (see `app/src/test`). It compiles but has not run yet: Maven Central refused the download of Robolectric's native runtime (HTTP 429), so the new layouts have not been seen rendered.
- A desktop pas2c build of the engine (`project_files/hwc`) links: `hw_device_rumble` has a weak no-op in the C runtime; the Android launcher's definition replaces it.
- Version 0.2.1, versionCode 7.

### Also in this version (moved here from Unreleased)

- The app now lives in the engine repository and is built from the engine in the same checkout; `fetch-deps.sh` no longer clones the engine. The Java package is `org.hedgewars.android`, the log tag `HWNative`. Application id, name and source link are build properties (`-PappId`, `-PappName`, `-PsourceUrl`); the defaults are unchanged (`party.hedge.host`, Hedge Party), so an installed 0.2.0 keeps updating. The licence page points at this repository only.
- The engine reports itself as `1.1.0` (was `1.1.0-party`) in its console output.
- Names that carried the app's branding into the engine are neutral now: the rumble hook is `hw_device_rumble`, the host test defines are `PAS2C_DESKTOP_GL` and `HW_GLES1`, the weapon menu log line starts with `[Touch]`. No change in behaviour.

## 0.2.0 (6): setup screen and options

The first build with a real game setup. Also gathers the fixes made after the 0.1.4 build.

### Added

- **Setup screen** with three tabs and a Start button that is always visible:
  - Teams: two to eight teams, each with a name, colour (the engine's nine clan colours), human or computer player (levels 1 to 5), one to eight hedgehogs, and a Customise page for hedgehog names, hats, fort, grave and voice pack. Random teams use the desktop game's themed names and hats.
  - Map: random island (with size filter, cavern and wacky), random maze (six styles), random perlin, forts, wave function collapse, and image maps from the game data; map complexity; theme picker with the theme icons; optional fixed seed.
  - Rules: the 16 built-in schemes of the desktop game, 13 weapon sets, the game styles (Lua scripts in `Scripts/Multiplayer`), nine key values (turn time, starting health, sudden death, water rise, mines and their timer, crate drops, health crate chance, explosive barrels) and a "more rules" section with the remaining values and all 25 rule switches.
- The last setup is remembered and comes back on the next launch.
- **Computer players**: the engine's AI plays any team set to a computer level. Checked on the host test with two computer teams and no input (see `README.md`).
- **Options menu** (Options button on the setup screen):
  - Sound: master volume, sound effects and music levels, mute, hedgehog voices.
  - Display and controls: HUD size and weapon menu size (automatic or fixed), screen edge margin (automatic or in millimetres), effects quality (six levels as on the desktop), show frames per second, vibration on big explosions, name tags above hedgehogs.
  - Advanced: share log, reset game data, reset setup and options, version and build information (engine commit, app commit, device), licence and source links, licence text and credits.
- Engine (`android-master`): `--effects-volume`, `--music-volume` and `--no-voices` options, and a `DeviceRumble` hook for vibration. The defaults behave exactly as before.
- `scripts/config-test.sh`: JVM checks of the generated game setup against the desktop frontend's sources (scheme presets, weapon sets, flag bits, engine command names, team and hedgehog lines).
- The launcher logs the notable game commands the engine reports (attack, turn end, weapon choice, jumps), which shows that a computer team took its turn.

### Changed

- The game configuration now follows the desktop frontend line for line (order and content of `getFullConfig`, `commonConfig` and `teamGameConfig`). Compared with 0.1.x it now also sends `e$inithealth`, `e$sentries`, `e$scriptparam` and `e$maze_size`, and the weapon lines per team with `eammstore` under the same condition as the desktop game.
- The team colour list is the engine's own nine colours (the 0.1.x list had a grey eighth colour the engine does not have).
- Share log moved from the setup screen into Options, Advanced. The red problem box after a failed game stays on the setup screen, with a Share last log button.
- Version 0.2.0, versionCode 6. The log report now also lists the engine commit, the options and the game setup that was sent.

### Fixed (since 0.1.4)

- A tap that resumes a paused game is no longer also a click, and a finger lifted while paused no longer resumes the game unless it went down while paused.
- Weapon menu: with a scaled HUD a tap on a weapon cell now selects it (the touch cursor was pushed to the bottom edge by a clamp in real screen units, so the tap never hit the cell), and the 32 px weapon icons stay crisp at fractional menu scales instead of being smoothed.
- Stale build guard: the build refuses to package pas2c output that does not match the engine checkout, and stops outright when the engine is not a git checkout.

## 0.1.4 (5)

- Safe area insets are symmetric: with the screen locked to sensor landscape a 180 degree turn moves the camera cut-out to the other side, so the larger of the left and right insets is used for both, and the same for top and bottom.

## 0.1.3 (4)

- HUD scaling: `GameActivity` measures the screen density and passes `--ui-scale` and `--menu-scale` to the engine, aiming for about 8 mm per control and per weapon cell. The engine lays out and hit tests the HUD in those scaled units.
- Safe area: the game window draws behind the camera cut-out and the rounded corners, and the engine keeps the HUD inside insets taken from the cut-out and `WindowInsets.getRoundedCorner` (`--inset-left`, `--inset-top`, `--inset-right`, `--inset-bottom`).
- Landscape is locked: SDL asked Android for every orientation when the engine created its window, which overrode the manifest, so the game could open in portrait.
- Pause and focus: losing focus pauses the game and regaining it resumes; a pause chosen with the pause button stays until tapped; the engine restores the GL context after a pause.
- Host test hooks for touch, focus and window events (`HP_TEST_SCRIPT`, `HP_TEST_ARGS`, `HP_TEST_SHOTS`, `HP_TEST_SIZE`).

## 0.1.2 (3)

- Fixed the crash on returning to the game after a pause (chat texture overrun): chat lines were re-rendered while their metrics were still zero, which produced a 4 by 1 surface that `PrettifySurfaceAlpha` read far past its end (a segmentation fault on the Fairphone 4). Found with the AddressSanitizer host test (`ASAN=ON host-test.sh`) by posting the same window restored event the phone sends.
- The engine and launcher are built without `_FORTIFY_SOURCE`: pas2c strings are 255 byte records that the generated code treats as single objects, which bionic's object size checks can reject.
- The setup screen keeps the team name fields above the keyboard, and the Share log button can be used again after a failed report.
- The log report includes the native tombstone of the last game crash.

## 0.1.1 (2)

- Fixed the start-up crash caused by Lua: Lua's `TString` struct hack tripped bionic's FORTIFY checks and aborted the process, so Lua is built without FORTIFY.
- On-device diagnostics: a logcat writer per process, a Java crash handler, the engine's stdout and stderr piped to logcat, engine fatal errors and `exit()` recorded in `files/logs/last_error.txt`, and a report that the Share log button hands to mail and messaging apps.
- The game runs in its own process (`:game`), so an engine exit no longer takes the setup screen down and the setup screen can show why the game stopped.

## 0.1.0 (1)

- First native build: the Hedgewars engine transpiled to C with `pas2c`, SDL2 with OpenGL ES 1.1, the Rust library for fixed point maths, collision and map generation, and a Java setup screen for a hotseat game of two to four teams with four hedgehogs each, a random map and a random theme.
- In-process IPC server that stands in for the Qt frontend and serves `files/game.ipc` to the engine.
- Trimmed game data packaged as assets and unpacked to internal storage on first start.
