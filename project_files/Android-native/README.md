# Hedge Party host (native Android)

A native Android build of the Hedgewars engine, with no WebView and no WebAssembly. The Pascal engine is transpiled to C with the upstream `pas2c` tool, compiled with the NDK's clang and run on SDL2. A small Java setup screen starts a local hotseat game with two to four teams, played with the engine's own touch interface.

This is the first step of the host app described in the top-level `README.md`. For now it only plays a game on the phone. The lobby, the hub for controller phones and the picture for the TV are not built, but the seams for them are marked below.

## Architecture

```
MainActivity (Java)         team count and names, unpacks game data on first run
   writes files/game.ipc    one engine message per line (GameConfig.java)
   |
GameActivity (SDLActivity)  loads libSDL2.so, libmain.so, runs SDL_main on its own thread
   |
libmain.so                  native/launcher/hp_main.c   SDL_main: starts the IPC server, runs the engine
                            native/launcher/hp_ipc.c    in-process stand-in for the Qt frontend
   |
engine (pas2c output)       hwengine_RunEngine(), connects to 127.0.0.1:<port> and asks for the config ('C')
   |-- fpcrtl               Pascal runtime in C (from upstream project_files/hwc/rtl)
   |-- PhysFS, Lua 5.1      bundled with the engine (misc/libphysfs, misc/liblua)
   |-- libhwengine_future   Rust library (hwFloat maths, collision, gear checksums, map generation, AI), built with cargo
   `-- SDL2, SDL2_image, SDL2_mixer (stb_vorbis), SDL2_ttf (FreeType), SDL2_net
