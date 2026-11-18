#!/usr/bin/env bash
# Runs the network test scenarios of scripts/net-test.sh several at a time, each with its own X display and output directories.
#
# Usage: net-suite.sh [-j N] [-r RESULTS] [-d BASE_DISPLAY] [scenario ...]
#   -j N      scenarios at a time (default 3, the value chosen for a 4-core machine, see docs/TESTING.md)
#   -r FILE   results file, tab-separated: scenario, attempt, PASS|FAIL, seconds, exit status, the RESULT line.
#             Default build/host-test/net-suite.tsv. A scenario that already has a PASS line in this file is skipped, so
#             a restarted run (a container restart, the 2-hour job limit) repeats only what did not pass. Use a new file
#             for a fresh run of the same scenarios.
#   -d N      the first X display: scenario slot k runs on display N+k (default 70). The spectator port of the viewer
#             scenarios follows the display (NET_SPEC_PORT = 18700 + display, as net-test.sh does).
#   scenario  names as net-test.sh takes them. Without names, the full release list (42 scenarios; about 2.5 hours serially).
#
# The build (the engine if it is missing, the launcher, the viewer stand-in when a viewer scenario is listed) runs once up
# front; the scenarios then run with NET_SKIP_BUILD=1. A failed scenario is retried once, at the end of the queue. The suite
# exits 1 if any scenario failed twice. It holds the machine's heavy lock (HP_HEAVY_LOCK, default the lock the other workers
# use) for the whole run, so one suite runs at a time and the other jobs wait for it.
# Ctrl-C or TERM stops the scenarios this suite started (and their children) and nothing else; run it again to resume.
# Environment: NET_TIMEOUT_S (per attempt, default 900), HP_HEAVY_LOCK.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
LOCK="${HP_HEAVY_LOCK:-/tmp/claude-0/-home-user/ac142ab0-1bea-5b6c-a7cb-3a9f55df2846/scratchpad/heavy.lock}"
if [ -z "${HP_SUITE_LOCKED:-}" ]; then
  echo "waiting for the heavy lock $LOCK"
  exec flock "$LOCK" env HP_SUITE_LOCKED=1 "$0" "$@"
fi
. "$HERE/common.sh"

ALL="malformed basic basic3 dropout dropout3 stall slowstart negcontrol solo settings viewer1 viewer2 viewer3 viewerflap
hostquit hostleave hostend rejoin rejoinlate lonerejoin lonerejoinb lonerejoinc joinerquit hostquitchat joinerquitchat injecte
hidden watch1 watchlate watchflap watchsend watchpause hostpause hostpausequit joinerpause pausecap hostalone-ai3
hostalone-human3 hostalone-ai3slow hostalone-human3slow hostalone-ai85 hostwatch"

JOBS=3; BASE=70; RESULTS="$BUILD_DIR/host-test/net-suite.tsv"
while getopts "j:r:d:h" o; do
  case $o in
    j) JOBS=$OPTARG ;;
    r) RESULTS=$OPTARG ;;
    d) BASE=$OPTARG ;;
    *) sed -n '2,21p' "$0"; exit 2 ;;
  esac
done
shift $((OPTIND - 1))
case "$JOBS" in ''|*[!0-9]*|0) echo "-j needs a number of at least 1"; exit 2 ;; esac
case "$BASE" in ''|*[!0-9]*) echo "-d needs a display number"; exit 2 ;; esac
if [ $# -gt 0 ]; then SEL=("$@"); else read -ra SEL <<< "$ALL"; fi

T="$BUILD_DIR/host-test"; W="$T/suite-logs"
mkdir -p "$W" "$(dirname "$RESULTS")"; touch "$RESULTS"
START=$(date +%s)

# Scenarios that already passed in this results file are skipped; the rest are queued (attempt 1).
todo_s=(); todo_a=()
for s in "${SEL[@]}"; do
  if awk -F'\t' -v s="$s" '$1 == s && $3 == "PASS" { f = 1 } END { exit !f }' "$RESULTS"; then
    echo "skip $s: already PASS in $RESULTS"; continue
  fi
  todo_s+=("$s"); todo_a+=(1)
done
echo "# net-suite $(date -u +%FT%TZ) jobs=$JOBS displays=$BASE-$((BASE + JOBS - 1)) queued=${#todo_s[@]} of ${#SEL[@]}" >> "$RESULTS"

if [ ${#todo_s[@]} -gt 0 ]; then
  # Build once. The viewer stand-in is built only when a listed scenario needs it.
  want=malformed
  for s in "${todo_s[@]}"; do
    case $s in
      malformed) ;;
      viewer*|hostpause|settings) want=viewer1 ;;
      *) [ "$want" = viewer1 ] || want=basic ;;
    esac
  done
  if [ "$want" != malformed ]; then
    log "build once for the suite (engine if missing, launcher, viewer stand-in if needed)"
    [ -d "$BUILD_DIR/assets/Data" ] || "$HERE/stage-assets.sh" > "$W/stage-assets.log" 2>&1
    NET_BUILD_ONLY=1 "$HERE/net-test.sh" "$want" > "$W/build.log" 2>&1 || { tail -30 "$W/build.log"; echo "build failed, see $W/build.log"; exit 1; }
    tail -1 "$W/build.log"
  elif [ ! -d "$BUILD_DIR/assets/Data" ]; then
    "$HERE/stage-assets.sh" > "$W/stage-assets.log" 2>&1
  fi
