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
    /** The narrowest phone that matters held upright: 360 x 740 dp at xxhdpi, 1080 x 2220 px; the cut-out is at the top. */
    static final String PORTRAIT_QUALIFIERS = "w360dp-h740dp-port-xxhdpi";
    static final int PW = 1080, PH = 2220;

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
        set(NetSession.class, "watching", false);
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
        set(NetSession.class, "hostInfo", null);
        set(NetSession.class, "wake", null);
        set(NetSession.class, "wifi", null);
        set(NetSession.class, "nsdStatus", "");
        set(NetSession.class, "hostPin", null);
        set(NetSession.class, "refusedCode", "");
        set(NetSession.class, "sentPin", "");
        NetSession.knownPins.clear();
        set(HostService.class, "relay", null);
        LocalNetwork.forceKnown = null;
        LocalNetwork.forceGranted = null;
        NetSession.discoveryFactory = c -> new HostDiscovery() {        // NSD does not run under Robolectric: a search that finds nothing
            @Override public void start(Listener l) { l.status("Searching for hosts on this Wi-Fi...", false); }
            @Override public void stop() { }
        };
        set(NetSession.class, "tickerOff", true);            // no background thread: a test sets hostInfo itself
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
        layoutAt(a, W, H, Insets.of(90, 0, 0, 0));
    }

    /** The same for the phone held upright: the window is PW x PH and the cut-out is at the top edge. */
    static void layoutUpright(Activity a) {
        layoutAt(a, PW, PH, Insets.of(0, 90, 0, 0));
    }

    private static void layoutAt(Activity a, int w, int h, Insets cut) {
        View decor = a.getWindow().getDecorView();
        WindowInsets in = new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.displayCutout(), cut).build();
        measure(decor, w, h);
        decor.dispatchApplyWindowInsets(in);
        measure(decor, w, h);
    }

    /** The same with the soft keyboard open (400 px of it), as when a team name is being edited. */
    static void layoutWithKeyboard(Activity a) {
        View decor = a.getWindow().getDecorView();
        WindowInsets in = new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(90, 0, 0, 0))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 400))
                .setVisible(WindowInsets.Type.ime(), true).build();
        measure(decor, W, H);
        decor.dispatchApplyWindowInsets(in);
        measure(decor, W, H);
    }

    /** The first view in the tree whose text or description is exactly this. */
    static View find(View v, String text) {
        if ((v instanceof TextView && text.contentEquals(((TextView) v).getText()))
                || (v.getContentDescription() != null && text.contentEquals(v.getContentDescription()))) return v;
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                View r = find(((ViewGroup) v).getChildAt(i), text);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** Whether the view and every parent up to the root are visible (not GONE or INVISIBLE). */
    static boolean shown(View v) {
        for (View x = v; x != null; ) {
            if (x.getVisibility() != View.VISIBLE) return false;
            Object p = x.getParent();
            x = p instanceof View ? (View) p : null;
        }
        return true;
    }

    private static void measure(View decor, int w, int h) {
        decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, w, h);
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
