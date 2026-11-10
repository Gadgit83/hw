package org.hedgewars.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Default player names. The list is the asset player-names.txt, copied from the hedgewars-party repository by
 * scripts/sync-player-names.sh (no names are written in the code). The name on the Network screen is picked at random the
 * first time, kept in the settings, and changes only when the player edits it or rolls again.
 */
final class PlayerNames {
    static final String PREF_KEY = "netname";
    static final String ASSET = "player-names.txt";
    static final int MAX_LENGTH = 20;
    private static final Random RANDOM = new Random();

    private PlayerNames() { }

    /** The valid names of a list: blank lines and lines starting with # are skipped, so are names over 20 characters or not plain ASCII. */
    static List<String> parse(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String raw : text.split("\n")) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            if (line.length() > MAX_LENGTH || !isPlainAscii(line)) continue;
            out.add(line);
        }
        return out;
    }

    private static boolean isPlainAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7e) return false;
        }
        return true;
    }

    /** The names shipped in the app; empty when the asset is missing or has no valid line. */
    static List<String> load(Context c) {
        try (InputStream in = c.getAssets().open(ASSET)) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            return parse(sb.toString());
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    /** A random name from the list that differs from {@code not} when the list has another; Build.MODEL when the list is empty. */
    static String pick(List<String> names, String not, Random rnd) {
        if (names.isEmpty()) return Build.MODEL;
        List<String> others = new ArrayList<>(names);
        others.remove(not);
        List<String> from = others.isEmpty() ? names : others;
        return from.get(rnd.nextInt(from.size()));
    }

    /** The remembered name; on the first use a random one, saved so that it stays the same until it is edited or rolled again. */
    static String current(Context c) {
        SharedPreferences p = c.getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE);
        String saved = p.getString(PREF_KEY, null);
        if (saved != null && !saved.trim().isEmpty()) return saved;
        String n = pick(load(c), null, RANDOM);
        p.edit().putString(PREF_KEY, n).apply();
        return n;
    }

    /** A different random name, saved. */
    static String reroll(Context c, String now) {
        String n = pick(load(c), now, RANDOM);
        c.getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE).edit().putString(PREF_KEY, n).apply();
        return n;
    }
}
