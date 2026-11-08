#!/usr/bin/env bash
# Launch smoke test: opens every screen (setup tabs, options tabs, team page, first launch without data) under
# Android 14 and 15 on a Fairphone-sized screen with Robolectric and fails if anything throws.
# Needs the generated inputs (scripts/stage-assets.sh and the rest of build-apk.sh's steps) for the Gradle project.
#   ROBOLECTRIC_DEPS=dir   cache of the Android jars (default ~/.cache/hedgeparty/robolectric), filled here if needed
#   HP_MAVEN_MIRROR=url    Maven Central mirror for Robolectric's own jars (default: Google's copy, as in fetch-robolectric.sh)
#   GRADLE_OFFLINE=1       pass --offline to Gradle
. "$(dirname "$0")/common.sh"
"$HOST_DIR/scripts/fetch-robolectric.sh"
log "UI smoke test (Robolectric, SDK 34 and 35)"
cd "$HOST_DIR"
export ROBOLECTRIC_DEPS="${ROBOLECTRIC_DEPS:-$HOME/.cache/hedgeparty/robolectric}"
export HP_MAVEN_MIRROR="${HP_MAVEN_MIRROR-https://maven-central.storage-download.googleapis.com/maven2}"
export BUILD_DIR THIRD_PARTY
./gradlew --no-daemon ${GRADLE_OFFLINE:+--offline} -Pabis="$(echo $ABIS | tr ' ' ',')" testDebugUnitTest --tests 'org.hedgewars.android.LaunchSmokeTest' --rerun-tasks
echo "UI smoke test passed"
