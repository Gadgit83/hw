# Hedgewars for Android (native)

A native Android build of the Hedgewars engine, with no WebView and no WebAssembly. The Pascal engine is transpiled to C with the upstream `pas2c` tool, compiled with the NDK's clang and run on SDL2. A Java setup screen starts a local hotseat game with two to eight teams, humans or computers, played with the engine's own touch interface.

This directory is `project_files/Android-native` of https://github.com/Gadgit83/hw, branch `android-master` (upstream Hedgewars master plus the Android changes to the engine). The app is built from the engine in the same checkout, so a change to the engine and the change to the app that needs it travel in one commit. The release builds are named **Hedge Party** (application id `party.hedge.host`); both name and id are build properties, see [App identity](#app-identity). The party mode that the name stands for (a host phone, controller phones, a TV) lives in https://github.com/Gadgit83/hedgewars-party; this app is its host side and, for now, plays a game on the phone. `docs/network.md` records what Hedgewars already has for network play.

Contents: [User guide](#user-guide), [Troubleshooting](#troubleshooting), [Architecture](#architecture), [Build guide](#build-guide), [Verification](#verification-done), [Source layout](#source-layout), [Licence and source offer](#licence-and-source-offer).

## User guide

### Install

```
adb install -r project_files/Android-native/app/build/outputs/apk/debug/app-debug.apk
```

or copy the APK to the phone and open it (allow installs from that source when asked). Minimum Android 7.0 (API 24), landscape only, OpenGL ES 1.1 (the engine's mobile renderer; a driver that no longer provides it would fail to start the game). The debug APK is signed with the debug key and is for sideloading only. It is about 100 MB because the game data is inside; see [Size](#size).

On the first start the game data is unpacked to the phone's storage (a percentage is shown and Start is disabled until it is done). That needs about 150 MB free. After that the app starts at once.

### The setup screen

One row along the top with the title, three tabs (Teams, Map, Rules) and a small Options button, and a bar along the bottom that always shows what will be played ("4 teams, 16 hedgehogs ... Default"), the data state and the Start game button. Everything you change is remembered for the next launch, including which tab you were on.

**Teams**

| Control | What it does |
|---|---|
| Add team, the cross on a team | Two to eight teams |
| Randomise (beside the Teams heading) | New random names, hats, fort, grave and voice for every team (keeps colour, player and hedgehog count) |
| Colour square | The engine's nine team colours. Teams with the same colour play on the same side (a clan) and take turns together |
| Name field | Team name (up to 32 characters; characters that cannot be in a file name are dropped) |
| Player button | Human, or CPU 1 (easiest) to CPU 5 (hardest); the list has the full names. A computer team plays by itself when its turn comes |
| Hogs stepper | One to eight hedgehogs per team (tap the number to type one) |
| Edit | A page for one team: name, player, colour, fort, grave and voice pack, and for each hedgehog a hat (pick from the whole hat list with pictures), a name, and a button for a new random name that suits the hat |

New teams get a random themed name and hats the way the desktop game does it (`Names/types.ini` and the name lists in `Data/Names`). Fort, grave, hat, theme and map lists are read from the unpacked game data, so they show exactly what is installed.

**Map**

| Map type | Options |
|---|---|
| Random island | Size filter (any, small, medium, large, cavern, wacky), complexity |
| Random maze | Style (small, medium, large tunnels or islands), complexity |
| Random perlin | Complexity |
| Forts | Distance between forts (teams are divided automatically) |
| Wave function collapse | Size filter, complexity |
| Image map | One of the maps in `Data/Maps`, with a preview and the number of hedgehogs it was made for |

Theme: Random, or pick one with its icon. Seed: leave empty for a new random map every game, or type any text; the same seed with the same map settings gives the same map. Hand-drawn maps and missions are not offered.

**Rules**

- Game style: Normal, or one of the Lua game styles from `Data/Scripts/Multiplayer` (Capture the Flag, Highlander, Tunnels, Racer and so on). A style may fix the scheme and the weapons (as in the desktop game); the pickers are then greyed out and say so.
- Scheme: the 16 built-in schemes of the desktop game (Default, Pro Mode, Shoppa, Clean Slate, Minefield, Barrel Mayhem, Tunnel Hogs, Timeless, Thinking with Portals, King Mode, Mutant, Construction Mode, The Specialists, Space Invasion, HedgeEditor, Racer).
- Weapon set: the 13 built-in sets (Default, Crazy, Pro Mode, Shoppa, Clean Slate, Minefield, Thinking with Portals, One of Everything, Highlander, Balanced Random Weapon, Construction Mode, Shoppa Pro, HedgeEditor). With "Pick the weapons that share the scheme's name" on (the same default as the desktop game), choosing a scheme also chooses the weapon set of the same name when there is one.
- Key values (all editable, hold the plus or minus to repeat, tap the number to type one): turn time (up to "Unlimited" at 9999 seconds), starting health, sudden death, water rise, mines, mine timer, crate drops, health crate chance, explosive barrels.
- More rules (button): damage modifier, health in a crate, health lost in sudden death, rope length, get away time, dud mines, air mines, sentries, world edge, and the 25 rule switches (switch hedgehog, divide teams, indestructible land, border, low gravity, laser sight, invulnerable, reset health, vampirism, karma, artillery, random order, king, place hedgehogs, shared ammo, no girders, no land objects, AI survival, unlimited attacks, reset weapons, ammo per hedgehog, no wind, more wind, tag team, bottom border).

Editing a number or a switch makes the scheme "changed from ..."; picking a scheme again resets all of them.

### Options

The Options button opens three tabs. Changes apply to the next game you start.

| Tab | Setting | Notes |
|---|---|---|
| Sound | Master volume, sound effects, music | Effects and music are a share of the master volume (engine options `--volume`, `--effects-volume`, `--music-volume`) |
| Sound | Mute, hedgehog voices | Mute passes `--nosound --nomusic`; voices off passes `--no-voices` |
| Display and controls | Buttons and text on screen; Weapon menu | Automatic (about 8 mm per control from the real screen density, with the value for this screen shown) or a fixed percentage (`--ui-scale`, `--menu-scale`) |
| Display and controls | Clear space at the edge | Automatic (from the rounded corners) or 0 to 15 mm. The camera cut-out and the navigation bar are avoided whatever you choose (`--inset-*`) |
| Display and controls | Effects quality | Six levels as on the desktop, best to lowest (`--raw-quality`) |
| Display and controls | Vibrate, frames per second, name tags | Vibration is a short buzz on big explosions; frames per second is `--showfps`; tags are `--no-teamtag`, `--no-hogtag`, `--no-healthtag` |
| Advanced | Share log | Builds one report and offers it to mail and messaging apps |
| Advanced | Reset game data | Unpacks the game data again (setup and options are kept) |
| Advanced | Reset setup and options | Back to the defaults |
| Advanced | About | App version, engine commit, app source commit, device |
| Advanced | Licence and source | Links to the corresponding source, the licence text and the credits |

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

Losing focus (home button, notification shade, phone call) pauses the game and getting focus back resumes it. A pause you chose with the button stays until you tap. Computer teams need no input: the game plays their turn by itself.

### Known limitations

- Hotseat only: no lobby, network play or controller phones yet. Everything is on one phone.
- Not offered: hand-drawn maps, mission and campaign maps, training, editing individual weapon counts (the 13 built-in weapon sets are), custom team flags, saved teams on disk (the setup is remembered as one whole).
- English only.
- HUD text and sprites are drawn larger by scaling the engine's artwork, so they are a little soft on very dense screens. The names and health tags above the hedgehogs are part of the world and keep their size (pinch to zoom in).
- Rendering uses OpenGL ES 1.1 as in the engine's mobile code path. Performance on low end devices is unmeasured.
- Effects and music are two levels relative to the master volume; the engine has no separate hardware volume for them.
- The first start unpacks the data, and the data is stored twice (APK and internal storage).
- Debug signed APK only. A release build needs a keystore (see [Releases](#releases)).
- Not yet checked on a device: see [Verification](#verification-done).

## Troubleshooting

**The game stops, shows a black screen or goes back to the setup screen.** A red box appears on the setup screen with the reason the engine gave, if it gave one, and a Share last log button. The same button is in Options, Advanced, Share log. It builds one text report (app and engine versions, device, options, the exact game setup that was sent to the engine, the engine log, the game process log, any native crash record from Android and the system log for the app) and opens the share sheet. Send it by mail or a messaging app.

**"Game data is missing" or sprites and sounds are absent.** Options, Advanced, Reset game data. The data is unpacked again the next time the setup screen is shown.

**Not enough free storage.** The first start needs about 150 MB free on the phone's internal storage; free some and tap Retry.

**The game runs slowly.** Options, Display and controls: lower the effects quality, or check the frames per second counter. Fewer hedgehogs and a smaller map also help; maps with many hedgehogs on 4096 by 2048 land (perlin) are the heaviest.

**The buttons are too small, too large or too close to the corner.** Options, Display and controls: turn off Automatic and set the size or the edge margin yourself.

**A team of computers does nothing.** They play after the map has loaded, when their turn comes. If a game was left running for a long time, the log (Share log) shows lines such as `engine command 'A' (attack start)` for every attack, jump and turn end that the computer made.

**Adb users:** the log tags are `HWNative` (launcher and app) and `HW_Engine` (the engine's own output). `adb logcat -s HWNative HW_Engine`.

## Architecture

```
MainActivity (Java)         setup screen: Teams / Map / Rules, Options, Share log
   TeamEditActivity         one team in detail
   OptionsActivity          sound, display and controls, advanced and about
   writes files/game.ipc    one engine message per line (GameConfig.java)
   |
GameActivity (SDLActivity)  loads libSDL2.so, libmain.so, runs SDL_main on its own thread, in its own process
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

SDL2_net is linked only because the engine uses it for its local IPC socket to the launcher; the app opens no other network connection.

### How a game starts

The engine is a client of a local IPC socket. It connects, sends `C` to ask for the game configuration, and the frontend replies with length prefixed text messages (`TL`, `etheme ...`, `eseed ...`, `e$turntime ...`, `eaddteam ...`, `eaddhh ...`, and so on). `hp_ipc.c` serves that socket from a thread of the same process and answers with the lines of `files/game.ipc`.

Seam for the hub: anything that can write a `game.ipc` (or later feed the same lines over the network) can start a game, with any number of teams, a scheme, weapon sets and a map. `GameConfig.java` is the only place that knows the message format.

### The game setup and how it becomes engine messages

`SetupModel` holds what the setup screen edits (teams and hedgehogs, map, theme, seed, style, scheme values, weapon set) and saves itself as text (`java.util.Properties`) in the preferences. `GameConfig.build` turns it into the lines, in the order the desktop frontend sends them:

| Order | Lines | Source in the Qt frontend |
|---|---|---|
| 1 | `TL` | `HWGame::commonConfig` (local game) |
| 2 | `escript Scripts/Multiplayer/<style>.lua` (style only) | `GameCFGWidget::getFullConfig` |
| 3 | `emap <name>` (image maps only), `etheme`, `eseed` | `getFullConfig` |
| 4 | `e$gmflags`, `e$damagepct`, `e$turntime` (seconds times 1000), `e$inithealth`, `e$sd_turns`, `e$casefreq`, `e$minestime` (times 1000), `e$minesnum`, `e$minedudpct`, `e$explosives`, `e$airmines`, `e$sentries`, `e$healthprob`, `e$hcaseamount`, `e$waterrise`, `e$healthdec`, `e$ropepct`, `e$getawaytime`, `e$worldedge` | `getFullConfig`, scheme entries 26 to 43 and the flag bits of entries 1 to 25 |
| 5 | `e$template_filter`, `e$feature_size`, `e$mapgen`, `e$scriptparam`, then `e$maze_size` for maze and perlin | `getFullConfig` |
| 6 | per team: `eammloadt`, `eammprob`, `eammdelay`, `eammreinf`, `eammstore` (unless per-hedgehog ammo without shared ammo), `eaddteam team<N> <colour> <name>`, `egrave`, `efort`, `evoicepack`, `eflag`, then per hedgehog `eaddhh <level> <health> <name>` and `ehat <hat>` | `HWGame::commonConfig`, `HWTeam::teamGameConfig` |

`e$mapgen` values are the engine's: 0 random island, 1 maze, 2 perlin, 4 forts, 5 wave function collapse, and 6 for an image map (with `emap`). The computer level in `eaddhh` is the engine's difficulty: 0 human, 1 the hardest to 5 the easiest; the screen shows the desktop game's levels, where level 5 is the hardest, so engine level = 6 minus level.

`SchemeData.java` (the 16 schemes and 13 weapon sets) is generated from the Qt sources by `scripts/gen-schemes.py`, not typed in. `scripts/config-test.sh` compares it, the flag bits, the `e$` lines and the team lines with the Qt frontend's source and the engine's registered commands on every run (see [Verification](#verification-done)).

### Engine options from the options menu

`GameActivity.engineOptions()` (called from `hp_main.c` through JNI just before the engine starts) returns the command line options for the saved options (`Options.toEngineArgs()`), and `GameActivity.engineDisplayInfo()` returns the HUD and menu scale and the insets. The options live in the app's preferences; the game process is started fresh for every game and reads them then.

Three of the options needed engine changes (commits on `android-master`): `--effects-volume` and `--music-volume` (percent of `--volume`; defaults 100 and 50, the engine's old fixed ratio), `--no-voices`, and a `DeviceRumble` call at the three places that already rumbled on iOS, which reaches `GameActivity.rumble()` through `hw_device_rumble` in `hp_main.c` and vibrates for 40 ms (throttled to one buzz per 150 ms).

### Size and safe area

`GameActivity.engineDisplayInfo()` (see `DisplayCalc.java`) measures the display and passes it to the engine as options (`--ui-scale`, `--menu-scale` in percent, `--inset-left/top/right/bottom` in pixels, see `hp_main.c`). Automatic scale aims for about 8 mm per control and per weapon cell from the real screen density, and the insets come from the display cut-out and the rounded corners (`WindowInsets.getRoundedCorner`, API 31+; otherwise 3.5 % of the screen height). The options menu can override the scales and the edge margin. The engine lays out and hit tests the HUD in those scaled units, so a screen at 160 dpi or less keeps the desktop layout. The game window itself fills the screen including behind the camera cut-out (`shortEdges`).

The weapon menu is drawn at its own scale and its touch cursor lives in the same menu units (the menu is laid out in `screen / cMenuScale`). The menu texture is enlarged by the next whole factor with nearest neighbour copies and drawn at the remaining fraction, so the 32 px pixel art icons stay crisp at a fractional scale without making any other HUD texture blocky. Choosing a weapon writes `[Touch] weapon menu select: slot S pos P ammotype N` to the engine log, which the host tests use as proof.

Orientation: all activities are `sensorLandscape`. SDL additionally asks Android for an orientation when the engine creates its window (with no hint it requests every orientation, which overrode the manifest and let the game open in portrait), so `GameActivity` overrides `setOrientationBis` and `hp_main.c` sets `SDL_HINT_ORIENTATIONS` to landscape.

The setup screens draw edge to edge and pad their content by the display cut-out, the system bars and the keyboard. While the keyboard is open the title and tabs make room, and the Start bar stays visible above the keyboard.

### Game data

The data from `share/hedgewars/Data` is trimmed by `scripts/stage-assets.sh` and packaged as APK assets. The engine uses PhysFS on real files, so `DataInstaller` copies the assets to `files/Data` on first start (a progress percentage is shown). Trade-off: the data exists twice on the device (about 110 MB extra) and the first start takes a few seconds. Mounting straight from the APK would need a custom PhysFS archiver and was left out for robustness.

Trimmed from the data: the 17 MB CJK font, other languages than English, single player missions, preview images, Qt frontend files, and most music (`HP_MUSIC=lite|full|none`, default lite keeps about nine tracks, themes without a track play nothing or the fallback). The mobile-only touch button artwork is taken from the old Android port's assets (`project_files/Android-build`), only for files the desktop data lacks. The setup screen offers what is staged: 34 themes, 299 hats, 41 graves, 17 forts, 15 voice packs, 44 image maps and 21 game styles at the time of writing.

## Build guide

### Prerequisites

- Linux x86_64 with JDK 17 or newer (21 tested), `git`, `rsync`, `cmake`, `ninja-build`
- GHC for pas2c: `apt-get install ghc` (9.4.7 tested)
- Rust with the Android targets: `rustup target add aarch64-linux-android x86_64-linux-android`
- Android SDK (`ANDROID_HOME`, detected in `~/Android/Sdk`, `~/android-sdk`) with `platforms;android-34`, `ndk;27.2.12479018`, `cmake;3.22.1`, build tools
- Network access to github.com, Maven (Google and Central), the Gradle distribution host and crates.io (not needed once the Gradle and cargo caches are filled: `./gradlew --offline` works from a warm cache)
- For the host tests: `clang`, `libclang-rt-dev` (AddressSanitizer), `xvfb`, `imagemagick`, `libsdl2-dev`, `libsdl2-image-dev`, `libsdl2-mixer-dev`, `libsdl2-ttf-dev`, `libsdl2-net-dev`, `bc`

Gradle itself is fetched by the committed wrapper (8.14.3, Android Gradle Plugin 8.7.3). The app uses only plain Android framework classes; AndroidX is switched on in `gradle.properties` only because Robolectric (unit tests) needs it. Nothing extra is packaged into the APK.

### From a fresh checkout to an APK

```
git clone -b android-master https://github.com/Gadgit83/hw.git
cd hw/project_files/Android-native
scripts/build-apk.sh                 # arm64-v8a and x86_64
ABIS=arm64-v8a scripts/build-apk.sh  # phone only, smaller APK
HP_PROFILE=test scripts/build-apk.sh  # the small test build, see "Build profiles"
```

It runs these steps, which can also be run on their own (all paths below are relative to `project_files/Android-native`; the engine is the repository root, `../..`):

1. `fetch-deps.sh` clones the SDL2 family at the tags pinned in `versions.env` into `third_party/`. Everything is a `git clone`, no archive downloads. To share one cache between several checkouts set `THIRD_PARTY=/some/dir` (the scripts and Gradle both honour it); clones that are already there are used as they are.
2. `gen-engine.sh` builds pas2c with GHC and transpiles the engine in this checkout to `build/engine-c`.
3. `build-rust.sh` builds `libhwengine_future.so` with cargo for each ABI (the Rust workspace is synced with `rsync --delete` first, so stale files from an earlier engine revision cannot linger).
4. `stage-assets.sh` stages the trimmed game data in `build/assets`.
5. `ui-smoke-test.sh` runs the launch smoke test (see [UI smoke test](#ui-smoke-test)); a failure stops the build. `SKIP_UI_SMOKE=1 scripts/build-apk.sh` skips it (for working on the build itself, not for releases).
6. `./gradlew assembleDebug` compiles the native code through CMake and packages the APK. When `build/` is already populated it can be run directly from this directory (`-Pabis=arm64-v8a` limits the ABIs).

Output: `app/build/outputs/apk/debug/app-debug.apk`.

#### App identity

The defaults are the Hedge Party release (`party.hedge.host`, so an installed app keeps updating). To build the same sources under another name and id:

```
./gradlew -PappId=org.example.hedgewars -PappName=Hedgewars -PsourceUrl=https://example.org/source assembleDebug
```

`appName` is the launcher label and the name shown inside the app and in the log report, `sourceUrl` is the link under Options, Advanced, Licence and source. Two builds with different ids install side by side.

#### Build profiles

`HP_PROFILE=full` (the default) is the normal app. `HP_PROFILE=test` builds a small APK for automated tests on a phone and the TV viewer, small enough to keep in git (GitHub refuses files over 100 MiB). The scripts read `HP_PROFILE` from the environment and pass it to Gradle as `-Pprofile=test` (Gradle also reads `HP_PROFILE` itself, so `./gradlew assembleDebug` with `HP_PROFILE=test` set works when `build/` was staged for it). The variable is not called `PROFILE`: the scripts stop with a message if `PROFILE` is set and `HP_PROFILE` is not. `stage-assets.sh` writes the profile it staged for to `build/assets/Data/.profile`, and the Gradle build stops with a message when that does not match its profile, so a test set cannot end up in a full APK or the reverse. Use a separate `BUILD_DIR` per profile if you build both, or stage again after switching: `stage-assets.sh` writes `build/assets` for one profile at a time.

| | full | test |
|---|---|---|
| Application id | `party.hedge.host` | `party.hedge.host.test` (installs next to the full app; the log FileProvider authority `${applicationId}.logs` follows it) |
| Launcher label | Hedge Party | Hedge Party (test) |
| `versionName` | 0.2.2 | 0.2.2-test (same `versionCode`) |
| Marker | none | red "TEST BUILD" badge next to the title on the setup screen, and on Options, Advanced, About (with a Profile line) |
| ABIs | arm64-v8a, x86_64 | arm64-v8a only (`ABIS=...` still overrides) |
| Signing | debug key | the same debug key |
| Game data | as described under [Game data](#game-data) | the reduced set below |
| APK | see [Size](#size) | about 14 MB |

What the test profile keeps of `share/hedgewars/Data` (everything else needed to play is the same as in the full profile):

| Folder | Kept | Dropped |
|---|---|---|
| Themes | Nature, Snow, Hell (offered), and EarthRise and Cave without their icon, which hides them from the theme picker and from Random: the game styles Space_Invasion and TechRacer set these themselves and the engine stops without them | the other 29 |
| Forts | Earth (the default team fort) | the other 16 |
| Sounds/voices | Default | the other 14 voice packs; Sounds/*.ogg effects are all kept |
| Graphics/Hats | NoHat, royalguard, policecap, clown-copper, cyborg1, InfernalHorns, Glasses, pinksunhat, anzac (the hats most themed team groups of `Names/types.ini` use), crown, Skull, angel, Sniper (put on by game modes), and `Reserved/` (the engine loads `Reserved/chef` itself and stops without it) | the other 286 hats |
| Graphics/Flags | hedgewars (the default team flag), cpu and cpu_plain (computer teams) | the other 290 flags |
| Maps | nothing: the random island, maze, perlin, wave function collapse and forts map types read no files from `Data/Maps` (they are generated from `map_templates.yaml`, `maze_templates.yaml`, `wfc_templates.toml`, `Tiles` and the theme) | all 44 image maps |
| Music | nothing | all |
| Graphics, Fonts (DejaVuSans-Bold), Scripts (all 21 game styles), Shaders, Tiles, Names, Locale (en), touch button artwork, graves | kept | `.svg`, `.xcf` and `.sifz` sources |

Because the data is staged by a whitelist, the setup screen offers only what is on disk: three themes (two more are installed but hidden), one fort, one voice pack, nine plus four hats, no "Image map" map type. A random theme, fort, voice, grave or hat is always picked from the installed ones, and `SetupModel.repair()` replaces a saved choice that is not installed (for example a deleted hat or theme). `scripts/config-test.sh` checks this, and on the test data that the offered themes are exactly Nature, Snow and Hell (300 random setups, every theme, fort, voice pack, grave, flag and hat that a generated game names exists in the staged data, and a setup that names missing content is repaired).

**Match with the TV viewer.** The TV viewer of https://github.com/Gadgit83/hedgewars-party (`tv/viewer/tools/package-data.sh`, checked at commit 74977b5) packages all 34 themes (Nature in `hwengine.data`, the others as loose files), every hat, flag and grave, the top level `Graphics` PNGs, `Hedgehog`, `AmmoMenu` and `SuddenDeath`, `Shaders`, `Tiles`, `Names`, `misc`, `Scripts`, one font, the English locale and the three template files. It ships no `Sounds`, `Music`, `Maps` or `Forts` (it runs the engine with `--nosound --nomusic`). The intersection with this profile, and so what a phone and the TV can both play, is: themes Nature, Snow and Hell; all of the kept hats, the hedgewars flag and all graves; random island, maze, perlin and wave function collapse maps; every game style. The Forts map type needs `Data/Forts` on the TV, which the TV package does not contain yet, so a Forts game cannot be watched on the TV until it does (the test profile still offers it, as it offers it in the full app). Image maps are not offered by the test profile because the TV has none. If the TV package gains or loses data, change the lists at the top of the test block in `stage-assets.sh`.

#### Releases

A release is a tag on `android-master` in https://github.com/Gadgit83/hw with the APK attached; the APK is the one built as above from that tag (`-PengineStale=fail`, see below). Until a release keystore exists the APK is signed with the builder's debug key, so updates over an installed build only work when it was signed with the same key.

A release style build, as used for the 0.2.0 APK:

```
export ANDROID_HOME=~/android-sdk
cd project_files/Android-native
./gradlew --offline clean
./gradlew --offline -PengineStale=fail -Pabis=arm64-v8a,x86_64 assembleDebug
```

Build from clean (`./gradlew clean`, or delete `app/build/outputs/apk` and the packaging intermediates): Android Gradle Plugin can package incrementally in place and leave unreferenced holes in the APK. Check the result with a zip walk (entry data should account for nearly the whole file) and with `apksigner verify --print-certs`.

### Stale engine C guard

`gen-engine.sh` writes `build/engine-c/.engine-stamp` with the commit of this checkout and a key hash of everything pas2c reads: the committed trees of `hedgewars/` and `tools/pas2c`, the uncommitted `git diff`, untracked files there, `gen-engine.sh` itself and `EXTRA_DEFS`. Changes elsewhere in the repository (the Java app, the Qt frontend) do not count, as pas2c does not read them. Every Gradle build runs the `checkEngineC` task before `preBuild`, which calls `gen-engine.sh --ensure`: if the stamp does not match the current engine checkout (or is missing) the C is regenerated (needs GHC), otherwise nothing happens. `./gradlew -PengineStale=fail assembleDebug` fails with a `STALE ENGINE C` message instead of regenerating. CMake repeats the check at configure time (`gen-engine.sh --check`, skip with `-DHP_SKIP_ENGINE_CHECK=ON`), the stamp is a configure dependency and the generated `.c` files are globbed with `CONFIGURE_DEPENDS`, so a regeneration is rebuilt by ninja. `gen-engine.sh --check` can also be run by hand. The host test output (`engine-c-host`) is generated separately and always regenerated by `host-test.sh`.

The guard needs a git checkout: if the repository root (or `HW_ENGINE_DIR`, when you point the build at another checkout) is not the root of a git repository, as with an unpacked source archive, `gen-engine.sh` (any mode) stops with `ENGINE CHECKOUT IS NOT A GIT REPOSITORY` and exit status 3 instead of computing a meaningless key, and a key that cannot be computed never counts as a match. Build from a clone.

Deliberate escape hatches, for working on the build itself and not for releases: `./gradlew -x checkEngineC ...` skips the Gradle check, and the CMake option `-DHP_SKIP_ENGINE_CHECK=ON` (a CMake cache variable, for example through the module's `externalNativeBuild` arguments, not an environment variable) skips the CMake one. Both package whatever C is in `build/engine-c`, stale or not. Release builds use `-PengineStale=fail` and neither hatch.

The commit of a build is written into `BuildConfig` (as the engine commit and the app source commit, which are the same repository) and shown in Options, Advanced, About ("+local changes" is added when the checkout had uncommitted changes).

### UI smoke test

`scripts/ui-smoke-test.sh` (about a minute, JVM only) opens every screen under Robolectric 4.14.1 on Android 14 and 15 (SDK 34 and 35) with a Fairphone 4 sized landscape display (`w915dp-h422dp-land-409dpi`, camera cut-out on the left) and fails if anything throws. It is `app/src/test/java/org/hedgewars/android/LaunchSmokeTest.java` and covers: the setup screen (all three tabs) with the game data installed and a setup saved by 0.2.0, and with default preferences; the setup screen on the very first launch with no game data, while it is unpacked; the options screen (all three tabs); and the team page for two teams. It exists because 0.2.1 crashed on every launch (`Window.getInsetsController()` called from `onCreate` before `setContentView`, a `NullPointerException` on Android 11 and later) and no test had ever opened a screen. `build-apk.sh` runs it before packaging and stops if it fails.

Prerequisites: the staged game data (`stage-assets.sh`, used as the "installed" data with a `.installed` marker that holds the stamp `DataInstaller` compares, as an empty marker never matches) and the Android jars Robolectric runs against. Robolectric normally downloads those itself from repo1.maven.org, which answers HTTP 429 to many networks, so it runs offline here (`robolectric.offline`) from a cache folder, `ROBOLECTRIC_DEPS` (default `~/.cache/hedgeparty/robolectric`), that `scripts/fetch-robolectric.sh` fills from Google's copy of Maven Central with repo1 as the fallback (`ROBOLECTRIC_MIRRORS` overrides the list), checking each jar against its published SHA-1. The script is idempotent and `ui-smoke-test.sh` calls it. Robolectric's own library jars come from Maven through Gradle; `HP_MAVEN_MIRROR` (default Google's copy) is tried first for them, and `GRADLE_OFFLINE=1` adds `--offline` once the Gradle cache is warm. `SKIP_UI_SMOKE=1` makes `build-apk.sh` skip the test.

`ScreenshotTest` (pictures of the screens, `./gradlew -Pscreenshots testDebugUnitTest --tests '*ScreenshotTest'`, PNGs in `build/screenshots`) is skipped unless `-Pscreenshots` is given.

### Host tests

No device is needed for these.

`scripts/config-test.sh` (a few seconds, JVM only) checks the setup model and the game configuration against the desktop frontend's source and the engine's registered commands: the 16 scheme presets, the 13 weapon sets, the 25 flag bits, the 18 `e$` value lines and their factors, the nine colours, the shape of every `eaddteam`, `eaddhh` and `ehat` line, the order inside a team block, the `eammstore` rule, 8 teams of 8 hedgehogs with every computer level, every map type, every game style, saving and loading, and the options to engine option mapping (including that the engine knows each option it is given).

`scripts/host-test.sh [seconds]` builds the same engine and launcher for Linux with desktop OpenGL, starts a game from the config that `GameConfig.java` produces, and runs it headless under Xvfb for a while, saving screenshots. Run `pkill Xvfb` before it if an earlier run was interrupted. Environment variables:

| Variable | Meaning |
|---|---|
| `HP_TEST_SCENARIO` | What game to start (see `native/host-test/GenConfig.java`): `two` (default), `ai` (two computer teams that play alone), `ai:WEAPONSET` (the same with that weapon set, e.g. `ai:Crazy`), `humanvsai`, `mixed8x8` (8 teams of 8 hedgehogs, humans and computers of every level), `scheme:NAME` (e.g. `scheme:Pro Mode`), `map:KIND` (`generated`, `maze`, `perlin`, `forts`, `wfc`, `image:NAME`), `style:NAME` |
| `HP_TEST_SEED` | A fixed map seed |
| `ASAN=ON` | Build with AddressSanitizer (needs `libclang-rt-dev`), which reports the out-of-bounds accesses that the phone's MTE and hardened allocator turn into crashes |
| `HP_TEST_RESTORE=1,3,6,14` | Post `SDL_WINDOWEVENT_RESTORED` at those seconds; the phone sends it right after start-up and after every pause, and the engine answers with `fullscr`, which reloads the video state, the chat lines and every texture |
| `HP_TEST_SCRIPT` | Timed input injection (touch, focus lost or gained and so on), see `native/host-test/hp_host_main.c` |
| `HP_TEST_ARGS` | Extra engine options, e.g. `--ui-scale 240 --inset-left 80 --inset-top 40` |
| `HP_TEST_SHOTS` | Seconds after the start at which extra screenshots `build/host-test/shot_<n>.png` are taken |
| `HP_TEST_SIZE` | Window size, e.g. `2340x1080` (default 1280x720) |
| `HP_TEST_DISPLAY` | Xvfb display number (default 77), so two runs can go side by side |
| `HP_TEST_SOUND=1` | Run with sound on, using the SDL dummy audio driver, instead of off |

The summary at the end shows whether the engine was still running, the IPC lines and, for computer players, the `engine command` lines that the launcher logs for every attack, jump, weapon choice and turn end the engine reports, plus a count per command letter.

## Verification done

- pas2c transpiles the whole engine (upstream master plus the Android commits) and clang compiles it for arm64-v8a and x86_64.
- `libmain.so` is linked with `-Wl,--no-undefined`; a separate `llvm-nm` check finds every undefined symbol in the NEEDED libraries.
- `scripts/config-test.sh`: see above, no failures.
- `scripts/host-test.sh` with `ASAN=ON`: games from the generated config start, generate the map, load the sprites and render the touch interface and teams, for 8 teams of 8 hedgehogs with mixed human and computer teams, for scheme, map type and game style scenarios, with 0 AddressSanitizer reports and no crash markers.
- Computer players: with two computer teams and no input, the engine log shows the AI choosing weapons, moving, jumping, attacking and ending turns by itself (the Rust AI library and `uAI.pas` work under pas2c).
- The options reach the engine: a computer-only game under AddressSanitizer with sound on (SDL dummy audio) and `--volume 100 --effects-volume 50 --music-volume 30 --no-voices --raw-quality 3 --showfps --no-teamtag --no-hogtag --no-healthtag` starts, shows the frames per second counter and no name tags, with no sanitizer report. `config-test.sh` also checks that the engine's option table (`ArgParsers.pas`) has an entry and a case for every option the app sends.
- Vibration: in a computer-only host test with a hand-edited loadout of only Skip, Watermelon and Hellish Handgrenade, the engine calls `hw_device_rumble` for each big explosion (the host test launcher logs `[hosttest] rumble 40 ms`). The Java side of it (`GameActivity.rumble`, the vibrator) is not checked.

Not verified: running on an Android device or emulator (the build machine has no KVM, so no x86_64 emulator could be started). The touch handling, safe area layout, scaled HUD and weapon menu, pause and focus handling were checked on the host with injected SDL touch and window events. The OpenGL ES 1.1 context on real drivers, audio, vibration, the Java display measurement, and the setup, team and options screens (their layout and touch behaviour) are only tested on the owner's phone. The setup screens were not rendered. The launch smoke test opens the setup, options and team screens under Robolectric (Android 14 and 15) and checks that nothing throws (see [UI smoke test](#ui-smoke-test)); that is not a look at the layout. An optional Robolectric screenshot test is in `app/src/test` (`./gradlew -Pscreenshots testDebugUnitTest --tests '*ScreenshotTest'` writes PNGs of the screens at 2340 x 1080 and 409 dpi to `build/screenshots`).

## Size

| Build | APK |
|---|---|
| test profile (arm64-v8a, reduced data, see [Build profiles](#build-profiles)) | about 14 MB (staged data 11 MB) |
| arm64-v8a + x86_64, lite music | about 98 MiB (102 MB) |
| arm64-v8a only | about 92 MiB (96 MB) |

Most of it is game data (112 MB staged, compressed little because the images and sound are already compressed). After the first start the app also holds about 112 MB of extracted data.

## Source layout

| Path | Purpose |
|---|---|
| `app/` | Gradle module: manifest, Java activities, game config builder, data installer |
| `app/.../MainActivity.java` | Setup screen (Teams, Map, Rules tabs, Start bar) |
| `app/.../TeamEditActivity.java`, `OptionsActivity.java` | One team in detail; options |
| `app/.../GameConfig.java`, `SetupModel.java`, `Rules.java`, `SchemeData.java`, `GameData.java`, `Options.java` | Pure Java (no Android classes): config builder, setup state and saving, scheme and weapon data (`SchemeData` is generated), lists read from the game data, options and their mapping to engine options |
| `app/.../Ui.java`, `Icons.java`, `Store.java`, `DisplayCalc.java`, `LogShare.java` | Framework-only view toolkit, pictures cut from the game's artwork, preferences, screen measuring, log sharing |
| `app/.../GameActivity.java`, `Diag.java`, `DataInstaller.java`, `LogProvider.java` | The SDL activity, diagnostics, first-run data unpacking, log provider |
| `native/CMakeLists.txt` | Builds SDL2 family, Lua, PhysFS, fpcrtl, the pas2c engine and `libmain.so` (used through Gradle `externalNativeBuild`) |
| `native/launcher/` | `SDL_main` and the in-process IPC server |
| `native/include/` | Header shims: `gles11.h` and `GL/gl.h` map the engine's GL includes to OpenGL ES 1.1 |
| `native/host-test/` | Desktop smoke test (`GenConfig.java` scenarios, `ConfigCheck.java`, the Linux launcher), not shipped |
| `scripts/` | Reproducible build steps, `gen-schemes.py`, `config-test.sh`, `host-test.sh` |
| `third_party/`, `build/` | Downloaded and generated, gitignored |

The engine changes (pas2c friendly constructs, the `PAS2C_ANDROID` define, GLES 1.1 context, SDL2_mixer binding fixes, two small changes that pas2c needs for current upstream, the touch HUD, the sound level options and the rumble hook) are ordinary commits on `android-master`, which is upstream Hedgewars master plus those commits and this directory. `git log master..android-master` lists them; `master` follows upstream. The branch `upstream-android` of the same repository holds the same work cut into a series that is meant for an upstream pull request (see its commit messages).

Because the Rust library now owns the `hwFloat` fixed-point maths, collision tests and gear checksums, the simulation is the same code as upstream's, which keeps replays and determinism in line with desktop Hedgewars. The pas2c C code calls it through ordinary function calls into `libhwengine_future.so`. These calls cannot be inlined across the library boundary: a microbenchmark on an x86_64 desktop measured about 3 ns per `hwFloat` operation (multiply plus add pair: 6.3 ns). Not measured: engine frame times on a phone.

## Licence and source offer

The engine is Hedgewars, GPL version 2 (art and sound GFDL, see `Data/COPYING` and `Data/CREDITS` inside the APK, also reachable from Options, Advanced). SDL2 and its satellite libraries are zlib licensed, stb_vorbis is public domain, FreeType is under the FreeType licence, PhysFS is zlib licensed, Lua is MIT licensed. The Java app, the launcher and the build scripts in this directory are part of the repository and under its licence, the GPL version 2 (`COPYING` in the repository root).

Because the APK ships GPL code, the corresponding source must be offered to everyone who receives it. For a Hedge Party APK the corresponding source is this repository at the commit shown under Options, Advanced, About: https://github.com/Gadgit83/hw, branch `android-master` (engine, the changes made for this port, the app, the launcher and the build scripts in one place). The link is in the app (Options, Advanced). The release app is named Hedge Party and does not use the Hedgewars name or logo; a build under another name is set with `-PappName` (see [App identity](#app-identity)), and anyone distributing a derivative must respect the same rule.
