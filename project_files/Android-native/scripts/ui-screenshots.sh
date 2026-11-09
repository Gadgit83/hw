#!/usr/bin/env bash
# Optional: draws PNG pictures of every app screen with Robolectric's native graphics (Skia) on this machine, no phone needed.
# Not part of build-apk.sh or the smoke test. See README, "UI screenshots".
#   HP_PROFILE=test|full   which staged game data to show (default full; BUILD_DIR/assets must be staged for it, see stage-assets.sh)
#   SCREENSHOT_DIR=dir     where the PNGs go (default BUILD_DIR/screenshots/<profile>)
#   ROBOLECTRIC_DEPS, HP_MAVEN_MIRROR, GRADLE_OFFLINE   as for ui-smoke-test.sh
# Two devices are drawn: fp4 (Fairphone 4 sideways, 2340 x 1080 px at 409 dpi, cut-out on the left) and narrow
# (780 x 360 dp at xxhdpi, also 2340 x 1080 px). Files are <device>-<screen>.png.
. "$(dirname "$0")/common.sh"
"$HOST_DIR/scripts/fetch-robolectric.sh"
cd "$HOST_DIR"
export ROBOLECTRIC_DEPS="${ROBOLECTRIC_DEPS:-$HOME/.cache/hedgeparty/robolectric}"
export HP_MAVEN_MIRROR="${HP_MAVEN_MIRROR-https://maven-central.storage-download.googleapis.com/maven2}"
export SCREENSHOT_DIR="${SCREENSHOT_DIR:-$BUILD_DIR/screenshots/$HP_PROFILE}"
export BUILD_DIR THIRD_PARTY
log "UI screenshots (profile $HP_PROFILE) into $SCREENSHOT_DIR"
rm -rf "$SCREENSHOT_DIR"
./gradlew --no-daemon ${GRADLE_OFFLINE:+--offline} -Pprofile="$HP_PROFILE" -Pabis="$(echo $ABIS | tr ' ' ',')" -Pscreenshots \
  testDebugUnitTest --tests 'org.hedgewars.android.ScreenshotTest' --rerun-tasks
ls "$SCREENSHOT_DIR"/*.png >/dev/null
echo "Screenshots are in $SCREENSHOT_DIR"
