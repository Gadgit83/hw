# Changelog

All notable changes to the native Android app (`project_files/Android-native`), released as Hedge Party. Versions are the `versionName` of the debug APK; `versionCode` is in brackets. The engine changes named below are commits on this branch, `android-master` of https://github.com/Gadgit83/hw. Until the app moved into this repository its history was kept in https://github.com/Gadgit83/hedgewars-party (folder `host/`); that history was carried over, so `git log -- project_files/Android-native` shows all of it.

Only some builds were put on a phone, so a version below is a build that was made, not necessarily one that was released. Releases are tags on `android-master` with the APK attached; there is none yet.

## 0.3.0-tv10 (114): fixes from the two-phone and TV test (test build)

`HP_PROFILE=test`, versionName `0.3.0-tv10-test`. Fixes from RESULT 8 and 9 on the device testing issue (Fairphone 4 host, Fairphone 3 joining, TV watching).

### Fixed

- A normal game over after a phone had dropped was reported as the host being lost: the TV got close code 4003 and `lastGame` said `host-lost`. The game is now over when every phone still in it saw the game over (4002, `over`).
- Join: the first join after the host phone sat idle could fail with "No route to host" (the joining phone had not found the host on the Wi-Fi yet). The join now tries again, up to 3 attempts 1.5 s apart, and says so ("Trying again (2 of 3)...").
- Hosts on this Wi-Fi: the search never ended, so "No host found on this Wi-Fi." and "Search again" did not appear. The search now starts at once and always ends.
- Stop watching took two steps (the game's question, then a second button in the lobby). It now leaves in one.

## 0.3.0-tv9 (113): TV sees the close code; no stale own host (test build)

`HP_PROFILE=test`, versionName `0.3.0-tv9-test`. Fixes from the TV test of 0.3.0-tv7b (RESULT 7 on the device testing issue).

### Fixed

- The TV saw close code 1006 with no reason at the end of every game, although the relay sent 4001 or 4002: after its close frame the relay held the connection open for 2 s, and the TV's WebView then reported the close as abnormal. The relay now ends the connection as soon as the TV answers its close frame (5 s at most if the TV never answers), and sends nothing after the close frame. The relay tests check this the way a browser behaves (it failed at 2001 ms before the fix, 0 ms after).
- A TV that went away (killed, Wi-Fi lost) was sometimes recorded as "connection closed" in the game report, not as gone: the relay now records why before letting its last reply out (net-test `viewerflap` passes again).
- Network, Hosts on this Wi-Fi: this phone's own host no longer shows in the list for a few seconds after Stop hosting (a phone cannot join itself).

## 0.3.0-tv8b (112): "More below" never hides the last row (test build)

`HP_PROFILE=test`, versionName `0.3.0-tv8b-test`. Layout only.

### Fixed

- Scrolling screens leave room below the last row for the "More below" mark, and the mark hides as soon as the end of the list is reached, so the last row is never under it.
- The screenshot test now scrolls the Rules and Network screens fully to the end for its end-of-list pictures.

## 0.3.0-tv8 (111): screen layout polish (test build)

`HP_PROFILE=test`, versionName `0.3.0-tv8-test`. Layout and styling only; no behaviour changes. Found by looking at the offline screenshots (`scripts/ui-screenshots.sh`, which now also draws the Network chooser with a host list, the watcher states and the Stop watching question).

### Changed

- Setup: the TEST BUILD badge is no longer clipped by the tab strip on a narrow screen; Randomise and Add team have a gap; two teams share the full width (four make two by two, five and six three columns); a "More below" mark shows when a screen continues below the visible part (six teams on a narrow screen, the rules).
- Rules: "More rules" is split into two cards of equal length that line up with the two above, instead of one card with an empty half; the weapons toggle has room between its label and the switch.
- Options, Display: each Automatic switch sits under a heading naming its control (Buttons and text on screen, Weapon menu); the sliders stay visible when switched off.
- The screens share one left and right margin (12 dp); the Done and Back buttons line up with the cards.
- Network: the title bar is the same as on the other screens (Back on the left, fixed above the cards). On a wide screen the host list is a card beside Host and Join by address, so Join by address is in view on a narrow phone. The host list's Join to watch button is visible on its row.
- Network, hosting: the TV addresses are smaller; TVs watching (green) and waiting (amber) are shown as counts of their own; the long explanations moved to the end of the card, so the TV state, the phone address and the phone list come first. The watching TV line is no longer monospace.
- Disabled buttons have readable text (the disabled Watching button).
- Dialogs: every dialog, including the Back question in the game (whose full-screen theme gave it the old black style), uses one dark green style. Stop watching on the Network screen says "Stop watching" and "Keep watching".
- The Problem box dismiss is a drawn cross on a 48 dp button.

## 0.3.0-tv7b (110): hosting and TV fixes from tv7 testing (test build)

`HP_PROFILE=test`, versionName `0.3.0-tv7b-test`.

### Changed

- Host Wi-Fi loss: the running game ends after 10 s without a LAN address (was 4 s), so brief drops (roams, DHCP renewals) do not end it.
- Hotspot and USB tether addresses: `NetSession.isLanInterface` also accepts `softap*`, `rndis*` and `usb*`, so the host screen lists them.
- The TV close code 4005 `ended` is sent for every other game end (a desync the relay acts on, the pause limit, every phone dropped). Before, these were sent as 4001 `host-ended`. `lastGame.ended` is `other` for them. Table in docs/multiphone-test.md.
- Hosting asks for the local network permission on Android 16 and later before it announces the phone. If it is refused, hosting goes on and the host screen says other phones may not find it.
- Host names from the NSD record lose control and bidi-format characters (Cc, Cf).
- NSD registration: a hosting that stops before NsdManager confirms the registration is unregistered as soon as it is confirmed, and the "Visible..." status is not shown after hosting stopped. The "could not resolve" status is ignored when the search is not running.

## 0.3.0-tv7 (109): hosting robustness, finding hosts, join check (test build)

`HP_PROFILE=test`, versionName `0.3.0-tv7-test`. Phase 5 of docs/tv-viewer-plan.md.

### Added

- Finding hosts: a hosting phone registers `_hedgeparty._tcp` with NsdManager on the phone port (46633). The TXT record holds `v` (app version), `compat` (`net/Compat.java`), `proto` (engine protocol 60), `name` (the device model: the player name is an open owner decision), `tv` (TV WebSocket port 8766), `data` (game data fingerprint) and `lp` (lobby protocol); `HostAdvert.txt`. The Network screen lists hosts found on the Wi-Fi ("Hosts on this Wi-Fi") with the version and whether this phone can join; "Join to play" and "Join to watch" on a row join at once. A host that does not match is listed, greyed, with the reason. The last host used comes first. The typed address stays. Resolving is serialised with a 5 s limit per try and three tries (`net/ResolveQueue.java`), because `resolveService` fails when two run at once and sometimes never answers; the search stops after 45 s ("Search again"). The host screen says whether the announcement worked.
- Join check: the hello carries `compat`, the app version and a fingerprint of the installed game data (`net/DataDigest.java`: SHA-256 over the sorted list of file paths and sizes under Data, dot-files left out, 32 hex digits; computed once after install and cached in `Data/.digest` beside `.installed`). The relay refuses, naming both sides: a different `compat` ("Different game versions"), a different app version ("This phone has X and the host has Y: update the app..."), different data ("Game data differs"), a phone with no fingerprint while the host has one. Watching phones are checked too. The whole manifest is compared, not only what the host's setup uses, because the theme, map and scheme are chosen after joining and each phone's teams bring their own forts, hats and voices; the test and full builds therefore refuse each other. The app version must match exactly (stricter than the TV, which compares `compat` only); relaxing it is one line in `Compat.refusal`.
- Engine link liveness: while a game runs the relay pings every engine link (control frame `\0K`, every 5 s) and the launcher answers on its own thread (so a phone that is generating a map or is paused still answers). A phone whose link has been silent for 20 s (any frame counts) is dropped like any dropout (team-gone `F`, the game goes on), a watcher's link is cut. This finds a half-open link of a phone that is not the one playing, which the silence rule cannot. The launcher also treats 60 s without any frame from the relay as a lost host. Reconnecting to a running game is not built (out of scope; a dropped phone can only watch again).
- Host Wi-Fi loss: the relay asks the host service once a second whether the phone still has a Wi-Fi, hotspot or Ethernet address; after 4 s without one the running game ends for everyone with "The host lost its Wi-Fi connection", and a lobby phone gets a notice.
- Local network permission: `android.permission.ACCESS_LOCAL_NETWORK` is declared and requested on Android 16 and later before searching; if it is refused the search is off, the screen says so and the typed address still works. `NEARBY_WIFI_DEVICES` is not declared (NSD and plain sockets do not need it). The app targets API 34, where the system should not enforce either; not checked on a phone with Android 16 or 17.
- Tests: `RelayTests` `testCompat`, `testDataDigest`, `testHostAdvert`, `testResolveQueue`, `testLinkLiveness`, `testHostNetworkLoss` and the query-string check; smoke tests for the discovered-hosts list (NSD replaced by a fake), a disabled incompatible host, one-tap join, the permission-denied fallback and the Wi-Fi and wake lock life cycle.

- Telling the TV how a game ended: the relay sends each TV a WebSocket close frame with a code and reason after the last queued game frame (4001 `host-ended`, 4002 `game-over`, 4003 `host-lost`, 4004 `relay-stopped`; before, an empty close and a socket closed after 1 s, so a host-ended game left the TV waiting until its own 15 s timeout), and the status GET gains `lastGame` `{n, ended, atMs}`. Table and rules in docs/multiphone-test.md.

### Changed

- The wake lock and the Wi-Fi lock are no longer released when the host's own lobby link ends while the relay still runs; they go when hosting stops (they were already taken for hosting, for joined phones and watchers, and the game screen of a network game already keeps the screen on).
- The status GET on the viewer port reads `proto=` and `compat=` from the query string (after `?` or `&`), not by substring: `?xcompat=...` is no longer taken for `compat`.

### Not changed

- The host's own engine (in the `:game` process) stalls when the host's screen turns off or the game screen goes to the background; the relay in the main process, kept alive by the foreground service, goes on, and the 20 s rule drops the host's teams. Documented in docs/multiphone-test.md.
- The player name, music, "host leaving ends the game" and the TV defaults are open owner decisions and were not touched; the host name in the record is the device model.

## 0.3.0-tv6 (108): a phone can join to watch (test build)

`HP_PROFILE=test`, versionName `0.3.0-tv6-test`.

### Added

- Watch: the Network screen's Join offers "Join to play" (as before) and "Join to watch". A watching phone sends no teams. The relay registers it as a spectator, not a player: it has its own cap (`Relay.MAX_SPECTATORS`, 8), is outside the map-digest, silence, dropout (no team-gone `F`) and desync rules, and cannot send game messages: everything on its engine link is ignored and logged (only its own `\0E` error, `\0Q` stop and game-over `q` are read; a pause or resume is ignored). It gets the same per-recipient setup as a phone with every team `erdriven`, then, for a game that has already begun, `e$spectate 1` and the whole log so far (its engine fast-forwards, as the TV's does), then the live stream. Joining before Start gives the game at Start; joining mid-game is allowed, and so is leaving and watching again ("Watch the game" in the lobby). The host's pause reaches the watcher's engine.
- The game screen of a watcher: the engine hides the touch controls, the weapon menu and the pause button and ignores taps on them (pan and zoom stay). This needs a small engine change, own commit "Network games: a watching phone has no controls" (local command `netwatch`, never a network message; every use is guarded by `GameType = gmtNet`). Back asks "Stop watching?".
- The lobby: the host's screen shows "Watching: N"; a spectator is listed with a "Watching" label and no teams. The relay report on every phone lists the watching phones (stream hash, messages they tried to send and which were ignored, desync reports) next to the players' figures. A watcher's report shows the same map digest and turn checksums.
- Host tests: scenarios `watch1`, `watchlate`, `watchflap`, `watchsend`, `watchpause`; `RelayTests.testWatchers`; smoke tests for the Watch lobby and the spectator game screen.

