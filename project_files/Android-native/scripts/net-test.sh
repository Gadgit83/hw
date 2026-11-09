#!/usr/bin/env bash
# Host-side proof of the multi-phone relay (no device needed): the relay core on the JVM and several desktop engines,
# each started like a phone starts its engine (game.ipc + net.cfg, launcher in network mode), under Xvfb.
# Builds what scripts/host-test.sh builds (run that once first, or let this script do the same steps).
# Usage: net-test.sh [basic|dropout|stall|solo|three|dropout3|viewer1|viewer2]     (default basic)
# Environment: HP_TEST_DISPLAY, a bare display number (default 66), NET_SEED, NET_TURNS_BEFORE_KILL, NET_TIMEOUT_S, NET_SILENCE_MS, NET_SCRIPT, NET_INPUT=1 (scripted touch input for the human teams), ASAN=ON
. "$(dirname "$0")/common.sh"
SCEN="${1:-basic}"
T="$BUILD_DIR/host-test"
B="$T/build"; [ "${ASAN:-OFF}" = ON ] && B="$T/build-asan"
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
OUT="$T/net-classes"; rm -rf "$OUT"; mkdir -p "$OUT"
JSRC="$HOST_DIR/app/src/main/java/org/hedgewars/android"
javac -d "$OUT" "$JSRC"/net/*.java "$JSRC/GameConfig.java" "$JSRC/GameData.java" "$JSRC/Rules.java" "$JSRC/SchemeData.java" \
  "$JSRC/SetupModel.java" "$HOST_DIR/native/host-test/NetHarness.java" 2>&1 | grep -v "Picked up" || true
export DISPLAY=":${HP_TEST_DISPLAY:-66}"
export ASAN_OPTIONS="${ASAN_OPTIONS:-detect_leaks=0:abort_on_error=0:halt_on_error=1}"
Xvfb "$DISPLAY" -screen 0 1280x720x24 >/dev/null 2>&1 &
XP=$!
trap 'kill $XP 2>/dev/null' EXIT
sleep 1
log "scenario $SCEN"
java -cp "$OUT" NetHarness "$BUILD_DIR/assets/Data" "$T/net-$SCEN" "$B/hp_hosttest" "$SCEN" 2>&1 | grep -v "Picked up"
