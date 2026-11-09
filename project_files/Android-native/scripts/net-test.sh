#!/usr/bin/env bash
# Host-side proof of the multi-phone relay (no device needed): the relay core on the JVM and several desktop engines,
# each started like a phone starts its engine (game.ipc + net.cfg, launcher in network mode), under Xvfb.
#
# Usage: net-test.sh [SCENARIO]      (default basic)
#   basic       two phones play a whole game
#   basic3      three phones play a whole game
#   dropout     two phones, phone B's engine is killed after a few turns        dropout3  the same with three phones
#   stall       two phones, phone B's engine is frozen (SIGSTOP): the silence rule must drop it
#   slowstart   two phones, phone B is 30 s late with its map digest: nobody may be dropped, no desync
#   negcontrol  NEGATIVE CONTROL: the relay alters one byte of one turn checksum for phone B; passes only if the
#               desync detection fires (a harness that cannot fail would prove nothing)
#   solo        one phone with three teams        viewer1  the same with the TV viewer from the start
#   viewer2     two phones, the TV viewer (viewer-sim.js + a spectator engine) joins late
#   malformed   no engines: hostile and malformed input to the relay (JSON bombs, huge lines, bad UTF-8, bad WebSocket
#               frames, hostile team fields, silence-rule arming, host role, caps); the relay must stay up
#   all         every scenario above in turn, with a summary at the end
# Exit status: 0 only if the scenario passed. A positive scenario fails on any "Desync detected", "Unexpected protocol"
# or engine queue error, a non-zero engine exit (except the victim's), differing per-turn checksums, differing relay and
# launcher stream hashes, or an unexpected dropout. The last line is "RESULT: PASS" or "RESULT: FAIL ...".
#
# Prerequisites (the engine scenarios; "malformed" needs only Java and the staged game data):
#   scripts/stage-assets.sh        stages the game data in build/assets/Data (also done by build-apk.sh)
#   scripts/host-test.sh           builds the desktop pas2c engine and the Rust library (this script runs it once itself if
#                                  build/engine-c-host or build/rust-host is missing; that needs clang, cmake, ninja, SDL2
#                                  dev packages, cargo, see its header)
#   Xvfb (the display is started here), node (viewer scenarios), a checkout of hedgewars-party (viewer scenarios, PARTY_REPO)
# Environment: HP_TEST_DISPLAY, a bare display number (default 66, as host-test.sh: no colon), NET_SEED,
#   NET_TURNS_BEFORE_KILL, NET_TIMEOUT_S, NET_SILENCE_MS, NET_GRACE_MS, NET_DELAY_MS (slowstart), NET_SCRIPT,
#   NET_INPUT=1 (scripted touch input for the human teams), ASAN=ON
. "$(dirname "$0")/common.sh"
SCEN="${1:-basic}"
if [ "$SCEN" = all ]; then
  rc=0; summary=""
  for s in malformed basic basic3 dropout dropout3 stall slowstart negcontrol solo viewer1 viewer2; do
    "$0" "$s" > "$BUILD_DIR/host-test-net-$s.log" 2>&1; r=$?
    res="$(grep '^RESULT:' "$BUILD_DIR/host-test-net-$s.log" | tail -1)"
    summary+="$(printf '%-11s exit %d  %s' "$s" "$r" "${res:-(no result line)}")"$'\n'
    [ "$r" -ne 0 ] && rc=1
  done
  printf '\n%s' "$summary"
  [ "$rc" -eq 0 ] && echo "ALL SCENARIOS PASSED" || echo "SOME SCENARIOS FAILED"
  exit $rc
