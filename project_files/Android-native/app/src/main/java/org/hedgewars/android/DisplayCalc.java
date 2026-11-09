package org.hedgewars.android;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.DisplayCutout;
import android.view.RoundedCorner;
import android.view.View;
import android.view.WindowInsets;

/** Sizes the touch interface for the real screen: HUD scale, weapon menu scale and the safe area margins. */
public final class DisplayCalc {
    private DisplayCalc() { }

    /** Pixels per millimetre of the real screen. */
    public static float pxPerMm(DisplayMetrics dm) {
        float dpi = (dm.xdpi + dm.ydpi) / 2f;
        if (!(dpi >= 100f && dpi <= 800f)) dpi = dm.densityDpi;
        return dpi / 25.4f;
    }

    /**
     * The automatic scales in percent: {HUD, weapon menu}. Engine units: a control sprite is about 50 units, a
     * weapon menu cell 49. Aim for about 8 mm per control and per weapon cell, never below the desktop size.
     */
    public static int[] autoScales(DisplayMetrics dm) {
        float mmPx = pxPerMm(dm);
        float ui = Math.max(1f, Math.min(3.4f, 8f * mmPx / 50f));
        float menu = Math.max(ui, Math.min(4f, 8f * mmPx / 49f));
        if (ui < 1.15f) ui = 1f;                 // ordinary desktop sized screens: leave as is
        return new int[] { Math.round(ui * 100f), Math.round(menu * 100f) };
    }

    /**
     * Folds the cut-out rectangles (screen pixels, {left, top, right, bottom}) of a screen w by h into the depth of the
     * cut-out at the left and the right edge and the rows it covers: {depthL, y0L, y1L, depthR, y0R, y1R}. The
     * activity is sensorLandscape and may be turned by 180 degrees while it runs, which moves the cut-out to the
     * opposite edge and mirrors its rows, so the opposite image of every rectangle is counted as well. That is the
     * simple, rotation proof choice: the engine is told once, at the start of a game, and never has to hear about a flip.
     */
    public static int[] edgeCutouts(int[][] rects, int w, int h) {
        int[] r = new int[6];
        for (int[] c : rects) {
            if (c[2] <= c[0] || c[3] <= c[1]) continue;
            if (c[0] <= 0 && c[2] < w / 2) {                 // touches the left edge
                add(r, 0, c[2], c[1], c[3]);
                add(r, 3, c[2], h - c[3], h - c[1]);          // the same cut-out after a 180 degree turn
            }
            if (c[2] >= w && c[0] > w / 2) {                 // touches the right edge
                add(r, 3, w - c[0], c[1], c[3]);
                add(r, 0, w - c[0], h - c[3], h - c[1]);
            }
        }
        return r;
    }

    private static void add(int[] r, int at, int depth, int y0, int y1) {
        if (r[at] == 0) {
            r[at] = depth; r[at + 1] = y0; r[at + 2] = y1;
        } else {
            r[at] = Math.max(r[at], depth); r[at + 1] = Math.min(r[at + 1], y0); r[at + 2] = Math.max(r[at + 2], y1);
        }
    }

