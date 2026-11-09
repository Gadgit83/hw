package org.hedgewars.android;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowInsets;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

/**
 * Pictures of every screen (scripts/ui-screenshots.sh, or {@code ./gradlew -Pscreenshots testDebugUnitTest --tests
 * '*ScreenshotTest'}), drawn with Robolectric's native graphics mode (Skia) into PNG files. They are for looking at: the
 * test checks nothing about the layout and is skipped unless -Pscreenshots is given. Each screen is drawn for two devices:
 * "fp4", a Fairphone 4 held sideways (2340 x 1080 px at 409 dpi, camera cut-out of 90 px on the left) and "narrow",
 * a 2340 x 1080 px phone at 480 dpi (780 x 360 dp), whose screen is a third smaller in dp. Not drawn: the game itself (the
 * engine draws it with SDL); the Back question of the game is drawn from the same dialog code over a black screen.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w915dp-h422dp-land-409dpi")
public class ScreenshotTest {
    private static final String[][] DEVICES = {
        { "fp4", "w915dp-h422dp-land-409dpi" },
        { "narrow", "w780dp-h360dp-land-xxhdpi" },
    };
    private static final String LOBBY = "{\"t\":\"state\",\"state\":\"setup\",\"host\":\"Pixel\",\"proto\":1,\"build\":\"0.3.0-mp2\",\"you\":2,"
            + "\"players\":[{\"id\":1,\"name\":\"Pixel\",\"host\":true,\"ready\":true,\"fg\":true,"
            + "\"teams\":[{\"name\":\"Red Team\",\"color\":0,\"level\":0}]},"
            + "{\"id\":2,\"name\":\"Fairphone\",\"host\":false,\"ready\":false,\"fg\":false,"
            + "\"teams\":[{\"name\":\"Blue Team\",\"color\":1,\"level\":3}]}],\"canStart\":false,\"game\":0}";

    private String device;
    private int w, h, cutout;

    /** Only when asked for (-Pscreenshots): it draws pictures, it does not check anything. */
    @org.junit.Before
    public void onlyWhenAsked() {
        org.junit.Assume.assumeTrue("screenshots not requested", Boolean.getBoolean("screenshots"));
    }

    private interface Body { void run(Context c) throws Exception; }

    /** Runs the body once for each device size, from a clean process state. */
    private void eachDevice(Body b) throws Exception {
        for (String[] d : DEVICES) {
            RuntimeEnvironment.setQualifiers(d[1]);
            device = d[0];
            Context c = RuntimeEnvironment.getApplication();
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
            cutout = Math.round(90 * dm.densityDpi / 409f);
            TestSupport.resetStatics();
            c.getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();
            TestSupport.installData(c);
            b.run(c);
        }
    }

    private static void settle() throws Exception {
        TestSupport.settle(1500);
    }

    private void doLayout(View decor, WindowInsets in) {
        int ew = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), eh = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY);
        decor.measure(ew, eh);
        decor.layout(0, 0, w, h);
        decor.dispatchApplyWindowInsets(in);
        decor.measure(ew, eh);
        decor.layout(0, 0, w, h);
    }

    private WindowInsets insets() {
        return new WindowInsets.Builder().setInsets(WindowInsets.Type.displayCutout(), Insets.of(cutout, 0, 0, 0)).build();
    }

    private Bitmap draw(Activity a) throws Exception {
        View decor = a.getWindow().getDecorView();
        doLayout(decor, insets());
        settle();
        doLayout(decor, insets());
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(bmp));
        return bmp;
    }

    private void save(Bitmap bmp, String name) throws Exception {
        File dir = new File(System.getProperty("screenshot.dir"));
        dir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(dir, device + "-" + name + ".png"))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
    }

    private void shoot(Activity a, String name) throws Exception {
        save(draw(a), name);
    }

    /** A dialog is a window of its own: drawn centred over the screen behind it, which is dimmed as the system does. */
    private void shootDialog(Bitmap behind, AlertDialog d, String name) throws Exception {
        View dv = d.getWindow().getDecorView();
        int maxW = Math.round(w * 0.6f);
        dv.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST));
        int dw = dv.getMeasuredWidth(), dh = dv.getMeasuredHeight();
        dv.layout(0, 0, dw, dh);
        Canvas cv = new Canvas(behind);
        cv.drawColor(Color.argb(110, 0, 0, 0));
        cv.save();
        cv.translate((w - dw) / 2f, (h - dh) / 2f);
        dv.draw(cv);
        cv.restore();
        save(behind, name);
    }

    private static void tab(Context c, int t) {
        c.getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE).edit().putInt("tab", t).commit();
    }

    private MainActivity main(Context c, int tab) throws Exception {
        tab(c, tab);
        TestSupport.resetStatics();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        settle();
        return a;
    }

    private static void click(Activity a, String text) throws Exception {
        View v = TestSupport.find(a.getWindow().getDecorView(), text);
        if (v == null) throw new IllegalStateException("no view '" + text + "'");
        v.performClick();
        settle();
    }

    @Test
    public void setupScreens() throws Exception {
        eachDevice(c -> {
            shoot(main(c, 0), "setup-teams-2");
            shoot(main(c, 1), "setup-map");
            MainActivity r = main(c, 2);
            shoot(r, "setup-rules");
            click(r, "More rules");
            View sv = TestSupport.find(r.getWindow().getDecorView(), "More rules");
            // the extra rules sit below the ones in view: scroll the nearest scroll container to the end as well
            shoot(r, "setup-rules-more");
            for (View p = sv; p != null && p.getParent() instanceof View; p = (View) p.getParent()) {
                if (p instanceof android.widget.ScrollView) { ((android.widget.ScrollView) p).fullScroll(View.FOCUS_DOWN); break; }
            }
            shoot(r, "setup-rules-more-end");
        });
    }

    @Test
    public void manyTeams() throws Exception {
        eachDevice(c -> {
            // six teams, one of them a computer team (level 3), saved as the setup the screen then shows
            SetupModel m = Store.loadSetup(c, Store.data(c));
            java.util.Random rnd = new java.util.Random(7);
            while (m.teams.size() < 6) m.teams.add(SetupModel.randomTeam(Store.data(c), rnd, m.freeColor(), m.teams));
            m.teams.get(1).level = 3;
            Store.saveSetup(c, m);
            c.getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE).edit().commit();
            shoot(main(c, 0), "setup-teams-6-cpu");
        });
    }

    @Test
    public void problemBox() throws Exception {
        eachDevice(c -> {
            File logs = Diag.logsDir(c);
            java.nio.file.Files.write(new File(logs, Diag.LAST_ERROR).toPath(), "The engine stopped with code 5.\nsecond line\n".getBytes("UTF-8"));
            java.nio.file.Files.write(new File(logs, "app_version.txt").toPath(), (Diag.versionName(c) + "\n").getBytes("UTF-8"));
            shoot(main(c, 0), "setup-problem-box");
            new File(logs, Diag.LAST_ERROR).delete();
        });
    }

    @Test
    public void teamAndOptions() throws Exception {
        eachDevice(c -> {
            TestSupport.resetStatics();
            TeamEditActivity t = Robolectric.buildActivity(TeamEditActivity.class,
                    new Intent(c, TeamEditActivity.class).putExtra("team", 0)).setup().get();
            settle();
            shoot(t, "team-edit");
            String[] names = { "options-sound", "options-display", "options-advanced" };
            for (int tab = 0; tab < 3; tab++) {
                c.getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE).edit().putInt("optionsTab", tab).commit();
                OptionsActivity o = Robolectric.buildActivity(OptionsActivity.class).setup().get();
                settle();
                shoot(o, names[tab]);
            }
        });
    }

    private static void netState(String stateJson, boolean hosting) throws Exception {
        java.lang.reflect.Field f = NetSession.class.getDeclaredField("state");
        f.setAccessible(true);
        f.set(null, org.hedgewars.android.net.Json.parseObject(stateJson));
        NetSession.joined = true;
        NetSession.hosting = hosting;
        NetSession.target = "192.168.1.20:46633";
    }

    private NetLobbyActivity lobby() throws Exception {
        NetLobbyActivity a = Robolectric.buildActivity(NetLobbyActivity.class, null).setup().get();
        settle();
        return a;
    }

    @Test
    public void network() throws Exception {
        eachDevice(c -> {
            shoot(lobby(), "net-chooser");
            TestSupport.resetStatics();

            netState(LOBBY.replace("\"canStart\":false", "\"canStart\":true").replace("\"you\":2", "\"you\":1"), true);
            NetSession.HostInfo hi = new NetSession.HostInfo(Arrays.asList("192.168.1.57", "10.0.0.4"), 8766, 1, "", "Game 1: 4 turns, log 120 messages",
                    Arrays.asList("/192.168.1.106:36617: round trip 12.3 ms (average 15.0, best 9.0, worst 41.0, 5 pongs of 5 pings), 3 frames behind (deepest 7), watching for 12 s"));
            hi.waiting = 2;
            NetSession.hostInfo = hi;
            NetLobbyActivity host = lobby();
            shoot(host, "net-host-tv");
            // Back asks before it leaves
            Bitmap behind = draw(host);
            host.onBackPressed();
            TestSupport.settle(200);
            shootDialog(behind, org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog(), "net-back-confirm");
            TestSupport.resetStatics();

            netState(LOBBY, false);
            shoot(lobby(), "net-joined");
            TestSupport.resetStatics();
        });
    }

    /** The Back question of a network game: GameActivity needs SDL, so its dialog is drawn over a black screen. */
    @Test
    public void gameBackDialog() throws Exception {
        eachDevice(c -> {
            File dir = c.getFilesDir();
            for (String role : new String[] { "player", "host" }) {
                java.nio.file.Files.write(new File(dir, "net.cfg").toPath(), ("host=1.2.3.4\nport=46633\ntoken=x\nrole=" + role + "\n").getBytes("UTF-8"));
                org.robolectric.android.controller.ActivityController<GameActivity> ctl = Robolectric.buildActivity(GameActivity.class);
                GameActivity a;
                try { a = ctl.create().get(); } catch (Throwable e) { a = ctl.get(); }
                a.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK));
                a.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK));
                TestSupport.settle(200);
                AlertDialog d = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
                Bitmap black = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                new Canvas(black).drawColor(Color.rgb(20, 40, 70));
                shootDialog(black, d, "game-back-" + role);
                d.dismiss();
            }
            new File(dir, "net.cfg").delete();
        });
    }
}
