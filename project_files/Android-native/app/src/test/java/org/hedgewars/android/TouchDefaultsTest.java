package org.hedgewars.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * TouchDefaults.compute must give exactly the places the engine reports for its default layout. The golden file
 * (src/test/resources/touch-defaults-golden.txt) holds those reports, written by scripts/touch-defaults-golden.sh from the host
 * engine. Re-run the script when hedgewars/uWorld.pas's touch placement changes; do not loosen the comparison here.
 */
public class TouchDefaultsTest {
    private static final String GOLDEN = "/touch-defaults-golden.txt";

    /** One set of the golden file: its inputs and the engine's ten reports, by name. */
    private static final class GoldenSet {
        final String line;
        final TouchDefaults.Inputs inputs;
        final Map<String, TouchLayout.Place> engine = new HashMap<>();

        GoldenSet(String line, TouchDefaults.Inputs inputs) {
            this.line = line;
            this.inputs = inputs;
        }
    }

    private static int num(Map<String, String> kv, String key) {
        String v = kv.get(key);
        assertNotNull("golden set has no " + key + ": " + kv, v);
        return Integer.parseInt(v);
    }

    /** Parses "set w=1280 h=720 ..." into the inputs it describes (the same key order the script writes). */
    private static TouchDefaults.Inputs parseInputs(String line) {
        Map<String, String> kv = new HashMap<>();
        for (String tok : line.substring("set ".length()).split(" ")) {
            int eq = tok.indexOf('=');
            assertTrue("bad token in golden set: " + tok, eq > 0);
            kv.put(tok.substring(0, eq), tok.substring(eq + 1));
        }
        return new TouchDefaults.Inputs(num(kv, "w"), num(kv, "h"), num(kv, "ui"),
                num(kv, "il"), num(kv, "it"), num(kv, "ir"), num(kv, "ib"),
                num(kv, "tl"), num(kv, "tr"), num(kv, "bl"), num(kv, "br"), num(kv, "m"),
                num(kv, "ly0"), num(kv, "ly1"), num(kv, "ry0"), num(kv, "ry1"),
                num(kv, "jump"), num(kv, "two") == 1, num(kv, "swap") == 1);
    }

    private static List<GoldenSet> readGolden() throws IOException {
        InputStream in = TouchDefaultsTest.class.getResourceAsStream(GOLDEN);
        assertNotNull("missing " + GOLDEN + " (run scripts/touch-defaults-golden.sh)", in);
        List<GoldenSet> sets = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            GoldenSet cur = null;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("set ")) {
                    cur = new GoldenSet(line, parseInputs(line));
                    sets.add(cur);
                } else {
                    assertNotNull("a report line before any set: " + line, cur);
                    TouchLayout.Place p = TouchLayout.reportPlace(line);
                    assertNotNull("not a report line: " + line, p);
                    cur.engine.put(TouchLayout.reportName(line), p);
                }
            }
        }
        return sets;
    }

    @Test
    public void goldenFileHasEnoughSetsAndEveryControl() throws IOException {
        List<GoldenSet> sets = readGolden();
        assertTrue("the golden file must cover at least 12 argument sets, has " + sets.size(), sets.size() >= 12);
        for (GoldenSet s : sets) {
            assertEquals("ten reports in " + s.line, TouchLayout.NAMES.length, s.engine.size());
            for (String name : TouchLayout.NAMES) {
                assertNotNull("no report for " + name + " in " + s.line, s.engine.get(name));
            }
        }
    }

    @Test
    public void computeMatchesTheEngineForEverySetAndControl() throws IOException {
        List<GoldenSet> sets = readGolden();
        for (GoldenSet s : sets) {
            Map<String, TouchLayout.Place> ours = TouchDefaults.compute(s.inputs);
            for (String name : TouchLayout.NAMES) {
                // the editor drops highjump without --two-jump-buttons; the engine's entry then carries no real place
                if (name.equals("highjump") && !s.inputs.twoJump) {
                    assertFalse("highjump is only computed with two jump buttons: " + s.line, ours.containsKey(name));
                    continue;
                }
                TouchLayout.Place want = s.engine.get(name);
                TouchLayout.Place got = ours.get(name);
                String where = name + " in " + s.line;
                assertNotNull("no computed place for " + where, got);
                assertEquals("x " + where, want.x, got.x, 0.0);
                assertEquals("y " + where, want.y, got.y, 0.0);
                assertEquals("s " + where, want.s, got.s, 0.0);
                assertEquals("w " + where, want.w, got.w, 0.0);
                assertEquals("h " + where, want.h, got.h, 0.0);
            }
        }
    }

    @Test
    public void fromDisplayMapsTheLauncherValuesInTheirOrder() {
        // distinct values per position, so a swapped pair would show: HUD, menu, insets L T R B, corners TL TR BL BR,
        // margin, cut-out left rows, cut-out right rows
        int[] d = { 240, 300, 11, 12, 13, 14, 21, 22, 23, 24, 31, 41, 42, 43, 44 };
        TouchDefaults.Inputs in = TouchDefaults.fromDisplay(2340, 1080, d, 100, true, false);
        assertEquals(2340, in.screenW);
        assertEquals(1080, in.screenH);
        assertEquals(240, in.uiScalePct);
        assertEquals(11, in.insetL);
        assertEquals(12, in.insetT);
        assertEquals(13, in.insetR);
        assertEquals(14, in.insetB);
        assertEquals(21, in.cornerTL);
        assertEquals(22, in.cornerTR);
        assertEquals(23, in.cornerBL);
        assertEquals(24, in.cornerBR);
        assertEquals(31, in.margin);
        assertEquals(41, in.cutLY0);
        assertEquals(42, in.cutLY1);
        assertEquals(43, in.cutRY0);
        assertEquals(44, in.cutRY1);
        assertEquals(100, in.jumpInsetPct);
        assertTrue(in.twoJump);
        assertFalse(in.swapFireJump);
    }

    @Test
    public void fromDisplayReadsAShapeValueBelowZeroAsZero() {
        int[] d = { 100, 200, 0, 0, 0, 0, -5, 0, 0, 0, 0, 0, 0, 0, 0 };
        TouchDefaults.Inputs in = TouchDefaults.fromDisplay(1280, 720, d, 0, false, true);
        assertEquals(0, in.cornerTL);
        assertTrue(in.swapFireJump);
    }

    @Test(expected = IllegalArgumentException.class)
    public void fromDisplayNeedsFifteenValues() {
        TouchDefaults.fromDisplay(1280, 720, new int[14], 0, false, false);
    }
}
