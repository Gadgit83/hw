#!/usr/bin/env bash
# Tiered release check. Always: the test-profile data and config-test, the test APK for arm64-v8a and armeabi-v7a, and the
# launch smoke test on it. Then the network suite, in one of two tiers chosen from the diff against a base:
#   full   the full release list (net-suite.sh with no names): with --full, or when `git diff --name-only BASE` touches
#          app/src/main/java/org/hedgewars/android/net/, native/launcher/ or hedgewars/ (the engine, at the repository root)
#   core   otherwise: malformed basic basic3 rejoin hostleave watch1 hostpause settings, plus the scenarios named after
#          the changed files (Rejoin, Join/NetLobby/NetSession, Pause, Watch/Spectat, Viewer, Chat)
#
# Usage: release-check.sh [--base REF] [--full | --core] [--plan]
#   --base REF   the release the change is measured from. Default: the newest tvN tag that HEAD is past.
#   --full       the full network list, whatever the diff says
#   --core       the core tier, whatever the diff says: for a change the coordinator has read and judged not to alter the
#                relay's or the engine's behaviour (a log line, a helper the relay does not call). The summary records it.
#   --plan       print the steps and the network tier, run nothing
# Output: build/release-check/<UTC time>/summary.txt (build/release-check/latest links to the newest one), with the
# per-step results and times, the network results file (net-suite.tsv) and the suite's summary.
# config-test, build-apk.sh and ui-smoke-test.sh each take the machine's heavy lock (HP_HEAVY_LOCK) for their own run;
# net-suite.sh takes it itself. Do not wrap this script in flock: that would hold the lock while the steps wait for it.
. "$(dirname "$0")/common.sh"
S="$HOST_DIR/scripts"
FULL=0; CORE_ONLY=0; PLAN=0; BASE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --full) FULL=1 ;;
    --core) CORE_ONLY=1 ;;
    --plan) PLAN=1 ;;
    --base) BASE="${2:?--base needs a ref}"; shift ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done
if ps -o args= -p "$PPID" 2>/dev/null | grep -F -- "$HP_HEAVY_LOCK" | grep -q "^flock "; then
  echo "release-check.sh is running under flock on the heavy lock: its steps would wait for that lock for ever." >&2
  echo "Run it without flock; it takes the lock for each heavy step." >&2
  exit 2
fi

REPO="$REPO_DIR"; HEAD_SHA="$(git -C "$REPO" rev-parse HEAD)"
if [ -z "$BASE" ]; then   # the newest release tag that HEAD is past (a tag on HEAD itself is not a base)
  for t in $(git -C "$REPO" tag -l 'tv[0-9]*' --sort=-version:refname | grep -E '^tv[0-9]+$'); do
    c="$(git -C "$REPO" rev-parse "$t^{commit}")"
    if [ "$c" != "$HEAD_SHA" ] && git -C "$REPO" merge-base --is-ancestor "$c" "$HEAD_SHA"; then BASE="$t"; break; fi
  done
  [ -n "$BASE" ] || { echo "no tvN release tag before HEAD; pass --base REF" >&2; exit 2; }
fi
git -C "$REPO" rev-parse --verify -q "$BASE^{commit}" >/dev/null || { echo "unknown base: $BASE" >&2; exit 2; }
CHANGED="$(git -C "$REPO" diff --name-only "$BASE")"

