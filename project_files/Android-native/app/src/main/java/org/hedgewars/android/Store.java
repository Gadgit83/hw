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

    /**
     * The options, read fresh from their own file every time. The game runs in its own process, which keeps its own copy of the
     * app's preferences, so the options are not kept in the preferences: a write of a preferences copy that was loaded earlier
     * (the main process rewrites its whole copy) put old options back, lost the saved controls layout and undid volume changes
     * made in a game. Until the file is first written, the options of an older version are read from the preferences.
     */
    public static Options loadOptions(Context c) {
        String text = readText(optionsFile(c));
        if (text == null) text = prefs(c).getString("options", null);
        return Options.fromText(text);
    }

    public static void saveOptions(Context c, Options o) {
        writeAtomic(optionsFile(c), o.toText());
    }

    /** Forgets the options: the next read gives the defaults (Reset setup and options). */
    public static void clearOptions(Context c) {
        optionsFile(c).delete();
    }

    private static File optionsFile(Context c) {
        return new File(c.getFilesDir(), "options.txt");
    }

    /** The text of a file, or null when it does not exist. */
    private static String readText(File f) {
        if (!f.exists()) return null;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
            return new String(b.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            android.util.Log.w(Diag.TAG, "options not read: " + e);
            return null;
        }
    }

    /** Writes a file in one piece: a temporary file, synced, then renamed over the old one. */
    private static void writeAtomic(File f, String text) {
        File tmp = new File(f.getPath() + ".tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (java.io.IOException e) {
            android.util.Log.w(Diag.TAG, "options not saved: " + e);
            return;
        }
        if (!tmp.renameTo(f)) android.util.Log.w(Diag.TAG, "options not saved: rename failed");
    }
}
