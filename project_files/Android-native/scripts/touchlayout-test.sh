#!/usr/bin/env bash
# Host-side check of the touchlayout command's scale reset (no device needed).
# A layout that scales the fire button (fire:0.5,0.5,1.5) must be reported at scale 1.500; the default layout
# (an empty touchlayout) must then report the fire button at its default scale 1.000. Before the reset of
# layoutScale (InitTouchLegacy, InitTouchPlaced) the second report kept 1.500 and the button stayed 1.5x.
# Usage: touchlayout-test.sh   (runs host-test.sh for 12 s; the engine log is build/host-test/engine.log)
# The last line is "RESULT: PASS" or "RESULT: FAIL ...".
. "$(dirname "$0")/common.sh"
T="$BUILD_DIR/host-test"
HP_TEST_SCRIPT="2000:ecmd:touchlayout fire:0.5,0.5,1.5;5000:ecmd:touchlayout" \
    "$HOST_DIR/scripts/host-test.sh" 12 > "$T/touchlayout-run.log" 2>&1 || true

reports="$(grep -o 'touch layout fire:[^ ]*' "$T/engine.log" || true)"
first="$(echo "$reports" | sed -n 1p)"
last="$(echo "$reports" | sed -n '$p')"
count="$(echo "$reports" | grep -c . || true)"
if [ "$count" -lt 2 ]; then
  echo "fire reports seen: $count (need 2, one per touchlayout command)"
  echo "RESULT: FAIL (the engine did not report the fire button twice; see $T/engine.log)"
  exit 1
fi
first_scale="$(echo "$first" | cut -d, -f3)"
last_scale="$(echo "$last" | cut -d, -f3)"
first="${first#touch layout }"; last="${last#touch layout }"
echo "after 'touchlayout fire:0.5,0.5,1.5': $first"
echo "after 'touchlayout' (default):        $last"
if [ "$first_scale" != "1.500" ]; then
  echo "RESULT: FAIL (the scaled layout was reported at scale $first_scale, expected 1.500)"
  exit 1
fi
if [ "$last_scale" != "1.000" ]; then
  echo "RESULT: FAIL (the default layout still reports fire at scale $last_scale, expected 1.000)"
  exit 1
fi
echo "RESULT: PASS (fire scale 1.500 with the scaled layout, 1.000 after the default layout)"
