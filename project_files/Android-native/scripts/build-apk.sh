#!/usr/bin/env bash
# Fresh checkout to debug APK: fetch deps, transpile the engine, build Rust, stage data, run Gradle.
# The generated inputs are reused while they are current: gen-engine.sh --ensure, build-rust.sh and stage-assets.sh each skip
# what their stamps show unchanged (see the header of each), so a second run without changes goes straight to Gradle.
#   ABIS="arm64-v8a x86_64" (default)   ABIs to build
#   HP_PROFILE=full|test                full (default): the normal app. test: party.hedge.host.test, arm64-v8a only, reduced game data
#   HP_BUILD_CACHE=dir                  opt-in shared cache of the engine C and the Rust library, keyed like the stamps (docs/TESTING.md)
#   SKIP_UI_SMOKE=1                     skip the launch smoke test (it runs by default and a failure stops the build)
#   --force                             regenerate the engine C and the Rust library and restage the data, whatever the stamps say
# The step times are printed at the end.
. "$(dirname "$0")/common.sh"
S="$HOST_DIR/scripts"
FORCE=0; [ "${1:-}" = --force ] && FORCE=1
TIMES=""
timed() { # timed NAME COMMAND...: runs it and notes how long it took
  local name="$1" t0; shift
  t0=$(date +%s)
  "$@"
  TIMES="$TIMES $name=$(( $(date +%s) - t0 ))s"
}
timed fetch-deps "$S/fetch-deps.sh"
if [ "$FORCE" = 1 ]; then
  timed engine-c "$S/gen-engine.sh"
  timed rust "$S/build-rust.sh" --force
  timed assets "$S/stage-assets.sh" --force
else
  timed engine-c "$S/gen-engine.sh" --ensure
  timed rust "$S/build-rust.sh"
  timed assets "$S/stage-assets.sh"
fi
if [ "${SKIP_UI_SMOKE:-0}" = 1 ]; then
  log "UI smoke test SKIPPED (SKIP_UI_SMOKE=1)"
else
  timed smoke "$S/ui-smoke-test.sh"
fi
log "gradle assembleDebug"
cd "$HOST_DIR"
timed gradle ./gradlew --no-daemon -Pprofile="$HP_PROFILE" -Pabis="$(echo $ABIS | tr ' ' ',')" assembleDebug
log "profile $HP_PROFILE, ABIs $ABIS"
ls -la app/build/outputs/apk/debug/*.apk
log "step times:$TIMES"
