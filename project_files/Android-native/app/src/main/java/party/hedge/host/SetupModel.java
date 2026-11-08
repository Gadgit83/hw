package party.hedge.host;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;

/**
 * Everything the setup screen edits: the teams and their hedgehogs, the map, the rules and the weapons.
 * It is kept as plain text (java.util.Properties) in the app's preferences so the last setup comes back
 * on the next launch. Pure Java, no Android classes.
 */
public final class SetupModel {
    public static final int MIN_TEAMS = 2, MAX_TEAMS = 8, MIN_HOGS = 1, MAX_HOGS = 8;

    /** Map types; the numbers 0 to 5 are the engine's e$mapgen values. */
    public static final int MAP_GENERATED = 0, MAP_MAZE = 1, MAP_PERLIN = 2, MAP_FORTS = 4, MAP_WFC = 5,
            MAP_IMAGE = 6;

    /** Team colours of the engine (ClanColorArray, kept in sync with QTfrontend/hwconsts.h), as 0xRRGGBB. */
    public static final int[] COLORS = {
        0xff0204, 0x4980c1, 0x1de6ba, 0xb541ef, 0xe55bb0, 0x20bf00, 0xfe8b0e, 0x8f5902, 0xffff01,
    };
    public static final String[] COLOR_NAMES = {
        "Red", "Blue", "Teal", "Purple", "Pink", "Green", "Orange", "Brown", "Yellow",
    };

    public static final String[] TEMPLATE_FILTERS = { "Any size", "Small", "Medium", "Large", "Cavern", "Wacky" };
    public static final String[] MAZE_STYLES = {
        "Small tunnels", "Medium tunnels", "Large tunnels", "Small islands", "Medium islands", "Large islands",
    };

    /** One team. Level 0 is a human; 1 to 5 is a computer player, 5 the hardest (the engine's level is 6 minus this). */
    public static final class Team {
        public String name = "Team";
        public int color;
        public int level;
        public int hogs = 4;
        public final String[] hogName = new String[MAX_HOGS];
        public final String[] hogHat = new String[MAX_HOGS];
        public String grave = "Statue", fort = "Earth", voice = "Default", flag = "hedgewars";

        public Team copy() {
            Team t = new Team();
            t.name = name; t.color = color; t.level = level; t.hogs = hogs;
            System.arraycopy(hogName, 0, t.hogName, 0, MAX_HOGS);
            System.arraycopy(hogHat, 0, t.hogHat, 0, MAX_HOGS);
            t.grave = grave; t.fort = fort; t.voice = voice; t.flag = flag;
            return t;
        }

        /** The engine's difficulty for eaddhh: 0 human, otherwise 6 minus the level. */
        public int engineLevel() {
            return level <= 0 ? 0 : 6 - Math.min(5, level);
        }
    }

    public final List<Team> teams = new ArrayList<>();

    public int mapType = MAP_GENERATED;
    public int templateFilter;            // 0 to 5, for generated maps and WFC
    public int mazeSize;                  // 0 to 5, for mazes
    public int featureSize = 12;          // 1 to 25
    public String imageMap = "";
    public String theme = "";             // empty: random
    public String seed = "";              // empty: random
    public String style = "";             // empty: Normal; otherwise a script name in Scripts/Multiplayer

    public String schemeName = "Default"; // the built-in scheme the rules started from
    public Rules rules = Rules.preset("Default");
    public String weaponSet = "Default";
    public boolean linkWeapons = true;
    public String scriptParam = "";

    // Defaults ----------------------------------------------------------------------------------

    public static SetupModel defaults(GameData d, Random rnd) {
        SetupModel m = new SetupModel();
        m.teams.add(randomTeam(d, rnd, 0, new ArrayList<Team>()));
        m.teams.add(randomTeam(d, rnd, 1, m.teams));
        return m;
    }

    /** A new random team: themed hats and names, as the Qt frontend's teamRandomEverything. */
    public static Team randomTeam(GameData d, Random rnd, int color, List<Team> others) {
        Team t = new Team();
        t.color = color;
        t.grave = d.randomGrave(rnd);
        t.fort = d.randomFort(rnd);
        t.voice = d.randomVoice(rnd);
        int kind = d.typeCount() > 0 ? rnd.nextInt(d.typeCount()) : 0;
        int r = rnd.nextInt(10);
        int mode = r <= 4 ? 0 : (r <= 8 ? 1 : 2);   // themed hats, one hat for all, a hat each
        for (int i = 0; i < MAX_HOGS; i++) {
            List<String> kindHats = d.typeHats(Math.min(kind, d.typeCount() - 1));
            if (mode == 0 && !kindHats.isEmpty()) t.hogHat[i] = kindHats.get(rnd.nextInt(kindHats.size()));
            else if (mode == 1) t.hogHat[i] = i == 0 ? d.randomHat(rnd) : t.hogHat[i - 1];
            else t.hogHat[i] = d.randomHat(rnd);
        }
        fillHogNames(d, rnd, t);
        t.name = d.randomTeamName(mode == 0 ? kind : 0, rnd);
        // Names must differ between teams; the engine would rename a duplicate anyway.
        for (int tries = 0; tries < 20 && nameTaken(others, t.name); tries++) t.name = d.randomTeamName(-1, rnd);
        return t;
    }

