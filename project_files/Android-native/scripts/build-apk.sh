#!/usr/bin/env bash
# Fresh checkout to debug APK: fetch deps, transpile the engine, build Rust, stage data, run Gradle.
#   ABIS="arm64-v8a x86_64" (default)   ABIs to build
. "$(dirname "$0")/common.sh"
S="$HOST_DIR/scripts"
"$S/fetch-deps.sh"
"$S/gen-engine.sh"
"$S/build-rust.sh"
"$S/stage-assets.sh"
log "gradle assembleDebug"
cd "$HOST_DIR"
./gradlew --no-daemon -Pabis="$(echo $ABIS | tr ' ' ',')" assembleDebug
ls -la app/build/outputs/apk/debug/*.apk
