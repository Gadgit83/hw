# Changelog

All notable changes to the native Android app (`project_files/Android-native`), released as Hedge Party. Versions are the `versionName` of the debug APK; `versionCode` is in brackets. The engine changes named below are commits on this branch, `android-master` of https://github.com/Gadgit83/hw. Until the app moved into this repository its history was kept in https://github.com/Gadgit83/hedgewars-party (folder `host/`); that history was carried over, so `git log -- project_files/Android-native` shows all of it.

Only some builds were put on a phone, so a version below is a build that was made, not necessarily one that was released. Releases are tags on `android-master` with the APK attached; there is none yet.

## 0.2.3 (9): screen corners, problem box, switch button, turn camera

### Fixed

- **A stale "The last game stopped with a problem" box.** After the update to 0.2.2 the launch crash of 0.2.1 still showed in a large red box. The box is now two lines at most (what happened; the details are only in the shared log), has a x that dismisses and clears the stored error, hides itself after 30 seconds (not shown again for that problem), is hidden while the keyboard is open, is cleared when a new game starts, and an error recorded by an older version is dropped when the app is updated (the version that wrote the logs is kept in `logs/app_version.txt`). Share last log stays in the box while it shows and in Options, Advanced.
- **The setup screen with the keyboard open.** The Start bar drops its status line while the keyboard is open (one line instead of about 100 px) and the focused card, with its hog count and type rows, is scrolled into view when it fits above the keyboard.
- **No way to switch hedgehog on a phone with the rule "Switch hedgehog in a turn".** The engine starts a switcher gear at the turn start but the touch interface only showed the switch button when Switch was the selected weapon, so there was no button and no gesture. The button now follows the gear: it shows while the switcher is there, a tap moves to the next hedgehog (as Tab does), and it goes away when the switcher ends (walking, aiming, jumping, firing, picking a weapon). The simulation is untouched. Checked on the host with the scheme rule on: two taps moved the turn from Ariel to Thaddeus to Robbie (`[Touch] switch, hedgehog ...`, `[Touch] active hedgehog ...`) and walking hid the button.

- **Touch code no longer dereferences a missing hedgehog.** When a team was removed or the game ended while a finger was down, `uTouch.pas` could read `CurrentHedgehog^.Gear` through a nil `CurrentHedgehog` or nil `Gear` (the held aim marker every frame, `aim`, the timer and bounce buttons, `ParseTeamCommand`'s own `CurrentHedgehog^.BotLevel`). All of these are guarded (a touch then does nothing; a held marker stops steering but keeps the finger count balanced), as are the switch button and the widget state update. Checked on the host under AddressSanitizer: with a finger held on the aim marker, the team of the current hedgehog was removed (the active hedgehog changed under the finger) and then the other team, followed by taps on the timer and bounce buttons and the marker again: no crash, 0 reports. The host test gained the script action `ipc:<text>` for this (`ipc:eteamgone uTeam name`). Whether the game then ends by itself was not reproduced.

### Changed

- **Controls sit in the corners.** The one symmetric safe area inset (the larger of the cut-out and the rounded corners, plus 3.5 %) is gone. The app measures the radius of each rounded corner and the camera cut-out (its depth and the rows it covers) and passes them to the engine (`--screen-shape`, engine change in `uWorld.pas`), which places every control just clear of its own corner's arc, and moves a control for the cut-out only when it overlaps the cut-out's rows. The timer, arrows, fire button, jump, weapon chest, pause button, health and the wind bar are placed this way; the weapon menu keeps clear of the corner too. Without real values (before Android 12) the old 3.5 % margin remains. The 180 degree flip of a sensor landscape activity is handled by making the shape symmetric under it (see README, "Engine options from the options menu"), not by re-reading the insets. Host check at 2340 by 1080 with 120 px corners and a 90 px cut-out on the left: before and after screenshots, and taps on the arrows, fire, jump, weapon chest and pause button all reach the engine.
- **Options, Display, Screen edge**: Automatic (just clear of the corners and the camera) or an extra margin of 0 to 10 mm on top of that. The old setting was an absolute 0 to 15 mm margin; a saved value is kept, capped at 10 mm.
- Version 0.2.3, versionCode 9.

### Added

- **Camera at the start of a turn** (touch only, `--turn-centre`, `--turn-zoom`; Options, Display): at the start of a turn, and when the switcher moves to another hedgehog, the camera glides to the active hedgehog (a real centre, within the map's camera limits) and zooms in to 140 % of the normal zoom (110 to 200 % in the options). Only once per turn start: a pinch or a drag during the turn is left alone, and a view you have pinched closer than the turn start zoom is not changed. It only touches the camera (`WorldDx`, `WorldDy`, `ZoomValue`), which the simulation and the turn checksums never read (checked in the source: `FollowGear`, the world offset and the zoom are read by rendering, input mapping and the camera only), and it runs for remote turns too.
- Smoke test: the problem box (an error of an older version is not shown, a current one is short, is hidden with the keyboard open, and the x clears it for good) and Options, Display with the new settings.
- Host test: the scenario `switch`, and `[Touch]` log lines for released controls, taps on jump, weapon chest and pause, the active hedgehog and the switch button.

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
