package org.hedgewars.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Builds the list of engine messages that the Qt frontend would send over IPC, one per line, from a
 * {@link SetupModel}. The native launcher (hp_ipc.c) serves the resulting file to the engine. This is
 * the seam a future hub can use: anything that can produce these lines can start a game.
 *
 * The order and the content follow the desktop frontend: GameCFGWidget::getFullConfig (QTfrontend/ui/widget/
 * gamecfgwidget.cpp) for the map, theme, rules and script, then HWGame::commonConfig (QTfrontend/game.cpp)
 * and HWTeam::teamGameConfig (QTfrontend/team.cpp) for the weapons and each team.
 */
public final class GameConfig {

    /** Team colours as 0xRRGGBB, see {@link SetupModel#COLORS}. */
    public static final int[] COLORS = SetupModel.COLORS;

    private GameConfig() { }

    /** Removes characters that would break the line based message format and limits the length. */
    public static String clean(String s, String fallback) {
        String t = s == null ? "" : s.replaceAll("[\\p{Cc}\\u2028\\u2029]", " ").trim();
        int cps = t.codePointCount(0, t.length());
        if (cps > 32) t = t.substring(0, t.offsetByCodePoints(0, 32)).trim();
        return t.isEmpty() ? fallback : t;
    }

    /** Team names also become file names in the engine (key bindings), so path characters are dropped. */
    public static String cleanTeamName(String s, String fallback) {
        return clean(s == null ? null : s.replaceAll("[\\\\/:*?\"<>|]", ""), fallback);
    }

    /** A seed in the form the Qt frontend uses, {uuid}, drawn from the given generator. */
    static String randomSeed(Random rnd) {
        return "{" + new UUID(rnd.nextLong(), rnd.nextLong()).toString() + "}";
    }

    private static String cleanSeed(String s) {
        String t = s == null ? "" : s.replaceAll("[\\p{Cc}\\u2028\\u2029]", " ").trim();
        if (t.length() > 120) t = t.substring(0, 120).trim();
        return t;
    }

    /** The lines of a game for this setup. {@code rnd} picks the random seed, theme and map details. */
    public static List<String> build(SetupModel m, GameData data, Random rnd) {
        List<String> l = new ArrayList<>();
        Rules r = m.effectiveRules(data);
        String weapons = Rules.weaponSet(m.effectiveWeaponSet(data));
        if (weapons == null) weapons = Rules.weaponSet("Default");

        l.add("TL");                                                // local (hotseat) game
        if (!m.style.isEmpty()) l.add("escript Scripts/Multiplayer/" + m.style + ".lua");

        String theme = m.theme;
        boolean image = m.mapType == SetupModel.MAP_IMAGE && !m.imageMap.isEmpty();
        if (image) {
            l.add("emap " + m.imageMap);
            if (theme.isEmpty()) {
                String own = data.mapTheme(m.imageMap);
                if (own != null && data.hasTheme(own)) theme = own;
            }
        }
        if (theme.isEmpty()) {
            List<String> all = data.themes();
            theme = all.isEmpty() ? "Nature" : all.get(rnd.nextInt(all.size()));
        }
        l.add("etheme " + theme);
        String seed = cleanSeed(m.seed);
        l.add("eseed " + (seed.isEmpty() ? randomSeed(rnd) : seed));

        l.add("e$gmflags " + r.gameFlags());
        l.add("e$damagepct " + r.value[Rules.V_DAMAGE]);
        l.add("e$turntime " + r.value[Rules.V_TURN_TIME] * 1000);
        l.add("e$inithealth " + r.value[Rules.V_HEALTH]);
        l.add("e$sd_turns " + r.value[Rules.V_SUDDEN]);
        l.add("e$casefreq " + r.value[Rules.V_CASE_PROB]);
        l.add("e$minestime " + r.value[Rules.V_MINES_TIME] * 1000);
        l.add("e$minesnum " + r.value[Rules.V_MINES]);
        l.add("e$minedudpct " + r.value[Rules.V_DUD]);
        l.add("e$explosives " + r.value[Rules.V_EXPLOSIVES]);
        l.add("e$airmines " + r.value[Rules.V_AIR_MINES]);
        l.add("e$sentries " + r.value[Rules.V_SENTRIES]);
        l.add("e$healthprob " + r.value[Rules.V_HEALTH_PROB]);
        l.add("e$hcaseamount " + r.value[Rules.V_HEALTH_AMOUNT]);
        l.add("e$waterrise " + r.value[Rules.V_WATER_RISE]);
        l.add("e$healthdec " + r.value[Rules.V_HEALTH_DEC]);
        l.add("e$ropepct " + r.value[Rules.V_ROPE]);
        l.add("e$getawaytime " + r.value[Rules.V_GETAWAY]);
        l.add("e$worldedge " + r.value[Rules.V_WORLD_EDGE]);
        l.add("e$template_filter " + m.templateFilter);
        l.add("e$feature_size " + m.featureSize);
        l.add("e$mapgen " + m.mapType);
        l.add("e$scriptparam " + cleanSeed(m.scriptParam));
        if (m.mapType == SetupModel.MAP_MAZE || m.mapType == SetupModel.MAP_PERLIN) {
            l.add("e$maze_size " + m.mazeSize);
        }

        // HWGame::commonConfig: for every team, its weapon lines, then the team itself.
        for (int t = 0; t < m.teams.size(); t++) teamLines(l, m.teams.get(t), t, weapons, r);
        return l;
    }

