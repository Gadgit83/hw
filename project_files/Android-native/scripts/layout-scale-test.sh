#!/usr/bin/env bash
# Host test of the controls editor's places at another UI scale (issue #26). The engine reports each control's place as
# fractions of the screen (IPC 'T', see ReportTouchLayout in hedgewars/uWorld.pas). At --ui-scale 250 the touch HUD is laid
# out in UI units: the 1280 x 720 screen is 512 x 288 UI units, and every control has the same size and place in UI units as
# at scale 1 (the screenshots show it). So the report, taken over the UI size, must match the report at scale 1, and the
# editor draws its outlines on the controls. Before the fix the fractions were taken over the screen size instead, which
# squeezed the places towards the middle, so the editor drew the controls in the wrong places and Save moved them.
# Five runs, each host-test.sh for 10 s, with two jump buttons as on the phone:
#   1. scale 1 (no --ui-scale): the reference places A.
#   2. --ui-scale 250: the places B1 must equal A in UI units.
#   3. --ui-scale 250 again: the places B1 are sent back as a touchlayout, and the places B2 reported after it must equal B1
#      (a round trip: sending the reported places back moves nothing).
#   4. and 5. --swap-fire-jump at scale 1 and at scale 2.5: the same comparison, and fire must be on the right at scale 1.
# Usage: layout-scale-test.sh   (the last line is "RESULT: PASS" or "RESULT: FAIL ...")
. "$(dirname "$0")/common.sh"
T="$BUILD_DIR/host-test"
SIZE="${HP_TEST_SIZE:-1280x720}"; SW="${SIZE%x*}"; SH="${SIZE#*x}"
SCALE=2.5          # --ui-scale 250
TOL_UI=2           # largest difference allowed between two reports of the same controls, in UI pixels
ENGINE_LOG="$T/engine.log"
mkdir -p "$T"

# The touch layout reports of the last run: lines "[hosttest] touch layout name:x,y,s,w,h", ten per report.
reports_of_last_run() { grep '^\[hosttest\] touch layout ' "$ENGINE_LOG" | sed 's/^\[hosttest\] touch layout //'; }

run() {  # run <label> <engine args> <script>
    HP_TEST_ARGS="$2" HP_TEST_SCRIPT="$3" "$HOST_DIR/scripts/host-test.sh" 10 > "$T/layout-scale-$1.log" 2>&1 || true
    cp "$ENGINE_LOG" "$T/layout-scale-$1.engine.log"
    cp "$T/screenshot.png" "$T/layout-scale-$1.png" 2>/dev/null || true
}

# Compares two blocks of ten reports name by name. The HUD is anchored to the screen edges in UI units: a control keeps its
# distance from one left or right edge, and from one top or bottom edge, whatever the scale. Each control is anchored to the
# edge it is nearest to in the first block (the reference), and the distance from that same edge is compared in UI pixels (the
# screen size divided by the scale), together with the drawn width and height. Measuring from the nearest edge in each block
# separately would flip the edge for a control near the middle of the screen (aim is) and report a difference that is not there.
compare() {  # compare <label> <block a> <scale a> <block b> <scale b> <tolerance in UI pixels>
    echo "$2" | awk -F'[:,]' '{ print $1, $2, $3, $5, $6 }' > "$T/cmp-a.txt"   # name x y w h
    echo "$4" | awk -F'[:,]' '{ print $1, $2, $3, $5, $6 }' > "$T/cmp-b.txt"
    awk -v sa="$3" -v sb="$5" -v W="$SW" -v H="$SH" -v tol="$6" -v label="$1" '
        NR == FNR { ax[$1] = $2; ay[$1] = $3; aw[$1] = $4; ah[$1] = $5; next }
        {
            count++
            if (!($1 in ax)) { printf "%s: %s is missing in the first block\n", label, $1; bad++; next }
            # the reference position in UI pixels, and the edge it is nearest to
            ua = ax[$1] * W / sa; fw = W / sa
            left = (ua < fw - ua)
            va = ay[$1] * H / sa; fh = H / sa
            top = (va < fh - va)
            ub = $2 * W / sb; wb = W / sb
            vb = $3 * H / sb; hb = H / sb
            d[1] = (left ? ua : fw - ua) - (left ? ub : wb - ub)
            d[2] = (top ? va : fh - va) - (top ? vb : hb - vb)
            d[3] = aw[$1] * W / sa - $4 * W / sb
            d[4] = ah[$1] * H / sa - $5 * H / sb
            worst = 0
            for (k = 1; k <= 4; k++) { if (d[k] < 0) d[k] = -d[k]; if (d[k] > worst) worst = d[k] }
            if (worst > maxd) maxd = worst
            if (worst > tol) {
                printf "%s: %s is out by %.1f UI px (from the %s and %s edges: %.1f,%.1f against %.1f,%.1f)\n", label, $1, worst,
                    left ? "left" : "right", top ? "top" : "bottom",
                    left ? ua : fw - ua, top ? va : fh - va, left ? ub : wb - ub, top ? vb : hb - vb
                bad++
            }
        }
        END {
            printf "%s: largest difference %.2f UI px over %d controls\n", label, maxd + 0, count
            exit (bad > 0 || count != 10)
        }
    ' "$T/cmp-a.txt" "$T/cmp-b.txt"
}

