package party.hedge.host;

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
     * {HUD scale %, weapon menu scale %, inset left, top, right, bottom} in screen pixels, with the player's
     * overrides from the options applied. The insets keep the touch HUD clear of the camera cut-out and the
     * rounded display corners; the cut-out and the system bars are respected whatever margin is chosen.
     */
    public static int[] measure(Activity a, Options o) {
        int[] r = new int[] { 100, 0, 0, 0, 0, 0 };
        try {
            DisplayMetrics dm = new DisplayMetrics();
            a.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            int h = Math.min(dm.widthPixels, dm.heightPixels);
            float mmPx = pxPerMm(dm);
            int[] auto = autoScales(dm);
            r[0] = o.hudAuto ? auto[0] : o.hudPct;
            r[1] = o.menuAuto ? auto[1] : o.menuPct;

            int l, t, rt, b;
            int margin = Math.round(h * 0.035f);     // fallback: a few percent of the short side
            View decor = a.getWindow().getDecorView();
            WindowInsets wi = Build.VERSION.SDK_INT >= 23 ? decor.getRootWindowInsets() : null;
            if (Build.VERSION.SDK_INT >= 31 && wi != null) {
                int[] pos = { RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
                        RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT };
                int radius = 0;
                for (int p : pos) {
                    RoundedCorner c = wi.getRoundedCorner(p);
                    if (c != null) radius = Math.max(radius, c.getRadius());
                }
                if (radius > 0) {
                    // a square corner of a control at (m, m) lies inside an arc of radius R when m >= 0.29 R
                    margin = Math.round(radius * 0.30f + mmPx);
                }
            }
            if (!o.marginAuto) margin = Math.round(o.marginMm * mmPx);
            l = t = rt = b = margin;
            if (Build.VERSION.SDK_INT >= 28 && wi != null) {
                DisplayCutout dc = wi.getDisplayCutout();
                if (dc != null) {
                    l = Math.max(l, dc.getSafeInsetLeft());
                    t = Math.max(t, dc.getSafeInsetTop());
                    rt = Math.max(rt, dc.getSafeInsetRight());
                    b = Math.max(b, dc.getSafeInsetBottom());
                }
            }
            if (Build.VERSION.SDK_INT >= 30 && wi != null) {
                Insets bars = wi.getInsets(WindowInsets.Type.navigationBars() | WindowInsets.Type.statusBars());
                // bars are hidden in game (immersive); only count them if they are really showing
                if (wi.isVisible(WindowInsets.Type.navigationBars())) {
                    l = Math.max(l, bars.left); rt = Math.max(rt, bars.right); b = Math.max(b, bars.bottom);
                }
            }
            // The activity is sensorLandscape, so a 180 degree flip moves the cut-out to the other side
            // and the measured insets are for the wrong side. Use the larger of each opposite pair for both.
            int lr = Math.max(l, rt);
            l = lr; rt = lr;
            int tb = Math.max(t, b);
            t = tb; b = tb;
            r[2] = l; r[3] = t; r[4] = rt; r[5] = b;
        } catch (RuntimeException e) {
            // keep the defaults
        }
        return r;
    }
}