    private static boolean nameTaken(List<Team> others, String n) {
        for (Team o : others) if (o.name.equalsIgnoreCase(n)) return true;
        return false;
    }

    /** Gives every hedgehog slot a hat-appropriate random name that differs from the others where possible. */
    public static void fillHogNames(GameData d, Random rnd, Team t) {
        List<String> taken = new ArrayList<>();
        for (int i = 0; i < MAX_HOGS; i++) {
            if (t.hogHat[i] == null) t.hogHat[i] = "NoHat";
            t.hogName[i] = d.randomHogName(t.hogHat[i], taken, rnd);
            taken.add(t.hogName[i]);
        }
    }

    /** The lowest colour not used by any team, or the next in turn once all nine are taken. */
    public int freeColor() {
        for (int c = 0; c < COLORS.length; c++) {
            boolean used = false;
            for (Team t : teams) used |= t.color == c;
            if (!used) return c;
        }
        return teams.size() % COLORS.length;
    }

    // Effective rules ---------------------------------------------------------------------------

    /** The scheme actually used: a style may force the Default scheme or name another (see GameData.styleCfg). */
    public Rules effectiveRules(GameData d) {
        if (style.isEmpty()) return rules;
        String s = d.styleCfg(style)[0];
        if (s.equals("*")) return rules;
        if (s.equals("locked")) return Rules.preset("Default");
        Rules p = Rules.preset(s);
        return p != null ? p : rules;
    }

    public String effectiveWeaponSet(GameData d) {
        if (style.isEmpty()) return weaponSet;
        String w = d.styleCfg(style)[1];
        if (w.equals("*")) return weaponSet;
        if (w.equals("locked")) return "Default";
        return Rules.weaponSet(w) != null ? w : weaponSet;
    }

    public boolean schemeLocked(GameData d) {
        return !style.isEmpty() && !d.styleCfg(style)[0].equals("*");
    }

    public boolean weaponsLocked(GameData d) {
        return !style.isEmpty() && !d.styleCfg(style)[1].equals("*");
    }

    /** Picks a built-in scheme; with linking on, the weapon set of the same name follows (as in the Qt frontend). */
    public void chooseScheme(String name) {
        Rules p = Rules.preset(name);
        if (p == null) return;
        schemeName = name;
        rules = p;
        if (linkWeapons && Rules.weaponSet(name) != null) weaponSet = name;
    }

    public boolean rulesEdited() {
        Rules p = Rules.preset(schemeName);
        return p == null || !p.sameAs(rules);
    }

    // Saving and loading ------------------------------------------------------------------------

    private static String esc(String s) {
        return s == null ? "" : s;
    }

