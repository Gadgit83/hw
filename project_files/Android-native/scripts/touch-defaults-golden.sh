#!/usr/bin/env bash
# Golden data for TouchDefaults.java (issue #26). Runs the host engine once per argument set below with the arguments the
# launcher would pass for that display (see native/launcher/hp_main.c and hedgewars/ArgParsers.pas), asks it for its default
# touch control places twice ('touchlayout' with an empty spec, then 'touchlayout ?') and writes the inputs and the places
# to app/src/test/resources/touch-defaults-golden.txt. TouchDefaultsTest checks the Java copy against that file, to the thousandth.
#
# Re-run this script when hedgewars/uWorld.pas's touch placement changes (InitTouchLegacy, InitTouchPlaced, PlaceJumpPair,
# ReportTouchLayout, CornerClearPx, HInset), or when the launcher's display arguments change (native/launcher/hp_main.c).
#
# Usage: touch-defaults-golden.sh        (one host engine run per set, each under the heavy lock; about 15 runs)
# Each set is key=value: w h (screen, landscape), ui (--ui-scale percent), il it ir ib (--inset-*), tl tr bl br m ly0 ly1 ry0 ry1
# (--screen-shape, with m the margin), jump (--jump-inset), two (--two-jump-buttons), swap (--swap-fire-jump).
. "$(dirname "$0")/common.sh"
OUT="$HOST_DIR/app/src/test/resources/touch-defaults-golden.txt"
T="$BUILD_DIR/host-test"
LOG="$T/engine.log"
mkdir -p "$T" "$(dirname "$OUT")"

SETS=(
  # legacy placement (every shape value 0): desktop, the phone's jump inset, two jump, swap
  "w=1280 h=720 ui=100 il=0 it=0 ir=0 ib=0 tl=0 tr=0 bl=0 br=0 m=0 ly0=0 ly1=0 ry0=0 ry1=0 jump=0 two=0 swap=0"
  "w=1280 h=720 ui=150 il=0 it=0 ir=0 ib=0 tl=0 tr=0 bl=0 br=0 m=0 ly0=0 ly1=0 ry0=0 ry1=0 jump=100 two=1 swap=0"
  "w=1280 h=720 ui=240 il=0 it=0 ir=0 ib=0 tl=0 tr=0 bl=0 br=0 m=0 ly0=0 ly1=0 ry0=0 ry1=0 jump=100 two=1 swap=1"
  "w=2340 h=1080 ui=100 il=0 it=0 ir=0 ib=0 tl=0 tr=0 bl=0 br=0 m=0 ly0=0 ly1=0 ry0=0 ry1=0 jump=100 two=0 swap=0"
  "w=2340 h=1080 ui=150 il=0 it=0 ir=0 ib=0 tl=0 tr=0 bl=0 br=0 m=0 ly0=0 ly1=0 ry0=0 ry1=0 jump=100 two=1 swap=1"
  "w=2160 h=1080 ui=240 il=0 it=0 ir=0 ib=0 tl=0 tr=0 bl=0 br=0 m=0 ly0=0 ly1=0 ry0=0 ry1=0 jump=0 two=0 swap=0"
  # placed (any shape value non-zero): side insets, top and bottom insets, rounded corners, margin, cut-out rows
  "w=2340 h=1080 ui=150 il=80 it=0 ir=80 ib=0 tl=0 tr=0 bl=0 br=0 m=0 ly0=0 ly1=0 ry0=0 ry1=0 jump=100 two=1 swap=0"
  "w=2340 h=1080 ui=240 il=80 it=40 ir=80 ib=40 tl=100 tr=100 bl=100 br=100 m=0 ly0=0 ly1=0 ry0=0 ry1=0 jump=100 two=1 swap=1"
  "w=2160 h=1080 ui=150 il=0 it=0 ir=0 ib=0 tl=0 tr=0 bl=0 br=0 m=0 ly0=0 ly1=0 ry0=100 ry1=400 jump=100 two=1 swap=0"
  "w=2160 h=1080 ui=240 il=0 it=60 ir=0 ib=40 tl=0 tr=0 bl=0 br=0 m=0 ly0=0 ly1=0 ry0=0 ry1=0 jump=0 two=0 swap=1"
  "w=2160 h=1080 ui=100 il=0 it=0 ir=0 ib=0 tl=100 tr=100 bl=100 br=100 m=20 ly0=0 ly1=0 ry0=0 ry1=0 jump=100 two=1 swap=0"
  "w=1280 h=720 ui=150 il=80 it=0 ir=80 ib=0 tl=100 tr=0 bl=100 br=0 m=16 ly0=0 ly1=200 ry0=0 ry1=0 jump=0 two=1 swap=1"
  "w=2340 h=1080 ui=240 il=0 it=0 ir=0 ib=0 tl=100 tr=100 bl=0 br=0 m=0 ly0=0 ly1=0 ry0=0 ry1=0 jump=100 two=0 swap=0"
  "w=1280 h=720 ui=100 il=0 it=0 ir=0 ib=0 tl=0 tr=0 bl=0 br=0 m=30 ly0=0 ly1=0 ry0=0 ry1=0 jump=100 two=1 swap=1"
  "w=2340 h=1080 ui=150 il=0 it=0 ir=0 ib=0 tl=0 tr=0 bl=0 br=0 m=0 ly0=0 ly1=0 ry0=0 ry1=150 jump=100 two=1 swap=0"
  "w=2160 h=1080 ui=240 il=100 it=50 ir=100 ib=50 tl=150 tr=150 bl=150 br=150 m=10 ly0=0 ly1=0 ry0=0 ry1=0 jump=100 two=1 swap=0"
)

