package org.hedgewars.android;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Reads the lists the setup screen offers (themes, hats, graves, forts, voices, maps, game styles and
 * the random name lists) from the extracted Data folder, the same folders the Qt frontend reads
 * through PhysFS. Pure Java, so the config tests run on a plain JVM.
 */
public final class GameData {
    public final File root;

    private List<String> themes, hiddenThemes, hats, graves, forts, voices, maps, styles;
    private final Map<String, List<String>> dictCache = new HashMap<>();
    private List<List<String>> typeTeamNames, typeHats;

    private volatile boolean warmed;

    public GameData(File dataRoot) {
        this.root = dataRoot;
    }

    /**
     * Reads every directory listing the screens use, so later calls only return the cached lists. Meant for a worker
     * thread; call the getters from other threads only once {@link #isWarm()} is true.
     */
    public synchronized void warm() {
        if (warmed) return;
        themes();
        hats();
        graves();
        forts();
        voices();
        imageMaps();
        styles();
        typeCount();
        warmed = true;
    }

    public boolean isWarm() {
        return warmed;
    }

    // Directory listings -----------------------------------------------------------------------

    private static List<String> sorted(List<String> l) {
        Collections.sort(l, String.CASE_INSENSITIVE_ORDER);
        return l;
    }

    private String[] list(String rel) {
        String[] r = new File(root, rel).list();
        return r == null ? new String[0] : r;
    }

    private List<String> pngNames(String rel) {
        List<String> l = new ArrayList<>();
        for (String f : list(rel)) {
            if (f.endsWith(".png") && new File(root, rel + "/" + f).isFile()) l.add(f.substring(0, f.length() - 4));
        }
        return sorted(l);
    }

    private void loadThemes() {
        if (themes != null) return;
        themes = new ArrayList<>();
        hiddenThemes = new ArrayList<>();
        for (String t : list("Themes")) {
            if (!new File(root, "Themes/" + t + "/theme.cfg").isFile()) continue;
            // As in the Qt ThemeModel: a theme without an icon is hidden from the pickers.
            if (new File(root, "Themes/" + t + "/icon.png").isFile()) themes.add(t); else hiddenThemes.add(t);
        }
        sorted(themes);
        sorted(hiddenThemes);
    }

    /** Themes the player may choose (those with an icon). */
    public List<String> themes() {
        loadThemes();
        return themes;
    }

    public boolean hasTheme(String t) {
        loadThemes();
        return themes.contains(t) || hiddenThemes.contains(t);
    }

    public List<String> hats() {
        if (hats == null) hats = pngNames("Graphics/Hats");
        return hats;
    }

    public List<String> graves() {
        if (graves == null) graves = pngNames("Graphics/Graves");
        return graves;
    }

    /** Fort names: the Forts/&lt;Name&gt;L.png files, as the Qt frontend lists them. */
    public List<String> forts() {
        if (forts == null) {
            forts = new ArrayList<>();
            for (String f : list("Forts")) {
                if (f.endsWith("L.png") && !f.contains("-icon")) forts.add(f.substring(0, f.length() - 5));
            }
            sorted(forts);
        }
        return forts;
    }

    public List<String> voices() {
        if (voices == null) {
            voices = new ArrayList<>();
            for (String v : list("Sounds/voices")) {
                if (new File(root, "Sounds/voices/" + v).isDirectory()) voices.add(v);
            }
            sorted(voices);
        }
        return voices;
    }

    /** Image maps: folders of Maps with a map.cfg and no map.lua (a map.lua makes it a mission). */
    public List<String> imageMaps() {
        if (maps == null) {
            maps = new ArrayList<>();
            for (String m : list("Maps")) {
                File d = new File(root, "Maps/" + m);
                if (m.startsWith("test") || !new File(d, "map.cfg").isFile() || new File(d, "map.lua").exists()) continue;
                if (!new File(d, "map.png").isFile() && !new File(d, "mask.png").isFile()) continue;
                maps.add(m);
            }
            sorted(maps);
        }
        return maps;
    }

    /** The theme named in an image map's map.cfg (first line), or null. */
    public String mapTheme(String map) {
        List<String> l = readLines(new File(root, "Maps/" + map + "/map.cfg"));
        return l.isEmpty() ? null : l.get(0).trim();
    }

    /** The hedgehog limit in an image map's map.cfg (second line), or 18 as the Qt frontend assumes. */
    public int mapHogLimit(String map) {
        List<String> l = readLines(new File(root, "Maps/" + map + "/map.cfg"));
        if (l.size() > 1) {
            try {
                int n = Integer.parseInt(l.get(1).trim());
                if (n > 0) return n;
            } catch (NumberFormatException ignored) { }
        }
        return 18;
    }

    // Game styles ------------------------------------------------------------------------------

    /** Script names (file name without .lua) in Scripts/Multiplayer, sorted. */
    public List<String> styles() {
        if (styles == null) {
            styles = new ArrayList<>();
            for (String f : list("Scripts/Multiplayer")) {
                if (f.endsWith(".lua")) styles.add(f.substring(0, f.length() - 4));
            }
            sorted(styles);
        }
        return styles;
    }

