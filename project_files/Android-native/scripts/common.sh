#!/usr/bin/env bash
# Shared paths and settings for the host build scripts.
set -euo pipefail
HOST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd "$HOST_DIR/.." && pwd)"
# shellcheck source=versions.env
. "$HOST_DIR/scripts/versions.env"
BUILD_DIR="${BUILD_DIR:-$HOST_DIR/build}"
THIRD_PARTY="${THIRD_PARTY:-$HOST_DIR/third_party}"
# Engine source checkout (the Gadgit83/hw fork, branch android-master by default, which tracks upstream master).
HW_ENGINE_DIR="${HW_ENGINE_DIR:-$REPO_DIR/upstream/hw}"
if [ -z "${ANDROID_HOME:-}" ]; then
  for c in "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/android-sdk" /home/user/android-sdk; do
    [ -n "$c" ] && [ -d "$c/platform-tools" ] && ANDROID_HOME="$c" && break
  done
fi
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
NDK="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/$NDK_VERSION}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
ABIS="${ABIS:-arm64-v8a x86_64}"
log() { printf '\n==> %s\n' "$*"; }
