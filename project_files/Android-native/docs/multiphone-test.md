# Network test script, build 0.3.0-mp2

Two tests, in this order: **Part A, one phone hosting with the TV watching**, then **Part B, two phones playing each other** (the TV can watch that too). Both are phase 4 and 6 of `docs/tv-viewer-plan.md` in the hedgewars-party repository: automatic discovery is not built yet (you type addresses), and there is no version check between phones.

Needs: the APK `hedge-party-0.3.0-mp2-debug.apk` (same debug key, installs over 0.2.x) on every phone, all phones and the TV on the same home Wi-Fi with router client isolation (AP isolation) off, the TV viewer app (`tv/viewer`, HWViewer.wgt) built as for the PC test.

## Part A: one phone hosts, the TV watches

What the phone serves to the TV is what the PC stand-in (`tv/e2e-host`, bridge on port 8766) served: a WebSocket on port **8766**, first message with the setup lines, `e$spectate 1` and the backlog, then live frames. The viewer page needs no code change, only its address.

1. Phone: install the APK, open it, wait for the data unpacking. Setup screen: Teams tab, set two teams or more (a human team and a Computer team is fine), set the Map and Rules tabs as you like. A short game: turn time 15 s, starting health 50.
2. Phone: tap **Network**, enter a name, tap **Host network game** (allow notifications on Android 13 or later; hosting works without them). The screen shows two addresses in large type: the phone address for other phones (`192.168.1.57:46633`) and **TV viewer address** (`ws://192.168.1.57:8766`). Write the TV one down.
3. TV: the viewer page has its address built in (`HW_WS_URL` in `tv/viewer/index.html`, line 32, now `ws://192.168.1.115:8766`, the PC). Change that line to the phone's address from step 2, then `tv\deploy-viewer.ps1` as before, and start the viewer app. (Until discovery exists this is the only way; a reserved IP address for the phone in the router saves redoing it.) The viewer shows "Waiting for the host to start a game" and retries every 3 s. Check on the phone: "TVs watching now" goes to 1 once it is connected to a running game; before a game the phone refuses the TV (that is why it waits).
4. Phone: **Start game**. The phone plays through its own relay (a single phone with two or more teams, in network mode), the TV loads the engine and the game and shows the same game a moment later (the plan's targets: about 1.5 s delay, 30 frames per second).
5. Play at least 6 turns on the phone. Watch on the TV: same map, same teams and positions, the TV follows the phone's moves, no "desync" screen.
6. TV late join: with the game running, close the TV viewer app and start it again after 2 minutes. It must catch up from the backlog and then follow live. Phone: "TVs watching now" shows it.
7. At game end read the phone's "Last game" report (see B4 for the lines) and note on the TV whether its results screen came up; the viewer page prints its digest of the map (`M`) in its own telemetry as before: compare with "Map digest M" on the phone.
8. Share log on the phone (Options, Advanced, Share log), and send the TV telemetry as for the PC test.

Report: what the TV showed, the delay you felt (phone action to TV), any freeze, the phone's report lines.

## Part B: two phones

Words: phone A is the host, phone B joins. "Own teams" are the teams on each phone's Teams tab. The TV may watch this game as in Part A (the host screen shows the TV address in the same way); not required.

### B1. Install and prepare

1. Install the APK on both phones (`adb install -r hedge-party-0.3.0-mp2-debug.apk`, or copy and open it). Open the app on each; wait for the first-run data unpacking if it appears.
2. Check the version: Options, Advanced, About must say 0.3.0-mp2 on both.
3. Phone A, setup screen: Teams tab. Keep or change the two teams (give them names you can tell apart, for example "A one" and "A two"). Set the Map and Rules tabs as you like; **phone A's Map and Rules decide the game**. For a short first game set Turn time 15 s and Starting health 50.
4. Phone B, Teams tab: its own teams, for example "B one" and one Computer team ("Computer, level 3"). Phone B's Map and Rules tabs are ignored in a network game.
5. Both phones: screen timeout long (or leave the app in front; the lobby and the game keep the screen on).

### B2. Host and join

1. Phone A: tap **Network**, enter a name, tap **Host network game**. Android 13 and later asks to allow notifications: Allow (hosting also works if you refuse, you just see no notification). The screen shows "Hosting" and the address in large type, for example `192.168.1.57:46633`. A notification "Hedge Party: hosting a game" appears.
2. Phone B: tap **Network**, enter a name, type phone A's address exactly as shown, tap **Join by address**. The lobby shows both phones and all their teams. If B says it could not reach the host: same Wi-Fi? AP isolation? Is A still showing "Hosting"?
3. Phone B: tap **Ready**. Phone A is always ready. Phone A: **Start game** is enabled once every phone is ready. Check that colours are different on every team (the relay changes a clashing colour).
4. Press **Start game** on phone A. Both phones show the game after the map is generated. Map generation can take a while; the faster phone starts playing first and the other catches up.

### B3. Play

1. Play at least 6 turns (the turn counter in the report counts every turn end). Players use their own teams on their own phone; the other phone shows the remote team's moves with a short delay (up to about a second) and the "synchronising" indicator when it waits.
2. Try: walking, jumping, a few different weapons, a Computer team's turn, a turn that runs out of time without any input.
3. Let the game run to the end, or end it with Back, Quit on both phones after at least 6 turns.

### B4. Compare the two screens (at game end)

Back in the lobby screen each phone shows "Last game: compare with the other phone". Read these lines out loud and compare phone A with phone B:

| Line | Must be |
|---|---|
| Map digest M | the same on both |
| Desync reports | `none` on both |
| Turn checksums N | the same list on both: every `turn:checksum` pair identical (a trailing `'` only says that phone received it from the other phone) |
| Game log hash (under "What the relay saw") | the same on both |
| "Stream sent to this phone: N messages, hash H" and the launcher's `Link totals` `rx N messages hash H` | on the same phone these two must match each other (nothing lost on the way); they differ between the phones, that is normal |
| Longest gap between two forwarded messages | roughly 1 second or less during play (recorded; it checks an assumption of the plan) |

Take screenshots of both screens next to each other.

### B5. Share the log from both phones

On each phone: **Share log** on that same screen (or Options, Advanced, Share log), send it to yourself. The report has a section "Network game on this phone" with the launcher's full per-turn lines and the relay's report. Send both files back.

### B6. Dropout test

Repeat B2 to B4 (a new game from the same lobby; both press Ready again) and then, after turn 3 or later:

1. Phone B: leave the game by Back, Quit (or force-close the app from recents, or switch Wi-Fi off on phone B).
2. Phone A should carry on; B's teams are removed from play (they do not take turns any more). Phone A may show a notice that a team left. Play 2 more turns on phone A.
3. At the end phone A's report lists the event "Phone B dropped" and "injected F ... (unsynced team gone, no timestamp)". There must be no "Desync detected" on phone A.
4. Phone B returns to its lobby screen (or the join screen if Wi-Fi was off) with a message.

Also worth one try each, write down what happens: A presses Home during the lobby for a minute and B stays listed; A leaves the game screen with Back (not Quit) in the middle of its own turn: the other phone waits, and after about 20 s the relay drops A's teams but keeps hosting.

### B7. What to report back

- The two screenshots of B4, and both shared logs (B5, and B6 for A).
- Phone models and Android versions; whether the notification showed on A; whether anything crashed or the layout looked wrong (the Network button on the setup screen and the lobby screen were never rendered by anyone).
- Wi-Fi: router model if you know it; whether Join worked at the first attempt.
- Any message in red, any freeze: roughly when, and which phone's turn it was.

### B8. New in 0.3.0-mp2, worth one try each

- Back on the Network screen asks "Leave the network game?" (or "Stop hosting?"); answering Leave/Stop hosting closes the link. The "Hedge Party: hosting a game" notification must be gone after Stop hosting and the phone must be able to sleep again (no wake lock: `adb shell dumpsys power | grep hedgeparty` shows nothing).
- Phone B presses Home (or opens another app) while still in the lobby; on phone A, **Start game** now asks "B is not on the Network screen ... Start anyway?". Answer Start anyway: phone B shows a notification "the game is starting"; tapping it joins the game. (If notifications are refused on phone B there is no notification and B misses the start: the relay waits 45 s for its engine and then drops it.)
- A setup the relay cannot send (for example a very long script parameter or seed made of non-Latin characters, over 255 bytes in a line) is refused with a message in the lobby on every phone instead of starting a game in which phones disagree.
- A slow phone: if one phone takes a long time to generate the map, the others wait for it and nobody is dropped; the 20 s silence rule only starts once every phone has sent its map digest, plus 40 s of extra grace before the first turn ends.

Known limits of this build: no automatic host discovery (type the address), no check that both phones run the same app or game data (use the same APK), a joining phone should stay on the Network screen until the host presses Start (otherwise it gets a notification to tap), one game at a time per lobby, the host's phone must stay hosting (stop hosting = game over for everyone).

Follow-ups of 0.3.0-mp2 (the engine link ping is done in 0.3.0-tv7): the engine link has no ping of its own (a phone whose process is frozen but whose TCP connection stays up is caught by the 20 s silence rule only when it is the phone expected to play; a liveness ping between the relay and each launcher would catch it at any time), and a dropped phone cannot reconnect to the running game (it can only watch as a TV would, which is not built either).

## Added in 0.3.0-tv3: leaving a game and the TV round trip

- **Back in a network game** asks "Leave the game?" (joining phone) or "End the game for everyone?" (host). The game keeps running while the question is up. Yes quits the engine cleanly. A joining phone that leaves is told "You left the game."; when the host ends the game the other phones are told "The host ended the game." and the host sees "You ended the game." The host's phone has the pause button in network games (top right): it pauses every phone ("Paused by the host" on the others, which cannot resume), and the TV just receives no moves until the host taps again; the relay resumes by itself after 10 minutes, and the silence rule is off while paused. Joining phones have no pause button.
- **TV section on the host screen**: for each TV the round trip relay to TV and back (a WebSocket ping every 2 s; it waits behind the frames already queued) and the frames waiting in that TV's queue. Both also appear in the end-of-game report and Share log under "TV viewers", with why each TV left. This does not include the time the TV page needs to draw; true render lag needs the viewer page to report it (follow-up in the hedgewars-party repository).

## TV compatibility rule (`compat`)

The status GET on the viewer port (8766) carries `compat` (string) and `protocol` (number) next to the informational `engine` label (app version and engine commit, which may differ between a phone and a TV). The TV joins only if its own `compat` equals the phone's exactly. `compat` is `hwnet-<protocol>-sim<N>`: `protocol` is the engine's network protocol (cNetProtoVersion, 60, from `HEDGEWARS_PROTO_VER` in the top-level CMakeLists.txt) and `N` is `Compat.SIM` in `net/Compat.java`. Bump `SIM` by hand, and only, when an engine change can alter simulation results (physics, weapons, AI, random number use, map generation, schemes, rules, Lua behaviour) or the format or meaning of the messages between phone and TV. Sound, touch, UI, rendering and launcher-only changes need no bump. A request with `?compat=X` that differs gets `compatible:false` and a reason. The value is also printed in the end-of-game report ("Compatibility: ...").

## Watching phones

Join offers "Join to play" and "Join to watch". A watcher is a spectator at the relay (cap 8, apart from the 8 players): no teams, no say in the game, everything it sends is ignored and logged. Its setup lines end with `e$spectate 1` (a local command; the launcher drops any `e` frame from the relay, see `hp_ipc_relay_frame_ok`), then the backlog and the live stream. Scenarios: `watch1`, `watchlate`, `watchflap`, `watchsend`, `watchpause`, and `injecte` (the launcher drops an injected `e` and an unknown frame).

## Added in 0.3.0-tv7: hosting robustness, finding hosts, join check (device test steps)

NSD, the Wi-Fi and wake locks, Doze and the permission cannot be run on the build machine; these steps are the test. Use two phones on the same Wi-Fi (A hosts, B joins), the test build on both. Report Android versions, router model, and what each step did.

1. **Finding the host.** A: Network, Host network game (allow notifications). A's Network screen says "Visible to other phones as ...". B: Network. Within about 10 s B lists A under "Hosts on this Wi-Fi" with the version and no red text. Tap "Join to play": B is in A's lobby. Repeat with "Join to watch". Note the time until the host appeared. If it never appears, say so, then type A's address (shown on A's screen): that must still join. Also report whether "Search again" finds it after the search ends (about 20 s).
2. **Incompatible host.** Install a different build on B (the full build, or tv6) and open the Network screen: A must be listed, greyed, with "Cannot join: ..." naming both versions or "Game data differs". The buttons must be disabled. Typing A's address and pressing Join must give the same reason from the relay (red text on B).
3. **Wi-Fi lock and wake lock.** While A hosts: `adb shell dumpsys wifi | grep -i hedgeparty` shows a lock (mode `LOW_LATENCY` on Android 10 and later) and `adb shell dumpsys power | grep hedgeparty` shows `hedgeparty:net`. After Stop hosting (on the notification or the screen) both are gone. The same on B while it sits in the lobby or a game, and after Leave.
4. **Host presses Home in the lobby for 60 s** (and 5 minutes, and with the screen switched off for 5 minutes): B must stay connected and listed in A's lobby. If B drops, note whether the host phone was on battery, its battery saver setting, and the Android version (Doze may cut the network of a screen-off phone; the wake lock is a partial wake lock and Doze ignores it).
5. **Host in a game, screen off or Home.** Start a game. On A press the power button during B's turn: B plays on normally. During A's turn A's engine stalls (the game screen is stopped by Android, A's engine lives in the `:game` process), B waits, and after 20 s of silence A's teams are dropped and the game goes on; the relay keeps running (check A's notification is still there). Pressing Home on A during a game does the same. A's game screen keeps the screen on while it is in front (no sleep during a 10-minute game).
6. **Wi-Fi off on the host mid-game.** A switches Wi-Fi off during a game: after about 10 s without an address (a brief drop, a roam or a DHCP renewal shorter than that does not end the game) A's relay ends the game; B shows "The game ended: The host lost its Wi-Fi connection" (or "the host left" if B notices first). A switches Wi-Fi on again: A can host a new game without restarting the app (if the port is reported in use, say so).
7. **Half-open phone.** B switches Wi-Fi off (or enables aeroplane mode) while it is not B's turn: A's relay must log the drop within about 20 s ("half-open link" in the shared log, Share log on A) instead of waiting for B's turn; A sees B's teams gone and the game goes on. With B's Wi-Fi switched off, B itself shows "The connection to the host was lost" within about a minute.
8. **Three games in a row and a 10-minute game** on one hosting session, without restarting the app: the relay keeps answering, B can always rejoin the lobby, and A's battery drain over 30 minutes is recorded (plan section 10).
9. **Notification permission denied** on A (Android 13 and later): hosting works, no notification shows; say whether the relay keeps running with A in the background for 5 minutes.
10. **Local network permission** (Android 16 and later only). On a phone with Android 16 or 17: does B ask for "nearby devices"/"local network" when it opens the Network screen? Deny it: the screen must say the search needs the permission and the typed address must still work (or say what the system does to the connection). Grant it in Settings, App, Permissions, press "Search again": the host appears. Report the exact permission name shown by `adb shell dumpsys package <id> | grep -i local` and whether hosting or joining by address worked while it was denied.
11. **Router client isolation.** If B cannot reach A at all (neither list nor typed address), the router isolates its Wi-Fi clients (AP isolation); report the router. If the list is empty but the typed address works, the router filters multicast; report that too.