# The network tier.
HOST_REL="${HOST_DIR#"$REPO/"}"
TIER=core; WHY=""
if [ "$FULL" = 1 ]; then TIER=full; WHY="--full"; fi
while IFS= read -r f; do
  [ -n "$f" ] || continue
  case "$f" in
    "$HOST_REL/app/src/main/java/org/hedgewars/android/net/"*|"$HOST_REL/native/launcher/"*|hedgewars/*)
      TIER=full; WHY="$WHY $f" ;;
  esac
done <<< "$CHANGED"
if [ "$CORE_ONLY" = 1 ] && [ "$FULL" = 0 ]; then
  [ "$TIER" = full ] && WHY="--core given; the diff alone would pick full:$WHY"
  TIER=core
fi
CORE="malformed basic basic3 rejoin hostleave watch1 hostpause settings"
EXTRA=""
add() { local s; for s in "$@"; do case " $CORE $EXTRA " in *" $s "*) ;; *) EXTRA="$EXTRA $s" ;; esac; done; }
while IFS= read -r f; do   # scenarios named after the changed files (used by the core tier)
  case "$f" in
    *Rejoin*) add rejoin rejoinlate ;;
    */net/Relay.java) add hostrejoin2 joinerrejoin2 hostrejoinpause ;;
    *JoinService*|*NetLobby*|*NetSession*|*Join*) add joinerquit joinerquitchat ;;
    *Pause*) add hostpause hostpausequit joinerpause pausecap ;;
    *Watch*|*Spectat*) add watch1 watchlate watchsend ;;
    *Viewer*|*viewer*) add viewer1 ;;
    *Chat*) add hostquitchat ;;
  esac
done <<< "$CHANGED"
if [ "$TIER" = full ]; then SCEN="(all, the full release list)"; else SCEN="$CORE$EXTRA"; fi

if [ "$PLAN" = 1 ]; then
  echo "base:          $BASE ($(git -C "$REPO" rev-parse --short "$BASE"), HEAD $(git -C "$REPO" rev-parse --short HEAD))"
  echo "changed files: $(printf '%s\n' "$CHANGED" | grep -c . || true)"
  echo "network tier:  $TIER ${WHY:+(because: $WHY)}"
  echo "scenarios:     $SCEN"
  echo "steps:         stage test data; config-test; test APK arm64-v8a+armeabi-v7a; smoke test; net-suite ($TIER)"
  exit 0
fi

RC="$BUILD_DIR/release-check/$(date -u +%Y%m%dT%H%M%SZ)"; mkdir -p "$RC"
SUM="$RC/summary.txt"; T0=$(date +%s)
say() { echo "$*" | tee -a "$SUM"; }
verdict() {
  local rc=$?
  { echo; echo "wall $(( $(date +%s) - T0 ))s, exit $rc"
    if [ "$rc" = 0 ]; then echo "RELEASE CHECK PASSED ($TIER network tier)"; else echo "RELEASE CHECK FAILED"; fi
  } >> "$SUM"
  ln -sfn "$RC" "$BUILD_DIR/release-check/latest"
  exit "$rc"
}
trap verdict EXIT
{ echo "release check $(date -u +%FT%TZ)"
  echo "app $(git -C "$REPO" rev-parse --short HEAD)$(git -C "$REPO" status --porcelain --untracked-files=no -- "$HOST_REL" | grep -q . && echo ' +local changes')"
  echo "base $BASE; network tier $TIER ${WHY:+(because: $WHY)}"
  echo "network scenarios: $SCEN"; echo; } > "$SUM"

step() { # step NAME COMMAND...: runs it, logs to $RC/NAME.log, stops the check at the first failure
  local name="$1" rc=0 t0; shift; t0=$(date +%s)
  echo "[$(date +%H:%M:%S)] $name"
  "$@" > "$RC/$name.log" 2>&1 || rc=$?
  if [ "$rc" = 0 ]; then say "PASS $name $(( $(date +%s) - t0 ))s"; return 0; fi
  say "FAIL $name $(( $(date +%s) - t0 ))s (exit $rc, log $RC/$name.log)"; tail -20 "$RC/$name.log" >&2
  exit "$rc"
}
APK_ENV=(HP_PROFILE=test ABIS="arm64-v8a armeabi-v7a")
step stage-test env HP_PROFILE=test "$S/stage-assets.sh"
step config-test heavy "$S/config-test.sh"
step apk heavy env "${APK_ENV[@]}" SKIP_UI_SMOKE=1 "$S/build-apk.sh"
step smoke heavy env "${APK_ENV[@]}" "$S/ui-smoke-test.sh"
if [ "$TIER" = full ]; then
  step network "$S/net-suite.sh" -r "$RC/net-suite.tsv"
else
  step network "$S/net-suite.sh" -r "$RC/net-suite.tsv" $CORE $EXTRA
fi
{ echo; echo "network suite summary:"; sed -n '/^summary/,$p' "$RC/network.log" | tail -n +2; } >> "$SUM"
