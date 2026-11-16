package org.hedgewars.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Random;

/** Keeps the last setup and the options in the app's preferences (one text blob each). */
public final class Store {
    /** The preferences file. The name is kept from the first builds so an update keeps the saved setup. */
    static final String PREFS_NAME = "hedgeparty";

    private Store() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static GameData cachedData;
    private static long cachedStamp = -1;

    /**
     * The lists read from the unpacked game data. One instance is kept (the folders are scanned once, not on every
     * return to a screen); a new one is made when the data has been unpacked again, which rewrites the marker file.
     */
    public static synchronized GameData data(Context c) {
        File root = new File(c.getFilesDir(), "Data");
        long stamp = new File(root, ".installed").lastModified();
        if (cachedData == null || !cachedData.root.equals(root) || stamp != cachedStamp) {
            cachedData = new GameData(root);
            cachedStamp = stamp;
        }
        return cachedData;
    }

    public static SetupModel loadSetup(Context c, GameData d) {
        Random rnd = new Random();
        String t = prefs(c).getString("setup", null);
        SetupModel m = t == null ? SetupModel.defaults(d, rnd) : SetupModel.fromText(t, d, rnd);
        m.repair(d, rnd);
        return m;
    }

    /**
     * The team a joining phone plays with: the one the player picked on the Network screen ("Your team"), else the first
     * human team of the setup, else the first team. An index that the setup no longer has falls back to the same default.
     */
    public static int joinTeamIndex(Context c, SetupModel m) {
        int chosen = prefs(c).getInt("joinTeam", -1);
        if (chosen >= 0 && chosen < m.teams.size()) return chosen;
        for (int i = 0; i < m.teams.size(); i++) if (m.teams.get(i).level <= 0) return i;
        return 0;
    }

    public static void saveJoinTeamIndex(Context c, int index) {
        prefs(c).edit().putInt("joinTeam", index).apply();
    }

    /** A copy of the team a joining phone sends (see {@link #joinTeamIndex}). */
    public static SetupModel.Team joinTeam(Context c, GameData d) {
        SetupModel m = loadSetup(c, d);
        return m.teams.get(joinTeamIndex(c, m)).copy();
    }

    public static void saveSetup(Context c, SetupModel m) {
        prefs(c).edit().putString("setup", m.toText()).apply();
    }

    public static Options loadOptions(Context c) {
        return Options.fromText(prefs(c).getString("options", null));
    }

    public static void saveOptions(Context c, Options o) {
        // commit, not apply: the game runs in another process and reads this file when it starts.
        prefs(c).edit().putString("options", o.toText()).commit();
    }
}
