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
    /** Set when the player typed or rolled the name; without it a stored Build.MODEL is not taken as a choice. */
    static final String CHOSEN_KEY = "netname_chosen";
    static final String ASSET = "player-names.txt";
    static final int MAX_LENGTH = 20;
    private static final Random RANDOM = new Random();

    private PlayerNames() { }

    /**
     * The valid names of a list: blank lines and lines starting with # are skipped, lines are trimmed, so are names over 20 characters or
     * not plain ASCII, and a name that repeats an earlier one (ignoring case) is dropped.
     */
    static List<String> parse(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String raw : text.split("\n")) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.length() > MAX_LENGTH || !isPlainAscii(line)) continue;
            if (!seen.add(line.toLowerCase(java.util.Locale.ROOT))) continue;
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

    /**
     * A random name from the list that differs from {@code not} (ignoring case) whenever the list has another; Build.MODEL when the list is empty.
     * Only a list whose every name is {@code not} can give the same string back.
     */
    static String pick(List<String> names, String not, Random rnd) {
        if (names.isEmpty()) return Build.MODEL;
        List<String> others = new ArrayList<>();
        for (String n : names) if (not == null || !n.equalsIgnoreCase(not)) others.add(n);
        List<String> from = others.isEmpty() ? names : others;
        return from.get(rnd.nextInt(from.size()));
    }

    /**
     * The remembered name. A name is remembered when the player typed or rolled it (then {@link #CHOSEN_KEY} is set), or was picked here at
     * random. A stored name equal to Build.MODEL that the player did not choose (tv12 saved it unconditionally) counts as not chosen: a
     * random name replaces it. When the list is missing the device model is returned and nothing is saved.
     */
    static String current(Context c) {
        return current(c, load(c));
    }

    /** {@link #current(Context)} with the list given (an empty one stands for a missing asset). */
    static String current(Context c, List<String> names) {
        SharedPreferences p = c.getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE);
        String saved = p.getString(PREF_KEY, null);
        boolean chosen = p.getBoolean(CHOSEN_KEY, false);
        if (saved != null && !saved.trim().isEmpty() && (chosen || !saved.equals(Build.MODEL))) return saved;
        if (names.isEmpty()) return saved != null && !saved.trim().isEmpty() ? saved : Build.MODEL;
        String n = pick(names, saved, RANDOM);
        p.edit().putString(PREF_KEY, n).putBoolean(CHOSEN_KEY, false).apply();
        return n;
    }

    /** The player typed this name: remember it and mark it as the player's own. A blank text is not saved. */
    static void typed(Context c, String text) {
        String n = text == null ? "" : text.trim();
        if (n.isEmpty()) return;
        c.getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE).edit().putString(PREF_KEY, n).putBoolean(CHOSEN_KEY, true).apply();
    }

    /** A different random name; saved as the player's choice unless the list is missing (then Build.MODEL, not saved). */
    static String reroll(Context c, String now) {
        List<String> names = load(c);
        String n = pick(names, now, RANDOM);
        if (!names.isEmpty()) typed(c, n);
        return n;
    }
}