    /**
     * {HUD scale %, weapon menu scale %, inset left, top, right, bottom, corner radius top left, top right, bottom left,
     * bottom right, extra margin, left cut-out y0, y1, right cut-out y0, y1} in screen pixels, with the player's
     * overrides from the options applied. The insets are only what the cut-out and the bars take; the engine places
     * every control just clear of its own corner's rounding (hedgewars/uWorld.pas, HInset). The 3.5 % of the screen
     * height fallback is used only when the system gives no real values (before Android 12 or no window insets).
     */
    public static int[] measure(Activity a, Options o) {
        int[] r = new int[] { 100, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
        try {
            DisplayMetrics dm = new DisplayMetrics();
            a.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            int w = Math.max(dm.widthPixels, dm.heightPixels), h = Math.min(dm.widthPixels, dm.heightPixels);
            float mmPx = pxPerMm(dm);
            int[] auto = autoScales(dm);
            r[0] = o.hudAuto ? auto[0] : o.hudPct;
            r[1] = o.menuAuto ? auto[1] : o.menuPct;

            int l = 0, t = 0, rt = 0, b = 0;
            int[] cut = new int[6];
            int tl = 0, tr = 0, bl = 0, br = 0;
            boolean real = false;
            View decor = a.getWindow().getDecorView();
            WindowInsets wi = Build.VERSION.SDK_INT >= 23 ? decor.getRootWindowInsets() : null;
            if (Build.VERSION.SDK_INT >= 31 && wi != null) {
                real = true;
                tl = radius(wi, RoundedCorner.POSITION_TOP_LEFT);
                tr = radius(wi, RoundedCorner.POSITION_TOP_RIGHT);
                bl = radius(wi, RoundedCorner.POSITION_BOTTOM_LEFT);
                br = radius(wi, RoundedCorner.POSITION_BOTTOM_RIGHT);
            }
            if (Build.VERSION.SDK_INT >= 28 && wi != null) {
                DisplayCutout dc = wi.getDisplayCutout();
                if (dc != null) {
                    java.util.List<android.graphics.Rect> rs = dc.getBoundingRects();
                    int[][] rects = new int[rs.size()][];
                    for (int i = 0; i < rects.length; i++) {
                        android.graphics.Rect q = rs.get(i);
                        rects[i] = new int[] { q.left, q.top, q.right, q.bottom };
                    }
                    cut = edgeCutouts(rects, w, h);
                    l = dc.getSafeInsetLeft(); rt = dc.getSafeInsetRight();
                    t = dc.getSafeInsetTop(); b = dc.getSafeInsetBottom();
                    // when the rectangles say more than the safe insets (a cut-out away from the edges), trust the insets
                    // only for the depth; rows come from the rectangles
                    if (cut[0] == 0 && cut[3] == 0) { l = Math.max(l, rt); rt = l; } else { l = rt = Math.max(cut[0], cut[3]); }
                }
            }
            if (Build.VERSION.SDK_INT >= 30 && wi != null && wi.isVisible(WindowInsets.Type.navigationBars())) {
                // bars are hidden in game (immersive); only count them if they are really showing, over every row
                Insets bars = wi.getInsets(WindowInsets.Type.navigationBars() | WindowInsets.Type.statusBars());
                l = Math.max(l, bars.left); rt = Math.max(rt, bars.right); b = Math.max(b, bars.bottom);
            }
            // A flip by 180 degrees swaps the sides: top and bottom, left and right, and the diagonal corners
            // are treated alike, so the engine's layout stays right after a rotation (see edgeCutouts).
            int lr = Math.max(l, rt); l = lr; rt = lr;
            int tb = Math.max(t, b); t = tb; b = tb;
            int d1 = Math.max(tl, br), d2 = Math.max(tr, bl);
            tl = br = d1; tr = bl = d2;
            int margin = 0;
            if (!o.marginAuto) margin = Math.round(o.marginMm * mmPx);
            else if (!real) margin = Math.round(h * 0.035f);   // fallback without real corner values
            if (!o.marginAuto && !real) margin += Math.round(h * 0.035f);
            r[2] = l; r[3] = t; r[4] = rt; r[5] = b;
            r[6] = tl; r[7] = tr; r[8] = bl; r[9] = br; r[10] = margin;
            r[11] = cut[1]; r[12] = cut[2]; r[13] = cut[4]; r[14] = cut[5];
            // the rows of the left cut-out are for the left edge as measured; the symmetric depth covers both
            if (cut[0] > 0 || cut[3] > 0) {
                int y0 = Math.min(cut[0] > 0 ? cut[1] : Integer.MAX_VALUE, cut[3] > 0 ? cut[4] : Integer.MAX_VALUE);
                int y1 = Math.max(cut[2], cut[5]);
                r[11] = y0; r[12] = y1;
                r[13] = y0; r[14] = y1;
            }
        } catch (RuntimeException e) {
            // keep the defaults
        }
        return r;
    }

    private static int radius(WindowInsets wi, int pos) {
        RoundedCorner c = wi.getRoundedCorner(pos);
        return c == null ? 0 : Math.max(0, c.getRadius());
    }
}
