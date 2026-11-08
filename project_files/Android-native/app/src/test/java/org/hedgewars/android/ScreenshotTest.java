package org.hedgewars.android;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

/**
 * Draws the setup, team and options screens to PNG files (build/screenshots) at the size of a Fairphone 4 held
 * sideways: 2340 x 1080 pixels at 409 dpi, with the camera cut-out on the left. Run with
 * {@code ./gradlew -Pscreenshots testDebugUnitTest --tests '*ScreenshotTest'}. Not a pass or fail test of the layout: the pictures are for
 * looking at.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w915dp-h422dp-land-409dpi")
public class ScreenshotTest {
    private static final int W = 2340, H = 1080;

    /** Only when asked for (-Pscreenshots): it draws pictures, it does not check anything. */
    @org.junit.Before
    public void onlyWhenAsked() {
        org.junit.Assume.assumeTrue("screenshots not requested", Boolean.getBoolean("screenshots"));
    }

    /** The game data is linked in from the staged assets, with the marker the app looks for. */
    private static void installData(Context c) throws Exception {
        File data = new File(c.getFilesDir(), "Data");
        data.mkdirs();
        File src = new File(System.getProperty("screenshot.data"));
        for (File f : src.listFiles()) {
            File to = new File(data, f.getName());
            if (!to.exists()) Files.createSymbolicLink(to.toPath(), f.toPath());
        }
        // the marker must hold the stamp, an empty file never matches and the app unpacks the data again
        java.lang.reflect.Method m = DataInstaller.class.getDeclaredMethod("stamp", Context.class);
        m.setAccessible(true);
        Files.write(new File(data, ".installed").toPath(), ((String) m.invoke(null, c)).getBytes("UTF-8"));
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 40; i++) {
            Thread.sleep(50);
            shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private static void shoot(Activity a, String name) throws Exception {
        View decor = a.getWindow().getDecorView();
        // The camera cut-out is on the left edge, the bars are hidden (full screen): only the cut-out is an inset.
        WindowInsets in = new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(90, 0, 0, 0))
                .build();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
        decor.dispatchApplyWindowInsets(in);
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
        settle();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(bmp));
        File dir = new File(System.getProperty("screenshot.dir"));
        dir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(dir, name + ".png"))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
    }

    private static void tab(Context c, int t) {
        c.getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE).edit().putInt("tab", t).commit();
    }

    @Test
    public void setupScreens() throws Exception {
        Context c = org.robolectric.RuntimeEnvironment.getApplication();
        installData(c);
        String[] names = { "setup-teams", "setup-map", "setup-rules" };
        for (int t = 0; t < 3; t++) {
            tab(c, t);
            MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
            settle();
            shoot(a, names[t]);
        }
    }

    @Test
    public void manyTeams() throws Exception {
        Context c = org.robolectric.RuntimeEnvironment.getApplication();
        installData(c);
        tab(c, 0);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        settle();
        // Add teams up to six through the Add team button.
        for (int i = 0; i < 4; i++) {
            View add = find(a.getWindow().getDecorView(), "Add team");
            if (add != null) add.performClick();
            settle();
        }
        shoot(a, "setup-teams-6");
    }

    @Test
    public void teamAndOptions() throws Exception {
        Context c = org.robolectric.RuntimeEnvironment.getApplication();
        installData(c);
        TeamEditActivity t = Robolectric.buildActivity(TeamEditActivity.class,
                new Intent(c, TeamEditActivity.class).putExtra("team", 0)).setup().get();
        settle();
        shoot(t, "team-edit");
        for (int tab = 0; tab < 3; tab++) {
            c.getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE).edit().putInt("optionsTab", tab).commit();
            OptionsActivity o = Robolectric.buildActivity(OptionsActivity.class).setup().get();
            settle();
            shoot(o, "options-" + tab);
        }
    }

    private static View find(View v, String text) {
        if (v instanceof android.widget.Button && text.contentEquals(((android.widget.Button) v).getText())) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = find(g.getChildAt(i), text);
                if (r != null) return r;
            }
        }
        return null;
    }
}
