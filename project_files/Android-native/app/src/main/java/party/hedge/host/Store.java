package party.hedge.host;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Random;

/** Keeps the last setup and the options in the app's preferences (one text blob each). */
public final class Store {
    private static final String FILE = "hedgeparty";

    private Store() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static GameData data(Context c) {
        return new GameData(new File(c.getFilesDir(), "Data"));
    }

    public static SetupModel loadSetup(Context c, GameData d) {
        Random rnd = new Random();
        String t = prefs(c).getString("setup", null);
        SetupModel m = t == null ? SetupModel.defaults(d, rnd) : SetupModel.fromText(t, d, rnd);
        m.repair(d, rnd);
        return m;
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
