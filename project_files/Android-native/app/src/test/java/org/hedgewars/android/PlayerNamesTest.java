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

    @Test
    public void parse_trimsAndDropsDuplicatesIgnoringCase() {
        List<String> n = PlayerNames.parse("  Alpha  \nBeta\nalpha\nBETA\nGamma\n");
        assertEquals(Arrays.asList("Alpha", "Beta", "Gamma"), n);
        assertTrue("a tab is not plain ASCII", PlayerNames.parse("A\tB\n").isEmpty());
    }

    @Test
    public void pick_neverReturnsTheCurrentNameWhenThereIsAnother() {
        List<String> l = Arrays.asList("A", "a", "A", "B");
        Random r = new Random(5);
        for (int i = 0; i < 100; i++) assertEquals("B", PlayerNames.pick(l, "A", r));
        for (int i = 0; i < 100; i++) assertNotEquals("B", PlayerNames.pick(l, "B", r));
    }

    private static android.content.SharedPreferences prefs() {
        return org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences(Store.PREFS_NAME, android.content.Context.MODE_PRIVATE);
    }

    @Test
    public void current_aStoredDeviceModelThatWasNotChosenIsReplacedByARandomName() {
        android.content.Context c = org.robolectric.RuntimeEnvironment.getApplication();
        List<String> l = Arrays.asList("Aa", "Bb", "Cc");
        prefs().edit().clear().putString(PlayerNames.PREF_KEY, android.os.Build.MODEL).apply();   // what tv12 saved on pause
        String n = PlayerNames.current(c, l);
        assertTrue("random name from the list: " + n, l.contains(n));
        assertEquals(n, prefs().getString(PlayerNames.PREF_KEY, ""));
        assertEquals("and it stays", n, PlayerNames.current(c, l));
    }

    @Test
    public void current_aDeviceModelTheUserTypedIsKept() {
        android.content.Context c = org.robolectric.RuntimeEnvironment.getApplication();
        prefs().edit().clear().apply();
        PlayerNames.typed(c, " " + android.os.Build.MODEL + " ");
        assertEquals(android.os.Build.MODEL, PlayerNames.current(c, Arrays.asList("Aa", "Bb")));
        assertTrue(prefs().getBoolean(PlayerNames.CHOSEN_KEY, false));
    }

    @Test
    public void current_otherStoredNamesAreKept_andBlankIsNotSaved() {
        android.content.Context c = org.robolectric.RuntimeEnvironment.getApplication();
        prefs().edit().clear().putString(PlayerNames.PREF_KEY, "Old Name").apply();
        assertEquals("Old Name", PlayerNames.current(c, Arrays.asList("Aa", "Bb")));
        PlayerNames.typed(c, "   ");
        assertEquals("Old Name", prefs().getString(PlayerNames.PREF_KEY, ""));
    }

    @Test
    public void current_aMissingListGivesTheModelAndSavesNothing() {
        android.content.Context c = org.robolectric.RuntimeEnvironment.getApplication();
        prefs().edit().clear().apply();
        assertEquals(android.os.Build.MODEL, PlayerNames.current(c, new java.util.ArrayList<>()));
        assertTrue("the fallback is not persisted", !prefs().contains(PlayerNames.PREF_KEY));
    }
}
