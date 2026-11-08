package party.hedge.host;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One game scheme: the 25 rule switches and 18 numbers that the Qt frontend keeps per scheme
 * (QTfrontend/model/gameSchemeModel.cpp). Index numbers (1 to 25 for switches, 26 to 43 for numbers)
 * are the Qt scheme entry numbers, so this file can be compared with the desktop code line by line.
 * Pure Java, no Android classes, so the config tests run on a plain JVM.
 */
public final class Rules {

    /** Engine game flag bit for each switch (entries 1 to 25), as in GameCFGWidget::getGameFlags. */
    public static final int[] FLAG_BITS = {
        0,
        0x00001000, 0x00000010, 0x00000004, 0x00000008, 0x00000020, 0x00000040, 0x00000080,
        0x00000100, 0x00000200, 0x00000400, 0x00000800, 0x00002000, 0x00004000, 0x00008000,
        0x00010000, 0x00020000, 0x00040000, 0x00080000, 0x00100000, 0x00200000, 0x00400000,
        0x00800000, 0x01000000, 0x02000000, 0x04000000,
    };

    /** Labels of the switches, entry 1 first. British English. */
    public static final String[] FLAG_LABELS = {
        "", "Switch hedgehog in a turn", "Divide teams", "Indestructible land", "Border around the map",
        "Low gravity", "Laser sight", "Invulnerable hedgehogs", "Reset health each turn", "Vampirism",
        "Karma", "Artillery mode", "Random hedgehog order", "King mode", "Place hedgehogs yourself",
        "Shared ammo for a clan", "No girders", "No land objects", "AI survival mode", "Unlimited attacks",
        "Reset weapons each turn", "Separate ammo for each hedgehog", "No wind", "More wind", "Tag team",
        "Bottom border",
    };

    /** Names of the numbers, entry 26 first (index 0 here is entry 26). */
    public static final String[] VALUE_LABELS = {
        "Damage modifier (%)", "Turn time (seconds)", "Starting health", "Sudden death after (turns)",
        "Crate drop frequency", "Mine timer (seconds)", "Mines", "Dud mines (%)", "Explosive barrels",
        "Air mines", "Sentries", "Health crate chance (%)", "Health in a crate", "Water rise per turn",
        "Health lost in sudden death", "Rope length (%)", "Get away time (%)", "World edge",
    };

    /** Allowed range of each number (entries 26 to 43), as in the Qt scheme editor. */
    public static final int[][] VALUE_RANGE = {
        { 10, 300 }, { 1, 9999 }, { 1, 1000 }, { 0, 51 }, { 0, 9 }, { -1, 5 }, { 0, 200 }, { 0, 100 },
        { 0, 200 }, { 0, 200 }, { 0, 200 }, { 0, 100 }, { 0, 1000 }, { 0, 100 }, { 0, 1000 }, { 25, 999 },
        { 0, 999 }, { 0, 3 },
    };

    public static final int V_DAMAGE = 26, V_TURN_TIME = 27, V_HEALTH = 28, V_SUDDEN = 29, V_CASE_PROB = 30,
            V_MINES_TIME = 31, V_MINES = 32, V_DUD = 33, V_EXPLOSIVES = 34, V_AIR_MINES = 35,
            V_SENTRIES = 36, V_HEALTH_PROB = 37, V_HEALTH_AMOUNT = 38, V_WATER_RISE = 39,
            V_HEALTH_DEC = 40, V_ROPE = 41, V_GETAWAY = 42, V_WORLD_EDGE = 43;

    public static final String[] WORLD_EDGES = { "None", "Wrap", "Bounce", "Sea" };

    /** Switch states, index 1 to 25 (index 0 unused). */
    public final boolean[] flag = new boolean[26];
    /** Numbers, index 26 to 43 (indices below 26 unused). */
    public final int[] value = new int[44];

    public Rules() { }

    public Rules copy() {
        Rules r = new Rules();
        System.arraycopy(flag, 0, r.flag, 0, flag.length);
        System.arraycopy(value, 0, r.value, 0, value.length);
        return r;
    }

    public static List<String> presetNames() {
        return new ArrayList<>(Arrays.asList(SchemeData.SCHEME_NAMES));
    }

    /** A built-in scheme by its Qt name, or null. */
    public static Rules preset(String name) {
        for (int i = 0; i < SchemeData.SCHEME_NAMES.length; i++) {
            if (SchemeData.SCHEME_NAMES[i].equals(name)) return presetAt(i);
        }
        return null;
    }

    public static Rules presetAt(int i) {
        Rules r = new Rules();
        String f = SchemeData.SCHEME_FLAGS[i];
        for (int k = 1; k <= 25; k++) r.flag[k] = f.charAt(k - 1) == '1';
        int[] v = SchemeData.SCHEME_VALUES[i];
        for (int k = 26; k <= 43; k++) r.value[k] = v[k - 26];
        return r;
    }

    /** The engine's game flags, as GameCFGWidget::getGameFlags builds them. */
    public int gameFlags() {
        int result = 0;
        for (int k = 1; k <= 25; k++) if (flag[k]) result |= FLAG_BITS[k];
        return result;
    }

    public boolean sameAs(Rules o) {
        return Arrays.equals(flag, o.flag) && Arrays.equals(value, o.value);
    }

    public void clampValues() {
        for (int k = 26; k <= 43; k++) {
            int[] rg = VALUE_RANGE[k - 26];
            value[k] = Math.max(rg[0], Math.min(rg[1], value[k]));
        }
    }

    /** Name of the built-in scheme with exactly these settings, or null. */
    public String matchingPreset() {
        for (int i = 0; i < SchemeData.SCHEME_NAMES.length; i++) {
            if (presetAt(i).sameAs(this)) return SchemeData.SCHEME_NAMES[i];
        }
        return null;
    }

    // Weapon sets ------------------------------------------------------------------------------

    public static List<String> weaponSetNames() {
        return new ArrayList<>(Arrays.asList(SchemeData.WEAPON_NAMES));
    }

    /** The 240 digit string of a weapon set (loadout, probability, delay, crate), or null. */
    public static String weaponSet(String name) {
        for (int i = 0; i < SchemeData.WEAPON_NAMES.length; i++) {
            if (SchemeData.WEAPON_NAMES[i].equals(name)) return SchemeData.WEAPON_STRINGS[i];
        }
        return null;
    }
}