```

Why pas2c rather than FreePascal cross compilation: `tools/pas2c` is a Haskell program that builds with the distribution's GHC and needs no Hackage packages. Its C output compiles with the NDK, so no FreePascal cross compiler for Android is needed (freepascal.org is not reachable from the build machine, and the old Hedgeroid port used that route).

### How a game starts

The engine is a client of a local IPC socket. It connects, sends `C` to ask for the game configuration, and the frontend replies with length prefixed text messages (`TL`, `etheme ...`, `eseed ...`, `e$turntime ...`, `eaddteam ...`, `eaddhh ...`, and so on, ending in `!`). `hp_ipc.c` serves that socket from a thread of the same process and answers with the lines of `files/game.ipc`.

Seam for the hub: anything that can write a `game.ipc` (or later feed the same lines over the network) can start a game, with any number of teams, a scheme, weapon sets and a map. `GameConfig.java` is the only place that knows the message format.

### Touch controls

The engine's own touch interface (`uTouch.pas`) is enabled through the `MOBILE` code paths. On a phone:

| Gesture | Action |
|---|---|
| Left / right arrow (bottom left) | Walk, hold to keep walking |
| Red button next to the arrows | Fire. Hold to charge the power, release to shoot |
| Round red button (bottom right) | Jump: tap for a forward jump, long press for a back jump |
| Drag on the aim marker (crosshair around the hedgehog) | Aim up and down |
| Weapon chest (bottom right), or tap your hedgehog | Open the weapon menu; tap a weapon to choose it, tap outside to close |
| Timer or bounce buttons (above the arrows, when the weapon uses them) | Set the fuse time or bounciness |
| One finger drag on empty map | Move the camera (the camera then stops following the hedgehog until the next turn) |
| Two finger pinch | Zoom |
| Pause button (top right, yellow II) | Pause. While paused, tap it or anywhere else to resume (the resuming tap is not also a click, and a finger that went down before the pause does not resume on lift); the map can still be dragged |
| Android Back key | Opens the engine's quit dialog (Escape) |

Losing focus (home button, notification shade, phone call) pauses the game and getting focus back resumes it. A pause you chose with the button stays until you tap.

Size and safe area: `GameActivity.engineDisplayInfo()` measures the display and passes it to the engine as options (`--ui-scale`, `--menu-scale` in percent, `--inset-left/top/right/bottom` in pixels, see `hp_main.c`). The scale aims for about 8 mm per control and per weapon cell from the real screen density, and the insets come from the display cut-out and the rounded corners (`WindowInsets.getRoundedCorner`, API 31+; otherwise 3.5 % of the screen height). The engine lays out and hit tests the HUD in those scaled units, so a screen at 160 dpi or less keeps the desktop layout. The game window itself fills the screen including behind the camera cut-out (`shortEdges`).

The weapon menu is drawn at its own scale and its touch cursor lives in the same menu units (the menu is laid out in `screen / cMenuScale`). The menu texture is enlarged by the next whole factor with nearest neighbour copies and drawn at the remaining fraction, so the 32 px pixel art icons stay crisp at a fractional scale without making any other HUD texture blocky. Choosing a weapon writes `[HP] weapon menu select: slot S pos P ammotype N` to the engine log, which the host tests use as proof.

Orientation: both activities are `sensorLandscape`. SDL additionally asks Android for an orientation when the engine creates its window (with no hint it requests every orientation, which overrode the manifest and let the game open in portrait), so `GameActivity` overrides `setOrientationBis` and `hp_main.c` sets `SDL_HINT_ORIENTATIONS` to landscape.

### Game data

The data from `share/hedgewars/Data` is trimmed by `scripts/stage-assets.sh` and packaged as APK assets. The engine uses PhysFS on real files, so `DataInstaller` copies the assets to `files/Data` on first start (a progress percentage is shown). Trade-off: the data exists twice on the device (about 110 MB extra) and the first start takes a few seconds. Mounting straight from the APK would need a custom PhysFS archiver and was left out for robustness.

Trimmed from the data: the 17 MB CJK font, other languages than English, single player missions, preview images, Qt frontend files, and most music (`HP_MUSIC=lite|full|none`, default lite keeps about nine tracks, themes without a track play nothing or the fallback). The mobile-only touch button artwork is taken from the old Android port's assets (`project_files/Android-build`), only for files the desktop data lacks.

## Source layout

| Path | Purpose |
|---|---|
| `app/` | Gradle module: manifest, Java activities, game config builder, data installer |
| `native/CMakeLists.txt` | Builds SDL2 family, Lua, PhysFS, fpcrtl, the pas2c engine and `libmain.so` (used through Gradle `externalNativeBuild`) |
| `native/launcher/` | `SDL_main` and the in-process IPC server |
| `native/include/` | Header shims: `gles11.h` and `GL/gl.h` map the engine's GL includes to OpenGL ES 1.1 |
| `native/host-test/` | Desktop smoke test, not shipped |
| `scripts/` | Reproducible build steps, see below |
| `third_party/`, `build/` | Downloaded and generated, gitignored |

The engine changes (pas2c friendly constructs, the `PAS2C_ANDROID` define, GLES 1.1 context, SDL2_mixer binding fixes, and two small changes that pas2c needs for current upstream) are commits on branch `android-master` of https://github.com/Gadgit83/hw, which is upstream master plus those commits. They are not patch files and no upstream code is committed here. The engine therefore tracks upstream master (see `docs/decisions.md`, D2). The older branch `android-legacy-1bbc396` of https://github.com/Gadgit83/hw (base `1bbc396d3`, the commit WebWars also uses) is kept as a fallback and is no longer used by the scripts by default. It can still be built by setting `HW_ENGINE_BRANCH=android-legacy-1bbc396`.

Because the Rust library now owns the `hwFloat` fixed-point maths, collision tests and gear checksums, the simulation is the same code as upstream's, which keeps replays and determinism in line with desktop Hedgewars. The pas2c C code calls it through ordinary function calls into `libhwengine_future.so`. These calls cannot be inlined across the library boundary: a microbenchmark on an x86_64 desktop measured about 3 ns per `hwFloat` operation (multiply plus add pair: 6.3 ns). Not measured: engine frame times on a phone.

## Prerequisites

- Linux x86_64 with JDK 17 or newer (21 tested), `git`, `rsync`, `cmake`, `ninja-build`
- GHC for pas2c: `apt-get install ghc` (9.4.7 tested)
- Rust with the Android targets: `rustup target add aarch64-linux-android x86_64-linux-android`
- Android SDK (`ANDROID_HOME`, detected in `~/Android/Sdk`, `~/android-sdk`) with `platforms;android-34`, `ndk;27.2.12479018`, `cmake;3.22.1`, build tools
- Network access to github.com, Maven (Google and Central), the Gradle distribution host and crates.io

Gradle itself is fetched by the committed wrapper (8.14.3, Android Gradle Plugin 8.7.3).

## Build

From a fresh checkout:

```
host/scripts/build-apk.sh                 # arm64-v8a and x86_64
ABIS=arm64-v8a host/scripts/build-apk.sh  # phone only, smaller APK
```

It runs these steps, which can also be run on their own:

1. `fetch-deps.sh` clones the engine fork (`HW_ENGINE_REPO`, branch `HW_ENGINE_BRANCH`, default `Gadgit83/hw` branch `android-master`, into `upstream/hw` or `HW_ENGINE_DIR`) and the SDL2 family at the tags pinned in `versions.env`, into `host/third_party`. Everything is a `git clone`, no archive downloads.
2. `gen-engine.sh` builds pas2c with GHC and transpiles the engine to `host/build/engine-c`.
3. `build-rust.sh` builds `libhwengine_future.so` with cargo for each ABI (the Rust workspace is synced with `rsync --delete` first, so stale files from an earlier engine revision cannot linger).
4. `stage-assets.sh` stages the trimmed game data in `host/build/assets`.
5. `./gradlew assembleDebug` compiles the native code through CMake and packages the APK. When `host/build` is already populated it can be run directly (`-Pabis=arm64-v8a` limits the ABIs).

Output: `host/app/build/outputs/apk/debug/app-debug.apk`.

### Stale engine C guard

`gen-engine.sh` writes `host/build/engine-c/.engine-stamp` with the engine commit and a key hash of everything pas2c reads: the committed trees of `hedgewars/` and `tools/pas2c`, the uncommitted `git diff`, untracked files there, `gen-engine.sh` itself and `EXTRA_DEFS`. Every Gradle build runs the `checkEngineC` task before `preBuild`, which calls `gen-engine.sh --ensure`: if the stamp does not match the current engine checkout (or is missing) the C is regenerated (needs GHC), otherwise nothing happens. `./gradlew -PengineStale=fail assembleDebug` fails with a `STALE ENGINE C` message instead of regenerating. CMake repeats the check at configure time (`gen-engine.sh --check`, skip with `-DHP_SKIP_ENGINE_CHECK=ON`), the stamp is a configure dependency and the generated `.c` files are globbed with `CONFIGURE_DEPENDS`, so a regeneration is rebuilt by ninja. `gen-engine.sh --check` can also be run by hand. The host test output (`engine-c-host`) is generated separately and always regenerated by `host-test.sh`.

The guard needs a git checkout of the engine: if `HW_ENGINE_DIR` is not the root of a git repository, `gen-engine.sh` (any mode) stops with `ENGINE CHECKOUT IS NOT A GIT REPOSITORY` and exit status 3 instead of computing a meaningless key, and a key that cannot be computed never counts as a match.

Deliberate escape hatches, for working on the build itself and not for releases: `./gradlew -x checkEngineC ...` skips the Gradle check, and the CMake option `-DHP_SKIP_ENGINE_CHECK=ON` (a CMake cache variable, for example through the module's `externalNativeBuild` arguments, not an environment variable) skips the CMake one. Both package whatever C is in `host/build/engine-c`, stale or not. Release builds use `-PengineStale=fail` and neither hatch.

For a release, also build from clean (`./gradlew clean`, or delete `host/app/build/outputs/apk` and the packaging intermediates): Android Gradle Plugin can package incrementally in place and leave unreferenced holes in the APK. Check the result with a zip walk (entry data should account for nearly the whole file).

## Install

```
adb install -r host/app/build/outputs/apk/debug/app-debug.apk
```

Minimum Android 7.0 (API 24), landscape, OpenGL ES 1.1 (the engine's mobile renderer; drivers that no longer provide it would fail to start the game). The debug APK is signed with the debug key and is for sideloading only.

## Size

| Build | APK |
|---|---|
| arm64-v8a + x86_64, lite music | about 98 MiB (102 MB) |
| arm64-v8a only | about 92 MiB (96 MB) |

Most of it is game data (112 MB staged, compressed little because the images and sound are already compressed). After the first start the app also holds about 112 MB of extracted data.

## Verification done

- pas2c transpiles the whole engine (upstream master `1e2216a` plus the Android commits) and clang compiles it for arm64-v8a and x86_64.
- `libmain.so` is linked with `-Wl,--no-undefined`; a separate `llvm-nm` check finds every undefined symbol in the NEEDED libraries.
- `scripts/host-test.sh` builds the same engine and launcher for Linux with desktop OpenGL, starts a two team game from the config produced by `GameConfig.java`, and runs it headless under Xvfb. The engine connects to the IPC server, receives the 60 config messages, generates a map, loads all sprites, and renders the touch interface and teams.
- `ASAN=ON scripts/host-test.sh 45` builds the same test with AddressSanitizer (needs `apt install libclang-rt-dev`), which reports the out-of-bounds accesses that the phone's MTE and hardened allocator turn into crashes. `HP_TEST_RESTORE=1,3,6,14` posts `SDL_WINDOWEVENT_RESTORED` at those seconds; the phone sends it right after start-up and after every pause, and the engine answers with `fullscr`, which reloads the video state, the chat lines and every texture (this reproduced the v0.1.1 crash in `PrettifySurfaceAlpha`).

Not verified: running on an Android device or emulator (the build machine has no KVM, so no x86_64 emulator could be started). The touch handling, safe area layout, scaled HUD and weapon menu, pause and focus handling were checked on the host with injected SDL touch and window events (`HP_TEST_SCRIPT`, `HP_TEST_ARGS`, `HP_TEST_SHOTS`, `HP_TEST_SIZE`, see `scripts/host-test.sh`). The OpenGL ES 1.1 context on real drivers, audio, the Java display measurement and the setup screen are only tested on the owner's phone.

## Known limitations

- Hotseat only: no lobby, network play, AI teams or scheme and weapon editing yet. The default scheme and weapon set are fixed in `GameConfig.java`.
- Fixed four hedgehogs per team, English only, random map and theme.
- HUD text and sprites are drawn larger by scaling the engine's artwork, so they are a little soft on very dense screens. The names and health tags above the hedgehogs are part of the world and keep their size (pinch to zoom in).
- Rendering uses OpenGL ES 1.1 as in the engine's mobile code path. Performance on low end devices is unmeasured.
- First start unpacks the data, and the data is stored twice (APK and internal storage).
- Debug signed APK only. A release build needs a keystore.

## Licence and source offer

The engine is Hedgewars, GPL version 2 (art and sound GFDL, see `Data/COPYING` and `Data/CREDITS` inside the APK). SDL2 and its satellite libraries are zlib licensed, stb_vorbis is public domain, FreeType is under the FreeType licence, PhysFS is zlib licensed, Lua is MIT licensed.

Because the APK ships GPL code, the corresponding source must be offered to everyone who receives it. The engine source, including the changes made for this port, is the `android-master` branch of https://github.com/Gadgit83/hw. The Android app, launcher and build scripts are in this repository. The app is named Hedge Party and does not use the Hedgewars name or logo.