- The viewer-port status GET has `compat` (`hwnet-60-sim1`, `net/Compat.java`) and `protocol` (60) for the TV to compare by exact equality; `engine` stays a label; `?compat=X` that differs makes `compatible` false. The report prints it. Rule in docs/multiphone-test.md.
- The launcher drops anything from the relay that is not on the relay's whitelist (plus `F`), above all `e` (`hp_ipc_relay_frame_ok`); a watching phone therefore gets `e$spectate 1` as its last setup line, not as a frame. Scenario `injecte` proves the drop.

### Changed

- The Back question in a network game no longer says the game keeps running while you decide without qualification: it says "Unless the host has paused".
- The relay counts a phone as alive for the silence rule only on a kept game message, not on a control frame; the status GET prunes old TV pollers on each request.

## 0.3.0-tv5b (107): less lag between phones and the TV (test build)

`HP_PROFILE=test`, versionName `0.3.0-tv5b-test`.

### Changed

- The engine of a network game now flushes its outgoing messages and sends its empty `+` time mark every 100 game ms instead of every 1000. A device run of tv5 showed 643 `+` in about 660 s with a longest gap of 1035 ms while the relay round trip was about 3 ms, so nearly all of the 1.5 to 2 s of lag after a TV rejoin was this batching (a key press waited in the engine's send buffer until the next flush) plus the TV's own buffering. The engine got a trusted local IPC command `netflush N` (10 to 1000 ms; engine commit "Network games: lower the interval of the empty '+' time mark..."); its default stays 1000, so desktop, local games and upstream are untouched. The launcher sends `enetflush 100` in network games only (`HP_NET_FLUSH_DEFAULT_MS` in hp_ipc.c; the harness can override it with `HP_NET_FLUSH_MS`).
- Why this cannot change a game: every command carries the sender's own game-time stamp and receivers apply it at exactly that tick. The `+` only tells a receiver that nothing earlier is still to come, so that it may advance; a shorter interval lets it advance sooner and changes nothing it computes. Replays and the relay log hold the same commands with the same stamps, only with more `+` lines.
- The launcher's netgame.txt now has a `PLUSGAPS` line (count of `+`, bytes sent, histogram of wall-clock gaps between them) and, with `HP_NET_TRACE` set, one `TX type stamp` line per sent message (harness measurements only).

### Measured (Linux harness, scenario viewer3: one phone with three teams, 20 turns, TV viewer connected from the start, about 200 s)

| flush interval | `+` gap median / p95 / longest | phone to relay | per viewer | input held in the engine before sending (mean / p95) |
|---|---|---|---|---|
| 1000 (before) | 1005 / 5574 / 11819 ms (long gaps are times with no game time moving) | 1.7 msg/s, 11 B/s | 1.3 msg/s, 14 B/s | 132 / 892 ms |
| 100 (now) | 105 / 194 / 322 ms | 10.1 msg/s, 44 B/s | 9.7 msg/s, 48 B/s | 32 / 7 ms |
| 50 (tried) | 56 / 59 / 173 ms | 18.9 msg/s, 80 B/s | 18.5 msg/s, 84 B/s | 0 / 1 ms |

Each viewer and each further phone receives about 8 messages and 34 bytes a second more than before (payload, before the WebSocket or TCP headers); 100 ms was chosen because 50 ms doubles that for no visible gain. The relay round trip stayed at 0.3 to 0.4 ms (average about 1 ms) with no frames queued behind it in every run. All three runs: PASS, 0 desync, 20 identical turn checksums.

## 0.3.0-tv5 (106): the host can pause a network game (test build)

`HP_PROFILE=test`, versionName `0.3.0-tv5-test`.

### Added

- The host can pause and resume a network game for everyone. The pause button is shown again, on the host's phone only, in network games; tapping it (or, while paused, tapping anywhere) asks the relay. The other phones show "Paused by the host" and cannot resume. The TV viewer simply receives no moves until the host resumes (a "paused" indicator on the TV page is not part of this build: the TV page is a separate project).
- How it works: the host's engine tells its launcher (`y1` / `y0`, a local message that is never sent on as a game message); the launcher sends the relay the control frame `\0P` / `\0R`; the relay accepts it from the host's engine link only (a joiner's request is logged and ignored), records it in the event list of the report, and sends `\0P` / `\0R` to every engine link, the host's included; each launcher pauses its own engine with the local command `enetpause 1` / `enetpause 0` (the engine's local IPC channel, which no network message can reach). The launcher tells the host's engine that it is the host with `enethost` (from `role=host` in net.cfg).
- The engine pause is the real one (`isPaused`: game time stops, the Paused banner shows), not the away-from-keyboard toggle that the pause command does in network games. Engine commit "Network games: the host can pause" (guarded to network games, three local commands `nethost`, `netpause`, `netpausereq`).
- While paused the relay's silence and map-wait rules are off (the pause is not blamed on anyone) and start again, from the moment of the resume, when the host resumes. A pause ends by itself after 10 minutes (`Config.pauseMaxMs`), or at once if the host's phone is gone. The relay's live text on the host's screen says "paused by the host".
- Host test scenarios `hostpause`, `hostpausequit`, `joinerpause`, `pausecap` (scripts/net-test.sh).

### Fixed

- Back in a network game showed no dialog (device test of 0.3.0-tv4): SDLActivity consumed the Back key and forwarded it to the engine, so `onBackPressed` was never called. `GameActivity.dispatchKeyEvent` now takes Back before SDL sees it when `net.cfg` says this is a network game (the dialog opens when the key goes up); local games still forward Back as Escape. New smoke test `gameBackKey_opensDialogInNetworkGame` dispatches real Back key events in network mode (checked to fail without the fix).

### Changed

- The viewer port's plain GET status JSON keeps `state`, `game`, `viewers`, `viewersEver` and adds `host` (the phone's name), `players` (one `{name, teams, host}` per phone in the lobby), `compatible` and `reason` (false with a reason when the request names another protocol, `?proto=N`), and `engine` (app version and engine commit). Checked in `RelayTests` (`testStatusGet`).
- The host screen no longer says there is no automatic discovery: the TV finds the phone on the same Wi-Fi; the address is the fallback. It also shows "waiting for a game": TVs that polled the status GET in the last 10 s.

### Why a pause cannot desync

Game time in Hedgewars network play moves by ticks, never by the wall clock. The engine of the team whose turn it is runs the game and stamps every message it sends with its tick; the other engines only advance to the tick of the next message they hold. A paused engine stops advancing and sends nothing; messages that reach it are queued and used after the resume. Whatever the wall-clock moments at which each phone pauses and resumes, every engine computes the same sequence of ticks and messages. The tests pause one phone 700 ms later than the other on purpose.

## 0.3.0-tv4 (105): leaving with the chat open (test build)

Review fixes on 0.3.0-tv3. `HP_PROFILE=test`, versionName `0.3.0-tv4-test`.

### Fixed

- Leaving a network game with Back and confirming while the chat was open (half a message typed) did not quit the engine: Escape only closed the chat and Y was ignored, so the game process was ended after 10 seconds and the others saw "engine link closed". The launcher now sends the relay an explicit user-quit control frame (`\0Q`, treated like the engine's own `Q`) and makes the engine quit with its `forcequit` command, whatever its UI state; the Escape and Y keys remain as a fallback. New host-test scenarios `hostquitchat` and `joinerquitchat`.
- Network games: a tap in the old pause corner no longer falls through to a map press or click (engine commit "Touch UI: swallow taps in the old pause corner in network games").
- A Stop that arrived while the host service was still opening its ports no longer leaves the relay hosting.
- The relay report keeps the last 50 TV viewer lines and events and counts the older ones.

## 0.3.0-tv3 (104): leaving a network game, TV round trip (test build)

Fixes from the phone-hosts, TV-watches device test of 0.3.0-tv2 (13 turns, passed), plus the reviewer's leftovers. `HP_PROFILE=test`, versionName `0.3.0-tv3-test`. 0.3.0-tv2 (103) moved the relay's and the lobby client's socket work off the UI thread (StrictMode in test builds).

### Fixed

- **Back in a network game did nothing; quitting needed a raw Escape then Y.** Back on the game screen of a network game now asks in the app: "Leave the game?" (a joining phone) or "End the game for everyone?" (the host), with "Keep playing" as the other answer (Back again also means keep playing). The game keeps running while the question is up, as a network game cannot pause. On yes the engine gets Escape then Y, the same path as typing them, so it sends its quit message and exits cleanly (engine log `engine returned 0`, the relay logs the quit). `GameActivity` falls back to closing the screen after 10 s if the engine does not answer. A local game is unchanged: Back is still forwarded as Escape. The role (host or joining phone) is written into `net.cfg` as `role=`; the launcher ignores the line.
- **The pause button did nothing in a network game.** The engine's pause command only toggles away-from-keyboard there (`chPause`, and only when a remote team is playing), so the button looked dead. It is now neither drawn nor pressable in network games (own engine commit, two guarded lines in `uTouch.pas` and `uWorld.pas`, touch interface only); leaving goes through Back. Local games keep the button.
- **Wrong wording after the host quit.** "You were dropped from the game: the player quit" is gone. The host that ends the game sees "You ended the game."; the other phones see "The host ended the game." (the host's quit now ends the game for everyone: relay end reason `The host ended the game`, event "(the host) ended the game"); a joining phone that quits is told "You left the game."; a phone that is dropped is still told why. The launcher shows a relay reason after "The game ended:" unless it is the host's.
- **A TV that disconnected was logged as "dropped: too slow".** A viewer that closes, is reset or fails a write is now logged and reported as having left, with the reason ("the TV closed the connection", "the TV disconnected (end of stream)", "connection lost (...)"); only a full outbound queue is "too slow", and the log says at how many frames.
- **Host restart race.** `HostService` waits for the relay stop that is still running before it binds the ports again, and retries the phone port a few times; the relay retries the TV viewer port (10 times, 1 s apart) instead of giving up for the whole hosting session. A failed bind no longer leaves a socket open.
- **`RelayClient`.** A refusal or a host close now go through `close(why)` (they left the writer thread running); `close` is idempotent and reports the end once; the writer task catches any `Throwable` and closes the client.
- **Network screen.** The addresses, the TV count, the live counters and the new per-TV figures are read by a background ticker (`NetSession.hostInfo`, once a second), not on the UI thread; the screen shows "Looking for the Wi-Fi address..." until the first snapshot. The Wi-Fi filter reads each interface in its own `try` (one unreadable interface no longer hides the others) and matches the hotspot `ap0`, `ap1` but not `apn0`.

### Added

- **TV lag measurement, relay side.** Every ~2 s the relay sends each viewer a WebSocket ping with a timestamp; the viewer's pong gives the round trip. The host screen's TV section shows, for each TV, last/average/best/worst round trip, the frames waiting in its outbound queue (and the deepest it reached) and how long it has been watching; the end-of-game report (and Share log) has a "TV viewers" section with the same figures and why each TV left. The ping waits behind the frames already queued, so a TV that falls behind shows a longer round trip. This is the relay-to-TV part only; the time the TV page needs to draw (true render lag) needs the viewer page to report it: follow-up.
- `scripts/net-test.sh` scenarios `viewer3` (a short game played to a normal game over with the viewer connected: the viewer and its spectator engine must see the game over, no desync, a round trip measured), `viewerflap` (the viewer disconnects and reconnects twice, each time with the backlog, and the two departures must be logged as lost connections, never "too slow"), `hostquit` and `joinerquit` (Escape then Y injected into the engine: clean exit, the right reason at the relay and on the other phone). The host test launcher gets `key:esc`, `key:y` and `waitfile:` script actions. `RelayTests` (scenario `malformed`) gained the ping/round trip, disconnect classification, real overflow, port retry, quit wording and client close checks.
- UI smoke test: the host screen's TV section, the Back confirmation (both texts, both answers, the role read from `net.cfg`), the end-of-game wording and the interface filter.

### Changed

- Version 0.3.0-tv3, versionCode 104.

## 0.3.0-tv1 (102): one phone hosts, the TV watches (test build)

Combines `android-master` 0.2.3, `multiphone` 0.3.0-mp2 and the test profile into one small APK for the owner's phone and TV test (`HP_PROFILE=test`, application id `party.hedge.host.test`, versionName `0.3.0-tv1-test`).

### Changed

- The network screen lists a TV address only for Wi-Fi, Ethernet and hotspot interfaces (names starting `wlan`, `swlan`, `ap`, `eth`), as `ws://IP:8766`. Cellular (`rmnet*`), VPN (`tun*`) and other interfaces are no longer offered. With no such address the screen says "No Wi-Fi: connect the phone to the same Wi-Fi as the TV".
- Version 0.3.0-tv1, versionCode 102.

## Unreleased: test build profile

### Added

- **Test profile** (`HP_PROFILE=test scripts/build-apk.sh`, or `-Pprofile=test` for Gradle): a small APK for automated phone and TV tests that can be kept in git. Application id `party.hedge.host.test` (installs next to the normal app, the log FileProvider authority follows it), label "Hedge Party (test)", `versionName` 0.2.2-test, arm64-v8a only, the same debug signing key, and a red "TEST BUILD" badge on the setup screen and on the About page. The game data is cut to three themes (Nature, Snow, Hell; EarthRise and Cave are installed hidden because two game styles set them), the Earth fort, the Default voice pack, thirteen hats and three flags, with no music and no image maps: about 11 MB staged and a 14 MB APK. Themes, hats and map types were chosen to be present in the TV viewer's data package too. The full profile is the default and its application id, data and APK content are unchanged. See the README, "Build profiles".
- `config-test.sh` checks that nothing the app picks at random (teams, hats, themes, forts, voices, graves, flags) or by default names content that is missing from the data, and that `repair()` replaces what is missing.

### Fixed

- The test profile offered five themes, not three: `stage-assets.sh` tried to delete the icons of the hidden themes EarthRise and Cave with a glob that `set -f` had switched off, so both kept `icon.png` and `icon@2x.png`. It now uses `find`, and the staging stops if a hidden theme still has an icon or a visible one has none. `config-test.sh` checks that the test data offers exactly Nature, Snow and Hell.
- A test data set could be packaged into a full APK, and the other way round. `stage-assets.sh` writes the profile into `build/assets/Data/.profile`, and `preBuild` stops with a message when it does not match the Gradle profile.

### Changed

- The profile variable is `HP_PROFILE` (was `PROFILE`, a name other tools set). The scripts stop with a message if `PROFILE` is set and `HP_PROFILE` is not, so an old `PROFILE=test` command cannot silently build the full app.
- The map type list offers "Image map" only when the game data has image maps (it showed a "No image maps were found" message before).
- Random themed hats skip hats that are not installed (`GameData.typeHats`); with the full data nothing changes.

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

## 0.3.0-mp2 (101): hardened relay, merged with 0.2.2 (test build)

Fixes from an independent review of 0.3.0-mp1 (the sync itself was proven on the host; these were the failures around it), and the merge of `android-master` 0.2.2.

### Fixed

- **A remote phone could crash the whole app (M1).** `Json` recursed once per `[`, so one line of 60000 `[` raised a `StackOverflowError`, which the reader threads did not catch (they caught `Exception`) and so ended the process. The reader now has a nesting limit of 32, limits on elements, nodes, string length and number length, and no input can raise anything but `IllegalArgumentException`. Every relay, lobby, engine-link, viewer and watchdog thread now catches `Throwable` and ends only its own connection, relay threads have an uncaught-exception handler that logs and closes only that connection, lobby lines are limited to 256 KB and the first line of a connection to 32 KB and 15 s, `Outbox` and `RelayClient` readers catch `Throwable` too, and a socket is closed when its reader ends. `scripts/net-test.sh malformed` (`native/host-test/RelayTests.java`) sends the 60000 `[` line (as first line, after a valid hello, inside a teams message), 60000-deep objects, 100 KB and 300 KB lines, invalid UTF-8, random bytes, junk engine frames, truncated frames, bad WebSocket frames and connection floods, and checks that the relay answers a normal phone afterwards.
- **A phone could inject engine commands and desynchronise the others (M2).** Grave, fort, voice, flag, hat and names from another phone went into the setup lines as they came, so a newline in a grave name was a new engine command on every phone and the TV. `NetTeams.fromJson` now accepts a grave, fort, voice, flag or hat only if the host's own game data has it (otherwise the default) and cleans team and hedgehog names as `GameConfig.clean` does (no control characters, 32 characters at most; the cleaners are public now), and reads at most 8 teams. `GameData.flags()` is new. The launcher used to skip a setup line longer than 255 bytes without a word, which left that phone with a different game: the relay now checks every setup line (255 bytes at most, no control characters, at most 255 lines because the TV viewer's first message counts them in one byte) and refuses to start the game with a clear message in every lobby ("The game cannot start: setup line 26 ... is 314 bytes ..."); the launcher also stops with an error instead of skipping in a network game.
- **The silence rule armed too early (M3).** It armed at the first forwarded message, so a phone still generating the map could be blamed while a healthy phone that was waiting for it was dropped. The rule now arms only when every live phone has sent its map digest `M`, adds a grace of 40 s before the first turn ends, treats a last message of type `M` like `N` (the next sender is unknown) and drops a phone that never sends `M` after 3 minutes.
- Host start race (m5): `HostService.begin()` clears the old error before the service is asked to start, and a failure of an earlier attempt can no longer overwrite a later one (generation counter).
- WebSocket (m7): a close frame is echoed with its status code, a ping's payload is echoed in the pong, masked payloads are unmasked, and a negative, oversized (more than 64 KB) or cut-off frame ends that viewer cleanly.
- Launcher (m8): the relay link has one write lock shared by the sender thread and the control frames, so an engine error frame can no longer land in the middle of a message.

### Changed

- **Back leaves the lobby (m4).** Back on the Network screen asks, then closes the link and releases the wake and Wi-Fi locks (and stops hosting, for the host). The host's Start warns when a joiner is not on its Network screen (phones now tell the relay when the screen goes to the background), and a joiner that receives the start while elsewhere gets a notification to tap instead of a silent failure.
- **Tokens and roles (m6).** Engine tokens are 128 bits from `SecureRandom`; at most 8 phones, 32 simultaneous connections and 16 TV viewers; the host role needs a secret that only the app process knows (made per hosting session, kept in memory, presented by the app's own client over loopback), not just a loopback address.
- Network button in the compact header of the setup screen, beside the Options icon button.
- `scripts/net-test.sh`: explicit scenario names (`basic`, `basic3`, `dropout`, `dropout3`, `stall`, `slowstart`, `negcontrol`, `solo`, `viewer1`, `viewer2`, `malformed`, `all`); the old `three` ran two phones. It exits non-zero on any desync, unexpected protocol line, engine queue error, non-zero engine exit (the victim's excepted), differing checksums, or a relay and launcher stream hash that differ, and ends with a `RESULT:` line. `negcontrol` has the relay alter one byte of one turn checksum and passes only if the desync detection fires. `slowstart` holds phone B's first message back by 30 s (launcher test hook `HP_NET_TX_DELAY_MS`).
- The UI smoke test (`scripts/ui-smoke-test.sh`) also opens the Network screen as the chooser, as a joined phone, as the host, and its Back confirmation, under SDK 34 and 35.
- Merged `android-master` 0.2.2: launch crash fix (`Ui.hideBars`), UI smoke test, compact setup screens, Options icon button, `HP_TEST_DISPLAY` as a bare number in both host scripts.
- Version 0.3.0-mp2, versionCode 101.

### Not done (follow-ups, see docs/multiphone-test.md)

- Liveness of the engine link by ping (m3) and reconnecting a phone that dropped (m9).

## 0.3.0-mp1 (100): two phones play through a relay (test build)

Phase 4 of the multi-phone plan (`docs/tv-viewer-plan.md` in https://github.com/Gadgit83/hedgewars-party): any phone can host, a second phone joins by typed address, each phone runs its own engine with its own teams. Experimental; the owner's two-phone test is `docs/multiphone-test.md`. Nothing about local hotseat games changes.

### Added

- **Relay** (`app/.../net/`, plain Java, no Android classes): lobby over TCP with one JSON object per line, engine links with the engine's own length-byte framing, the Hedgewars server's message whitelist and the team-ownership check for hedgehog speech, an in-memory game log with a hash, per-recipient bounded queues with their own writer threads, and the plan's dropout rule: when a phone is lost the relay sends every other engine the unsynced `F<team>` for each of its teams, with no timestamp, never `f`. It also keeps the turn checksums (`N`), the map digest (`M`) and message counts, and sends every phone a report when the game ends. Port 46633.
- **TV viewer endpoint** in the relay: WebSocket on port 8766 with the framing and behaviour of the e2e-host bridge (tv/e2e-host in hedgewars-party), so the existing TV viewer page connects unchanged. The host screen shows the address. A one-phone host plays through its own relay and the TV can watch it.
- **Hosting**: `HostService`, a foreground service (type `connectedDevice`, notification channel, Stop action, wake lock and Wi-Fi lock) that runs the relay in the main process, so it survives the game process.
- **Network screen** (`NetLobbyActivity`, the Network button on the setup screen): Host network game, Join by address, the list of phones and teams, Ready, Start, and the report of the last game to compare between phones.
- **Launcher network mode** (`hp_ipc.c`): when `files/net.cfg` exists the launcher joins the game through the relay: the IPC reader only queues, a sender thread writes to the relay, a downstream thread writes the relay's frames to the engine. It writes `files/logs/netgame.txt` with every `N` and `M` that crosses the link in either direction and hashes of both streams. Share log includes it.
- `GameConfig.teamLines` and `GameConfig.forRecipient` (the setup split: shared lines once, per-team blocks, then `TN` and `erdriven` after the teams a phone does not own).
- Host tests: `scripts/net-test.sh [basic|dropout|stall|solo]` (0.3.0-mp2: see the scenario list in the script) runs the relay on the JVM and real engines as separate phones under Xvfb (`native/host-test/NetHarness.java`).

### Changed

- Version 0.3.0-mp1, versionCode 100. New permissions: foreground service (connected device), notifications, wake lock, Wi-Fi and network state, multicast state (declared for the foreground service type, and for discovery in a later phase).
- `host-test.sh` takes `HP_TEST_DISPLAY` for the X display.

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