fi
T="$BUILD_DIR/host-test"
B="$T/build"; [ "${ASAN:-OFF}" = ON ] && B="$T/build-asan"
JSRC="$HOST_DIR/app/src/main/java/org/hedgewars/android"
OUT="$T/net-classes-$SCEN"
if [ "$SCEN" = malformed ]; then
  log "relay and test classes"
  rm -rf "$OUT"; mkdir -p "$OUT"
  javac -d "$OUT" "$JSRC"/net/*.java "$JSRC/GameConfig.java" "$JSRC/GameData.java" "$JSRC/Rules.java" "$JSRC/SchemeData.java" \
    "$JSRC/SetupModel.java" "$HOST_DIR/native/host-test/RelayTests.java" 2>&1 | grep -v "Picked up" || true
  log "scenario malformed"
  set +e; java -cp "$OUT" RelayTests "$BUILD_DIR/assets/Data" 2>&1 | grep --line-buffered -v "Picked up" | tee "$T/net-malformed.log"; rc=${PIPESTATUS[0]}; set -e
  exit "$rc"
fi
export CC=clang
if [ ! -d "$BUILD_DIR/engine-c-host" ] || [ ! -f "$BUILD_DIR/rust-host/libhwengine_future.so" ]; then
  log "engine for the host (scripts/host-test.sh builds the same)"; HP_TEST_SCENARIO=ai "$HOST_DIR/scripts/host-test.sh" 1 >/dev/null 2>&1 || true
fi
log "launcher (network mode)"
cmake -S "$HOST_DIR/native/host-test" -B "$B" -G Ninja -DCMAKE_BUILD_TYPE=Release -DHP_ASAN="${ASAN:-OFF}" >/dev/null
ninja -C "$B" | tail -1
if [[ "$SCEN" == viewer* ]]; then
  # The existing TV stand-ins from hedgewars-party (tv/e2e-host): the viewer page's test client and the spectator engine launcher.
  PARTY="${PARTY_REPO:-/home/user/hedgewars-party}"; PC="$BUILD_DIR/host-test/pc_host"; mkdir -p "$PC/src"
  { git -C "$PARTY" show origin/main:tv/e2e-host/src/pc_host.c; echo 'void hw_device_rumble(int ms) { (void)ms; }   /* the engine now calls this hook */'; } > "$PC/src/pc_host.c"
  git -C "$PARTY" show origin/main:tv/e2e-host/src/CMakeLists.txt | sed "s/HP_GLES1=0/HW_GLES1=0/" > "$PC/src/CMakeLists.txt"   # the app renamed this define
  git -C "$PARTY" show origin/main:tv/e2e-host/tools/viewer-sim.js > "$PC/viewer-sim.js"
  cmake -S "$PC/src" -B "$PC/build" -G Ninja -DCMAKE_BUILD_TYPE=Release -DHP_ENGINE_SRC="$REPO_DIR" -DHP_ENGINE_C="$BUILD_DIR/engine-c-host" \
    -DHP_RUST_LIB="$BUILD_DIR/rust-host/libhwengine_future.so" >/dev/null && ninja -C "$PC/build" | tail -1
  export NET_PCHOST="$PC/build/pc_host" NET_VIEWER_SIM="$PC/viewer-sim.js"
fi
log "relay and harness classes"
rm -rf "$OUT"; mkdir -p "$OUT"
javac -d "$OUT" "$JSRC"/net/*.java "$JSRC/GameConfig.java" "$JSRC/GameData.java" "$JSRC/Rules.java" "$JSRC/SchemeData.java" \
  "$JSRC/SetupModel.java" "$HOST_DIR/native/host-test/NetHarness.java" 2>&1 | grep -v "Picked up" || true
export DISPLAY=":${HP_TEST_DISPLAY:-66}"
export NET_SPEC_PORT="${NET_SPEC_PORT:-$((18700 + ${HP_TEST_DISPLAY:-66}))}"   # the spectator engine of the viewer scenarios; one per display
export ASAN_OPTIONS="${ASAN_OPTIONS:-detect_leaks=0:abort_on_error=0:halt_on_error=1}"
Xvfb "$DISPLAY" -screen 0 1280x720x24 >/dev/null 2>&1 &
XP=$!
trap 'kill $XP 2>/dev/null' EXIT
sleep 1
log "scenario $SCEN"
set +e
java -cp "$OUT" NetHarness "$BUILD_DIR/assets/Data" "$T/net-$SCEN" "$B/hp_hosttest" "$SCEN" 2>&1 | grep --line-buffered -v "Picked up" | tee "$T/net-$SCEN.log"
rc=${PIPESTATUS[0]}
set -e
exit "$rc"
