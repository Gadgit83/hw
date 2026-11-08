#!/usr/bin/env bash
# JVM checks of the setup model and GameConfig against the desktop frontend's sources (no device, no engine run):
# scheme presets, weapon sets, flag bits, e$ lines and engine commands, 8 teams of 8 hedgehogs, every map type and
# game style, saving and loading. Needs the staged game data (stage-assets.sh) and the engine checkout.
. "$(dirname "$0")/common.sh"
T="$BUILD_DIR/config-test"
JSRC="$HOST_DIR/app/src/main/java/party/hedge/host"
rm -rf "$T"; mkdir -p "$T"
javac -d "$T" "$JSRC/GameConfig.java" "$JSRC/GameData.java" "$JSRC/Rules.java" "$JSRC/SchemeData.java" \
  "$JSRC/SetupModel.java" "$JSRC/Options.java" "$HOST_DIR/native/host-test/ConfigCheck.java"
java -cp "$T" ConfigCheck "$BUILD_DIR/assets/Data" "$HW_ENGINE_DIR"
