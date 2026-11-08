#!/usr/bin/env bash
# Build the engine's Rust library (map generator, AI helpers) for Android with cargo.
# Needs: rustup targets aarch64-linux-android / x86_64-linux-android and the NDK.
# The workspace is copied to host/build/rust-src first so no Cargo.lock lands in the engine checkout.
. "$(dirname "$0")/common.sh"
command -v cargo >/dev/null || { echo "cargo not found"; exit 1; }
SRC="$BUILD_DIR/rust-src"
mkdir -p "$SRC" "$BUILD_DIR/rust"
cp -a "$HW_ENGINE_DIR/rust/." "$SRC/"
for abi in $ABIS; do
  case "$abi" in
    arm64-v8a) triple=aarch64-linux-android; clang=aarch64-linux-android24-clang ;;
    x86_64)    triple=x86_64-linux-android;  clang=x86_64-linux-android24-clang ;;
    *) echo "unsupported ABI $abi"; exit 1 ;;
  esac
  rustup target add "$triple" >/dev/null 2>&1 || true
  log "cargo build $triple"
  up=$(echo "$triple" | tr 'a-z-' 'A-Z_')
  export "CARGO_TARGET_${up}_LINKER=$TOOLCHAIN/bin/$clang"
  export "CARGO_TARGET_${up}_RUSTFLAGS=-C link-arg=-Wl,-soname,libhwengine_future.so -C link-arg=-Wl,-z,max-page-size=16384"
  ( cd "$SRC/lib-hwengine-future" && CARGO_TARGET_DIR="$BUILD_DIR/rust-target" cargo build --release --target "$triple" )
  mkdir -p "$BUILD_DIR/rust/$abi"
  cp "$BUILD_DIR/rust-target/$triple/release/libhwengine_future.so" "$BUILD_DIR/rust/$abi/"
  "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$BUILD_DIR/rust/$abi/libhwengine_future.so"
done
ls -la "$BUILD_DIR"/rust/*/
