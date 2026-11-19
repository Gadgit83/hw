#!/usr/bin/env bash
# Build the engine's Rust library (map generator, AI helpers) for Android with cargo, one ABI at a time.
# Needs: rustup targets aarch64-linux-android / armv7-linux-androideabi / x86_64-linux-android and the NDK.
# The workspace is copied to build/rust-src first so no Cargo.lock lands in the engine checkout.
#
# An ABI is built only when its inputs changed. Its stamp, build/rust-stamps/<abi>.stamp, holds a key over the Rust sources
# (the committed tree of rust/, the uncommitted diff and the untracked files), this script, the ABI, the NDK version and the
# rustc and cargo versions. An ABI whose stamp matches, with its library in place, is skipped.
#   HP_BUILD_CACHE=dir      opt-in shared cache: a library stored under the same key is copied instead of built, and a built
#                           library is stored there. Cargo's own target folder is never shared (it holds absolute paths).
#   build-rust.sh           build the ABIs whose inputs changed
#   build-rust.sh --force   build every ABI again (the cache is not read; the result is still stored)
. "$(dirname "$0")/common.sh"
FORCE=0; [ "${1:-}" = --force ] && FORCE=1
command -v cargo >/dev/null || { echo "cargo not found"; exit 1; }
SRC="$BUILD_DIR/rust-src"
STAMPS="$BUILD_DIR/rust-stamps"   # beside build/rust, not inside it: the Gradle build packages build/rust as jniLibs
mkdir -p "$BUILD_DIR/rust" "$STAMPS"

triple_of() {
  case "$1" in
    arm64-v8a) echo aarch64-linux-android ;;
    x86_64) echo x86_64-linux-android ;;
    armeabi-v7a) echo armv7-linux-androideabi ;;
    *) echo "unsupported ABI $1" >&2; return 1 ;;
  esac
}
clang_of() {
  case "$1" in
    arm64-v8a) echo aarch64-linux-android24-clang ;;
    x86_64) echo x86_64-linux-android24-clang ;;
    armeabi-v7a) echo armv7a-linux-androideabi24-clang ;;
  esac
}
rust_key() { # rust_key ABI: the key of that ABI's inputs. Fails when the engine is not a git checkout.
  local abi="$1"
  git -C "$HW_ENGINE_DIR" rev-parse --show-toplevel >/dev/null 2>&1 || return 1
  {
    git -C "$HW_ENGINE_DIR" rev-parse HEAD:rust || return 1
    git -C "$HW_ENGINE_DIR" diff HEAD -- rust
    git -C "$HW_ENGINE_DIR" ls-files -o --exclude-standard -- rust | sort | while IFS= read -r f; do
      echo "untracked $f"; cat "$HW_ENGINE_DIR/$f"
    done
    sha256sum "$0"
    echo "abi $abi triple $(triple_of "$abi") ndk $NDK_VERSION $(rustc -V 2>/dev/null) $(cargo -V 2>/dev/null)"
  } | sha256sum | cut -d' ' -f1
}

todo=()
for abi in $ABIS; do
  triple_of "$abi" >/dev/null || exit 1
  key="$(rust_key "$abi")" || key=""
  stamp="$STAMPS/$abi.stamp"; so="$BUILD_DIR/rust/$abi/libhwengine_future.so"
  if [ "$FORCE" = 0 ] && [ -n "$key" ] && [ -s "$so" ] && [ -f "$stamp" ] && grep -qx "key $key" "$stamp"; then
    echo "rust $abi is up to date, skipped"
    continue
  fi
  cached=""
  [ -n "${HP_BUILD_CACHE:-}" ] && [ -n "$key" ] && cached="$HP_BUILD_CACHE/rust/$key/libhwengine_future.so"
  if [ "$FORCE" = 0 ] && [ -n "$cached" ] && [ -s "$cached" ]; then
    mkdir -p "$BUILD_DIR/rust/$abi"
    cp "$cached" "$so"
    echo "key $key" > "$stamp"
    echo "rust $abi restored from the shared cache: $cached"
    continue
  fi
  todo+=("$abi $key")
done

if [ "${#todo[@]}" -gt 0 ]; then
  mkdir -p "$SRC"
  rsync -a --delete --exclude target "$HW_ENGINE_DIR/rust/" "$SRC/"
  for item in "${todo[@]}"; do
    abi="${item% *}"; key="${item#* }"
    triple="$(triple_of "$abi")"; clang="$(clang_of "$abi")"
    stamp="$STAMPS/$abi.stamp"; so="$BUILD_DIR/rust/$abi/libhwengine_future.so"
    rm -f "$stamp"
    rustup target add "$triple" >/dev/null 2>&1 || true
    log "cargo build $triple"
    up=$(echo "$triple" | tr 'a-z-' 'A-Z_')
    export "CARGO_TARGET_${up}_LINKER=$TOOLCHAIN/bin/$clang"
    export "CARGO_TARGET_${up}_RUSTFLAGS=-C link-arg=-Wl,-soname,libhwengine_future.so -C link-arg=-Wl,-z,max-page-size=16384"
    ( cd "$SRC/lib-hwengine-future" && CARGO_TARGET_DIR="$BUILD_DIR/rust-target" cargo build --release --target "$triple" )
    mkdir -p "$BUILD_DIR/rust/$abi"
    cp "$BUILD_DIR/rust-target/$triple/release/libhwengine_future.so" "$BUILD_DIR/rust/$abi/"
    "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$so"
    if [ -n "$key" ]; then
      echo "key $key" > "$stamp"
      if [ -n "${HP_BUILD_CACHE:-}" ]; then
        mkdir -p "$HP_BUILD_CACHE/rust/$key"
        if cp "$so" "$HP_BUILD_CACHE/rust/$key/.tmp.$$" && mv -f "$HP_BUILD_CACHE/rust/$key/.tmp.$$" "$HP_BUILD_CACHE/rust/$key/libhwengine_future.so"; then
          echo "stored in the shared cache: $HP_BUILD_CACHE/rust/$key"
        else
          echo "warning: could not store the Rust library in the shared cache" >&2
        fi
      fi
    fi
  done
fi
ls -la "$BUILD_DIR"/rust/*/
