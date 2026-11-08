# Changelog

All notable changes to the native Android app (`project_files/Android-native`), released as Hedge Party. Versions are the `versionName` of the debug APK; `versionCode` is in brackets. The engine changes named below are commits on this branch, `android-master` of https://github.com/Gadgit83/hw. Until the app moved into this repository its history was kept in https://github.com/Gadgit83/hedgewars-party (folder `host/`); that history was carried over, so `git log -- project_files/Android-native` shows all of it.

Only some builds were put on a phone, so a version below is a build that was made, not necessarily one that was released. Releases are tags on `android-master` with the APK attached; there is none yet.

## Unreleased

### Changed

- The app now lives in the engine repository and is built from the engine in the same checkout; `fetch-deps.sh` no longer clones the engine. The Java package is `org.hedgewars.android`, the log tag `HWNative`. Application id, name and source link are build properties (`-PappId`, `-PappName`, `-PsourceUrl`); the defaults are unchanged (`party.hedge.host`, Hedge Party), so an installed 0.2.0 keeps updating. The licence page points at this repository only.
- The engine reports itself as `1.1.0` (was `1.1.0-party`) in its console output.

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
