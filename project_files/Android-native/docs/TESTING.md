# Testing

## Strategy: what to run, and when

Network scenarios are real games and take about 5 minutes each, so run them only where they can catch something.

1. **While fixing:** run only the tests that cover the change.
   - **App screens only:** the unit and Robolectric tests (`scripts/ui-smoke-test.sh`). No network scenarios and no APK build.
   - **Relay or launcher:** the two or three scenarios that cover the change, plus `malformed` if message parsing changed.
   - **Engine:** `settings` and `basic`. Add the layout tests if the change touches the touch controls.
   - **Every fix:** show it failing first, on the unfixed code, then passing.
2. **After merging a branch:** the build, plus the scenarios for the merged area.
3. **Before a device build:** the full suite once, with `scripts/net-suite.sh` (about an hour at `-j 3`), then `scripts/config-test.sh` and `scripts/build-apk.sh` for both ABIs.
4. **A late fix after the full suite:** rerun only the scenarios for its area, not the whole suite.

Make the cloud look like the phones. The UI scale, the slow-phone timing and short-range aiming each caused a bug that passed in the cloud and failed on a phone. So when a change touches one of these, test it with the phone's arguments and screen size (2340x1080, the app's own launcher arguments), and with a slow phone (the `*slow` scenarios).

Device requests on issue #1 give exact steps, the logs to capture (`adb logcat -s HW_Engine` and the relay log), and a screenshot at the moment that matters.

## Network scenarios in parallel: `scripts/net-suite.sh`

