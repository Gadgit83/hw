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
# It is set with HP_PROFILE, not PROFILE: PROFILE is a generic name that other tools set, and a silent full build is the worse failure.
if [ -n "${PROFILE:-}" ] && [ -z "${HP_PROFILE:-}" ]; then
  echo "PROFILE is no longer read; use HP_PROFILE=$PROFILE (full or test)"; exit 1
fi
HP_PROFILE="${HP_PROFILE:-full}"
case "$HP_PROFILE" in
  full) ABIS="${ABIS:-arm64-v8a x86_64}" ;;
  test) ABIS="${ABIS:-arm64-v8a}" ;;
  *) echo "unknown HP_PROFILE=$HP_PROFILE (full or test)"; exit 1 ;;
esac
export HP_PROFILE
log() { printf '\n==> %s\n' "$*"; }

# The machine's heavy lock, shared with the other workers (docs/TESTING.md). heavy CMD... runs a heavy step under it. A
# caller that already holds the lock (HP_HEAVY_HELD=1) runs the command directly. flock -o: the lock stays with the flock process
# and is not handed down, so a background child that outlives the step (an Xvfb left by a killed host test) cannot keep it.
HP_HEAVY_LOCK="${HP_HEAVY_LOCK:-/tmp/claude-0/-home-user/ac142ab0-1bea-5b6c-a7cb-3a9f55df2846/scratchpad/heavy.lock}"
heavy() {
  if [ -n "${HP_HEAVY_HELD:-}" ]; then "$@"; return; fi
  flock -o "$HP_HEAVY_LOCK" env HP_HEAVY_HELD=1 "$@"
}

# One hash of what git knows about some paths of a checkout: the blob id of every tracked file, the content of each file that
# differs from the index (or is gone), and the content of each untracked file. Git's own diff shows a binary file only as
# "differ", so the content is hashed here. Fails when GITDIR is not a git checkout (the caller then never matches a stamp).
tree_fingerprint() { # GITDIR PATH...
  local g="$1" f; shift
  git -C "$g" rev-parse --show-toplevel >/dev/null 2>&1 || return 1
  {
    git -C "$g" ls-files -s -- "$@"
    git -C "$g" diff --name-only -- "$@" | sort | while IFS= read -r f; do
      echo "changed $f"; sha256sum "$g/$f" 2>/dev/null || echo gone; done
    git -C "$g" ls-files -o --exclude-standard -- "$@" | sort | while IFS= read -r f; do
      echo "untracked $f"; sha256sum "$g/$f"; done
  } | sha256sum | cut -d' ' -f1
}
