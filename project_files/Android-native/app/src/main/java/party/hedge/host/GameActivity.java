package party.hedge.host;

import android.content.pm.ActivityInfo;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.DisplayCutout;
import android.view.RoundedCorner;
import android.view.View;
import android.view.WindowInsets;
import android.view.KeyEvent;

import org.libsdl.app.SDLActivity;

/**
 * Hosts the native engine. SDLActivity loads libSDL2.so and libmain.so and runs SDL_main
 * (host/native/launcher/hp_main.c) on its own thread. The engine reads its game from
 * files/game.ipc, which MainActivity wrote before starting this activity.
 *
 * This activity runs in its own process (":game", see the manifest). The engine keeps global
 * state and may end the process with exit() when it hits a fatal error, so keeping it apart
 * means a failed game cannot take the setup screen down, and the setup screen can show why.
 */
public class GameActivity extends SDLActivity {

    @Override
    protected String[] getLibraries() {
        // SDL2_image, SDL2_mixer, SDL2_ttf, SDL2_net and the Rust library are NEEDED by libmain.so
        // and are found by the dynamic linker in the same directory.
        return new String[] { "SDL2", "main" };
    }

    @Override
    public void loadLibraries() {
        try {
            super.loadLibraries();
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            Diag.writeError(this, "Could not load the native game libraries: " + e.getMessage());
            throw e;
        }
    }

    /**
     * SDL asks for an orientation whenever the engine creates its window and, with no hint and a
     * resizable window, picks FULL_USER (every orientation the phone allows). That overrides the
     * manifest's sensorLandscape, so the game could open in portrait. Stay in landscape.
     */
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    /**
     * Called from the native launcher (hp_main.c, through JNI) just before the engine starts.
     * Returns {HUD scale %, weapon menu scale %, inset left, top, right, bottom} in screen pixels.
     * The insets keep the touch HUD clear of the camera cut-out and the rounded display corners.
     */
    @SuppressWarnings("unused")
    public int[] engineDisplayInfo() {
        int[] r = new int[] { 100, 0, 0, 0, 0, 0 };
        try {
            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            int w = Math.max(dm.widthPixels, dm.heightPixels);
            int h = Math.min(dm.widthPixels, dm.heightPixels);
            float dpi = (dm.xdpi + dm.ydpi) / 2f;
            if (!(dpi >= 100f && dpi <= 800f)) dpi = dm.densityDpi;
            // Engine units: a control sprite is about 50 units, a weapon menu cell 49. Aim for about
            // 8 mm per control and per weapon cell, never below the desktop size.
            float mmPx = dpi / 25.4f;
            float ui = Math.max(1f, Math.min(3.4f, 8f * mmPx / 50f));
            float menu = Math.max(ui, Math.min(4f, 8f * mmPx / 49f));
            if (ui < 1.15f) ui = 1f;                 // ordinary desktop sized screens: leave as is
            r[0] = Math.round(ui * 100f);
            r[1] = Math.round(menu * 100f);

            int l = 0, t = 0, rt = 0, b = 0;
            int margin = Math.round(h * 0.035f);     // fallback: a few percent of the short side
            View decor = getWindow().getDecorView();
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        // The native launcher removes this marker when the engine ends cleanly.
        Diag.markGameStarted(this);
        super.onCreate(savedInstanceState);
    }

    /** The engine keeps global state in its statics, so end the process to start every game clean. */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /**
     * The engine has no Android back button handling, so the system Back key is forwarded as
     * Escape, which opens its pause/quit dialog. Leaving the game finishes the activity.
     */
    @Override
    public void onBackPressed() {
        SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ESCAPE);
        SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ESCAPE);
    }
}
