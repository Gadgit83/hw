#!/usr/bin/env bash
# Shared paths and settings for the host build scripts.
set -euo pipefail
HOST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"      # project_files/Android-native
REPO_DIR="$(cd "$HOST_DIR/../.." && pwd)"                        # the repository root, which is the engine
# shellcheck source=versions.env
. "$HOST_DIR/scripts/versions.env"
BUILD_DIR="${BUILD_DIR:-$HOST_DIR/build}"
THIRD_PARTY="${THIRD_PARTY:-$HOST_DIR/third_party}"
# The engine is the checkout this app lives in. HW_ENGINE_DIR is only for building the app against another
# git checkout of the engine (it must contain this directory's scripts' expectations: hedgewars/, tools/pas2c, rust/, ...).
HW_ENGINE_DIR="${HW_ENGINE_DIR:-$REPO_DIR}"
if [ -z "${ANDROID_HOME:-}" ]; then
  for c in "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/android-sdk" /home/user/android-sdk; do
    [ -n "$c" ] && [ -d "$c/platform-tools" ] && ANDROID_HOME="$c" && break
  done
fi
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
NDK="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/$NDK_VERSION}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
# Build profile: full (default, the normal app) or test (small APK for automated phone and TV tests, see README "Build profiles").
PROFILE="${PROFILE:-full}"
case "$PROFILE" in
  full) ABIS="${ABIS:-arm64-v8a x86_64}" ;;
  test) ABIS="${ABIS:-arm64-v8a}" ;;
  *) echo "unknown PROFILE=$PROFILE (full or test)"; exit 1 ;;
esac
export PROFILE
log() { printf '\n==> %s\n' "$*"; }
