package org.hedgewars.android;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One game scheme: the 25 rule switches and the 18 numbers that the Qt frontend keeps per scheme
 * (QTfrontend/model/gameSchemeModel.cpp), plus three numbers only the Android app has (death explosion size, terrain
 * destruction and the aim helper, entries 44, 45 and 46). Index numbers (1 to 25 for switches, 26 to 43 for numbers)
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
        "Death explosion size (%)", "Terrain destruction (%)", "Aim helper",
    };

    /** Allowed range of each number (entries 26 to 46), as in the Qt scheme editor. The last three are not in the Qt scheme. */
    public static final int[][] VALUE_RANGE = {
        { 10, 300 }, { 1, 9999 }, { 1, 1000 }, { 0, 51 }, { 0, 9 }, { -1, 5 }, { 0, 200 }, { 0, 100 },
        { 0, 200 }, { 0, 200 }, { 0, 200 }, { 0, 100 }, { 0, 1000 }, { 0, 100 }, { 0, 1000 }, { 25, 999 },
        { 0, 999 }, { 0, 3 }, { 50, 300 }, { 100, 200 }, { 0, 2 },
    };

    public static final int V_DAMAGE = 26, V_TURN_TIME = 27, V_HEALTH = 28, V_SUDDEN = 29, V_CASE_PROB = 30,
            V_MINES_TIME = 31, V_MINES = 32, V_DUD = 33, V_EXPLOSIVES = 34, V_AIR_MINES = 35,
            V_SENTRIES = 36, V_HEALTH_PROB = 37, V_HEALTH_AMOUNT = 38, V_WATER_RISE = 39,
            V_HEALTH_DEC = 40, V_ROPE = 41, V_GETAWAY = 42, V_WORLD_EDGE = 43,
            V_DEATH_BOOM = 44, V_LAND_BOOM = 45, V_AIM_HELPER = 46;

    /** Values the Rules tab offers for the later numbers. 100 is the unchanged game and is never sent to the engine. */
    public static final int[] DEATH_BOOM_OPTIONS = { 50, 100, 150, 200, 300 };
    public static final int[] LAND_BOOM_OPTIONS = { 100, 125, 150, 200 };
    /** Aim helper: 0 is off (never sent), 1 short, 2 full. The engine draws the path while the local player aims. */
    public static final int[] AIM_HELPER_OPTIONS = { 0, 1, 2 };
    public static final String[] AIM_HELPER_NAMES = { "Off", "Short", "Full" };

    public static final String[] WORLD_EDGES = { "None", "Wrap", "Bounce", "Sea" };

    /** Switch states, index 1 to 25 (index 0 unused). */
    public final boolean[] flag = new boolean[26];
    /** Numbers, index 26 to 46 (indices below 26 unused). Entries 44 and 45 are saved with the rules too, and default to 100. */
    public final int[] value = new int[47];

    /** The options of a choice entry (44, 45 or 46), or null for the other numbers. */
    public static int[] optionsOf(int entry) {
        if (entry == V_DEATH_BOOM) return DEATH_BOOM_OPTIONS;
        if (entry == V_LAND_BOOM) return LAND_BOOM_OPTIONS;
        if (entry == V_AIM_HELPER) return AIM_HELPER_OPTIONS;
        return null;
    }

    /** The position of a value in its options, or 0 (the first option) when it is not one of them. */
    public static int optionIndex(int[] options, int v) {
        for (int i = 0; i < options.length; i++) if (options[i] == v) return i;
        return 0;
    }

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
        r.value[V_DEATH_BOOM] = 100;
        r.value[V_LAND_BOOM] = 100;
        r.value[V_AIM_HELPER] = 0;
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
        for (int k = 26; k <= 46; k++) {
            int[] rg = VALUE_RANGE[k - 26];
            value[k] = Math.max(rg[0], Math.min(rg[1], value[k]));
            // The later choice numbers only take the values the Rules tab offers; anything else falls back to the unchanged 100.
            int[] opts = optionsOf(k);
            if (opts != null && optionIndex(opts, value[k]) == 0 && opts[0] != value[k]) value[k] = 100;
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
