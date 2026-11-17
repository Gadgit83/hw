#!/usr/bin/env bash
# Host test of the controls layout (issue #21): sends a full ten-control layout to the engine live, as the editor's Save does
# (the touchlayout command, IPC 'e', 240 characters), asks the engine for its report (touchlayout ?) and checks that all ten
# controls come back. Runs host-test.sh; the spec is written with '|' for ';' because the script is split on ';'.
# Usage: layout-test.sh [seconds]   (default 20)
. "$(dirname "$0")/common.sh"
SECS="${1:-20}"
SPEC="fire:0.500,0.800,1.000|jump:0.300,0.800,1.000|highjump:0.300,0.600,1.000|left:0.100,0.800,1.000|right:0.200,0.800,1.000"
SPEC="$SPEC|aim:0.800,0.500,1.000|weapons:0.900,0.100,1.000|pause:0.950,0.050,1.000|timer:0.500,0.050,1.000|bounce:0.700,0.300,1.000"
[ "${#SPEC}" -eq 240 ] || { echo "layout spec is ${#SPEC} characters, expected 240"; exit 1; }
LOG="$BUILD_DIR/host-test/engine.log"

export HP_TEST_SCRIPT="6000:ecmd:touchlayout ${SPEC};8000:ecmd:touchlayout ?"
"$(dirname "$0")/host-test.sh" "$SECS"

fail=0
sent=$({ grep -c "engine command -> 0" "$LOG" || true; })
[ "$sent" -eq 2 ] || { echo "layout test: the two touchlayout commands were not both written (engine command -> 0 seen $sent times)"; fail=1; }
# the engine reports the places after each touchlayout too; the report to check is the one after the '?' command, the last block
LAST=$(grep -n "engine command -> 0" "$LOG" | tail -1 | cut -d: -f1)
REPORT=$(tail -n +"$LAST" "$LOG" | grep "^\[hosttest\] touch layout ")
for name in fire jump highjump left right aim weapons pause timer bounce; do
  echo "$REPORT" | grep -q "^\[hosttest\] touch layout $name:" || { echo "layout test: the engine did not report $name"; fail=1; }
done
reports=$(echo "$REPORT" | grep -c . || true)
[ "$reports" -eq 10 ] || { echo "layout test: the engine reported $reports controls, expected 10"; fail=1; }
[ "$fail" -eq 0 ] && echo "layout test: all ten controls reported back after a 240 character touchlayout"
exit $fail