Host tests (no phones): `scripts/net-test.sh <scenario>`; the join check, the liveness ping, Wi-Fi loss and the NSD record are in `RelayTests` (`scripts/net-test.sh malformed`). NSD itself (registration, discovery, resolving) is not run anywhere on the build machine; only its parsing, listing and resolve queue are tested.

### How the TV is told the game ended (WebSocket close codes and `lastGame`)

When a game ends, or hosting stops, the relay sends every connected TV a WebSocket close frame with a status code and a reason, after every game frame that was queued for that TV (the close is the last thing it reads), then keeps the socket open for 2 s so the TV can read and answer, then closes it. Codes:

| code | reason | when |
|---|---|---|
| 4001 | `host-ended` | the host ended the game (Back, End the game; the host's engine quit) |
| 4002 | `game-over` | a normal game over: every engine finished |
| 4003 | `host-lost` | the host lost its Wi-Fi (`The host lost its Wi-Fi connection`), or every engine is gone |
| 4004 | `relay-stopped` | hosting stopped (Stop hosting, the app closed from recents) |
| 4005 | `ended` | any other end the relay decides: a desync it acts on (two phones: the game ends for both), the pause limit, every phone dropped |
| 4006 | `password` | at connect, not at the end: the TV sent no PIN or a wrong one (0.3.0-tv12, see docs/network.md, Game password) |
| 4007 | `password-locked` | at connect: too many wrong PINs from the TV's address, locked for 30 s |

The status GET on 8766 gains `lastGame`: `{"n": <game number>, "ended": "host"|"over"|"host-lost"|"stopped"|"other", "atMs": <epoch ms>}` for the most recent game that ended (absent before the first); `ended` matches the codes above (`host` = 4001, `over` = 4002, `host-lost` = 4003, `stopped` = 4004, `other` = 4005). A TV that does not know 4005 should treat it as a generic end. `state` is unchanged (`setup` or `playing`). Checked in `RelayTests.testViewerCloseCodes` (each code, the order of the last game frame and the close, `lastGame`), and in the net-test scenarios `viewer3` and `hostpause`, where a small WebSocket probe in the harness (`NetHarness.CloseProbe`; the TV page's own stand-in `viewer-sim.js` lives in hedgewars-party and was not changed) must see `4002 game-over`.
