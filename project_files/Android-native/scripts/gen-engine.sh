#!/usr/bin/env bash
# Transpile the Pascal engine to C with upstream's pas2c tool.
#  1. build tools/pas2c with the system GHC (apt: ghc; no Hackage packages needed)
#  2. write the config.inc that CMake would normally generate
#  3. run pas2c with the PAS2C_ANDROID define (selects the MOBILE code paths)
# Output: host/build/engine-c/*.c|*.h (gitignored) and host/build/engine-c/.engine-stamp.
#
# Modes:
#   gen-engine.sh            always regenerate and write the stamp
#   gen-engine.sh --check    exit 1 with a message when the stamp does not match the engine checkout
#   gen-engine.sh --ensure   regenerate only when the stamp does not match (used by the Gradle build)
#
# The stamp records the engine commit and a hash of everything pas2c reads (the committed tree of
# hedgewars/ and tools/pas2c, the uncommitted diff, untracked files, this script and EXTRA_DEFS).
# The Android build refuses to package C that was generated from a different engine state.
. "$(dirname "$0")/common.sh"
MODE="${1:-generate}"
P2C_DIR="$BUILD_DIR/pas2c"
# EXTRA_DEFS (e.g. "-d HP_DESKTOP_GL") and OUT_DIR serve the host smoke test (scripts/host-test.sh).
OUT="${OUT_DIR:-$BUILD_DIR/engine-c}"
STAMP="$OUT/.engine-stamp"

# The key and the stamp are only meaningful for a git checkout: without git every git call below
# would fail silently and the key would collapse to the hash of this script. Fail hard instead.
require_git_engine() {
  local top
  top="$(git -C "$HW_ENGINE_DIR" rev-parse --show-toplevel 2>/dev/null)" || top=""
  # the directory must be the root of its own checkout, not a plain copy inside some other repository
  if [ -z "$top" ] || [ "$(cd "$top" && pwd -P)" != "$(cd "$HW_ENGINE_DIR" && pwd -P)" ]; then
    echo "ENGINE CHECKOUT IS NOT A GIT REPOSITORY: $HW_ENGINE_DIR" >&2
    echo "The engine C cannot be checked against it. Point HW_ENGINE_DIR at a git checkout of the engine (host/scripts/fetch-deps.sh creates one)." >&2
    return 1
  fi
}

engine_key() {
  # Everything the generated C depends on, as one hash.
  require_git_engine || return 1
  {
    git -C "$HW_ENGINE_DIR" rev-parse HEAD:hedgewars HEAD:tools/pas2c
    git -C "$HW_ENGINE_DIR" diff HEAD -- hedgewars tools/pas2c
    git -C "$HW_ENGINE_DIR" ls-files -o --exclude-standard -- hedgewars tools/pas2c | sort | while read -r f; do
      echo "untracked $f"; cat "$HW_ENGINE_DIR/$f"
    done
    sha256sum "$HOST_DIR/scripts/gen-engine.sh"
    echo "defs ${EXTRA_DEFS:-}"
  } | sha256sum | cut -d' ' -f1
}
engine_head() { git -C "$HW_ENGINE_DIR" rev-parse HEAD 2>/dev/null || echo unknown; }

# A key that cannot be computed is a mismatch, never a match.
stamp_matches() {
  local key
  [ -f "$STAMP" ] || return 1
  key="$(engine_key)" || return 1
  [ -n "$key" ] && grep -qx "key $key" "$STAMP"
}

case "$MODE" in --check|--ensure|generate) require_git_engine || exit 3 ;; esac

case "$MODE" in
  --check)
    if stamp_matches; then echo "engine C in $OUT matches engine $(engine_head | cut -c1-9)"; exit 0; fi
    echo "STALE ENGINE C: $OUT was not generated from the current engine checkout ($HW_ENGINE_DIR at $(engine_head | cut -c1-9))." >&2
    echo "Run host/scripts/gen-engine.sh (or host/scripts/build-apk.sh) to regenerate it before building." >&2
    exit 1 ;;
  --ensure)
    if stamp_matches; then echo "engine C is up to date ($(engine_head | cut -c1-9))"; exit 0; fi
    echo "engine C is stale or missing, regenerating" ;;
  generate) ;;
  *) echo "usage: gen-engine.sh [--check|--ensure]" >&2; exit 2 ;;
esac

command -v ghc >/dev/null || { echo "ghc not found (apt-get install ghc), needed to regenerate stale engine C"; exit 1; }
mkdir -p "$P2C_DIR/obj" "$P2C_DIR/alt" "$OUT"
rm -f "$STAMP"

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
    -d ENDIAN_LITTLE -d DEBUGFILE -d PAS2C_ANDROID ${EXTRA_DEFS:-} >"$P2C_DIR/pas2c.log" 2>&1 || { tail -20 "$P2C_DIR/pas2c.log"; exit 1; }
echo "generated $(ls "$OUT"/*.c | wc -l) C files in $OUT"
{ echo "engine $(engine_head)"; echo "dirty $(git -C "$HW_ENGINE_DIR" diff HEAD --shortstat -- hedgewars tools/pas2c | tr -s ' ')"; echo "defs ${EXTRA_DEFS:-}"; echo "key $(engine_key)"; } > "$STAMP"
echo "stamp: $(head -1 "$STAMP")"