run scale1 "--two-jump-buttons" "3000:ecmd:touchlayout ?"
A="$(reports_of_last_run | tail -n 10)"
if [ "$(echo "$A" | grep -c .)" -ne 10 ]; then
    echo "layout scale test: the engine did not report the ten controls at scale 1 (see $T/layout-scale-scale1.log)"
    echo "RESULT: FAIL (no reference report)"
    exit 1
fi

run scale250 "--two-jump-buttons --ui-scale 250" "3000:ecmd:touchlayout ?"
B1="$(reports_of_last_run | tail -n 10)"
if [ "$(echo "$B1" | grep -c .)" -ne 10 ]; then
    echo "layout scale test: the engine did not report the ten controls at scale 2.5 (see $T/layout-scale-scale250.log)"
    echo "RESULT: FAIL (no report at scale 2.5)"
    exit 1
fi

# the places reported at scale 2.5 as a layout command, "name:x,y,s|...": | stands for the semicolon, see host-test.sh
SPEC="$(echo "$B1" | awk -F'[:,]' '{ printf "%s%s:%s,%s,%s", (NR > 1 ? "|" : ""), $1, $2, $3, $4 }')"
run roundtrip "--two-jump-buttons --ui-scale 250" "3000:ecmd:touchlayout ${SPEC};6000:ecmd:touchlayout ?"
B2="$(reports_of_last_run | tail -n 10)"
if [ "$(echo "$B2" | grep -c .)" -ne 10 ]; then
    echo "layout scale test: the engine did not report the ten controls after the layout (see $T/layout-scale-roundtrip.log)"
    echo "RESULT: FAIL (no report after the layout)"
    exit 1
fi

# With --swap-fire-jump the fire button is on the right (the bottom-right the brief describes): the same check at both scales,
# and fire must be in the right-hand quarter of the screen at scale 1.
run swap1 "--two-jump-buttons --swap-fire-jump" "3000:ecmd:touchlayout ?"
SW1="$(reports_of_last_run | tail -n 10)"
run swap250 "--two-jump-buttons --swap-fire-jump --ui-scale 250" "3000:ecmd:touchlayout ?"
SW2="$(reports_of_last_run | tail -n 10)"
if [ "$(echo "$SW1" | grep -c .)" -ne 10 ] || [ "$(echo "$SW2" | grep -c .)" -ne 10 ]; then
    echo "layout scale test: the swapped layout did not report the ten controls (see $T/layout-scale-swap1.log, layout-scale-swap250.log)"
    echo "RESULT: FAIL (no swapped report)"
    exit 1
fi

fail=0
compare "scale 2.5 against scale 1" "$A" 1 "$B1" "$SCALE" "$TOL_UI" || fail=1
compare "round trip at scale 2.5" "$B1" "$SCALE" "$B2" "$SCALE" "$TOL_UI" || fail=1
compare "swapped fire and jump, scale 2.5 against scale 1" "$SW1" 1 "$SW2" "$SCALE" "$TOL_UI" || fail=1
fire_x="$(echo "$SW1" | awk -F'[:,]' '$1 == "fire" { print $2 }')"
if awk -v x="$fire_x" 'BEGIN { exit !(x > 0.75) }'; then
    echo "swapped: fire is on the right at scale 1 (x $fire_x)"
else
    echo "swapped: fire is not on the right at scale 1 (x $fire_x, expected above 0.75)"
    fail=1
fi
if [ "$fail" -eq 0 ]; then
    echo "RESULT: PASS (the places at scale 2.5 match scale 1 in UI units, and sending them back moves nothing)"
else
    echo "RESULT: FAIL (see the differences above; logs in $T)"
fi
exit $fail
