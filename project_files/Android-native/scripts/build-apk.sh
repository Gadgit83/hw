#!/usr/bin/env bash
# Fresh checkout to debug APK: fetch deps, transpile the engine, build Rust, stage data, run Gradle.
#   ABIS="arm64-v8a x86_64" (default)   ABIs to build
#   HP_PROFILE=full|test                full (default): the normal app. test: party.hedge.host.test, arm64-v8a only, reduced game data
#   SKIP_UI_SMOKE=1                     skip the launch smoke test (it runs by default and a failure stops the build)
. "$(dirname "$0")/common.sh"
S="$HOST_DIR/scripts"
"$S/fetch-deps.sh"
"$S/gen-engine.sh"
"$S/build-rust.sh"
"$S/stage-assets.sh"
if [ "${SKIP_UI_SMOKE:-0}" = 1 ]; then
  log "UI smoke test SKIPPED (SKIP_UI_SMOKE=1)"
else
  "$S/ui-smoke-test.sh"
fi
log "gradle assembleDebug"
cd "$HOST_DIR"
./gradlew --no-daemon -Pprofile="$HP_PROFILE" -Pabis="$(echo $ABIS | tr ' ' ',')" assembleDebug
log "profile $HP_PROFILE, ABIs $ABIS"
ls -la app/build/outputs/apk/debug/*.apk
