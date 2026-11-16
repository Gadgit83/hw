package org.hedgewars.android;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The places of the touch controls (issue #21), as the engine's --touch-layout argument and touchlayout command take them:
 * "name:x,y,s;name:x,y,s;...". x and y are the centre of the control as fractions of the screen width and height (x from the
 * left, y from the top) and s is its size as a fraction of the default size. Only the display changes; the engine never
 * reads these values in the simulation. Pure Java (no Android classes) so it can be tested on a plain JVM.
 */
public final class TouchLayout {
    /** The engine's names, in the order the layout is written. */
    public static final String[] NAMES = { "fire", "jump", "highjump", "left", "right", "aim", "weapons", "pause", "timer", "bounce" };
    /** The labels the editor shows, in the same order as NAMES. */
    public static final String[] LABELS = { "Fire", "Jump", "High jump", "Left", "Right", "Aim", "Weapons", "Pause", "Timer", "Bounce" };
    public static final double MIN_SCALE = 0.4;
    public static final double MAX_SCALE = 2.5;
    /** Two layouts within this distance (as fractions) count as the same place. */
    public static final double SAME = 0.0005;

    private TouchLayout() { }

    /** One control as the engine reports it: its centre and size as fractions, and its drawn width and height. */
    public static final class Place {
        public final double x, y, s, w, h;

        public Place(double x, double y, double s, double w, double h) {
            this.x = x;
            this.y = y;
            this.s = s;
            this.w = w;
            this.h = h;
        }
    }

    /** The index of a name in NAMES, or -1. */
    public static int indexOf(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
        return -1;
    }

    /** A non-negative decimal number, or NaN when it is not one. */
    static double number(String t) {
        if (t == null || t.isEmpty()) return Double.NaN;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (!((c >= '0' && c <= '9') || c == '.')) return Double.NaN;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * The places in a layout string, by name: x and y within the screen, s within {@link #MIN_SCALE} to {@link #MAX_SCALE}.
     * Entries that are not valid are left out, so a bad layout gives the default places.
     */
    public static Map<String, double[]> parse(String spec) {
        Map<String, double[]> out = new LinkedHashMap<>();
        if (spec == null) return out;
        for (String entry : spec.split(";")) {
            int colon = entry.indexOf(':');
            if (colon < 1) continue;
            String name = entry.substring(0, colon);
            if (indexOf(name) < 0) continue;
            String[] n = entry.substring(colon + 1).split(",", -1);
            if (n.length != 3) continue;
            double x = number(n[0]), y = number(n[1]), s = number(n[2]);
            if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(s)) continue;
            out.put(name, new double[] { clamp(x, 0, 1), clamp(y, 0, 1), clamp(s, MIN_SCALE, MAX_SCALE) });
        }
        return out;
    }

    /** The layout string for the places in NAMES order, three decimals each. */
    public static String format(Map<String, double[]> places) {
        StringBuilder b = new StringBuilder();
        for (String name : NAMES) {
            double[] p = places.get(name);
            if (p == null) continue;
            if (b.length() > 0) b.append(';');
            b.append(name).append(':')
                    .append(String.format(Locale.ROOT, "%.3f", p[0])).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", p[1])).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", p[2]));
        }
        return b.toString();
    }

    /** The layout string in canonical form, or "" when it places nothing (the engine's default layout). */
    public static String sanitize(String spec) {
        return format(parse(spec));
    }

    /** The name in an engine report entry ("fire:0.920,0.850,1.000,0.120,0.210"), or null. */
    public static String reportName(String entry) {
        int colon = entry == null ? -1 : entry.indexOf(':');
        if (colon < 1) return null;
        String name = entry.substring(0, colon);
        return indexOf(name) < 0 ? null : name;
    }

    /** The place in an engine report entry, or null when it is not one. */
    public static Place reportPlace(String entry) {
        if (reportName(entry) == null) return null;
        String[] n = entry.substring(entry.indexOf(':') + 1).split(",", -1);
        if (n.length != 5) return null;
        double[] v = new double[5];
        for (int i = 0; i < 5; i++) {
            v[i] = number(n[i]);
            if (Double.isNaN(v[i])) return null;
        }
        return new Place(v[0], v[1], v[2], v[3], v[4]);
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
