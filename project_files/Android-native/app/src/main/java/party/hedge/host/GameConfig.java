package party.hedge.host;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds the list of engine messages that the Qt frontend would send over IPC, one per line.
 * The native launcher (hp_ipc.c) serves the resulting file to the engine. This is the seam a
 * future hub can use: anything that can produce these lines can start a game.
 */
public final class GameConfig {

    /** Default weapon scheme: initial ammo, crate probability, delay, crate ammo (60 entries each). */
    private static final String[] DEFAULT_AMMO = {
        "939192942219912103223511100120000000021110010101111100010001",
        "040504054160065554655446477657666666615551010111541111111073",
        "000000000000020550000004000700400000000022000000060002000000",
        "131111031211111112311411111111111111121111111111111111111111",
    };

    /** Team colours as the Qt frontend sends them (ARGB, unsigned decimal). */
    public static final long[] COLORS = {
        0xffff0204L, 0xff4980c1L, 0xff1de6baL, 0xffb541efL,
        0xffe55bb0L, 0xff20bf00L, 0xfffe8b0eL, 0xff7a7a7aL,
    };

    public static final String[] DEFAULT_TEAM_NAMES = { "Red Team", "Blue Team", "Teal Team", "Purple Team" };

    private static final String[] HOG_NAMES = {
        "Spike", "Bristle", "Quill", "Prickles", "Thistle", "Bramble", "Burr", "Nettle",
        "Conker", "Acorn", "Truffle", "Hazel", "Pippin", "Bracken", "Sorrel", "Fennel",
    };

    public static final int HOGS_PER_TEAM = 4;

    private GameConfig() { }

    /** Removes characters that would break the line based message format. */
    static String clean(String s, String fallback) {
        String t = s == null ? "" : s.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ").trim();
        int cps = t.codePointCount(0, t.length());
        if (cps > 24) t = t.substring(0, t.offsetByCodePoints(0, 24)).trim();
        return t.isEmpty() ? fallback : t;
    }

    public static List<String> build(String[] teamNames, List<String> themes, long seedValue) {
        Random rnd = new Random(seedValue);
        List<String> l = new ArrayList<>();
        l.add("TL");                                           // local (hotseat) game
        l.add("etheme " + themes.get(rnd.nextInt(themes.size())));
        l.add("eseed {" + Long.toHexString(seedValue) + "}");
        l.add("e$gmflags 8192");                               // random hedgehog order
        l.add("e$damagepct 100");
        l.add("e$turntime 45000");
        l.add("e$sd_turns 15");
        l.add("e$casefreq 5");
        l.add("e$minestime 3000");
        l.add("e$minesnum 4");
        l.add("e$minedudpct 0");
        l.add("e$explosives 2");
        l.add("e$airmines 0");
        l.add("e$healthprob 35");
        l.add("e$hcaseamount 25");
        l.add("e$waterrise 47");
        l.add("e$healthdec 5");
        l.add("e$ropepct 100");
        l.add("e$getawaytime 100");
        l.add("e$worldedge 0");
        l.add("e$template_filter 0");
        l.add("e$feature_size 12");
        l.add("e$mapgen 0");                                   // random map
        int hog = 0;
        for (int t = 0; t < teamNames.length; t++) {
            l.add("eammloadt " + DEFAULT_AMMO[0]);
            l.add("eammprob " + DEFAULT_AMMO[1]);
            l.add("eammdelay " + DEFAULT_AMMO[2]);
            l.add("eammreinf " + DEFAULT_AMMO[3]);
            l.add("eammstore");
            l.add("eaddteam team" + t + " " + (COLORS[t] & 0xffffffL) + " " + clean(teamNames[t], DEFAULT_TEAM_NAMES[t % 4]));
            l.add("egrave Statue");
            l.add("efort Earth");
            l.add("evoicepack Default");
            l.add("eflag hedgewars");
            for (int h = 0; h < HOGS_PER_TEAM; h++) {
                l.add("eaddhh 0 100 " + HOG_NAMES[hog++ % HOG_NAMES.length]);
                l.add("ehat NoHat");
            }
        }
        l.add("!");
        return l;
    }

    public static void write(File target, List<String> lines) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8)) {
            for (String s : lines) w.write(s + "\n");
        }
    }
}
