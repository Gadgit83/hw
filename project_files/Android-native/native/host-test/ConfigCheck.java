import org.hedgewars.android.GameConfig;
import org.hedgewars.android.GameData;
import org.hedgewars.android.Options;
import org.hedgewars.android.Rules;
import org.hedgewars.android.SetupModel;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JVM checks of the setup model and GameConfig (no device, no engine run needed). It compares what the app sends with
 * the sources of the desktop frontend and the engine, so a drift shows up as a failure:
 *  - scheme presets, weapon sets and the game flag bits against QTfrontend (gameSchemeModel.cpp, weapons.h,
 *    hwconsts.cpp.in, gamecfgwidget.cpp)
 *  - every config line is a command the engine registers (RegisterVariable in hedgewars/*.pas) and has the
 *    argument shape the engine handler splits (eaddteam, eaddhh, e$...)
 *  - every e$ line carries the value GameCFGWidget::getFullConfig would send for the same scheme entry
 *  - line order of one team block as HWGame::commonConfig / HWTeam::teamGameConfig send it
 * Usage: ConfigCheck DataDir EngineCheckout
 */
public class ConfigCheck {
    static int failures, checks;
    /** e$ name -> {scheme entry, factor}, read from GameCFGWidget::getFullConfig. */
    static final Map<String, int[]> qtLines = new HashMap<>();

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    public static void main(String[] a) throws Exception {
        File dataDir = new File(a[0]);
        File hw = new File(a[1]);
        GameData d = new GameData(dataDir);
        Random rnd = new Random(7);

        checkAgainstQt(hw);
        Set<String> engineCommands = engineCommands(hw);
        check(engineCommands.contains("addhh") && engineCommands.contains("gmflags"), "engine command list parsed");

        // Every scheme preset, with its same-named weapon set where there is one.
        for (String name : Rules.presetNames()) {
            SetupModel m = teams(d, rnd, 2, 4);
            m.chooseScheme(name);
            validate(m, d, engineCommands, "scheme " + name);
            compareValues(m, d, "scheme " + name);
        }
        // Every weapon set.
        for (String w : Rules.weaponSetNames()) {
            SetupModel m = teams(d, rnd, 2, 4);
            m.weaponSet = w;
            List<String> l = GameConfig.build(m, d, rnd);
            String s = Rules.weaponSet(w);
            check(l.contains("eammloadt " + s.substring(0, 60)) && l.contains("eammreinf " + s.substring(180, 240)),
                    "weapon set " + w + " lines");
            validate(m, d, engineCommands, "weapons " + w);
        }
        // Every map type.
        int[] types = { SetupModel.MAP_GENERATED, SetupModel.MAP_MAZE, SetupModel.MAP_PERLIN, SetupModel.MAP_FORTS,
                SetupModel.MAP_WFC, SetupModel.MAP_IMAGE };
        for (int t : types) {
            SetupModel m = teams(d, rnd, 2, 4);
            m.mapType = t;
            if (t == SetupModel.MAP_IMAGE) m.imageMap = d.imageMaps().isEmpty() ? "Hedgewars" : d.imageMaps().get(0);   // the test profile has no image maps
            m.mazeSize = 3;
            m.templateFilter = 4;
            m.featureSize = 7;
            List<String> l = validate(m, d, engineCommands, "map type " + t);
            check(l.contains("e$mapgen " + t), "mapgen " + t);
            check(l.contains("e$feature_size 7") && l.contains("e$template_filter 4"), "map parameters " + t);
            boolean maze = l.contains("e$maze_size 3");
            check(maze == (t == SetupModel.MAP_MAZE || t == SetupModel.MAP_PERLIN), "maze_size only for maze and perlin, type " + t);
            check(l.contains("emap " + m.imageMap) == (t == SetupModel.MAP_IMAGE), "emap only for image maps, type " + t);
        }
        // Every game style, including how its cfg locks or sets the scheme and weapons.
        for (String s : d.styles()) {
            SetupModel m = teams(d, rnd, 2, 4);
            m.style = s;
            List<String> l = validate(m, d, engineCommands, "style " + s);
            check(l.get(1).equals("escript Scripts/Multiplayer/" + s + ".lua"), "escript for " + s);
            String[] cfg = d.styleCfg(s);
            if (cfg[0].equals("locked")) check(m.effectiveRules(d).sameAs(Rules.preset("Default")), "locked scheme " + s);
            if (cfg[1].equals("locked")) check(m.effectiveWeaponSet(d).equals("Default"), "locked weapons " + s);
        }
        // 8 teams of 8, mixed human and computer.
        {
            SetupModel m = teams(d, rnd, 8, 8);
            for (int i = 0; i < 8; i++) m.teams.get(i).level = i < 3 ? 0 : i - 2;
            List<String> l = validate(m, d, engineCommands, "8x8");
            int hogs = 0, ai = 0;
            for (String s : l) if (s.startsWith("eaddhh ")) {
                hogs++;
                if (!s.startsWith("eaddhh 0 ")) ai++;
            }
            check(hogs == 64, "64 hedgehogs, got " + hogs);
            check(ai == 40, "40 computer hedgehogs (levels 1 to 5), got " + ai);
            Set<String> levels = new HashSet<>();
            for (String s : l) if (s.startsWith("eaddhh ")) levels.add(s.split(" ")[1]);
            check(levels.size() == 6, "all of human and levels 1..5 present: " + levels);
            // level 5 in the UI (hardest) is engine level 1; level 1 (easiest) is engine level 5
            SetupModel.Team t = new SetupModel.Team();
            t.level = 5;
            check(t.engineLevel() == 1, "level 5 is engine 1");
            t.level = 1;
            check(t.engineLevel() == 5, "level 1 is engine 5");
        }
        // Rules edited by hand and a fixed seed.
        {
            SetupModel m = teams(d, rnd, 3, 5);
            m.seed = "my seed";
            m.rules.value[Rules.V_TURN_TIME] = 20;
            m.rules.value[Rules.V_HEALTH] = 150;
            m.rules.value[Rules.V_MINES_TIME] = -1;
            m.rules.flag[6] = true;
            List<String> l = validate(m, d, engineCommands, "edited");
            check(l.contains("eseed my seed"), "fixed seed");
            check(l.contains("e$turntime 20000") && l.contains("e$inithealth 150") && l.contains("e$minestime -1000"), "edited values");
            check(l.contains("e$gmflags " + (8192 | 0x40)), "laser sight flag");
            check(l.contains("eaddhh 0 150 " + m.teams.get(0).hogName[0]), "hog health follows the scheme");
            check(m.rulesEdited(), "edited rules are noticed");
        }
        // Save and load.
        {
            SetupModel m = teams(d, rnd, 5, 3);
            m.chooseScheme("Shoppa");
            m.mapType = SetupModel.MAP_MAZE;
            m.mazeSize = 2;
            m.style = d.styles().get(0);
            m.theme = d.themes().get(1);
            m.rules.value[Rules.V_TURN_TIME] = 33;
            m.teams.get(1).level = 4;
            m.teams.get(2).name = "Ünïcode \u00e9 team";
            SetupModel back = SetupModel.fromText(m.toText(), d, rnd);
            check(back.toText().equals(m.toText()), "setup text round trip");
            check(GameConfig.build(back, d, new Random(1)).equals(GameConfig.build(m, d, new Random(1))), "config identical after reload");
            check(SetupModel.fromText("", d, rnd).teams.size() == 2, "empty text gives the default two teams");
            check(SetupModel.fromText("garbage \\u12", d, rnd).teams.size() == 2, "bad text gives the default");
        }
        // Options.
        {
            Options o = new Options();
            List<String> args = o.toEngineArgs();
            check(args.contains("--volume") && args.get(args.indexOf("--volume") + 1).equals("100"), "default volume is the engine's 100");
            check(args.contains("--raw-quality") && args.get(args.indexOf("--raw-quality") + 1).equals("0"), "default quality is best");
            o.mute = true;
            o.voices = false;
            o.quality = 0;
            o.showFps = true;
            o.teamTags = false;
            Options back = Options.fromText(o.toText());
            check(back.toText().equals(o.toText()), "options round trip");
            List<String> a2 = back.toEngineArgs();
            check(a2.contains("--nosound") && a2.contains("--nomusic") && !a2.contains("--volume"), "mute");
            check(a2.contains("--showfps") && a2.contains("--no-teamtag"), "fps and tags");
            check(Options.qualityFlags(0) == 0xbfe, "quality 0 flags " + Integer.toHexString(Options.qualityFlags(0)));
            Options v = new Options();
            v.voices = false;
            v.effects = 60;
            v.music = 30;
            List<String> a3 = v.toEngineArgs();
            check(a3.contains("--no-voices") && a3.contains("--effects-volume") && a3.contains("--music-volume"), "voices, effects, music");
            Set<String> argNames = engineArgNames(hw);
            for (String s : a3) if (s.startsWith("--")) check(argNames.contains(s), "engine knows option " + s);
            for (String s : a2) if (s.startsWith("--")) check(argNames.contains(s), "engine knows option " + s);
        }
        checkContentPresent(d, dataDir);
        System.out.println("ConfigCheck: " + checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * Nothing the app chooses at random may be missing from the data on disk (the test profile ships a reduced data set):
     * default and random teams, the randomise button, random themes in the game config, and repair() of a setup that names
     * content that is absent.
     */
    static void checkContentPresent(GameData d, File dataDir) {
        check(!d.themes().isEmpty() && !d.forts().isEmpty() && !d.voices().isEmpty() && d.hats().contains("NoHat"), "data has a theme, fort, voice pack and NoHat");
        for (int seed = 0; seed < 300; seed++) {
            Random r = new Random(seed);
            SetupModel m = teams(d, r, 2 + seed % 7, 8);
            for (SetupModel.Team t : m.teams) {
                check(d.graves().contains(t.grave) && d.forts().contains(t.fort) && d.voices().contains(t.voice), "random team grave, fort and voice are installed");
                for (int h = 0; h < SetupModel.MAX_HOGS; h++) check(d.hats().contains(t.hogHat[h]), "random hat installed: " + t.hogHat[h]);
            }
            List<String> l = GameConfig.build(m, d, r);
            for (String s : l) {
                if (s.startsWith("etheme ")) check(d.themes().contains(s.substring(7)), "random theme installed: " + s);
                if (s.startsWith("egrave ")) check(new File(dataDir, "Graphics/Graves/" + s.substring(7) + ".png").isFile(), "grave file: " + s);
                if (s.startsWith("efort ")) check(new File(dataDir, "Forts/" + s.substring(6) + "L.png").isFile(), "fort file: " + s);
                if (s.startsWith("evoicepack ")) check(new File(dataDir, "Sounds/voices/" + s.substring(11)).isDirectory(), "voice pack: " + s);
                if (s.startsWith("eflag ")) check(new File(dataDir, "Graphics/Flags/" + s.substring(6) + ".png").isFile(), "flag file: " + s);
                if (s.startsWith("ehat ")) check(new File(dataDir, "Graphics/Hats/" + s.substring(5) + ".png").isFile(), "hat file: " + s);
            }
        }
        // A setup saved with the full data (hats, theme, fort, voice, image map, style that are not installed) is repaired.
        SetupModel m = teams(d, new Random(3), 2, 4);
        SetupModel.Team t = m.teams.get(0);
        t.fort = "Castle"; t.voice = "Pirate"; t.grave = "NoSuchGrave"; t.hogHat[0] = "sth_Sonic";
        m.theme = "Cake"; m.mapType = SetupModel.MAP_IMAGE; m.imageMap = "Hogville"; m.style = "NoSuchStyle";
        m.repair(d, new Random(4));
        check(d.forts().contains(t.fort) && d.voices().contains(t.voice) && d.graves().contains(t.grave) && t.hogHat[0].equals("NoHat"), "repair replaces missing team content");
        check(m.theme.isEmpty() && m.style.isEmpty(), "repair clears a missing theme and style");
        check(m.mapType != SetupModel.MAP_IMAGE || d.imageMaps().contains(m.imageMap), "repair leaves no missing image map");
    }

    static SetupModel teams(GameData d, Random rnd, int n, int hogs) {
        SetupModel m = SetupModel.defaults(d, rnd);
        while (m.teams.size() < n) m.teams.add(SetupModel.randomTeam(d, rnd, m.freeColor(), m.teams));
        while (m.teams.size() > n) m.teams.remove(m.teams.size() - 1);
        for (SetupModel.Team t : m.teams) t.hogs = hogs;
        return m;
    }

    /** Structural checks of one config; returns its lines. */
    static List<String> validate(SetupModel m, GameData d, Set<String> engine, String what) {
        List<String> l = GameConfig.build(m, d, new Random(3));
        check(l.get(0).equals("TL"), what + ": starts with TL");
        Pattern cmd = Pattern.compile("^e(\\$?)([a-z_]+)(?: (.*))?$");
        int teams = 0, hogs = 0, hats = 0;
        boolean inTeams = false;
        for (String s : l) {
            check(!s.isEmpty() && s.getBytes(StandardCharsets.UTF_8).length <= 255, what + ": line length of '" + s + "'");
            check(s.indexOf('\n') < 0 && s.indexOf('\r') < 0, what + ": no line breaks");
            if (s.equals("TL")) continue;
            Matcher mt = cmd.matcher(s);
            if (!mt.matches()) {
                check(false, what + ": unrecognised line " + s);
                continue;
            }
            String name = mt.group(2);
            check(engine.contains(name), what + ": engine registers '" + name + "' (" + s + ")");
            if (s.startsWith("eaddteam ")) {
                inTeams = true;
                teams++;
                check(s.matches("eaddteam \\S+ \\d+ .+"), what + ": eaddteam shape " + s);
            }
            if (s.startsWith("eaddhh ")) {
                hogs++;
                check(s.matches("eaddhh [0-5] [1-9]\\d* .+"), what + ": eaddhh shape " + s);
            }
            if (s.startsWith("ehat ")) hats++;
            if (!inTeams && s.startsWith("e$")) check(s.matches("e\\$[a-z_]+( -?\\d+)?|e\\$scriptparam.*"), what + ": numeric e$ value " + s);
        }
        check(teams == m.teams.size(), what + ": team count");
        int expectHogs = 0;
        for (SetupModel.Team t : m.teams) expectHogs += t.hogs;
        check(hogs == expectHogs && hats == expectHogs, what + ": hedgehog count " + hogs + " of " + expectHogs);
        // Team block order as HWTeam::teamGameConfig: eaddteam, egrave, efort, evoicepack, eflag, then eaddhh/ehat pairs.
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).startsWith("eaddteam ")) {
                check(l.get(i + 1).startsWith("egrave ") && l.get(i + 2).startsWith("efort ")
                        && l.get(i + 3).startsWith("evoicepack ") && l.get(i + 4).startsWith("eflag ")
                        && l.get(i + 5).startsWith("eaddhh ") && l.get(i + 6).startsWith("ehat "), what + ": team block order at line " + i);
                // the weapon lines precede the team: loadout, probability, delay, reinforcements, [store]
                int k = i - 1;
                if (l.get(k).equals("eammstore")) k--;
                check(l.get(k).startsWith("eammreinf ") && l.get(k - 1).startsWith("eammdelay ")
                        && l.get(k - 2).startsWith("eammprob ") && l.get(k - 3).startsWith("eammloadt "), what + ": weapon lines before team at " + i);
            }
        }
        // eammstore is sent unless per-hog ammo is on without shared ammo (HWGame::commonConfig).
        Rules r = m.effectiveRules(d);
        int stores = 0;
        for (String s : l) if (s.equals("eammstore")) stores++;
        check(stores == ((r.flag[15] || !r.flag[21]) ? m.teams.size() : 0), what + ": eammstore count");
        return l;
    }

    /** Each e$ line carries the value of its scheme entry, with the factors the Qt frontend applies. */
    static void compareValues(SetupModel m, GameData d, String what) {
        Rules r = m.effectiveRules(d);
        List<String> l = GameConfig.build(m, d, new Random(3));
        int seen = 0;
        for (String s : l) {
            if (!s.startsWith("e$")) continue;
            String[] p = s.substring(2).split(" ", 2);
            int[] e = qtLines.get(p[0]);
            if (e == null) continue;
            seen++;
            int expect = r.value[e[0]] * e[1];
            check(Integer.parseInt(p[1]) == expect, what + ": " + s + " expected " + expect);
        }
        check(seen == qtLines.size(), what + ": all 18 value lines present, " + seen);
    }

    // ---- comparison with the Qt frontend sources -------------------------------------------

    static void checkAgainstQt(File hw) throws Exception {
        String model = read(new File(hw, "QTfrontend/model/gameSchemeModel.cpp"));
        Pattern names = Pattern.compile("predefSchemesNames = QStringList\\(\\)(.*?);", Pattern.DOTALL);
        Matcher nm = names.matcher(model);
        check(nm.find(), "scheme names found in Qt source");
        List<String> qtNames = new ArrayList<>();
        Matcher q = Pattern.compile("<<\\s*\"([^\"]*)\"").matcher(nm.group(1));
        while (q.find()) qtNames.add(q.group(1));
        check(qtNames.equals(Rules.presetNames()), "scheme names equal the Qt list: " + qtNames);

        // The value blocks in order: defaultScheme, then each name in the schemes.append() order.
        List<String> blocks = new ArrayList<>();
        Matcher dm = Pattern.compile("QList<QVariant> defaultScheme = QList<QVariant>\\(\\)(.*?);", Pattern.DOTALL).matcher(model);
        check(dm.find(), "Qt default scheme found");
        blocks.add(dm.group(1));
        Matcher am = Pattern.compile("schemes\\.append\\((\\w+)\\);").matcher(model);
        List<String> vars = new ArrayList<>();
        while (am.find()) vars.add(am.group(1));
        for (int i = 1; i < 16; i++) {
            Matcher bm = Pattern.compile("QList<QVariant> " + vars.get(i) + ";\\s*" + vars.get(i) + "(.*?);", Pattern.DOTALL).matcher(model);
            check(bm.find(), "Qt block " + vars.get(i));
            blocks.add(bm.group(1));
        }
        for (int i = 0; i < 16; i++) {
            List<String> vals = new ArrayList<>();
            Matcher vm = Pattern.compile("<<\\s*(?:QVariant\\(([^)]*)\\)|predefSchemesNames\\[\\d+\\])").matcher(blocks.get(i));
            while (vm.find()) vals.add(vm.group(1));
            check(vals.size() == 45, "45 entries in Qt scheme " + qtNames.get(i) + ", got " + vals.size());
            Rules r = Rules.presetAt(i);
            boolean same = true;
            for (int k = 1; k <= 25; k++) same &= vals.get(k).trim().equals("true") == r.flag[k];
            for (int k = 26; k <= 43; k++) same &= Integer.parseInt(vals.get(k).trim()) == r.value[k];
            check(same, "scheme " + qtNames.get(i) + " equals the Qt values");
        }

        // Game flag bits: "if (schemeData(N).toBool()) result |= 0x....".
        String cfgw = read(new File(hw, "QTfrontend/ui/widget/gamecfgwidget.cpp"));
        Matcher fm = Pattern.compile("schemeData\\((\\d+)\\)\\.toBool\\(\\)\\)\\s*result \\|= (0x[0-9a-fA-F]+)").matcher(cfgw);
        int flagsSeen = 0;
        while (fm.find()) {
            int k = Integer.parseInt(fm.group(1));
            flagsSeen++;
            check(Rules.FLAG_BITS[k] == (int) Long.parseLong(fm.group(2).substring(2), 16), "flag bit of entry " + k);
        }
        check(flagsSeen == 25, "25 flag lines in getGameFlags, got " + flagsSeen);

        // Weapon sets: names and the 240 digit strings.
        String wh = read(new File(hw, "QTfrontend/weapons.h"));
        String hc = read(new File(hw, "QTfrontend/hwconsts.cpp.in"));
        Matcher wm = Pattern.compile("qMakePair\\(QString\\(\"([^\"]+)\"\\),\\s*(?:\\*cDefaultAmmoStore|QString\\(\\s*AMMOLINE_(\\w+?)_QT)").matcher(hc);
        int sets = 0;
        while (wm.find()) {
            String key = wm.group(2) == null ? "DEFAULT" : wm.group(2);
            StringBuilder sb = new StringBuilder();
            for (String part : new String[] { "QT", "PROB", "DELAY", "CRATE" }) {
                Matcher dfm = Pattern.compile("#define\\s+AMMOLINE_" + key + "_" + part + "\\s+\"(\\d+)\"").matcher(wh);
                check(dfm.find(), "weapons.h " + key + "_" + part);
                sb.append(dfm.group(1));
            }
            check(wm.group(1).equals(Rules.weaponSetNames().get(sets)), "weapon set name " + wm.group(1));
            check(sb.toString().equals(Rules.weaponSet(wm.group(1))), "weapon set " + wm.group(1) + " equals the Qt string");
            sets++;
        }
        check(sets == 13, "13 weapon sets, got " + sets);

        // The e$ lines of getFullConfig and the scheme entry each reads.
        Matcher em = Pattern.compile("QString\\(\"e\\$(\\w+) %1\"\\)\\.arg\\(schemeData\\((\\d+)\\)\\.toInt\\(\\)( \\* 1000)?\\)").matcher(cfgw);
        while (em.find()) qtLines.put(em.group(1), new int[] { Integer.parseInt(em.group(2)), em.group(3) == null ? 1 : 1000 });
        check(qtLines.size() == 18, "18 numeric scheme e$ lines in getFullConfig, got " + qtLines.size());

        // Colours.
        String hcol = read(new File(hw, "QTfrontend/hwconsts.h"));
        int at = hcol.indexOf("#define HW_TEAMCOLOR_ARRAY  { 0xffff0204");
        check(at > 0, "team colour array found");
        Matcher cm = Pattern.compile("0x[0-9a-f]{8}").matcher(hcol.substring(at, hcol.indexOf("0 }", at)));
        int ci = 0;
        while (cm.find()) {
            check((int) (Long.parseLong(cm.group().substring(2), 16) & 0xffffff) == SetupModel.COLORS[ci], "colour " + ci);
            ci++;
        }
        check(ci == SetupModel.COLORS.length, "9 team colours, got " + ci);
    }

    /** Names registered with RegisterVariable anywhere in the engine. */
    static Set<String> engineCommands(File hw) throws Exception {
        Set<String> s = new HashSet<>();
        File[] pas = new File(hw, "hedgewars").listFiles();
        Pattern p = Pattern.compile("RegisterVariable\\('([^']+)'");
        for (File f : pas) {
            if (!f.getName().endsWith(".pas")) continue;
            Matcher m = p.matcher(read(f));
            while (m.find()) s.add(m.group(1));
        }
        return s;
    }

    static Set<String> engineArgNames(File hw) throws Exception {
        Set<String> s = new HashSet<>();
        String src = read(new File(hw, "hedgewars/ArgParsers.pas"));
        // The option table: "const reallyAll: array[0..N] of shortstring = ( ... );" and its case labels.
        Matcher tm = Pattern.compile("reallyAll: array\\[0\\.\\.(\\d+)\\] of shortstring = \\((.*?)\\);", Pattern.DOTALL).matcher(src);
        check(tm.find(), "engine option table found");
        Matcher m = Pattern.compile("'(--[a-z0-9-]+)'").matcher(tm.group(2));
        int n = 0;
        while (m.find()) {
            s.add(m.group(1));
            n++;
        }
        check(n == Integer.parseInt(tm.group(1)) + 1, "engine option table bound 0.." + tm.group(1) + " matches its " + n + " entries");
        Matcher cm = Pattern.compile("\\{--[a-z0-9-]+\\}\\s+(\\d+)\\s*:").matcher(src);
        int cases = 0;
        while (cm.find()) cases++;
        check(cases >= n, "every option in the table has a case label (" + cases + " labels, " + n + " options)");
        return s;
    }
}