fi

record() { # scenario attempt status seconds exit detail: one line, appended under the file's own lock
  ( flock 9; printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$@" >&9 ) 9>>"$RESULTS"
}
kill_tree() { # stops a process this suite started and its descendants
  local c
  for c in $(pgrep -P "$1" 2>/dev/null || true); do kill_tree "$c"; done
  kill "$1" 2>/dev/null || true
}
declare -A JOB_S=() JOB_A=() JOB_SLOT=()
cleanup() { local p; for p in "${!JOB_S[@]}"; do kill_tree "$p"; done; }
trap cleanup EXIT
trap 'exit 130' INT TERM

run_job() { # scenario attempt slot
  local s=$1 a=$2 disp lf t0 dur rc=0 res st
  disp=$((BASE + $3)); lf="$W/$s.try$a.log"; t0=$(date +%s)
  NET_SKIP_BUILD=1 HP_TEST_DISPLAY=$disp NET_SPEC_PORT=$((18700 + disp)) NET_TIMEOUT_S="${NET_TIMEOUT_S:-900}" \
    "$HERE/net-test.sh" "$s" > "$lf" 2>&1 || rc=$?
  dur=$(( $(date +%s) - t0 ))
  res=$(grep '^RESULT:' "$lf" | tail -1 || true)
  st=FAIL; [ "$rc" -eq 0 ] && st=PASS
  record "$s" "$a" "$st" "$dur" "$rc" "${res:-RESULT: no result line, see $lf}"
  echo "[$(date +%H:%M:%S)] $s attempt $a on :$disp $st in ${dur}s (exit $rc) ${res}"
  return "$rc"
}

free=(); for ((i = 0; i < JOBS; i++)); do free+=("$i"); done
failed_twice=()
while [ ${#todo_s[@]} -gt 0 ] || [ ${#JOB_S[@]} -gt 0 ]; do
  while [ ${#todo_s[@]} -gt 0 ] && [ ${#free[@]} -gt 0 ]; do
    slot=${free[0]}; free=("${free[@]:1}")
    s=${todo_s[0]}; a=${todo_a[0]}; todo_s=("${todo_s[@]:1}"); todo_a=("${todo_a[@]:1}")
    run_job "$s" "$a" "$slot" &
    JOB_S[$!]=$s; JOB_A[$!]=$a; JOB_SLOT[$!]=$slot
  done
  p=0; rc=0; wait -n -p p || rc=$?
  s=${JOB_S[$p]}; a=${JOB_A[$p]}; free+=("${JOB_SLOT[$p]}")
  unset "JOB_S[$p]" "JOB_A[$p]" "JOB_SLOT[$p]"
  if [ "$rc" -ne 0 ]; then
    if [ "$a" -eq 1 ]; then todo_s+=("$s"); todo_a+=(2); echo "retrying $s once"
    else failed_twice+=("$s"); fi
  fi
done

WALL=$(( $(date +%s) - START ))
{
  echo "# net-suite end $(date -u +%FT%TZ) wall=${WALL}s jobs=$JOBS failed_twice=${failed_twice[*]:-none}"
} >> "$RESULTS"
echo
echo "summary (last attempt per scenario, from $RESULTS):"
printf '%-18s %-6s %-8s %8s\n' scenario result attempt seconds
bad=0
for s in "${SEL[@]}"; do
  line=$(awk -F'\t' -v s="$s" '$1 == s { l = $0 } END { print l }' "$RESULTS")
  if [ -z "$line" ]; then printf '%-18s %-6s\n' "$s" NOT-RUN; bad=1; continue; fi
  IFS=$'\t' read -r _s att st secs _rc _det <<< "$line"
  printf '%-18s %-6s %-8s %7ss\n' "$s" "$st" "$att" "$secs"
  [ "$st" = PASS ] || bad=1
done
echo "wall time ${WALL}s with $JOBS at a time"
if [ ${#failed_twice[@]} -gt 0 ]; then echo "SUITE FAILED (failed twice): ${failed_twice[*]}"; exit 1; fi
if [ "$bad" -ne 0 ]; then echo "SUITE INCOMPLETE: a scenario has no PASS yet"; exit 1; fi
echo "SUITE PASSED"
