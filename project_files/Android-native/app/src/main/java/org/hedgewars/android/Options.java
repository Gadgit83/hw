package org.hedgewars.android;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The options menu settings: sound, display and controls. Pure Java (no Android classes) so the mapping to
 * engine options can be tested on a plain JVM; {@link Store} keeps it in the app's preferences.
 */
public final class Options {
    // Sound
    public int master = 78;            // 0 to 100 (78 is the engine's own default of 100 out of 128)
    public int effects = 100;          // percent of master
    public int music = 50;             // percent of master (50 is the engine's fixed ratio)
    public boolean mute;
    public boolean voices = true;

    // Display and controls
    public boolean hudAuto = true;
    public int hudPct = 150;           // 100 to 340
    public boolean menuAuto = true;
    public int menuPct = 200;          // 100 to 400
    public boolean marginAuto = true;
    public int marginMm = 2;           // 0 to 10 millimetres of extra clear space at the screen edge (when not automatic)
    public boolean centreTurn = true;  // centre the camera on the active hedgehog when a turn starts
    public boolean zoomTurn = true;    // zoom in at turn start
    public int zoomPct = 140;          // 110 to 200: how much closer than the default zoom the turn start view is
    public int quality = 5;            // 0 (lowest) to 5 (best), as the desktop slider
    public boolean showFps;
    public boolean vibration = true;
    public boolean teamTags = true;
    public boolean hogTags = true;
    public boolean healthTags = true;

    public Options copy() {
        return fromText(toText());
    }

    /** The desktop frontend's quality levels as engine "reduced quality" flags (GameUIConfig::translateQuality). */
    public static int qualityFlags(int level) {
        final int blurryLand = 0x2, noBackground = 0x4, simpleRope = 0x8, water2d = 0x10, antiBoom = 0x20,
                killFlakes = 0x40, slowMenu = 0x80, plainSplash = 0x100, clampLess = 0x200, desyncVBlank = 0x800;
        switch (level) {
            case 5: return 0;
            case 4: return blurryLand;
            case 3: return blurryLand | killFlakes | plainSplash;
            case 2: return blurryLand | killFlakes | plainSplash | water2d | antiBoom | slowMenu;
            case 1: return blurryLand | killFlakes | plainSplash | water2d | antiBoom | slowMenu | simpleRope | desyncVBlank;
            default: return blurryLand | killFlakes | plainSplash | water2d | antiBoom | slowMenu | simpleRope
                    | desyncVBlank | noBackground | clampLess;
        }
    }

    /** Engine command line options for these settings (sound, quality, tags, frame rate counter). */
    public List<String> toEngineArgs() {
        List<String> a = new ArrayList<>();
        if (mute) {
            a.add("--nosound");
            a.add("--nomusic");
        } else {
            a.add("--volume");
            a.add(Integer.toString(Math.round(Math.max(0, Math.min(100, master)) * 128f / 100f)));
            a.add("--effects-volume");
            a.add(Integer.toString(Math.max(0, Math.min(100, effects))));
            a.add("--music-volume");
            a.add(Integer.toString(Math.max(0, Math.min(100, music))));
            if (!voices) a.add("--no-voices");
        }
        a.add("--raw-quality");
        a.add(Integer.toString(qualityFlags(quality)));
        if (centreTurn) a.add("--turn-centre");
        if (zoomTurn) {
            a.add("--turn-zoom");
            a.add(Integer.toString(Math.max(110, Math.min(200, zoomPct))));
        }
        if (showFps) a.add("--showfps");
        if (!teamTags) a.add("--no-teamtag");
        if (!hogTags) a.add("--no-hogtag");
        if (!healthTags) a.add("--no-healthtag");
        return a;
    }

    private static int intOf(Properties p, String k, int def, int lo, int hi) {
        try {
            return Math.max(lo, Math.min(hi, Integer.parseInt(p.getProperty(k, ""))));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean boolOf(Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        return v == null ? def : v.equals("1");
    }

    public String toText() {
        Properties p = new Properties();
        p.setProperty("master", Integer.toString(master));
        p.setProperty("effects", Integer.toString(effects));
        p.setProperty("music", Integer.toString(music));
        p.setProperty("mute", mute ? "1" : "0");
        p.setProperty("voices", voices ? "1" : "0");
        p.setProperty("hudAuto", hudAuto ? "1" : "0");
        p.setProperty("hudPct", Integer.toString(hudPct));
        p.setProperty("menuAuto", menuAuto ? "1" : "0");
        p.setProperty("menuPct", Integer.toString(menuPct));
        p.setProperty("marginAuto", marginAuto ? "1" : "0");
        p.setProperty("marginMm", Integer.toString(marginMm));
        p.setProperty("centreTurn", centreTurn ? "1" : "0");
        p.setProperty("zoomTurn", zoomTurn ? "1" : "0");
        p.setProperty("zoomPct", Integer.toString(zoomPct));
        p.setProperty("quality", Integer.toString(quality));
        p.setProperty("showFps", showFps ? "1" : "0");
        p.setProperty("vibration", vibration ? "1" : "0");
        p.setProperty("teamTags", teamTags ? "1" : "0");
        p.setProperty("hogTags", hogTags ? "1" : "0");
        p.setProperty("healthTags", healthTags ? "1" : "0");
        StringWriter w = new StringWriter();
        try {
            p.store(w, null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return w.toString();
    }

    public static Options fromText(String text) {
        Options o = new Options();
        Properties p = new Properties();
        try {
            p.load(new StringReader(text == null ? "" : text));
        } catch (IOException | IllegalArgumentException e) {
            return o;
        }
        o.master = intOf(p, "master", o.master, 0, 100);
        o.effects = intOf(p, "effects", o.effects, 0, 100);
        o.music = intOf(p, "music", o.music, 0, 100);
        o.mute = boolOf(p, "mute", o.mute);
        o.voices = boolOf(p, "voices", o.voices);
        o.hudAuto = boolOf(p, "hudAuto", o.hudAuto);
        o.hudPct = intOf(p, "hudPct", o.hudPct, 100, 340);
        o.menuAuto = boolOf(p, "menuAuto", o.menuAuto);
        o.menuPct = intOf(p, "menuPct", o.menuPct, 100, 400);
        o.marginAuto = boolOf(p, "marginAuto", o.marginAuto);
        o.marginMm = intOf(p, "marginMm", o.marginMm, 0, 10);
        o.centreTurn = boolOf(p, "centreTurn", o.centreTurn);
        o.zoomTurn = boolOf(p, "zoomTurn", o.zoomTurn);
        o.zoomPct = intOf(p, "zoomPct", o.zoomPct, 110, 200);
        o.quality = intOf(p, "quality", o.quality, 0, 5);
        o.showFps = boolOf(p, "showFps", o.showFps);
        o.vibration = boolOf(p, "vibration", o.vibration);
        o.teamTags = boolOf(p, "teamTags", o.teamTags);
        o.hogTags = boolOf(p, "hogTags", o.hogTags);
        o.healthTags = boolOf(p, "healthTags", o.healthTags);
        return o;
    }
}