    /**
     * The block of one team (HWGame::commonConfig and HWTeam::teamGameConfig): its weapon lines, the team itself
     * and its hedgehogs. A network game builds the same blocks from teams that arrive from other phones.
     */
    public static void teamLines(List<String> l, SetupModel.Team tm, int t, String weapons, Rules r) {
        boolean sharedAmmo = r.flag[15];
        boolean perHogAmmo = r.flag[21];
        l.add("eammloadt " + weapons.substring(0, 60));
        l.add("eammprob " + weapons.substring(60, 120));
        l.add("eammdelay " + weapons.substring(120, 180));
        l.add("eammreinf " + weapons.substring(180, 240));
        if (sharedAmmo || !perHogAmmo) l.add("eammstore");
        l.add("eaddteam team" + t + " " + (SetupModel.COLORS[tm.color] & 0xffffff) + " "
                + cleanTeamName(tm.name, "Team " + (t + 1)));
        l.add("egrave " + tm.grave);
        l.add("efort " + tm.fort);
        l.add("evoicepack " + tm.voice);
        l.add("eflag " + tm.flag);
        for (int h = 0; h < tm.hogs; h++) {
            l.add("eaddhh " + tm.engineLevel() + " " + r.value[Rules.V_HEALTH] + " "
                    + clean(tm.hogName[h], "Hedgehog " + (h + 1)));
            l.add("ehat " + tm.hogHat[h]);
        }
    }

    /**
     * The lines one phone sends its engine in a network game, made from the canonical lines of {@link #build}
     * (the same for everyone, so the map, rules and team order are identical): the first line becomes
     * {@code TN}, and every team the phone does not own gets {@code erdriven} straight after its
     * {@code eaddteam}, which makes the engine take that team's moves from the message queue (plan section 2.6).
     * {@code owned[i]} says whether the phone owns the team with index i in the canonical order.
     */
    public static List<String> forRecipient(List<String> canonical, boolean[] owned) {
        List<String> out = new ArrayList<>();
        int team = 0;
        for (String line : canonical) {
            if (line.equals("TL")) { out.add("TN"); continue; }
            out.add(line);
            if (line.startsWith("eaddteam ")) {
                if (team >= owned.length || !owned[team]) out.add("erdriven");
                team++;
            }
        }
        return out;
    }

    /**
     * The lines of a phone that comes back into a running game. Every team is driven from outside, as for a watcher, so the engine
     * replays the game so far from the relay's log, the phone's own teams included (their moves are in the log). The phone's own
     * teams also get {@code eowner <nick>}: when the relay later sends the unsynced team-back message, the engine hands such a team
     * back to this phone only if its owner equals the engine's own nick (uTeams.pas, chTeamBack). The other phones' engines never
     * get an owner line, so the team stays driven from outside on their side. The caller adds {@code e$spectate 1}.
     */
    public static List<String> forRejoin(List<String> canonical, boolean[] owned, String nick) {
        List<String> out = new ArrayList<>();
        int team = 0;
        for (String line : canonical) {
            if (line.equals("TL")) { out.add("TN"); continue; }
            out.add(line);
            if (line.startsWith("eaddteam ")) {
                out.add("erdriven");
                if (team < owned.length && owned[team]) out.add("eowner " + nick);
                team++;
            }
        }
        return out;
    }

    public static void write(File target, List<String> lines) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8)) {
            for (String s : lines) w.write(s + "\n");
        }
    }
}