# The engine's report lines: "[hosttest] touch layout name:x,y,s,w,h" (ten per report, see ReportTouchLayout)
report_lines() { grep '^\[hosttest\] touch layout ' "$LOG" | sed 's/^\[hosttest\] touch layout //' || true; }

TMP="$(mktemp "$T/golden.XXXXXX")"
{
  echo "# Golden data for TouchDefaults (issue #26): the engine's default touch control places, as its 'T' reports (thousandths)."
  echo "# Written by scripts/touch-defaults-golden.sh from the host engine; do not edit by hand. Re-run the script when"
  echo "# hedgewars/uWorld.pas's touch placement changes. Each 'set' line is the inputs, then the ten reports in engine order."
} > "$TMP"

n=0
for s in "${SETS[@]}"; do
  n=$((n + 1))
  declare -A F=()
  for kv in $s; do F["${kv%%=*}"]="${kv#*=}"; done

  ARGS="--ui-scale ${F[ui]} --inset-left ${F[il]} --inset-top ${F[it]} --inset-right ${F[ir]} --inset-bottom ${F[ib]}"
  ARGS="$ARGS --screen-shape ${F[tl]},${F[tr]},${F[bl]},${F[br]},${F[m]},${F[ly0]},${F[ly1]},${F[ry0]},${F[ry1]}"
  ARGS="$ARGS --jump-inset ${F[jump]}"
  if [ "${F[two]}" = 1 ]; then ARGS="$ARGS --two-jump-buttons"; fi
  if [ "${F[swap]}" = 1 ]; then ARGS="$ARGS --swap-fire-jump"; fi

  echo "==> set $n/${#SETS[@]}: ${F[w]}x${F[h]} $ARGS"
  export HP_TEST_ARGS="$ARGS"
  export HP_TEST_SIZE="${F[w]}x${F[h]}"
  # 'touchlayout' with an empty spec puts the default back and reports it; 'touchlayout ?' reports again. Both must agree.
  export HP_TEST_SCRIPT="6000:ecmd:touchlayout;8000:ecmd:touchlayout ?"
  heavy "$HOST_DIR/scripts/host-test.sh" 13 > "$T/golden-$n.log" 2>&1 || { tail -30 "$T/golden-$n.log"; rm -f "$TMP"; exit 1; }
  cp "$LOG" "$T/golden-$n.engine.log"

  sent=$(grep -c 'engine command -> 0' "$LOG" || true)
  [ "$sent" -eq 2 ] || { echo "set $n: the two touchlayout commands were not both written (seen $sent)"; rm -f "$TMP"; exit 1; }
  mapfile -t REP < <(report_lines)
  [ "${#REP[@]}" -eq 20 ] || { echo "set $n: the engine reported ${#REP[@]} lines, expected 20 (two reports of ten)"; rm -f "$TMP"; exit 1; }
  for i in $(seq 0 9); do
    [ "${REP[$i]}" = "${REP[$((i + 10))]}" ] || { echo "set $n: the two reports differ at ${REP[$i]}"; rm -f "$TMP"; exit 1; }
  done
  names=$(printf '%s\n' "${REP[@]:0:10}" | cut -d: -f1 | tr '\n' ' ')
  [ "$names" = "fire jump highjump left right aim weapons pause timer bounce " ] || { echo "set $n: names out of order: $names"; rm -f "$TMP"; exit 1; }

  printf 'set w=%s h=%s ui=%s il=%s it=%s ir=%s ib=%s tl=%s tr=%s bl=%s br=%s m=%s ly0=%s ly1=%s ry0=%s ry1=%s jump=%s two=%s swap=%s\n' \
    "${F[w]}" "${F[h]}" "${F[ui]}" "${F[il]}" "${F[it]}" "${F[ir]}" "${F[ib]}" "${F[tl]}" "${F[tr]}" "${F[bl]}" "${F[br]}" \
    "${F[m]}" "${F[ly0]}" "${F[ly1]}" "${F[ry0]}" "${F[ry1]}" "${F[jump]}" "${F[two]}" "${F[swap]}" >> "$TMP"
  printf '%s\n' "${REP[@]:0:10}" >> "$TMP"
  unset F
done

mv "$TMP" "$OUT"
echo "wrote $OUT ($n sets)"
