#!/usr/bin/env bash
# Host-side smoke test (no device needed). Builds the same pas2c engine and launcher for
# Linux x86_64 against desktop SDL2/OpenGL, starts a two-team game from the same config file
# the app writes, runs it headless under Xvfb for a while and saves screenshots.
# Needs: apt install clang cmake ninja-build libsdl2-dev libsdl2-image-dev libsdl2-mixer-dev
#        libsdl2-ttf-dev libsdl2-net-dev xvfb imagemagick
# Usage: [ASAN=ON] [HP_TEST_RESTORE=8,16] [HP_TEST_SCRIPT=...] [HP_TEST_ARGS=...] [HP_TEST_SHOTS=5,9] [HP_TEST_SIZE=2340x1080] host-test.sh [seconds]   (default 25)
#   HP_TEST_SCRIPT   timed input injection, see native/host-test/hp_host_main.c (touch events, focus lost/gained, ...)
#   HP_TEST_ARGS     extra engine options, e.g. "--ui-scale 240 --inset-left 80 --inset-top 40"
#   HP_TEST_SHOTS    seconds after start at which extra screenshots $T/shot_<n>.png are taken
#   HP_TEST_SIZE     window size (default 1280x720)
#   ASAN=ON builds with AddressSanitizer; HP_TEST_RESTORE posts SDL_WINDOWEVENT_RESTORED at those
#   seconds (the phone sends it at start-up and after every pause; it makes the engine run "fullscr").
. "$(dirname "$0")/common.sh"
SECS="${1:-25}"
T="$BUILD_DIR/host-test"
mkdir -p "$T" "$BUILD_DIR/rust-host"
export CC=clang   # ASAN=ON needs the clang runtime (apt: libclang-rt-dev)

log "rust library for the host"
mkdir -p "$BUILD_DIR/rust-src"; rsync -a --delete --exclude target "$HW_ENGINE_DIR/rust/" "$BUILD_DIR/rust-src/"
( cd "$BUILD_DIR/rust-src/lib-hwengine-future" && CARGO_TARGET_DIR="$BUILD_DIR/rust-target" cargo build --release >"$T/cargo.log" 2>&1 ) || { tail -30 "$T/cargo.log"; exit 1; }
cp "$BUILD_DIR/rust-target/release/libhwengine_future.so" "$BUILD_DIR/rust-host/"

log "pas2c with desktop GL"
OUT_DIR="$BUILD_DIR/engine-c-host" EXTRA_DEFS="-d HP_DESKTOP_GL" "$HOST_DIR/scripts/gen-engine.sh" | tail -1

log "build"
B="$T/build"; [ "${ASAN:-OFF}" = ON ] && B="$T/build-asan"
cmake -S "$HOST_DIR/native/host-test" -B "$B" -G Ninja -DCMAKE_BUILD_TYPE=Release -DHP_ASAN="${ASAN:-OFF}" >/dev/null
ninja -C "$B" | tail -1

log "game config from the app's GameConfig.java"
mkdir -p "$T/cfg" "$T/user"
javac -d "$T/cfg" "$HOST_DIR/app/src/main/java/party/hedge/host/GameConfig.java" "$HOST_DIR/native/host-test/GenConfig.java"
DATA="$BUILD_DIR/assets/Data"
java -cp "$T/cfg" GenConfig "$T/game.ipc" "$DATA/Themes" 4242 "Red Team" "Blue Team"

log "run for ${SECS}s under Xvfb"
export DISPLAY=:77
export ASAN_OPTIONS="${ASAN_OPTIONS:-detect_leaks=0:abort_on_error=0:halt_on_error=1}"
SIZE="${HP_TEST_SIZE:-1280x720}"; SW="${SIZE%x*}"; SH="${SIZE#*x}"
Xvfb :77 -screen 0 ${SIZE}x24 >/dev/null 2>&1 &
XP=$!
sleep 1
"$B/hp_hosttest" "$DATA" "$T/user" "$T/game.ipc" "$SW" "$SH" >"$T/engine.log" 2>&1 &
EP=$!
t0=$(date +%s.%N); n=0
SHOTS="${HP_TEST_SHOTS:-}"
for at in ${SHOTS//,/ }; do
  n=$((n+1)); now=$(date +%s.%N); d=$(echo "$at - ($now - $t0)" | bc); [ "$(echo "$d > 0" | bc)" = 1 ] && sleep "$d"
  import -window root "$T/shot_$n.png"
done
now=$(date +%s.%N); d=$(echo "$SECS - 3 - ($now - $t0)" | bc); [ "$(echo "$d > 0" | bc)" = 1 ] && sleep "$d"
import -window root "$T/screenshot.png"
sleep 3
alive=no; kill -0 $EP 2>/dev/null && alive=yes
kill $EP 2>/dev/null; sleep 1; kill $XP 2>/dev/null
echo "engine still running after ${SECS}s: $alive"
grep -E "Establishing IPC|engine connected|sent .* config|Generating land|HedgeParty" "$T/engine.log" | head
{ grep -ci "segmentation\|abort\|fatal\|AddressSanitizer" "$T/engine.log" || true; } | sed "s/^/crash markers: /"
echo "screenshot: $T/screenshot.png"
