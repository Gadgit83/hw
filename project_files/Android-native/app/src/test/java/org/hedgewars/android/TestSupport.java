package org.hedgewars.android;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.graphics.Insets;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;

/** Shared helpers of the Robolectric tests: installed game data, a Fairphone-sized layout pass, process state reset. */
final class TestSupport {
    /** Screen of a Fairphone 4 held sideways, in pixels (the qualifiers are w915dp-h422dp-land-409dpi). */
    static final int W = 2340, H = 1080;
    static final String QUALIFIERS = "w915dp-h422dp-land-409dpi";

    private TestSupport() { }

    /**
     * Makes the staged game data look unpacked: its folders are linked into files/Data and the marker holds the stamp
     * DataInstaller.isInstalled compares with. An empty marker never matches, and the app would unpack the data again.
     */
    static void installData(Context c) throws Exception {
        File data = new File(c.getFilesDir(), "Data");
        data.mkdirs();
        File src = new File(System.getProperty("screenshot.data"));
        File[] kids = src.listFiles();
        if (kids == null || kids.length == 0) {
            throw new IllegalStateException("no staged game data in " + src + " (run scripts/stage-assets.sh)");
        }
        for (File f : kids) {
            File to = new File(data, f.getName());
            if (!to.exists()) Files.createSymbolicLink(to.toPath(), f.toPath());
        }
        Method m = DataInstaller.class.getDeclaredMethod("stamp", Context.class);
        m.setAccessible(true);
        Files.write(new File(data, ".installed").toPath(), ((String) m.invoke(null, c)).getBytes("UTF-8"));
        if (!DataInstaller.isInstalled(c)) throw new IllegalStateException("the test's install marker does not match the stamp");
    }

    /** Forget what an earlier test left in static fields, as a fresh process would. */
    static void resetStatics() throws Exception {
        set(MainActivity.class, "installing", false);
        set(MainActivity.class, "dataReady", false);
        set(MainActivity.class, "installError", null);
        set(MainActivity.class, "installProgress", "");
        set(MainActivity.class, "shown", null);
        set(Store.class, "cachedData", null);
        set(Store.class, "cachedStamp", -1L);
        // The network session is process-wide state too.
        set(NetSession.class, "hosting", false);
        set(NetSession.class, "joined", false);
        set(NetSession.class, "target", "");
        set(NetSession.class, "state", null);
        set(NetSession.class, "closedReason", "");
        set(NetSession.class, "notice", "");
        set(NetSession.class, "relaySummary", "");
        set(NetSession.class, "pendingStart", null);
        set(NetSession.class, "inGame", false);
        set(NetSession.class, "lobbyVisible", false);
        set(NetSession.class, "client", null);
        set(NetSession.class, "listener", null);
    }

    private static void set(Class<?> k, String name, Object v) throws Exception {
        Field f = k.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, v);
    }

    /** Lets background threads post to the main looper and runs what they posted. */
    static void settle(long millis) throws Exception {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(50);
            shadowOf(Looper.getMainLooper()).idle();
        }
    }

    /** Measure and lay out the window at full size with the camera cut-out on the left, as the system would. */
    static void layout(Activity a) {
        View decor = a.getWindow().getDecorView();
        WindowInsets in = new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(90, 0, 0, 0)).build();
        measure(decor);
        decor.dispatchApplyWindowInsets(in);
        measure(decor);
    }

    private static void measure(View decor) {
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
    }

    /** All text on screen, to show that something was really built. */
    static String texts(View v) {
        StringBuilder sb = new StringBuilder();
        collect(v, sb);
        return sb.toString();
    }

    private static void collect(View v, StringBuilder sb) {
        if (v instanceof TextView) sb.append(((TextView) v).getText()).append(" | ");
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) collect(((ViewGroup) v).getChildAt(i), sb);
        }
    }
}