    public String toText() {
        Properties p = new Properties();
        p.setProperty("v", "1");
        p.setProperty("teams", Integer.toString(teams.size()));
        for (int i = 0; i < teams.size(); i++) {
            Team t = teams.get(i);
            String k = "t" + i + ".";
            p.setProperty(k + "name", esc(t.name));
            p.setProperty(k + "color", Integer.toString(t.color));
            p.setProperty(k + "level", Integer.toString(t.level));
            p.setProperty(k + "hogs", Integer.toString(t.hogs));
            p.setProperty(k + "grave", esc(t.grave));
            p.setProperty(k + "fort", esc(t.fort));
            p.setProperty(k + "voice", esc(t.voice));
            p.setProperty(k + "flag", esc(t.flag));
            for (int h = 0; h < MAX_HOGS; h++) {
                p.setProperty(k + "h" + h + ".name", esc(t.hogName[h]));
                p.setProperty(k + "h" + h + ".hat", esc(t.hogHat[h]));
            }
        }
        p.setProperty("mapType", Integer.toString(mapType));
        p.setProperty("template", Integer.toString(templateFilter));
        p.setProperty("maze", Integer.toString(mazeSize));
        p.setProperty("feature", Integer.toString(featureSize));
        p.setProperty("imageMap", esc(imageMap));
        p.setProperty("theme", esc(theme));
        p.setProperty("seed", esc(seed));
        p.setProperty("style", esc(style));
        p.setProperty("scheme", esc(schemeName));
        StringBuilder fl = new StringBuilder();
        for (int k = 1; k <= 25; k++) fl.append(rules.flag[k] ? '1' : '0');
        p.setProperty("flags", fl.toString());
        StringBuilder vals = new StringBuilder();
        for (int k = 26; k <= 43; k++) vals.append(k > 26 ? "," : "").append(rules.value[k]);
        p.setProperty("values", vals.toString());
        p.setProperty("weapons", esc(weaponSet));
        p.setProperty("link", linkWeapons ? "1" : "0");
        p.setProperty("scriptParam", esc(scriptParam));
        StringWriter w = new StringWriter();
        try {
            p.store(w, null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return w.toString();
    }

    private static int intOf(Properties p, String k, int def, int lo, int hi) {
        try {
            int v = Integer.parseInt(p.getProperty(k, ""));
            return Math.max(lo, Math.min(hi, v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Parses saved text; anything missing or invalid falls back to a default, so old saves still load. */
    public static SetupModel fromText(String text, GameData d, Random rnd) {
        SetupModel m = new SetupModel();
        Properties p = new Properties();
        try {
            p.load(new StringReader(text == null ? "" : text));
        } catch (IOException | IllegalArgumentException e) {
            return defaults(d, rnd);
        }
        int n = intOf(p, "teams", 0, 0, MAX_TEAMS);
        for (int i = 0; i < n; i++) {
            String k = "t" + i + ".";
            Team t = new Team();
            t.name = p.getProperty(k + "name", "Team " + (i + 1));
            t.color = intOf(p, k + "color", i % COLORS.length, 0, COLORS.length - 1);
            t.level = intOf(p, k + "level", 0, 0, 5);
            t.hogs = intOf(p, k + "hogs", 4, MIN_HOGS, MAX_HOGS);
            t.grave = p.getProperty(k + "grave", "Statue");
            t.fort = p.getProperty(k + "fort", "Earth");
            t.voice = p.getProperty(k + "voice", "Default");
            t.flag = p.getProperty(k + "flag", "hedgewars");
            for (int h = 0; h < MAX_HOGS; h++) {
                t.hogName[h] = p.getProperty(k + "h" + h + ".name", "");
                t.hogHat[h] = p.getProperty(k + "h" + h + ".hat", "NoHat");
            }
            m.teams.add(t);
        }
        if (m.teams.size() < MIN_TEAMS) return defaults(d, rnd);
        for (Team t : m.teams) {
            for (int h = 0; h < MAX_HOGS; h++) {
                if (t.hogName[h].isEmpty()) t.hogName[h] = d.randomHogName(t.hogHat[h], new ArrayList<String>(), rnd);
            }
        }
        m.mapType = intOf(p, "mapType", MAP_GENERATED, 0, 6);
        if (m.mapType == 3) m.mapType = MAP_GENERATED;       // hand-drawn maps are not offered
        m.templateFilter = intOf(p, "template", 0, 0, 5);
        m.mazeSize = intOf(p, "maze", 0, 0, 5);
        m.featureSize = intOf(p, "feature", 12, 1, 25);
        m.imageMap = p.getProperty("imageMap", "");
        m.theme = p.getProperty("theme", "");
        m.seed = p.getProperty("seed", "");
        m.style = p.getProperty("style", "");
        m.schemeName = p.getProperty("scheme", "Default");
        Rules r = Rules.preset(m.schemeName);
        if (r == null) {
            m.schemeName = "Default";
            r = Rules.preset("Default");
        }
        String fl = p.getProperty("flags", "");
        if (fl.length() == 25) for (int k = 1; k <= 25; k++) r.flag[k] = fl.charAt(k - 1) == '1';
        String[] vs = p.getProperty("values", "").split(",");
        if (vs.length == 18) {
            try {
                for (int k = 26; k <= 43; k++) r.value[k] = Integer.parseInt(vs[k - 26].trim());
            } catch (NumberFormatException ignored) { }
        }
        r.clampValues();
        m.rules = r;
        m.weaponSet = p.getProperty("weapons", "Default");
        if (Rules.weaponSet(m.weaponSet) == null) m.weaponSet = "Default";
        m.linkWeapons = !"0".equals(p.getProperty("link", "1"));
        m.scriptParam = p.getProperty("scriptParam", "");
        return m;
    }

    /** Keeps the model valid against the data on disk (a deleted hat, theme or map falls back to something present). */
    public void repair(GameData d, Random rnd) {
        for (Team t : teams) {
            if (!d.graves().isEmpty() && !d.graves().contains(t.grave)) t.grave = d.randomGrave(rnd);
            if (!d.forts().isEmpty() && !d.forts().contains(t.fort)) t.fort = d.randomFort(rnd);
            if (!d.voices().isEmpty() && !d.voices().contains(t.voice)) t.voice = d.randomVoice(rnd);
            for (int h = 0; h < MAX_HOGS; h++) {
                if (!d.hats().isEmpty() && !d.hats().contains(t.hogHat[h])) t.hogHat[h] = "NoHat";
            }
        }
        if (!theme.isEmpty() && !d.themes().contains(theme)) theme = "";
        if (mapType == MAP_IMAGE && (imageMap.isEmpty() || !d.imageMaps().contains(imageMap))) {
            if (d.imageMaps().isEmpty()) mapType = MAP_GENERATED; else imageMap = d.imageMaps().get(0);
        }
        if (!style.isEmpty() && !d.styles().contains(style)) style = "";
    }
}
