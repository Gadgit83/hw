#!/usr/bin/env bash
# Transpile the Pascal engine to C with upstream's pas2c tool.
#  1. build tools/pas2c with the system GHC (apt: ghc; no Hackage packages needed)
#  2. write the config.inc that CMake would normally generate
#  3. run pas2c with the PAS2C_ANDROID define (selects the MOBILE code paths)
# Output: host/build/engine-c/*.c|*.h (gitignored).
. "$(dirname "$0")/common.sh"
command -v ghc >/dev/null || { echo "ghc not found (apt-get install ghc)"; exit 1; }
P2C_DIR="$BUILD_DIR/pas2c"
OUT="$BUILD_DIR/engine-c"
mkdir -p "$P2C_DIR/obj" "$P2C_DIR/alt" "$OUT"

log "building pas2c"
( cd "$HW_ENGINE_DIR/tools/pas2c" && ghc -O1 --make Main.hs -i. -o "$P2C_DIR/pas2c" \
    -odir "$P2C_DIR/obj" -hidir "$P2C_DIR/obj" >"$P2C_DIR/ghc.log" 2>&1 ) || { tail -20 "$P2C_DIR/ghc.log"; exit 1; }

cat > "$P2C_DIR/alt/config.inc" <<'EOC'
const cNetProtoVersion = 60;
      cVersionString = '1.1.0-party';
      cRevisionString = 'android';
      cHashString = 'native';
      cDefaultPathPrefix = '/data/Data';
      cFontsPaths: array [0..1] of PChar = (nil, nil);
EOC

log "pas2c: Pascal -> C"
"$P2C_DIR/pas2c" -n hwengine -i "$HW_ENGINE_DIR/hedgewars" -o "$OUT" -a "$P2C_DIR/alt" \
    -d ENDIAN_LITTLE -d DEBUGFILE -d PAS2C_ANDROID >"$P2C_DIR/pas2c.log" 2>&1 || { tail -20 "$P2C_DIR/pas2c.log"; exit 1; }
echo "generated $(ls "$OUT"/*.c | wc -l) C files in $OUT"
