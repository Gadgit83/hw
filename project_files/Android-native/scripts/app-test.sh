#!/usr/bin/env bash
# Quick JVM tests of the app (Robolectric and the other unit tests) without assembling the APK: no dex, no packaging,
# no native build. The native inputs are reused: gen-engine.sh --ensure, build-rust.sh and stage-assets.sh each skip what
# is current (their stamps), so only what is missing or stale is built, exactly as build-apk.sh would build it.
#   app-test.sh                                  every unit test (src/test; the screenshot test is off unless -Pscreenshots)
#   app-test.sh LaunchSmokeTest RejoinCardTest   only those classes (simple names or fully qualified)
# Environment, as in build-apk.sh and ui-smoke-test.sh: HP_PROFILE (full|test), ABIS (default: the profile's ABIs),
# ROBOLECTRIC_DEPS, HP_MAVEN_MIRROR, GRADLE_OFFLINE=1. The tests are always rerun (cleanTestDebugUnitTest), so an
# unchanged test is not skipped by Gradle's up-to-date check.
. "$(dirname "$0")/common.sh"
S="$HOST_DIR/scripts"
T0=$(date +%s)
"$S/fetch-deps.sh"
"$S/fetch-robolectric.sh"
"$S/gen-engine.sh" --ensure
"$S/build-rust.sh"
"$S/stage-assets.sh"
export ROBOLECTRIC_DEPS="${ROBOLECTRIC_DEPS:-$HOME/.cache/hedgeparty/robolectric}"
export HP_MAVEN_MIRROR="${HP_MAVEN_MIRROR-https://maven-central.storage-download.googleapis.com/maven2}"
export BUILD_DIR THIRD_PARTY
TESTS=()
for c in "$@"; do
  case "$c" in *.*) TESTS+=(--tests "$c") ;; *) TESTS+=(--tests "*.$c") ;; esac
done
log "unit tests, profile $HP_PROFILE${*:+, classes: $*} (no APK)"
cd "$HOST_DIR"
./gradlew --no-daemon ${GRADLE_OFFLINE:+--offline} -Pprofile="$HP_PROFILE" -Pabis="$(echo $ABIS | tr ' ' ',')" \
  cleanTestDebugUnitTest testDebugUnitTest "${TESTS[@]}"
echo "app tests passed in $(( $(date +%s) - T0 ))s"
