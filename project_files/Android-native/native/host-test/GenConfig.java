import party.hedge.host.GameConfig;
import party.hedge.host.GameData;
import party.hedge.host.Rules;
import party.hedge.host.SetupModel;

import java.io.File;
import java.util.List;
import java.util.Random;

/**
 * Writes a game.ipc for the host test from the app's own GameConfig.
 * Usage: GenConfig out.ipc DataDir seed [scenario]
 * Scenarios (default "two"):
 *   two              two human teams, four hedgehogs each, random map (the 0.1.x default)
 *   ai               two computer teams (level 5 and 3), nobody to press anything: the AI plays alone
 *   ai:WEAPONSET     the same, with that weapon set (e.g. ai:Crazy, where big explosions are likely)
 *   humanvsai        a human team and a level 5 computer team
 *   mixed8x8         eight teams of eight hedgehogs, humans and computers of every level
 *   scheme:NAME      two teams, the built-in scheme NAME with its weapon set, e.g. scheme:Pro Mode
 *   map:KIND         generated | maze | perlin | forts | wfc | image:NAME
 *   style:NAME       a game style (Scripts/Multiplayer/NAME.lua)
 */
public class GenConfig {
    static SetupModel base(GameData d, long seed, int teams, int hogs) {
        Random rnd = new Random(seed);
        SetupModel m = SetupModel.defaults(d, rnd);
        while (m.teams.size() < teams) {
            m.teams.add(SetupModel.randomTeam(d, rnd, m.freeColor(), m.teams));
        }
        for (SetupModel.Team t : m.teams) t.hogs = hogs;
        return m;
    }

    public static void main(String[] a) throws Exception {
        long seed = Long.parseLong(a.length > 2 ? a[2] : "12345");
        String scenario = a.length > 3 ? a[3] : "two";
        GameData d = new GameData(new File(a[1]));
        SetupModel m;
        if (scenario.equals("ai")) {
            m = base(d, seed, 2, 4);
            m.teams.get(0).level = 5;
            m.teams.get(1).level = 3;
        } else if (scenario.startsWith("ai:")) {
            m = base(d, seed, 2, 4);
            m.teams.get(0).level = 5;
            m.teams.get(1).level = 5;
            m.weaponSet = scenario.substring(3);
        } else if (scenario.equals("humanvsai")) {
            m = base(d, seed, 2, 4);
            m.teams.get(1).level = 5;
        } else if (scenario.equals("mixed8x8")) {
            m = base(d, seed, 8, 8);
            for (int i = 0; i < 8; i++) m.teams.get(i).level = i < 3 ? 0 : i - 2;
        } else if (scenario.startsWith("scheme:")) {
            m = base(d, seed, 2, 4);
            m.chooseScheme(scenario.substring(7));
        } else if (scenario.startsWith("map:")) {
            m = base(d, seed, 2, 4);
            String k = scenario.substring(4);
            if (k.equals("generated")) { m.mapType = SetupModel.MAP_GENERATED; m.templateFilter = 2; }
            else if (k.equals("maze")) { m.mapType = SetupModel.MAP_MAZE; m.mazeSize = 4; }
            else if (k.equals("perlin")) m.mapType = SetupModel.MAP_PERLIN;
            else if (k.equals("forts")) m.mapType = SetupModel.MAP_FORTS;
            else if (k.equals("wfc")) { m.mapType = SetupModel.MAP_WFC; m.templateFilter = 1; }
            else if (k.startsWith("image:")) { m.mapType = SetupModel.MAP_IMAGE; m.imageMap = k.substring(6); }
            else throw new IllegalArgumentException(k);
        } else if (scenario.startsWith("style:")) {
            m = base(d, seed, 2, 4);
            m.style = scenario.substring(6);
        } else {
            m = base(d, seed, 2, 4);
        }
        if (System.getenv("HP_TEST_SEED") != null) m.seed = System.getenv("HP_TEST_SEED");
        List<String> lines = GameConfig.build(m, d, new Random(seed));
        GameConfig.write(new File(a[0]), lines);
        System.err.println("GenConfig: " + scenario + ", " + lines.size() + " lines, " + m.teams.size() + " teams");
    }
}
