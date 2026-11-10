package org.hedgewars.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The default player names list: what the parser keeps and how a name is picked. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = { 34 })
public class PlayerNamesTest {
    @Test
    public void parse_skipsCommentsAndBlanks() {
        List<String> n = PlayerNames.parse("# header\n\nAlpha\r\n   \n#Beta\nGamma Delta\n");
        assertEquals(Arrays.asList("Alpha", "Gamma Delta"), n);
    }

    @Test
    public void parse_dropsLongAndNonAscii() {
        String twenty = "12345678901234567890";
        List<String> n = PlayerNames.parse(twenty + "\n" + twenty + "1\nCafé\nOk\n");
        assertEquals(Arrays.asList(twenty, "Ok"), n);
    }

    @Test
    public void parse_nullAndEmpty() {
        assertTrue(PlayerNames.parse(null).isEmpty());
        assertTrue(PlayerNames.parse("# only a comment\n").isEmpty());
    }

    @Test
    public void pick_isFromTheListAndDiffersFromTheCurrent() {
        List<String> l = Arrays.asList("A", "B", "C");
        Random r = new Random(1);
        for (int i = 0; i < 50; i++) {
            String p = PlayerNames.pick(l, "B", r);
            assertTrue(l.contains(p));
            assertNotEquals("B", p);
        }
        assertEquals("Solo", PlayerNames.pick(Arrays.asList("Solo"), "Solo", r));
    }

    @Test
    public void pick_emptyListFallsBackToTheDeviceModel() {
        assertEquals(android.os.Build.MODEL, PlayerNames.pick(PlayerNames.parse(""), null, new Random()));
    }
}