    public static String styleTitle(String script) {
        return script.replace('_', ' ');
    }

    /**
     * What a style's .cfg file asks for: {scheme, weapons}. Each is a name (underscores for spaces),
     * "*" for "the player's choice" or "locked" for "the Default one, not changeable". Without a
     * cfg file both are "locked", as in the Qt GameStyleModel.
     */
    public String[] styleCfg(String script) {
        String scheme = "locked", weapons = "locked";
        File f = new File(root, "Scripts/Multiplayer/" + script + ".cfg");
        if (f.isFile()) {
            List<String> toks = new ArrayList<>();
            for (String line : readLines(f)) {
                for (String t : line.trim().split("\\s+")) if (!t.isEmpty()) toks.add(t);
            }
            if (toks.size() > 0) scheme = toks.get(0);
            if (toks.size() > 1) weapons = toks.get(1);
        }
        return new String[] { scheme.replace('_', ' '), weapons.replace('_', ' ') };
    }

    // Random names (port of QTfrontend/util/namegen.cpp) ---------------------------------------

    static List<String> readLines(File f) {
        List<String> l = new ArrayList<>();
        if (!f.isFile()) return l;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String s;
            while ((s = r.readLine()) != null) l.add(s);
        } catch (IOException ignored) { }
        return l;
    }

    private List<String> dictContents(String name) {
        List<String> cached = dictCache.get(name);
        if (cached != null) return cached;
        List<String> out = new ArrayList<>();
        for (String s : readLines(new File(root, "Names/" + name + ".txt"))) if (!s.isEmpty()) out.add(s);
        if (out.isEmpty()) out.add(name);
        dictCache.put(name, out);
        return out;
    }

    private List<String> dictsForHat(String hat) {
        List<String> out = new ArrayList<>();
        for (String s : readLines(new File(root, "Names/" + hat + ".cfg"))) if (!s.isEmpty()) out.add(s);
        if (out.isEmpty()) out.add("generic");
        return out;
    }

    /** A random hedgehog name suited to the hat, avoiding the names in {@code taken} where possible. */
    public String randomHogName(String hat, List<String> taken, Random rnd) {
        List<String> dicts = dictsForHat(hat);
        List<String> dict = new ArrayList<>(dictContents(dicts.get(rnd.nextInt(dicts.size()))));
        dict.removeAll(taken);
        if (dict.isEmpty()) dict = dictContents("generic");
        return dict.get(rnd.nextInt(dict.size()));
    }

    private void loadTypes() {
        if (typeTeamNames != null) return;
        typeTeamNames = new ArrayList<>();
        typeHats = new ArrayList<>();
        typeTeamNames.add(new ArrayList<String>());
        typeHats.add(new ArrayList<String>());
        int counter = 0;
        for (String line : readLines(new File(root, "Names/types.ini"))) {
            if (line.equals("#####")) {
                counter++;
                if (counter % 2 == 0) {
                    typeTeamNames.add(new ArrayList<String>());
                    typeHats.add(new ArrayList<String>());
                }
            } else if (line.equals("*****") || line.equals("*END*")) {
                break;
            } else if (counter % 2 == 0) {
                typeTeamNames.get(counter / 2).add(line);
            } else {
                typeHats.get((counter - 1) / 2).add(line);
            }
        }
    }

    private static <T> T pick(List<T> l, Random rnd) {
        return l.get(rnd.nextInt(l.size()));
    }

    /** A random team name (kind -1: from any group, 0: the generic group). */
    public String randomTeamName(int kind, Random rnd) {
        loadTypes();
        if (kind < 0) kind = rnd.nextInt(typeTeamNames.size());
        List<String> l = typeTeamNames.get(kind);
        return l.isEmpty() ? "Team" : pick(l, rnd);
    }

    /** Number of themed hat groups in types.ini. */
    public int typeCount() {
        loadTypes();
        return typeHats.size();
    }

    /** The hats of a themed group in types.ini that are installed (a trimmed data set, as in the test profile, lacks some). */
    public List<String> typeHats(int kind) {
        loadTypes();
        if (hats().isEmpty()) return typeHats.get(kind);
        List<String> installed = new ArrayList<>();
        for (String h : typeHats.get(kind)) if (hats().contains(h)) installed.add(h);
        return installed;
    }

    /** A random hat from the whole hat list. */
    public String randomHat(Random rnd) {
        List<String> h = hats();
        return h.isEmpty() ? "NoHat" : pick(h, rnd);
    }

    public String randomGrave(Random rnd) {
        List<String> g = graves();
        return g.isEmpty() ? "Statue" : pick(g, rnd);
    }

    public String randomFort(Random rnd) {
        List<String> f = forts();
        return f.isEmpty() ? "Earth" : pick(f, rnd);
    }

    public String randomVoice(Random rnd) {
        // The Qt frontend picks any voice pack; keep to the English ones so a random team is understandable.
        List<String> v = new ArrayList<>();
        for (String s : voices()) if (!s.contains("_")) v.add(s);
        if (v.isEmpty()) v = voices();
        return v.isEmpty() ? "Default" : pick(v, rnd);
    }

    public static List<String> asList(String... s) {
        return new ArrayList<>(Arrays.asList(s));
    }
}