`malformed` (the relay's own unit tests, about 2 minutes, many short timing checks) always runs first and on its own: alongside game scenarios it failed now and then on a different check each time.

`scripts/net-test.sh SCENARIO` runs one network scenario (see its header for the list). `scripts/net-suite.sh` runs many of them.

    scripts/net-suite.sh                              # the full release list (41 scenarios), 3 at a time
    scripts/net-suite.sh -j 2 basic rejoin hostend    # chosen scenarios, 2 at a time
    scripts/net-suite.sh -r build/host-test/run2.tsv  # a new results file for a fresh run

- The build runs once up front (engine if missing, launcher, viewer stand-in when a viewer scenario is listed). Scenarios then run with `NET_SKIP_BUILD=1`.
- Scenario slot k runs on X display `70+k` (`-d` changes the base) with its own spectator port (`NET_SPEC_PORT`) and its own output directories (`build/host-test/net-<scenario>`, `net-classes-<scenario>`). The relay listens on an ephemeral port. Nothing else is shared between parallel runs.
- Per-attempt logs: `build/host-test/suite-logs/<scenario>.try<N>.log`.
- Results file (default `build/host-test/net-suite.tsv`): one line per attempt, tab-separated: scenario, attempt, PASS or FAIL, seconds, exit status, the RESULT line. Lines starting with `#` are run markers.
- Resume: a scenario with a PASS line in the results file is skipped. After a container restart or the 2-hour job limit, rerun the same command with the same `-r` file; only scenarios that did not pass run again.
- Retry: a failed scenario is retried once at the end of the queue. The suite exits 1 if any scenario failed twice, and prints the summary.
- Lock: the suite holds the machine's heavy lock (`HP_HEAVY_LOCK`, default the lock the other workers use) for its whole run, so one suite runs at a time. Do not wrap it in `flock` yourself: it takes the lock on its own (and, if it finds its parent already holds that lock, runs on under it rather than waiting for ever). Ctrl-C or TERM stops only the processes the suite started.
- `NET_TIMEOUT_S` (default 900 per attempt) is passed to the harness.

### Measured (6 scenarios: basic settings dropout hostquit watch1 rejoin, 4-core container, other workers running)

| run | wall time | sum of scenario times | result |
|---|---|---|---|
| `-j 1` | 1373 s | 1353 s | 6 PASS, 0 desync |
| `-j 3` | 591 s | 1049 s (per scenario 105 to 318 s) | 6 PASS, 0 desync |

`-j 3` is 2.3 times faster than `-j 1`. The load average rose to about 10 during the `-j 3` run, which includes other workers, so `-j 4` would add little. The default is 3.

## Can network test games run faster than real time?

Short answer: probably yes for most scenarios, by a factor of 3 to 4, but only with a test-only engine switch, and not yet verified. The timing and the determinism risks are below. This is a feasibility note, not a prototype; nothing in the engine has been changed.

### What paces a game today

- **Engine clock.** `hedgewars/hwengine.pas` (`DoTimer`) advances `RealTicks` by the SDL wall time since the last call and runs `Lag` game ticks for it (`hedgewars/uGame.pas`). One game tick is one millisecond of wall time. `Lag` is capped at 100 ticks per call unless `fastUntilLag` or `fastForward` is on. The main loop calls `DoTimer` every `cTimerInterval` (8 ms, `--frame-interval`). The game is therefore paced at real time.
- **Game time vs real time.** Turn timers (`TurnTimeLeft`, `cHedgehogTurnTime`), gear physics and the checksum use `GameTicks`, not `RealTicks` (`uGears.pas`). Captions, chat and animations use `RealTicks`. So scaling the tick rate changes only how quickly game ticks are processed, not what they compute.
- **Fast-forward already exists.** For network games, `fastUntilLag` sets `Lag` to 2500 per call (`uGame.pas`, line 84), so an engine that is behind runs as fast as the CPU allows. Replays and the rejoin catch-up use it, and the `ff` and `sff` commands use it for demos.
- **Lockstep waits.** An ExtDriven (remote) team's commands come from the relay. When none is ready, `NetGetNextCmd` sets `isInLag` and the engine stops, so a fast engine waits for its peers and stays consistent.
- **Wall-clock delays inside the engine.** The AI thread sleeps for wall time: `SDL_Delay(700)` before a computer turn when `not cQuickAI` (`uAI.pas` line 597), and `SDL_Delay(100)` per think step (`uAI.pas` lines 648, 654). The AI's think limit `cMaxAIThinkTime` (9000) counts `GameTicks`. `--quick-ai` already removes part of this delay; it is an existing opt-in precedent.
- **Relay timers (wall clock).** `silenceMs` 20000 (a silent phone is dropped when it is the one expected to play), `graceMs` 40000, `rejoinMs` 120000 (the 2-minute window), `pauseMaxMs` (10 min in the app, 15 s in `pausecap`).
- **Harness timers (wall clock, ms).** `NET_REJOIN_AFTER_S` (8 s), `NET_LONE_AWAY_S` (5 s), the 40 s hidden phase, the 30 s pause and late start of `slowstart`, `HP_NET_START_DELAY_MS` (20 s load of a slow phone), `HP_NET_RX_DELAY_MS` and `HP_NET_TX_DELAY_MS`, and the pause and resume offsets in the scripted touch input.

### Proposed mechanism (not built)

1. An opt-in engine option, `--time-scale K` (default 1, so behaviour is identical without it). It multiplies the wall-clock delta in `DoTimer` by K and scales the `Lag` cap of 100 by K. Pass it through `HP_TEST_ARGS`, which the test launcher already forwards to the engine (`native/host-test/hp_host_main.c`), so the harness needs no change for the engine. It touches `hedgewars/ArgParsers.pas`, `uVariables.pas` (the default) and `hwengine.pas` (`DoTimer`). It would be a separate `hedgewars/`-only commit.
2. The relay and the harness keep their wall-clock timers unchanged. The engine's speed-up makes messages arrive sooner, and the silence rule then has more slack, not less. Scaling the relay timers by 1/K would be wrong, because the AI sleeps and any network delay stay in real time. Scaling them would make the rules tighter in game terms and invite false dropouts.
3. Scenarios with scripted wall-clock waits keep real time unless their timers are scaled by hand: all the rejoin, hostalone, hidden, pause and slowstart scenarios, and the slow-phone ones (`hostalone-*slow`, `slowstart`, which model a phone that is genuinely slow to load). The timing tests (`pausecap`, `rejoinlate`, `lonerejoin*`) need real time for their windows.
4. Good candidates: `basic`, `basic3`, `dropout`, `dropout3`, `solo`, `settings`, `viewer1`, `viewer2`, `watch1`, `watchsend`, `negcontrol` (a desync check that must still fire). These are turn-driven and have no wall-clock script.

### Risks to determinism

- The AI runs on its own thread and its result depends on how many think steps happen per game tick. Scaling the tick rate changes that ratio, so a computer turn may choose a different action. The checksums would then differ between real-time and scaled runs. This is not a desync, because the relayed commands keep the phones in step, but it would break a comparison of checksums with real-time runs. Remedy: scale the AI's `SDL_Delay`s with K too, or run the scaled and real-time checksums for `basic` and compare.
- The `Lag > 100` cap exists for real-time catch-up. Changing it is safe only in lockstep games; a fast engine must not run ahead of its peers, which `isInLag` already prevents.
- Any wall-clock value that reaches game logic would break determinism. I found none in `hedgewars/*.pas` other than the `RealTicks` uses for visuals, the AI wall delays above, `uVideoRec.pas` (recording only) and `DoTimer` itself.
- Renderer load: a software-rendered engine still draws once per `DoTimer` call. Faster ticks mean fewer draws per game second, which helps.

### Estimate (not measured)

- Current: about 200 to 320 s per turn-driven scenario, almost all of it game ticks. Most of that is game ticks, but the split is not measured yet.
- With K = 4 and the AI delays left in: about 70 to 100 s per scenario, a factor of 3. With the AI delays also scaled: about 50 to 70 s, a factor of 4 to 5, limited by launch and map loading.
- A full suite of 41 at -j 3: about 2.5 h serially, about 1 h at -j 3 now (the suite measured 591 s for six scenarios at -j 3). With K = 4 for the turn-driven subset, about 25 to 35 min.
- Effort: about one hour for the engine option and the first check, plus a checksum comparison of `basic` at K = 1 and K = 4 (the decider for the determinism question). Not started; it needs the heavy lock and a pas2c rebuild.

### Recommendation

Do the checksum comparison first: K = 1 vs K = 4 on `basic`. If the turn checksums match, extend to the candidate list. If they differ, scale the AI delays with K and compare again. Do not scale the relay or the slow-phone scenarios.

## Other testing notes

- `scripts/net-test.sh` header lists every scenario and its environment variables. `docs/multiphone-test.md` describes the phone and TV checks.
- `NET_BUILD_ONLY=1 scripts/net-test.sh viewer1` builds the engine, launcher and viewer stand-in and exits. `NET_SKIP_BUILD=1` skips every build step (the suite sets it).
