package org.hedgewars.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.view.View;
import android.content.Context;
import android.content.Intent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.hedgewars.android.net.RelayClient;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Launch smoke test: every screen the user can open is created, started, resumed, laid out and destroyed on a
 * Fairphone-sized landscape screen under Android 14 and 15, and nothing may throw. The menus are also opened held upright
 * on a 360 dp wide phone, and turned from landscape to upright while open (the game screen stays landscape). It exists because 0.2.1 crashed on
 * launch on every phone (Window.getInsetsController() before setContentView) and no test opened a screen.
 *
 * What it covers:
 *  - MainActivity, all three tabs, with the game data installed and a saved setup written by 0.2.0, and with default
 *    preferences;
 *  - MainActivity on the very first launch, when there is no game data yet and it is being unpacked;
 *  - OptionsActivity, all three tabs, and TeamEditActivity for two teams;
 *  - NetLobbyActivity as the chooser, as a joined phone, as the host, and its Back confirmation.
 *
 * Needs the staged game data (scripts/stage-assets.sh) and the Android 14 and 15 jars in the Robolectric cache
 * (scripts/fetch-robolectric.sh). Run it with scripts/ui-smoke-test.sh.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = { 34, 35 }, qualifiers = TestSupport.QUALIFIERS)
public class LaunchSmokeTest {
    /** A setup as 0.2.0 saved it: format v1, two teams, only the keys that version wrote. */
    private static final String SETUP_0_2_0 = "#Hedge Party 0.2.0\n"
            + "v=1\nteams=2\n"
            + "t0.name=Red Team\nt0.color=0\nt0.level=0\nt0.hogs=4\nt0.grave=Statue\nt0.fort=Earth\nt0.voice=Default\nt0.flag=hedgewars\n"
            + "t1.name=CPU 1\nt1.color=1\nt1.level=2\nt1.hogs=4\nt1.grave=Plane\nt1.fort=Plane\nt1.voice=Default\nt1.flag=hedgewars\n"
            + "mapType=0\ntemplate=0\nmaze=0\nfeature=12\nimageMap=\ntheme=\nseed=\nstyle=\nscheme=Default\n"
            + "flags=0000000000000000000000000\nweapons=Default\nlink=1\n";

    @Before
    public void freshProcess() throws Exception {
        TestSupport.resetStatics();
    }

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    private static void prefsInt(String key, int v) {
        app().getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(key, v).commit();
    }

    /** Runs the whole life cycle of an activity the way the system does, with a layout pass and idle time. */
    private static <A extends Activity> String launch(Class<A> k, Intent in, long settleMillis) throws Exception {
        return run(k, in, settleMillis, false);
    }

    /**
     * The same with the phone held upright (TestSupport.PORTRAIT_QUALIFIERS). Each label in mustShow must be on screen
     * and inside the window, and every button must lie inside the window's width.
     */
    private static <A extends Activity> String launchUpright(Class<A> k, Intent in, long settleMillis, String... mustShow) throws Exception {
        RuntimeEnvironment.setQualifiers(TestSupport.PORTRAIT_QUALIFIERS);
        try {
            return run(k, in, settleMillis, true, mustShow);
        } finally {
            RuntimeEnvironment.setQualifiers(TestSupport.QUALIFIERS);
        }
    }

    private static <A extends Activity> String run(Class<A> k, Intent in, long settleMillis, boolean upright, String... mustShow) throws Exception {
        ActivityController<A> ctl = Robolectric.buildActivity(k, in);
        A a = ctl.create().start().postCreate(null).resume().visible().get();
        a.onWindowFocusChanged(true);
        int w = upright ? TestSupport.PW : TestSupport.W, h = upright ? TestSupport.PH : TestSupport.H;
        if (upright) TestSupport.layoutUpright(a); else TestSupport.layout(a);
        TestSupport.settle(settleMillis);
        if (upright) TestSupport.layoutUpright(a); else TestSupport.layout(a);
        View decor = a.getWindow().getDecorView();
        String text = TestSupport.texts(decor);
        assertNotNull(decor);
        if (a instanceof MainActivity) assertNetworkButtonFits(a, w);
        if (upright) {
            assertTrue("upright screen is taller than wide: " + a.getClass().getSimpleName(), Ui.upright(a));
            assertButtonsInside(decor, w);
            for (String label : mustShow) assertShownInside(decor, label, w, h);
        }
        ctl.pause().stop().destroy();
        return text;
    }

    /** A control with this text or description is on screen and lies inside the window. */
    private static void assertShownInside(View decor, String label, int w, int h) {
        View v = TestSupport.find(decor, label);
        assertNotNull("'" + label + "' is on the screen", v);
        assertTrue("'" + label + "' is shown", TestSupport.shown(v));
        int[] xy = new int[2];
        v.getLocationOnScreen(xy);
        assertTrue("'" + label + "' lies inside the window: x " + xy[0] + " to " + (xy[0] + v.getWidth()) + ", y " + xy[1] + " to " + (xy[1] + v.getHeight()),
                xy[0] >= 0 && xy[0] + v.getWidth() <= w && xy[1] >= 0 && xy[1] + v.getHeight() <= h);
    }

    /** Every shown button lies inside the window's width, so nothing is cut off at the right edge. */
    private static void assertButtonsInside(View v, int w) {
        if (v instanceof android.widget.Button && v.getVisibility() == View.VISIBLE && v.getWidth() > 0 && TestSupport.shown(v)) {
            int[] xy = new int[2];
            v.getLocationOnScreen(xy);
            assertTrue("button '" + ((android.widget.Button) v).getText() + "' is cut off: x " + xy[0] + " to " + (xy[0] + v.getWidth()),
                    xy[0] >= 0 && xy[0] + v.getWidth() <= w);
        }
        if (v instanceof android.view.ViewGroup) {
            for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) assertButtonsInside(((android.view.ViewGroup) v).getChildAt(i), w);
        }
    }

    /** The Network button in the compact header shows its whole word and lies inside the window of the given width. */
    private static void assertNetworkButtonFits(Activity a, int w) {
        android.widget.TextView t = findText(a.getWindow().getDecorView(), "Network");
        assertNotNull("the header has a Network button", t);
        int[] xy = new int[2];
        t.getLocationOnScreen(xy);
        assertTrue("Network is not cut off (ellipsis)", t.getLayout() == null || t.getLayout().getEllipsisCount(0) == 0);
        assertTrue("Network lies inside the screen: x " + xy[0] + " to " + (xy[0] + t.getWidth()), xy[0] >= 0 && xy[0] + t.getWidth() <= w);
    }

    private static android.widget.TextView findText(android.view.View v, String s) {
        if (v instanceof android.widget.TextView && s.contentEquals(((android.widget.TextView) v).getText())) return (android.widget.TextView) v;
        if (v instanceof android.view.ViewGroup) {
            for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) {
                android.widget.TextView r = findText(((android.view.ViewGroup) v).getChildAt(i), s);
                if (r != null) return r;
            }
        }
        return null;
    }

    private void mainTabs(String setup) throws Exception {
        TestSupport.installData(app());
        if (setup != null) {
            app().getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE).edit().putString("setup", setup).commit();
        }
        for (int tab = 0; tab < 3; tab++) {
            prefsInt("tab", tab);
            String text = launch(MainActivity.class, null, 1500);
            assertTrue("tab " + tab + " shows the header: " + text, text.contains("Teams") && text.contains("Rules"));
            assertFalse("tab " + tab + " reports an error: " + text, text.contains("Could not"));
            System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": MainActivity tab " + tab + " ok");
            TestSupport.resetStatics();
        }
    }

    @Test
    public void mainActivity_allTabs_installedData_savedBy020() throws Exception {
        mainTabs(SETUP_0_2_0);
    }

    @Test
    public void mainActivity_allTabs_installedData_defaultPrefs() throws Exception {
        mainTabs(null);
    }

    /** First launch: no files/Data, the data is unpacked from the assets while the screen is up. */
    @Test
    public void mainActivity_firstLaunch_noData() throws Exception {
        assertFalse(DataInstaller.isInstalled(app()));
        for (int tab = 0; tab < 3; tab++) {
            prefsInt("tab", tab);
            String text = launch(MainActivity.class, null, 1500);
            assertTrue("tab " + tab + " shows the header: " + text, text.contains("Teams"));
            System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": MainActivity first launch tab " + tab + " ok");
            TestSupport.resetStatics();
        }
    }

    // The Rules tab's explanations -------------------------------------------------------------------

    /** Every rule and switch on the Rules tab has an explanation, so a new setting cannot ship without one. */
    @Test
    public void rules_everySettingHasAnExplanation() throws Exception {
        java.util.List<String> keys = new java.util.ArrayList<>(java.util.Arrays.asList("style", "scheme", "weapons.link", "weapons.set"));
        for (int k = 1; k < Rules.FLAG_LABELS.length; k++) keys.add("flag." + k);
        for (int e = 0; e < Rules.VALUE_LABELS.length; e++) keys.add("value." + (Rules.V_DAMAGE + e));
        for (String key : keys) {
            assertFalse("no explanation for the rule '" + key + "'", RuleHelp.text(key).trim().isEmpty());
        }
        // Every game style in the game data has its own description (a style not listed gets the generic one).
        TestSupport.installData(app());
        for (String style : new GameData(new java.io.File(app().getFilesDir(), "Data")).styles()) {
            assertTrue("no description for the game style '" + style + "'", RuleHelp.hasStyle(style));
        }
    }

    /**
     * Every setting row on the Rules tab, the hidden "More rules" section included, has an info button: a row with a stepper
     * or a choice button but no "About ..." button fails, so a new setting cannot ship without its explanation on screen.
     */
    @Test
    public void rules_everyRowHasInfoButton() throws Exception {
        TestSupport.installData(app());
        prefsInt("tab", 2);
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).create().start().postCreate(null).resume().visible();
        MainActivity a = ctl.get();
        a.onWindowFocusChanged(true);
        TestSupport.layout(a);
        TestSupport.settle(1500);
        TestSupport.layout(a);
        View decor = a.getWindow().getDecorView();
        java.util.List<String> missing = new java.util.ArrayList<>();
        int[] rows = { 0 };
        checkSettingRows(decor, missing, rows);
        android.widget.Button more = (android.widget.Button) TestSupport.find(decor, "More rules");
        assertNotNull("the Rules tab has a More rules button", more);
        more.performClick();
        TestSupport.settle(300);
        TestSupport.layout(a);
        checkSettingRows(a.getWindow().getDecorView(), missing, rows);
        assertTrue("the setting rows were checked (" + rows[0] + ")", rows[0] >= 20);
        assertTrue("settings without an info button: " + missing, missing.isEmpty());
        ctl.pause().stop().destroy();
    }

    private static void checkSettingRows(View v, java.util.List<String> missing, int[] rows) {
        if (!(v instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup g = (android.view.ViewGroup) v;
        boolean info = false, control = false;
        String label = "";
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof Ui.Glyph && String.valueOf(c.getContentDescription()).startsWith("About ")) info = true;
            if (c instanceof android.widget.Button) {
                String t = String.valueOf(((android.widget.Button) c).getText());
                if (t.equals("+") || t.endsWith("\u25be")) control = true;
            }
            if (label.isEmpty() && c instanceof android.widget.TextView && !(c instanceof android.widget.Button)) {
                label = String.valueOf(((android.widget.TextView) c).getText());
            }
        }
        if (control) {
            rows[0]++;
            if (!info) missing.add(label.isEmpty() ? "(unnamed row)" : label);
        }
        for (int i = 0; i < g.getChildCount(); i++) checkSettingRows(g.getChildAt(i), missing, rows);
    }

    // Held upright (the menus follow the rotation; the game stays sideways) ------------------------

    /** Every tab of the Setup screen opens upright on a 360 dp wide phone, with the header, tabs, Start and Options on screen. */
    @Test
    public void portrait_mainActivity_allTabs() throws Exception {
        TestSupport.installData(app());
        String[][] tabs = { { "Teams", "Add team" }, { "Map", "Theme" }, { "Rules", "Game style" } };
        for (int tab = 0; tab < tabs.length; tab++) {
            prefsInt("tab", tab);
            String text = launchUpright(MainActivity.class, null, 1500, "Teams", "Map", "Rules", "Network", "Options", "Start game", tabs[tab][1]);
            assertTrue("tab " + tab + " shows the header: " + text, text.contains("Teams") && text.contains("Rules"));
            System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": upright MainActivity tab " + tab + " ok");
            TestSupport.resetStatics();
        }
    }

    @Test
    public void portrait_mainActivity_firstLaunch_noData() throws Exception {
        String text = launchUpright(MainActivity.class, null, 1500, "Network", "Options");
        assertTrue("first launch upright shows the header: " + text, text.contains("Teams"));
    }

    @Test
    public void portrait_optionsActivity_allTabs() throws Exception {
        TestSupport.installData(app());
        for (int tab = 0; tab < 3; tab++) {
            prefsInt("optionsTab", tab);
            String text = launchUpright(OptionsActivity.class, null, 600, "\u2039 Done", "Sound", "Display", "Advanced");
            assertFalse("upright options tab " + tab + " is empty", text.trim().isEmpty());
            System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": upright OptionsActivity tab " + tab + " ok");
        }
    }

    @Test
    public void portrait_teamEditActivity() throws Exception {
        TestSupport.installData(app());
        app().getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE).edit().putString("setup", SETUP_0_2_0).commit();
        Intent in = new Intent(app(), TeamEditActivity.class).putExtra("team", 1);
        String text = launchUpright(TeamEditActivity.class, in, 600, "\u2039 Done", "Randomise team");
        assertFalse("upright team edit is empty", text.trim().isEmpty());
    }

    @Test
    public void portrait_netLobbyActivity_chooser() throws Exception {
        String text = launchUpright(NetLobbyActivity.class, null, 600, "\u2039 Back");
        assertFalse("upright network chooser is empty", text.trim().isEmpty());
    }

    /** Rotating a menu keeps the tab it was on and lays it out again for the new shape of the window. */
    @Test
    public void rotation_mainActivity_keepsTabAndRebuildsLayout() throws Exception {
        TestSupport.installData(app());
        prefsInt("tab", 1);
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class);
        MainActivity a = ctl.create().start().postCreate(null).resume().visible().get();
        TestSupport.layout(a);
        TestSupport.settle(1500);
        assertTrue("landscape map tab: " + TestSupport.texts(a.getWindow().getDecorView()), TestSupport.texts(a.getWindow().getDecorView()).contains("Theme"));
        assertFalse("landscape has no stacked tab strip", Ui.upright(a));

        RuntimeEnvironment.setQualifiers(TestSupport.PORTRAIT_QUALIFIERS);
        try {
            MainActivity b = (MainActivity) ctl.recreate().get();
            TestSupport.layoutUpright(b);
            TestSupport.settle(1500);
            TestSupport.layoutUpright(b);
            View decor = b.getWindow().getDecorView();
            assertTrue("upright after rotation", Ui.upright(b));
            assertTrue("still on the map tab after rotation: " + TestSupport.texts(decor), TestSupport.texts(decor).contains("Theme"));
            assertShownInside(decor, "Map", TestSupport.PW, TestSupport.PH);
            assertButtonsInside(decor, TestSupport.PW);
            assertNetworkButtonFits(b, TestSupport.PW);
            ctl.pause().stop().destroy();
        } finally {
            RuntimeEnvironment.setQualifiers(TestSupport.QUALIFIERS);
        }
    }

    @Test
    public void optionsActivity_allTabs() throws Exception {
        TestSupport.installData(app());
        for (int tab = 0; tab < 3; tab++) {
            prefsInt("optionsTab", tab);
            String text = launch(OptionsActivity.class, null, 600);
            assertFalse("options tab " + tab + " is empty", text.trim().isEmpty());
            System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": OptionsActivity tab " + tab + " ok");
        }
    }

    @Test
    public void optionsActivity_noData() throws Exception {
        launch(OptionsActivity.class, null, 300);
    }

    @Test
    public void teamEditActivity_installedData() throws Exception {
        TestSupport.installData(app());
        app().getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE).edit().putString("setup", SETUP_0_2_0).commit();
        for (int team = 0; team < 2; team++) {
            Intent in = new Intent(app(), TeamEditActivity.class).putExtra("team", team);
            String text = launch(TeamEditActivity.class, in, 600);
            assertFalse("team " + team + " is empty", text.trim().isEmpty());
            System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": TeamEditActivity team " + team + " ok");
        }
    }

    // The problem box ------------------------------------------------------------------------------

    private static java.io.File logs() {
        return Diag.logsDir(app());
    }

    private static void writeProblem(String text, String writtenByVersion) throws Exception {
        java.nio.file.Files.write(new java.io.File(logs(), Diag.LAST_ERROR).toPath(), text.getBytes("UTF-8"));
        java.nio.file.Files.write(new java.io.File(logs(), "app_version.txt").toPath(), (writtenByVersion + "\n").getBytes("UTF-8"));
    }

    /** The 0.2.1 launch crash that 0.2.2 still showed after the update: an older version's error must not show. */
    @Test
    public void problemBox_errorFromOlderVersion_isDroppedAndNotShown() throws Exception {
        TestSupport.installData(app());
        writeProblem("The app crashed (RuntimeException: Unable to start activity).\n", "0.2.1 (7)");
        Diag.dropStaleProblem(app(), logs());                  // what the setup screen process does when it starts
        assertTrue("the stale error file is removed", Diag.lastError(app()) == null);
        String text = launch(MainActivity.class, null, 1200);
        assertFalse("the box must not show: " + text, text.contains("stopped with a problem"));
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": stale problem not shown ok");
    }

    /** An error of this version shows short, hides while the keyboard is open, and the x clears it for good. */
    @Test
    public void problemBox_currentError_shortHidesWithKeyboardAndDismisses() throws Exception {
        TestSupport.installData(app());
        prefsInt("tab", 0);
        writeProblem("The engine stopped with code 5.\nsecond line with details that belong in the log\n", Diag.versionName(app()));
        Diag.dropStaleProblem(app(), logs());                  // same version: kept
        assertTrue(Diag.lastError(app()) != null);
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class, null);
        MainActivity a = ctl.create().start().postCreate(null).resume().visible().get();
        a.onWindowFocusChanged(true);
        TestSupport.layout(a);
        TestSupport.settle(1500);
        TestSupport.layout(a);
        android.view.View decor = a.getWindow().getDecorView();
        android.view.View box = TestSupport.find(decor, "Share last log");
        assertNotNull("the share button is there", box);
        assertTrue("the box is visible", TestSupport.shown(box));
        String text = TestSupport.texts(decor);
        assertTrue(text, text.contains("The engine stopped with code 5."));
        assertFalse("details stay in the log: " + text, text.contains("second line with details"));

        TestSupport.layoutWithKeyboard(a);
        TestSupport.settle(200);
        assertFalse("hidden while the keyboard is open", TestSupport.shown(box));
        TestSupport.layout(a);
        TestSupport.settle(200);
        assertTrue("back after the keyboard closes", TestSupport.shown(box));

        android.view.View x = TestSupport.find(decor, "Dismiss");
        assertNotNull("the dismiss button is there", x);
        x.performClick();
        TestSupport.settle(200);
        assertFalse("hidden after dismissing", TestSupport.shown(box));
        assertTrue("the stored error is kept for the shared report", Diag.lastError(app()) != null);
        ctl.pause().stop().destroy();
        // and it does not come back
        String again = launch(MainActivity.class, null, 800);
        assertFalse(again, again.contains("stopped with a problem"));
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": problem box ok");
    }

    /** Options, Display: the screen edge setting, the camera setting, and a layout pass with the saved values. */
    @Test
    public void optionsActivity_displayTab_screenEdgeAndCamera() throws Exception {
        TestSupport.installData(app());
        prefsInt("optionsTab", 1);
        String text = launch(OptionsActivity.class, null, 600);
        assertTrue(text, text.contains("Screen edge") && text.contains("Extra space at the edge"));
        assertTrue(text, text.contains("Camera at the start of a turn") && text.contains("Centre on the active hedgehog"));
        Options o = new Options();
        o.marginAuto = false;
        o.marginMm = 7;
        o.zoomPct = 160;
        Store.saveOptions(app(), o);
        text = launch(OptionsActivity.class, null, 600);
        assertTrue(text, text.contains("7 mm"));
        Options back = Store.loadOptions(app());
        assertTrue(back.marginMm == 7 && !back.marginAuto && back.zoomPct == 160);
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": options display tab ok");
    }

    // The network screen ---------------------------------------------------------------------------------------

    private static void netState(String stateJson, boolean hosting) throws Exception {
        java.lang.reflect.Field f = NetSession.class.getDeclaredField("state");
        f.setAccessible(true);
        f.set(null, org.hedgewars.android.net.Json.parseObject(stateJson));
        NetSession.joined = true;
        NetSession.hosting = hosting;
        NetSession.target = "192.168.1.20:46633";
    }

    private static final String LOBBY = "{\"t\":\"state\",\"state\":\"setup\",\"host\":\"Pixel\",\"proto\":1,\"build\":\"0.3.0-mp2\",\"you\":2,"
            + "\"players\":[{\"id\":1,\"name\":\"Pixel\",\"host\":true,\"ready\":true,\"fg\":true,"
            + "\"teams\":[{\"name\":\"Red Team\",\"color\":0,\"level\":0}]},"
            + "{\"id\":2,\"name\":\"Fairphone\",\"host\":false,\"ready\":false,\"fg\":false,"
            + "\"teams\":[{\"name\":\"Blue Team\",\"color\":1,\"level\":3}]}],\"canStart\":false,\"game\":0}";

    private static final String LOBBY_WATCH = "{\"t\":\"state\",\"state\":\"setup\",\"host\":\"Pixel\",\"proto\":1,\"build\":\"0.3.0-tv6\",\"you\":3,"
            + "\"players\":[{\"id\":1,\"name\":\"Pixel\",\"host\":true,\"ready\":true,\"fg\":true,\"watching\":false,"
            + "\"teams\":[{\"name\":\"Red Team\",\"color\":0,\"level\":0}]},"
            + "{\"id\":3,\"name\":\"Tablet\",\"host\":false,\"ready\":true,\"fg\":true,\"watching\":true,\"teams\":[]}],"
            + "\"canStart\":false,\"game\":0,\"watching\":1}";

    /** The Watch path: a watching phone's lobby (label, no Ready, no teams button), and the host's "Watching: N". */
    @Test
    public void netLobbyActivity_watchPath() throws Exception {
        TestSupport.installData(app());
        netState(LOBBY_WATCH, false);
        NetSession.watching = true;
        String text = launch(NetLobbyActivity.class, null, 600);
        assertTrue("a watching phone is labelled and has no Ready or My teams: " + text,
                text.contains("Joined as a watcher") && text.contains("Watching") && text.contains("Tablet (you)") && text.contains("watching only")
                        && !text.contains("Ready") && !text.contains("My teams again") && text.contains("Stop watching"));
        TestSupport.resetStatics();
        netState(LOBBY_WATCH.replace("\"you\":3", "\"you\":1"), true);
        String host = launch(NetLobbyActivity.class, null, 600);
        assertTrue("the host sees how many watch and who: " + host, host.contains("Phones watching: 1") && host.contains("Tablet") && host.contains("Start game"));
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": NetLobbyActivity watch path ok");
    }

    /** The relay's start for a phone that watches (what NetSession's relay handler receives), handed to the same code path as the relay's line. */
    private static void relayStartForWatcher() throws Exception {
        RelayClient.StartInfo s = new RelayClient.StartInfo();
        s.you = 3;
        s.game = 1;
        s.token = "watch-token";
        s.nick = "Tablet";
        s.host = "192.168.1.20";
        s.port = 46633;
        s.asWatcher = true;
        java.lang.reflect.Field hf = NetSession.class.getDeclaredField("HANDLER");
        hf.setAccessible(true);
        ((RelayClient.Handler) hf.get(null)).onStart(s);
    }

    /** Join to watch (issue 23): a watcher that is sent the start opens the game screen at once, with the Network screen in front. */
    @Test
    public void netLobbyActivity_watcherStartOpensTheGame() throws Exception {
        TestSupport.installData(app());
        netState(LOBBY_WATCH, false);
        NetSession.watching = true;
        ActivityController<NetLobbyActivity> ctl = Robolectric.buildActivity(NetLobbyActivity.class);
        NetLobbyActivity a = ctl.create().start().postCreate(null).resume().visible().get();
        a.onWindowFocusChanged(true);
        TestSupport.settle(300);
        relayStartForWatcher();
        TestSupport.settle(600);
        Intent next = org.robolectric.Shadows.shadowOf(a).getNextStartedActivity();
        assertNotNull("a watcher sent the start leaves the lobby for the game screen", next);
        assertEquals(GameActivity.class.getName(), next.getComponent().getClassName());
        ctl.pause().stop().destroy();
        TestSupport.resetStatics();
        NetSession.inGame = false;
        NetSession.pendingStart = null;
        NetSession.lobbyVisible = false;
    }

    /** The same start while the Network screen is not in front: it waits as a pending start, and the screen opens the game when it comes back. */
    @Test
    public void netLobbyActivity_watcherStartWaitsForTheScreen() throws Exception {
        TestSupport.installData(app());
        netState(LOBBY_WATCH, false);
        NetSession.watching = true;
        NetSession.lobbyVisible = false;
        relayStartForWatcher();
        assertNotNull("the start is kept while the Network screen is not in front", NetSession.pendingStart);
        ActivityController<NetLobbyActivity> ctl = Robolectric.buildActivity(NetLobbyActivity.class);
        NetLobbyActivity a = ctl.create().start().postCreate(null).resume().visible().get();
        a.onWindowFocusChanged(true);
        TestSupport.settle(600);
        Intent next = org.robolectric.Shadows.shadowOf(a).getNextStartedActivity();
        assertNotNull("the Network screen opens the game for the kept start", next);
        assertEquals(GameActivity.class.getName(), next.getComponent().getClassName());
        assertNull("the kept start is used once", NetSession.pendingStart);
        ctl.pause().stop().destroy();
        TestSupport.resetStatics();
        NetSession.inGame = false;
        NetSession.lobbyVisible = false;
    }

    /** A watcher whose game has ended (it asked to watch after the end, or the game ended while it watched) sees "The game ended" and why, not a lobby. */
    @Test
    public void netLobbyActivity_watcherOfAnEndedGameSeesTheEnd() throws Exception {
        TestSupport.installData(app());
        netState(LOBBY_WATCH, false);
        NetSession.watching = true;
        NetSession.lastEndReason = "Every player left the game and nobody came back";
        try {
            String text = launch(NetLobbyActivity.class, null, 600);
            assertTrue("the watcher is told the game ended, and why: " + text,
                    text.contains("The game ended") && text.contains("Every player left the game and nobody came back") && !text.contains("game running"));
        } finally {
            NetSession.lastEndReason = "";
            TestSupport.resetStatics();
        }
    }

    /** "Stop watching" confirmed in the game screen (another process) leaves the relay at once: the lobby shows the chooser, not "Watching". */
    @Test
    public void netLobbyActivity_stopWatchingInGame_leavesInOneStep() throws Exception {
        TestSupport.installData(app());
        fakeHosts();
        netState(LOBBY_WATCH, false);
        NetSession.watching = true;
        BackConfirm.markLeave(app().getFilesDir());
        String text = launch(NetLobbyActivity.class, null, 600);
        assertTrue("back at the chooser: " + text, text.contains("Host network game") && text.contains("Join by address") && !text.contains("Joined as a watcher"));
        assertTrue("not joined any more", !NetSession.joined && !NetSession.watching);
        assertTrue("the note is used up", !BackConfirm.takeLeave(app().getFilesDir()));
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": stop watching in one step ok");
    }

    /** The spectator's game screen state: role=watch in net.cfg gives the Stop watching question (and nothing else about the player's). */
    @Test
    public void gameBackConfirm_watcher() throws Exception {
        TestSupport.installData(app());
        Activity a = Robolectric.buildActivity(NetLobbyActivity.class, null).create().start().resume().visible().get();
        final boolean[] yes = { false };
        android.app.AlertDialog d = BackConfirm.show(a, BackConfirm.WATCH, () -> yes[0] = true, () -> { });
        org.robolectric.shadows.ShadowAlertDialog sd = org.robolectric.Shadows.shadowOf(d);
        assertTrue(String.valueOf(sd.getTitle()).equals("Stop watching?"));
        assertTrue(String.valueOf(sd.getMessage()).contains("carries on for the players"));
        assertTrue(d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).getText().toString().equals("Stop watching"));
        assertTrue(d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).getText().toString().equals("Keep watching"));
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
        TestSupport.settle(100);
        assertTrue(yes[0]);
        java.io.File dir = app().getFilesDir();
        org.hedgewars.android.net.RelayClient.StartInfo si = new org.hedgewars.android.net.RelayClient.StartInfo();
        si.host = "127.0.0.1"; si.port = 46633; si.token = "t"; si.nick = "n"; si.asWatcher = true;
        org.hedgewars.android.net.RelayClient.writeGameFiles(new java.io.File(dir, "game.ipc"), new java.io.File(dir, "net.cfg"), si, "/x");
        assertTrue("a watcher's net.cfg says role=watch", BackConfirm.role(dir) == BackConfirm.WATCH);
        // Back in the spectator's game screen opens the Stop watching question
        org.robolectric.android.controller.ActivityController<GameActivity> c = Robolectric.buildActivity(GameActivity.class);
        GameActivity g;
        try { g = c.create().get(); } catch (Throwable e) { g = c.get(); }
        g.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK));
        g.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK));
        TestSupport.settle(100);
        android.app.AlertDialog gd = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("the Stop watching question is up in the spectator's game screen", gd != null && gd.isShowing()
                && String.valueOf(org.robolectric.Shadows.shadowOf(gd).getTitle()).equals("Stop watching?"));
        gd.dismiss();
        new java.io.File(dir, "net.cfg").delete();
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": spectator game screen state ok");
    }

    /**
     * The Network screen shows "Your team" with the default team (the first human team of the setup). A joining phone sends only that
     * team in its hello; the host still sends its whole setup; a team chosen under "Your team" is the one that joins.
     */
    @Test
    public void netLobbyActivity_yourTeam_joinSendsOnlyThatTeam() throws Exception {
        TestSupport.installData(app());
        app().getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE).edit().putString("setup", SETUP_0_2_0).commit();
        String text = launch(NetLobbyActivity.class, null, 600);
        assertTrue("the Network screen shows Your team with the default team: " + text, text.contains("Your team") && text.contains("Red Team"));
        java.util.List<SetupModel.Team> joiner = NetSession.teamsToSend(app(), false, false, false);
        assertTrue("a joining phone sends exactly one team: " + joiner.size(), joiner.size() == 1);
        assertTrue("and it is the default team: " + joiner.get(0).name, joiner.get(0).name.equals("Red Team"));
        assertTrue("the host sends its whole setup", NetSession.teamsToSend(app(), true, false, false).size() == 2);
        assertTrue("a watcher sends none", NetSession.teamsToSend(app(), false, true, false).isEmpty());
        Store.saveJoinTeamIndex(app(), 1);
        joiner = NetSession.teamsToSend(app(), false, false, false);
        assertTrue("the team chosen under Your team joins: " + joiner.get(0).name, joiner.size() == 1 && joiner.get(0).name.equals("CPU 1"));
        text = launch(NetLobbyActivity.class, null, 600);
        assertTrue("the chosen team is shown: " + text, text.contains("CPU 1"));
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": Your team on the Network screen ok");
    }

    /** The lobby: the host gets Remove next to a joiner's team; a joiner gets My team again (its own team) and no Remove. */
    @Test
    public void netLobbyActivity_removeAndMyTeamAgain() throws Exception {
        TestSupport.installData(app());
        netState(LOBBY.replace("\"you\":2", "\"you\":1"), true);
        String host = launch(NetLobbyActivity.class, null, 600);
        assertTrue("the host can remove a joiner: " + host, host.contains("Remove") && host.contains("Fairphone"));
        TestSupport.resetStatics();
        netState(LOBBY, false);
        String joiner = launch(NetLobbyActivity.class, null, 600);
        assertTrue("a joiner sends its own team again: " + joiner, joiner.contains("My team again") && !joiner.contains("Remove"));
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": remove and My team again ok");
    }

    private static void editTexts(android.view.View v, java.util.List<android.widget.EditText> out) {
        if (v instanceof android.widget.EditText) out.add((android.widget.EditText) v);
        if (v instanceof android.view.ViewGroup) {
            for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) editTexts(((android.view.ViewGroup) v).getChildAt(i), out);
        }
    }

    /** The text of the Join by address field on a freshly opened Network screen, once the phone's own address has been looked up. */
    private static String joinAddressText() throws Exception {
        Activity a = Robolectric.buildActivity(NetLobbyActivity.class, null).create().start().postCreate(null).resume().visible().get();
        TestSupport.layout(a);
        TestSupport.settle(400);
        java.util.List<android.widget.EditText> fields = new java.util.ArrayList<>();
        editTexts(a.getWindow().getDecorView(), fields);
        for (android.widget.EditText e : fields) {
            if (e.getHint() != null && e.getHint().toString().startsWith("Host address")) return e.getText().toString();
        }
        fail("the Join by address field is not on the Network screen");
        return null;
    }

    /** The phone's own Wi-Fi prefix is the start of the address field: the person types only the last number. */
    @Test
    public void netLobbyActivity_addressFieldStartsWithOwnSubnet() throws Exception {
        TestSupport.installData(app());
        app().getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE).edit().remove("netaddr").commit();
        NetLobbyActivity.ownAddresses = () -> java.util.Arrays.asList("192.168.1.143");
        try {
            assertEquals("192.168.1.", joinAddressText());
        } finally {
            NetLobbyActivity.ownAddresses = NetSession::localAddresses;
        }
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": address field prefilled with the Wi-Fi prefix ok");
    }

    /** No address of the phone's own to go by: the field starts with the usual prefix. A last address used comes first. */
    @Test
    public void netLobbyActivity_addressFieldFallsBackAndKeepsTheLastAddress() throws Exception {
        TestSupport.installData(app());
        android.content.SharedPreferences p = app().getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE);
        p.edit().remove("netaddr").commit();
        NetLobbyActivity.ownAddresses = java.util.Collections::emptyList;
        try {
            assertEquals("192.168.1.", joinAddressText());
            p.edit().putString("netaddr", "10.0.0.5:46633").commit();
            assertEquals("10.0.0.5:46633", joinAddressText());
        } finally {
            NetLobbyActivity.ownAddresses = NetSession::localAddresses;
            p.edit().remove("netaddr").commit();
        }
    }

    @Test
    public void subnetPrefix_isTheWifiPrefixOfTheFirstAddress() {
        assertEquals("192.168.1.", NetLobbyActivity.subnetPrefix(java.util.Arrays.asList("192.168.1.143")));
        assertEquals("10.0.0.", NetLobbyActivity.subnetPrefix(java.util.Arrays.asList("garbage", "10.0.0.7")));
        assertEquals("", NetLobbyActivity.subnetPrefix(java.util.Collections.<String>emptyList()));
    }

    /** Leaving a network game (Back, then Leave the game or Stop watching) starts the Network lobby again; a local game does not. */
    @Test
    public void gameFinish_networkGameReturnsToTheLobby() throws Exception {
        TestSupport.installData(app());
        java.io.File dir = app().getFilesDir();
        java.nio.file.Files.write(new java.io.File(dir, "net.cfg").toPath(), "role=player\n".getBytes("UTF-8"));
        GameActivity g = gameActivity();
        g.finish();
        Intent next = org.robolectric.Shadows.shadowOf(g).getNextStartedActivity();
        assertNotNull("a network game starts the lobby when it finishes", next);
        assertEquals(NetLobbyActivity.class.getName(), next.getComponent().getClassName());
        assertTrue("the lobby is brought to the front", (next.getFlags() & Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) != 0);
        new java.io.File(dir, "net.cfg").delete();
        GameActivity local = gameActivity();
        local.finish();
        assertNull("a local game finishes as before", org.robolectric.Shadows.shadowOf(local).getNextStartedActivity());
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": network game returns to the lobby ok");
    }

    /**
     * The in-game Options dialog on a small landscape phone (the Moto G6: 640 x 360 dp): the Mute switch keeps its full height,
     * and the Controls layout button lies inside the dialog's scroll view, reachable by scrolling to the bottom.
     */
    @Test
    public void gameOptions_smallLandscape_controlsLayoutReachable() throws Exception {
        RuntimeEnvironment.setQualifiers("w640dp-h360dp-land-xxhdpi");
        try {
            GameActivity g = gameActivity();
            g.pauseMenuAction(GameActivity.PAUSE_OPTIONS);
            TestSupport.settle(300);
            android.app.AlertDialog d = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            assertTrue("the Options dialog is up", d != null && d.isShowing());
            View decor = d.getWindow().getDecorView();
            android.util.DisplayMetrics dm = g.getResources().getDisplayMetrics();
            int w = dm.widthPixels, h = dm.heightPixels;
            decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, w, h);
            TestSupport.settle(100);
            android.widget.Switch mute = (android.widget.Switch) TestSupport.find(decor, "Mute all sound and music");
            assertNotNull("the Mute switch is in the dialog", mute);
            assertTrue("the Mute switch keeps its height (" + mute.getHeight() + " px)", mute.getHeight() >= 40 * dm.density);
            android.widget.Button controls = (android.widget.Button) TestSupport.find(decor, "Controls layout");
            assertNotNull("the Controls layout button is in the dialog", controls);
            // the scroll view that holds the rows: the nearest one above the Controls layout button
            android.widget.ScrollView sv = null;
            for (View p = (View) controls.getParent(); p != null && sv == null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
                if (p instanceof android.widget.ScrollView) sv = (android.widget.ScrollView) p;
            }
            assertNotNull("the Options rows are in a scroll view", sv);
            int top = offsetIn(controls, sv);
            sv.scrollTo(0, Math.max(0, top + controls.getHeight() - sv.getHeight()));
            int visibleTop = top - sv.getScrollY();
            assertTrue("the Controls layout button is in the visible part after scrolling: y " + visibleTop + " of " + sv.getHeight(),
                    visibleTop >= 0 && visibleTop + controls.getHeight() <= sv.getHeight());
            d.dismiss();
        } finally {
            RuntimeEnvironment.setQualifiers(TestSupport.QUALIFIERS);
        }
    }

    /** The top edge of a view inside an ancestor, in the ancestor's coordinates. */
    private static int offsetIn(View v, View ancestor) {
        int y = 0;
        while (v != ancestor) {
            y += v.getTop();
            v = (View) v.getParent();
        }
        return y;
    }

    /** Writes the role of this phone's network game the way the Network screen does (net.cfg); "player" is a joined phone. */
    private static void writeRole(String role) throws Exception {
        java.nio.file.Files.write(new java.io.File(app().getFilesDir(), "net.cfg").toPath(),
                ("role=" + role + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** A game screen as the system creates it; the native library is not loaded under Robolectric, the screen still works. */
    private static GameActivity gameActivity() {
        org.robolectric.android.controller.ActivityController<GameActivity> c = Robolectric.buildActivity(GameActivity.class);
        try {
            return c.create().get();
        } catch (Throwable e) {
            return c.get();
        }
    }

    /** Title, message and the buttons of a question dialog, to compare two questions word for word. */
    private static String questionOf(android.app.AlertDialog d) {
        org.robolectric.shadows.ShadowAlertDialog sd = org.robolectric.Shadows.shadowOf(d);
        StringBuilder sb = new StringBuilder(String.valueOf(sd.getTitle())).append('|').append(String.valueOf(sd.getMessage()));
        for (int which : new int[] { android.content.DialogInterface.BUTTON_POSITIVE, android.content.DialogInterface.BUTTON_NEUTRAL,
                android.content.DialogInterface.BUTTON_NEGATIVE }) {
            android.widget.Button b = d.getButton(which);
            boolean shown = b != null && b.getVisibility() == android.view.View.VISIBLE;
            sb.append('|').append(shown ? b.getText().toString() : "-");
        }
        return sb.toString();
    }

    /**
     * The pause screen's Quit game asks the same question as the Back key, for a joined phone, the host and a watcher (a local game
     * quits through the engine's own question, which is not a dialog here). Options opens the options dialog with the way to the
     * display and controls options, which opens the app's Options at the display tab.
     */
    @Test
    public void gamePauseMenu_quitAsksTheBackQuestion_optionsOpen() throws Exception {
        java.io.File net = new java.io.File(app().getFilesDir(), "net.cfg");
        try {
            for (String role : new String[] { "host", "player", "watch" }) {
                writeRole(role);
                GameActivity g = gameActivity();
                g.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK));
                g.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK));
                TestSupport.settle(100);
                android.app.AlertDialog byBack = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
                assertTrue(role + ": Back opens the question", byBack != null && byBack.isShowing());
                String backText = questionOf(byBack);
                byBack.dismiss();

                g.pauseMenuAction(GameActivity.PAUSE_QUIT);
                TestSupport.settle(100);
                android.app.AlertDialog byPause = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
                assertTrue(role + ": Quit game opens the question", byPause != null && byPause.isShowing());
                assertEquals(role + ": Quit game asks the same question as Back", backText, questionOf(byPause));
                byPause.dismiss();
                System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": pause Quit game (" + role + ") asks: " + backText);
            }

            writeRole("host");
            GameActivity g = gameActivity();
            g.pauseMenuAction(GameActivity.PAUSE_OPTIONS);
            TestSupport.settle(100);
            android.app.AlertDialog options = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            assertTrue("Options opens a dialog", options != null && options.isShowing());
            assertEquals("Options", String.valueOf(org.robolectric.Shadows.shadowOf(options).getTitle()));
            android.widget.TextView controls = findText(options.getWindow().getDecorView(), "Controls layout");
            assertNotNull("the options dialog has the Controls layout entry", controls);
            controls.performClick();
            TestSupport.settle(100);
            assertNull("Controls layout is the editor, not the app's Options", org.robolectric.Shadows.shadowOf(g).getNextStartedActivityForResult());
            assertNotNull("Controls layout opens the editor over the game", findEditor(g.getWindow().getDecorView()));
            System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": pause Options dialog and Controls layout ok");
        } finally {
            net.delete();
        }
    }

    /** The controls editor (issue #21) as the engine's report sets it: the places it reports, one line per control. */
    private static final String[] ENGINE_PLACES = {
        "fire:0.900,0.800,1.000,0.100,0.200", "jump:0.700,0.800,1.000,0.120,0.230", "highjump:0.560,0.800,1.000,0.120,0.230",
        "left:0.100,0.800,1.000,0.120,0.230", "right:0.200,0.800,1.000,0.120,0.230", "aim:0.300,0.500,1.000,0.100,0.200",
        "weapons:0.950,0.150,1.000,0.080,0.120", "pause:0.500,0.040,1.000,0.060,0.100", "timer:0.150,0.600,1.000,0.080,0.120",
        "bounce:0.250,0.600,1.000,0.080,0.120" };

    private static ControlsLayoutView findEditor(android.view.View v) {
        if (v instanceof ControlsLayoutView) return (ControlsLayoutView) v;
        if (v instanceof android.view.ViewGroup) {
            for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) {
                ControlsLayoutView r = findEditor(((android.view.ViewGroup) v).getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private static void touch(android.view.View v, int action, float x, float y) {
        android.view.MotionEvent e = android.view.MotionEvent.obtain(1L, 1L, action, x, y, 0);
        v.dispatchTouchEvent(e);
        e.recycle();
    }

    /**
     * Controls layout (issue #21): the editor opens over the game and takes the engine's places; a drag moves the fire control and
     * Save keeps the layout, which the next game gets as --touch-layout. Cancel keeps the saved layout.
     */
    @Test
    public void gameControlsLayout_dragSaves_launchPassesIt_cancelKeepsIt() throws Exception {
        java.io.File net = new java.io.File(app().getFilesDir(), "net.cfg");
        try {
            writeRole("host");
            Store.saveOptions(app(), new Options());
            GameActivity g = gameActivity();
            g.pauseMenuAction(GameActivity.PAUSE_OPTIONS);
            TestSupport.settle(100);
            android.app.AlertDialog options = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            findText(options.getWindow().getDecorView(), "Controls layout").performClick();
            TestSupport.settle(100);
            ControlsLayoutView editor = findEditor(g.getWindow().getDecorView());
            assertNotNull("the editor opens over the game", editor);
            for (String entry : ENGINE_PLACES) g.touchLayoutReport(entry);
            TestSupport.settle(100);
            editor.layout(0, 0, 1280, 720);
            android.widget.Button save = (android.widget.Button) findText(editor, "Save");
            assertTrue("Save is on once the engine has reported its places", save.isEnabled());

            // the fire control is centred at 0.9, 0.8 of the screen: drag it 200 px left and 100 px up
            float cx = 0.9f * 1280, cy = 0.8f * 720;
            touch(editor, android.view.MotionEvent.ACTION_DOWN, cx, cy);
            touch(editor, android.view.MotionEvent.ACTION_MOVE, cx - 100, cy - 50);
            touch(editor, android.view.MotionEvent.ACTION_MOVE, cx - 200, cy - 100);
            touch(editor, android.view.MotionEvent.ACTION_UP, cx - 200, cy - 100);
            save.performClick();
            TestSupport.settle(100);
            assertNull("the editor closes on Save", findEditor(g.getWindow().getDecorView()));

            Options saved = Store.loadOptions(app());
            java.util.Map<String, double[]> places = TouchLayout.parse(saved.touchLayout);
            assertTrue("the saved layout has the fire control", places.containsKey("fire"));
            assertEquals("fire moved left", 0.9 - 200 / 1280.0, places.get("fire")[0], 0.002);
            assertEquals("fire moved up", 0.8 - 100 / 720.0, places.get("fire")[1], 0.002);
            assertEquals("the size is kept", 1.0, places.get("fire")[2], 0.002);
            java.util.List<String> args = java.util.Arrays.asList(g.engineOptions());
            assertTrue("the next game is launched with --touch-layout", args.contains("--touch-layout"));
            assertEquals("the layout is the saved one", saved.touchLayout, args.get(args.indexOf("--touch-layout") + 1));

            // Cancel: a drag is thrown away and the saved layout stays
            g.pauseMenuAction(GameActivity.PAUSE_OPTIONS);
            TestSupport.settle(100);
            android.app.AlertDialog again = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            findText(again.getWindow().getDecorView(), "Controls layout").performClick();
            TestSupport.settle(100);
            editor = findEditor(g.getWindow().getDecorView());
            assertNotNull("the editor opens again", editor);
            for (String entry : ENGINE_PLACES) g.touchLayoutReport(entry);
            TestSupport.settle(100);
            editor.layout(0, 0, 1280, 720);
            touch(editor, android.view.MotionEvent.ACTION_DOWN, 0.5f * 1280, 0.04f * 720);
            touch(editor, android.view.MotionEvent.ACTION_MOVE, 0.2f * 1280, 0.5f * 720);
            touch(editor, android.view.MotionEvent.ACTION_UP, 0.2f * 1280, 0.5f * 720);
            findText(editor, "Cancel").performClick();
            TestSupport.settle(100);
            assertNull("the editor closes on Cancel", findEditor(g.getWindow().getDecorView()));
            assertEquals("Cancel keeps the saved layout", saved.touchLayout, Store.loadOptions(app()).touchLayout);
            System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": controls layout drag, save, launch args and cancel ok");
        } finally {
            net.delete();
        }
    }

    @Test
    public void netLobbyActivity_chooser_neitherHostingNorJoined() throws Exception {
        TestSupport.installData(app());
        String text = launch(NetLobbyActivity.class, null, 600);
        assertTrue("chooser shows Host and Join: " + text, text.contains("Host network game") && text.contains("Join by address") && text.contains("Join to play") && text.contains("Join to watch"));
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": NetLobbyActivity chooser ok");
    }

    /** A game running on the host: the phone of this test ("Fairphone", id 2) is away, or joins the game (not in it), or plays in it. */
    private static String runningState(boolean youAway, boolean hostHere) {
        return "{\"t\":\"state\",\"state\":\"playing\",\"host\":\"Pixel\",\"proto\":1,\"build\":\"0.3.0-mp2\",\"you\":2,"
                + "\"players\":[{\"id\":1,\"name\":\"Pixel\",\"host\":true,\"ready\":true,\"fg\":true,\"away\":" + (hostHere ? "false" : "true")
                + ",\"awayLeftMs\":90000,\"teams\":[{\"name\":\"Red Team\",\"color\":0,\"level\":0}]},"
                + "{\"id\":2,\"name\":\"Fairphone\",\"host\":false,\"ready\":true,\"fg\":true,\"away\":" + (youAway ? "true" : "false")
                + ",\"awayLeftMs\":60000,\"teams\":[{\"name\":\"Blue Team\",\"color\":1,\"level\":0}]}],"
                + "\"canStart\":false,\"game\":1,\"watching\":0}";
    }

    /** The Watch the game button (issue 22, device): offered for a joiner that is away, gave up, or arrived late, and for the host when its seat is out. */
    @Test
    public void netLobbyActivity_watchButtonVisibility() throws Exception {
        TestSupport.installData(app());
        // away: the Rejoin button and the Watch button are both there
        netState(runningState(true, true), false);
        NetSession.inGame = false;
        String away = launch(NetLobbyActivity.class, null, 600);
        assertTrue("away: Rejoin and Watch both offered: " + away, away.contains("Rejoin the game") && away.contains("Watch the game"));
        TestSupport.resetStatics();
        // a joiner who arrived late (the game runs, this phone is not in it)
        netState(runningState(false, true), false);
        NetSession.inGame = false;
        String late = launch(NetLobbyActivity.class, null, 600);
        assertTrue("late joiner: Watch offered: " + late, late.contains("Watch the game") && late.contains("State: game running"));
        TestSupport.resetStatics();
        // playing in it: no Watch button
        netState(runningState(false, true), false);
        NetSession.inGame = true;
        String playing = launch(NetLobbyActivity.class, null, 600);
        assertFalse("playing in the game: no Watch: " + playing, playing.contains("Watch the game"));
        TestSupport.resetStatics();
        // the host, its own seat out of the game: Watch offered (it watches over its loopback relay)
        netState(runningState(false, false), true);
        NetSession.inGame = false;
        NetSession.gaveUp = true;
        String host = launch(NetLobbyActivity.class, null, 600);
        assertTrue("host out of the game: Watch offered: " + host, host.contains("Watch the game"));
        TestSupport.resetStatics();
        // the host after the game ended: no Watch, even when a line on the screen offers it (a stale tap must not connect)
        netState(LOBBY_WATCH.replace("\"you\":3", "\"you\":1"), true);
        NetSession.notice = "The game ended. You can watch.";
        String ended = launch(NetLobbyActivity.class, null, 600);
        assertFalse("the host after the end: no Watch button: " + ended, ended.contains("Watch the game"));
        TestSupport.resetStatics();
        // a watching phone never offers it again
        netState(runningState(true, true), false);
        NetSession.watching = true;
        NetSession.inGame = true;      // a watcher in the running game: "Watching", not a second Watch button
        String w = launch(NetLobbyActivity.class, null, 600);
        assertFalse("already watching: no second Watch button: " + w, w.contains("Watch the game"));
        TestSupport.resetStatics();
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": NetLobbyActivity watch button visibility ok");
    }

    /** The lobby before the relay has said what the game is (a rejoin starts with no state): no "lobby (setting up)", no Start game. */
    @Test
    public void netLobbyActivity_hostWithoutStateShowsNoStaleLobby() throws Exception {
        TestSupport.installData(app());
        netState(LOBBY, true);
        java.lang.reflect.Field f = NetSession.class.getDeclaredField("state");
        f.setAccessible(true);
        f.set(null, null);
        String text = launch(NetLobbyActivity.class, null, 600);
        assertTrue("connecting, not a lobby: " + text, text.contains("State: connecting..."));
        assertFalse("no stale lobby label: " + text, text.contains("lobby (setting up)"));
        assertFalse("no Start game while the state is unknown: " + text, text.contains("Start game"));
        TestSupport.resetStatics();
        // a game running on this host: no Start game either
        netState(runningState(false, true).replace("\"you\":2", "\"you\":1"), true);
        NetSession.inGame = true;
        String running = launch(NetLobbyActivity.class, null, 600);
        assertFalse("a running game has no Start game: " + running, running.contains("Start game"));
        TestSupport.resetStatics();
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": NetLobbyActivity no stale lobby ok");
    }

    /** The offer rule as a truth table (NetSession.offerWatch). */
    @Test
    public void netSession_offerWatchRule() {
        assertFalse(NetSession.offerWatch(true, false, true, true, ""));                 // already watching
        assertTrue(NetSession.offerWatch(false, false, true, false, ""));                // away or late: game runs, not in it
        assertFalse(NetSession.offerWatch(false, true, true, false, ""));                // playing in it
        assertTrue(NetSession.offerWatch(false, true, true, true, ""));                  // gave up
        assertFalse(NetSession.offerWatch(false, false, false, false, ""));              // no game, nothing offered
        assertTrue(NetSession.offerWatch(false, false, false, false, "The game ended. You can watch."));   // a line that offers it
    }

    /**
     * A host that watches its own running game: its seat's lobby link stays open (the relay closes every phone when the host's lobby link
     * closes in the setup), and the watching link is a second connection that says it watches and is not a host.
     */
    @Test
    public void netSession_hostWatchKeepsTheSeatOpen() throws Exception {
        TestSupport.installData(app());
        try (ServerSocket relay = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            relay.setSoTimeout(5000);
            NetSession.connect(app(), "127.0.0.1", relay.getLocalPort(), "Pixel", true);
            Socket seatSock = relay.accept();
            try {
                RelayClient seat = NetSession.client;
                netState(runningState(false, false), true);
                NetSession.watchOwnHostedGame(app(), "Pixel", relay.getLocalPort());
                Socket watchSock = relay.accept();
                String hello = new BufferedReader(new InputStreamReader(watchSock.getInputStream(), StandardCharsets.UTF_8)).readLine();
                assertTrue("the watching link says it watches and is not the host: " + hello,
                        hello.contains("\"watch\":true") && hello.contains("\"host\":false"));
                assertSame("the host's seat is still the client after Watch", seat, NetSession.client);
                assertTrue("the host is watching", NetSession.watching);
                seatSock.setSoTimeout(300);
                int b;
                try {
                    b = seatSock.getInputStream().read();
                } catch (SocketTimeoutException stillOpen) {
                    b = -2;
                }
                assertTrue("the host's lobby link was not closed (read " + b + ")", b != -1);
                watchSock.close();
            } finally {
                seatSock.close();
                NetSession.leave();
                TestSupport.resetStatics();
            }
        }
    }

    /** A Watch tapped after the game has ended connects nothing: the seat stays, no second link is accepted, nothing is watched. */
    @Test
    public void netSession_hostWatchAfterTheGameEndedConnectsNothing() throws Exception {
        TestSupport.installData(app());
        try (ServerSocket relay = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            relay.setSoTimeout(5000);
            NetSession.connect(app(), "127.0.0.1", relay.getLocalPort(), "Pixel", true);
            Socket seatSock = relay.accept();
            try {
                RelayClient seat = NetSession.client;
                netState(LOBBY_WATCH.replace("\"you\":3", "\"you\":1"), true);       // the relay says the game is over (setup)
                try {
                    NetSession.watchOwnHostedGame(app(), "Pixel", relay.getLocalPort());
                    fail("a game that ended is not watched");
                } catch (NetSession.Cancelled expected) {
                    // nothing to connect: the ended state is shown instead
                }
                relay.setSoTimeout(300);
                try {
                    relay.accept();
                    fail("a Watch after the end opened a link to the relay");
                } catch (SocketTimeoutException none) {
                    // no link was opened
                }
                assertSame("the seat is untouched", seat, NetSession.client);
                assertFalse("nothing is watched", NetSession.watching);
            } finally {
                seatSock.close();
                NetSession.leave();
                TestSupport.resetStatics();
            }
        }
    }

    // Phase 5: finding hosts ----------------------------------------------------------------------------------

    private static org.hedgewars.android.net.HostAdvert.Found advert(String service, String host, int port, String compat, String version, String data) {
        java.util.Map<String, String> t = org.hedgewars.android.net.HostAdvert.txt(version, compat, 60, service, 8766, data);
        java.util.Map<String, byte[]> raw = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, String> e : t.entrySet()) raw.put(e.getKey(), e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return org.hedgewars.android.net.HostAdvert.parse(service, host, port, raw);
    }

    private static void buttons(android.view.View v, String text, java.util.List<android.widget.Button> out) {
        if (v instanceof android.widget.Button && text.contentEquals(((android.widget.Button) v).getText())) out.add((android.widget.Button) v);
        if (v instanceof android.view.ViewGroup) {
            for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) buttons(((android.view.ViewGroup) v).getChildAt(i), text, out);
        }
    }

    /** A fake search that reports two hosts at once: one that matches this phone, one with another game version. */
    private static void fakeHosts(final org.hedgewars.android.net.HostAdvert.Found... found) {
        NetSession.discoveryFactory = c -> new HostDiscovery() {
            @Override public void start(Listener l) {
                l.status("Searching for hosts on this Wi-Fi...", false);
                l.hosts(java.util.Arrays.asList(found));
            }
            @Override public void stop() { }
        };
    }

    @Test
    public void netLobbyActivity_discoveredHosts_listedAndIncompatibleDisabled() throws Exception {
        TestSupport.installData(app());
        String digest = NetSession.dataDigest(app());
        String ver = Diag.versionName(app());
        String C = org.hedgewars.android.net.Compat.VALUE;
        fakeHosts(advert("Fairphone", "127.0.0.1", 1, C, ver, digest),
                advert("OldPhone", "192.168.1.60", 46633, "hwnet-60-sim0", "0.2.9", digest),
                advert("OtherData", "192.168.1.61", 46633, C, ver, "ffffffffffffffffffffffffffffffff"));
        Activity a = Robolectric.buildActivity(NetLobbyActivity.class, null).create().start().postCreate(null).resume().visible().get();
        TestSupport.settle(800);
        TestSupport.layout(a);
        android.view.View root = a.getWindow().getDecorView();
        String text = TestSupport.texts(root);
        assertTrue("the three hosts are listed: " + text, text.contains("Fairphone") && text.contains("OldPhone") && text.contains("OtherData"));
        assertTrue("incompatible hosts show the reason: " + text, text.contains("Cannot join: Different game versions") && text.contains("Cannot join: Game data differs"));
        java.util.List<android.widget.Button> play = new java.util.ArrayList<>(), watch = new java.util.ArrayList<>();
        buttons(root, "Join to play", play);
        buttons(root, "Join to watch", watch);
        // the typed-address buttons (left column, so first in the view tree) and three host rows; the compatible host comes first of those
        assertTrue("one play and one watch button per host plus the address ones: " + play.size(), play.size() == 4 && watch.size() == 4);
        assertTrue("the compatible host can be joined", play.get(1).isEnabled() && watch.get(1).isEnabled());
        assertTrue("the incompatible hosts cannot", !play.get(2).isEnabled() && !play.get(3).isEnabled() && !watch.get(2).isEnabled() && !watch.get(3).isEnabled());
        assertTrue("the typed address entry stays", text.contains("Join by address") && play.get(0).isEnabled());
        // one tap on the compatible host connects (nothing listens on 127.0.0.1:1, so the failure message proves the attempt)
        play.get(1).performClick();
        TestSupport.settle(2100);
        String trying = TestSupport.texts(a.getWindow().getDecorView());
        assertTrue("the join says it tries again: " + trying, trying.contains("Trying again (2 of 3)..."));
        TestSupport.settle(4500);                       // three attempts, 1.5 s apart
        String after = TestSupport.texts(a.getWindow().getDecorView());
        assertTrue("the tap tried to reach the host: " + after, after.contains("Could not reach 127.0.0.1:1"));
        assertTrue("the host picked is remembered",
                app().getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE).getString("lasthost", "").equals("Fairphone"));
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": discovered hosts list ok");
    }

    @Test
    public void netLobbyActivity_noHostFound_offersSearchAgainAndTypedAddress() throws Exception {
        TestSupport.installData(app());
        NetSession.discoveryFactory = c -> new HostDiscovery() {
            @Override public void start(Listener l) { l.status("No host found on this Wi-Fi.", true); }
            @Override public void stop() { }
        };
        Activity a = Robolectric.buildActivity(NetLobbyActivity.class, null).create().start().postCreate(null).resume().visible().get();
        TestSupport.settle(800);
        String text = TestSupport.texts(a.getWindow().getDecorView());
        assertTrue(text, text.contains("No host found on this Wi-Fi.") && text.contains("Search again") && text.contains("Join by address"));
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": no host found ok");
    }

    @Test
    public void netLobbyActivity_localNetworkPermissionDenied_fallsBackToTypedAddress() throws Exception {
        TestSupport.installData(app());
        final int[] started = { 0 };
        NetSession.discoveryFactory = c -> new HostDiscovery() {
            @Override public void start(Listener l) { started[0]++; }
            @Override public void stop() { }
        };
        LocalNetwork.forceKnown = true;
        LocalNetwork.forceGranted = false;
        NetLobbyActivity a = Robolectric.buildActivity(NetLobbyActivity.class, null).create().start().postCreate(null).resume().visible().get();
        TestSupport.settle(300);
        assertTrue("the permission is asked for", org.robolectric.Shadows.shadowOf(a).getLastRequestedPermission() != null
                && org.robolectric.Shadows.shadowOf(a).getLastRequestedPermission().requestedPermissions[0].equals(LocalNetwork.PERMISSION));
        assertTrue("no search runs before the answer", started[0] == 0);
        a.onRequestPermissionsResult(LocalNetwork.REQUEST, new String[] { LocalNetwork.PERMISSION }, new int[] { android.content.pm.PackageManager.PERMISSION_DENIED });
        TestSupport.settle(300);
        TestSupport.layout(a);
        android.view.View root = a.getWindow().getDecorView();
        String text = TestSupport.texts(root);
        assertTrue("the refusal is explained and the typed address offered: " + text,
                text.contains("permission, which was not given") && text.contains("Type the host's address") && text.contains("Join by address"));
        assertTrue("still no search", started[0] == 0);
        java.util.List<android.widget.Button> play = new java.util.ArrayList<>();
        buttons(root, "Join to play", play);
        assertTrue("the typed-address join is enabled", play.size() == 1 && play.get(0).isEnabled());
        // granted later (the person changed it in Settings): Search again works
        LocalNetwork.forceGranted = true;
        java.util.List<android.widget.Button> again = new java.util.ArrayList<>();
        buttons(root, "Search again", again);
        assertTrue("Search again is offered", again.size() == 1);
        again.get(0).performClick();
        TestSupport.settle(600);
        assertTrue("the search starts once the permission is there", started[0] == 1);
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": local network permission denied ok");
    }

    @Test
    public void netLobbyActivity_networkToUseOffered_vpnHintWhenAVpnIsOn() throws Exception {
        TestSupport.installData(app());
        NetworkPicker.save(app(), NetworkPicker.AUTO);
        NetworkPicker.interfaces = () -> java.util.Arrays.asList(
                new NetworkPicker.Iface("wlan0", "192.168.1.62", "Wi-Fi", 24),
                new NetworkPicker.Iface("tun0", "100.64.0.4", "VPN", 32));
        try {
            Activity a = Robolectric.buildActivity(NetLobbyActivity.class, null).create().start().postCreate(null).resume().visible().get();
            TestSupport.settle(800);
            TestSupport.layout(a);
            String text = TestSupport.texts(a.getWindow().getDecorView());
            assertTrue("the Network to use option is shown: " + text, text.contains("Network to use") && text.contains("Automatic"));
            android.widget.TextView hint = findText(a.getWindow().getDecorView(), NetworkPicker.VPN_HINT);
            assertTrue("the VPN hint is shown with Automatic: " + text, hint != null && hint.getVisibility() == android.view.View.VISIBLE);
        } finally {
            NetworkPicker.interfaces = null;
            NetworkPicker.save(app(), NetworkPicker.AUTO);
        }
        NetworkPicker.interfaces = () -> java.util.Arrays.asList(new NetworkPicker.Iface("wlan0", "192.168.1.62", "Wi-Fi", 24));
        try {
            Activity a = Robolectric.buildActivity(NetLobbyActivity.class, null).create().start().postCreate(null).resume().visible().get();
            TestSupport.settle(800);
            String text = TestSupport.texts(a.getWindow().getDecorView());
            android.widget.TextView hint = findText(a.getWindow().getDecorView(), NetworkPicker.VPN_HINT);
            assertTrue("the option is there: " + text, text.contains("Network to use"));
            assertTrue("no VPN, no hint (the text stays in the tree, hidden): " + text, hint != null && hint.getVisibility() == android.view.View.GONE);
        } finally {
            NetworkPicker.interfaces = null;
        }
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": network to use and VPN hint ok");
    }

    @Test
    public void netSession_locksFollowHosting() throws Exception {
        NetSession.acquireLocks(app());
        java.lang.reflect.Field wf = NetSession.class.getDeclaredField("wifi");
        wf.setAccessible(true);
        java.lang.reflect.Field kf = NetSession.class.getDeclaredField("wake");
        kf.setAccessible(true);
        assertTrue("both locks held", ((android.net.wifi.WifiManager.WifiLock) wf.get(null)).isHeld() && ((android.os.PowerManager.WakeLock) kf.get(null)).isHeld());
        NetSession.releaseLocks();
        assertFalse("released when not hosting", ((android.net.wifi.WifiManager.WifiLock) wf.get(null)).isHeld() || ((android.os.PowerManager.WakeLock) kf.get(null)).isHeld());
        NetSession.acquireLocks(app());
        HostService.relay = new org.hedgewars.android.net.Relay(new org.hedgewars.android.net.Relay.Config());
        NetSession.releaseLocks();                              // the host's own link ended, the relay still runs: keep the locks
        assertTrue("kept while the relay runs", ((android.net.wifi.WifiManager.WifiLock) wf.get(null)).isHeld() && ((android.os.PowerManager.WakeLock) kf.get(null)).isHeld());
        HostService.relay = null;
        NetSession.releaseLocks();
        assertFalse("released after hosting stopped", ((android.net.wifi.WifiManager.WifiLock) wf.get(null)).isHeld() || ((android.os.PowerManager.WakeLock) kf.get(null)).isHeld());
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": locks ok");
    }


    @Test
    public void netLobbyActivity_joinedState() throws Exception {
        TestSupport.installData(app());
        netState(LOBBY, false);
        String text = launch(NetLobbyActivity.class, null, 600);
        assertTrue("joined lobby lists the phones and teams: " + text,
                text.contains("Joined") && text.contains("Fairphone") && text.contains("Blue Team") && text.contains("Ready") && text.contains("Leave"));
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": NetLobbyActivity joined state ok");
    }

    private static final String PLAYING_AWAY = "{\"t\":\"state\",\"state\":\"playing\",\"host\":\"Pixel\",\"proto\":1,\"build\":\"0.3.0-tv14\",\"you\":2,"
            + "\"players\":[{\"id\":1,\"name\":\"Pixel\",\"host\":true,\"ready\":true,\"fg\":true,\"away\":false,"
            + "\"teams\":[{\"name\":\"Red Team\",\"color\":0,\"level\":0}]},"
            + "{\"id\":2,\"name\":\"Fairphone\",\"host\":false,\"ready\":false,\"fg\":true,\"away\":true,\"awayLeftMs\":90000,"
            + "\"teams\":[{\"name\":\"Blue Team\",\"color\":1,\"level\":3}]}],\"canStart\":false,\"game\":1}";

    /** A phone that left the game or lost the connection can rejoin: from its seat in the lobby, or from the note it kept (after a restart). */
    @Test
    public void netLobbyActivity_rejoin() throws Exception {
        TestSupport.installData(app());
        // Away, still connected (Back > Leave the game): the lobby offers the seat back.
        netState(PLAYING_AWAY, false);
        String text = launch(NetLobbyActivity.class, null, 600);
        assertTrue("the away phone is offered its seat: " + text, text.contains("You are away from the game") && text.contains("Rejoin the game") && text.contains("Fairphone (you)"));
        assertTrue("and the list says who is away and for how long: " + text, text.contains("away") && text.contains("sitting out") && text.contains("90 s"));
        // The window ran out: the phone is told so and offered to watch.
        NetSession.gaveUp = true;
        netState(PLAYING_AWAY.replace("\"away\":true,\"awayLeftMs\":90000", "\"away\":false,\"gaveUp\":true"), false);
        text = launch(NetLobbyActivity.class, null, 600);
        assertTrue("given up: " + text, text.contains("You were away too long") && text.contains("Watch the game") && !text.contains("You are away from the game"));
        NetSession.gaveUp = false;
        // Not connected (Wi-Fi lost, app restarted): the note kept at join brings up the offer on the chooser.
        NetSession.joined = false;
        NetSession.hosting = false;
        NetSession.state = null;
        NetSession.clearRejoin(app());
        assertTrue("no note, no offer", !launch(NetLobbyActivity.class, null, 600).contains("Rejoin your game"));
        NetSession.saveRejoin(app(), "192.168.1.20", 46633, "Fairphone", "0123456789abcdef0123456789abcdef", false);
        NetSession.RejoinNote n = NetSession.loadRejoin(app());
        assertTrue("the note round trips", n != null && n.host.equals("192.168.1.20") && n.port == 46633 && n.name.equals("Fairphone") && n.token.equals("0123456789abcdef0123456789abcdef"));
        text = launch(NetLobbyActivity.class, null, 600);
        assertTrue("the chooser offers the game back: " + text, text.contains("Rejoin your game") && text.contains("Fairphone") && text.contains("192.168.1.20:46633")
                && text.contains("Rejoin the game") && text.contains("Forget it") && text.contains("Host network game"));
        NetSession.rejoinRefusal = "rejoin-late";
        text = launch(NetLobbyActivity.class, null, 600);
        assertTrue("after a late attempt it says why and offers watching: " + text, text.contains("You were away too long") && text.contains("Join to watch"));
        NetSession.rejoinRefusal = "";
        NetSession.clearRejoin(app());
        assertTrue("forgotten", NetSession.loadRejoin(app()) == null);
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": NetLobbyActivity rejoin offers ok");
    }

    @Test
    public void netLobbyActivity_hostState() throws Exception {
        TestSupport.installData(app());
        netState(LOBBY.replace("\"canStart\":false", "\"canStart\":true").replace("\"you\":2", "\"you\":1"), true);
        String text = launch(NetLobbyActivity.class, null, 600);
        assertTrue("host lobby shows Start and the address block: " + text,
                text.contains("Hosting") && text.contains("Start game") && text.contains("TV viewer address") && text.contains("Stop hosting"));
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": NetLobbyActivity host state ok");
    }

    @Test
    public void netLobbyActivity_hostShowsGamePassword() throws Exception {
        TestSupport.installData(app());
        NetSession.hostPin = NetSession.randomPin();
        assertTrue("a random PIN is always 4 digits: " + NetSession.hostPin, NetSession.hostPin.matches("\\d{4}"));
        NetSession.hostPin = "0427";
        netState(LOBBY.replace("\"canStart\":false", "\"canStart\":true").replace("\"you\":2", "\"you\":1"), true);
        String text = launch(NetLobbyActivity.class, null, 600);
        assertTrue("the host lobby shows the 4-digit password, leading zero kept: " + text, text.contains("Game password: 0427") && text.contains("Change"));
        NetSession.hostPin = null;
        String off = launch(NetLobbyActivity.class, null, 600);
        assertTrue("with the setting off the host lobby says so: " + off, off.contains("Game password: off (anyone on this Wi-Fi can join)"));
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": host lobby game password ok");
    }

    @Test
    public void netLobbyActivity_defaultsToNoPassword_andTurningItOnShowsFourDigits() throws Exception {
        TestSupport.installData(app());
        assertTrue("the game password is off by default", !NetSession.pinSetting(app()));
        assertTrue("no password without the setting", NetSession.beginHostPin(app()) == null);
        NetSession.setPinOn(app(), true);
        String pin = NetSession.beginHostPin(app());
        assertTrue("turned on, a 4-digit PIN is made: " + pin, pin != null && pin.matches("\\d{4}"));
        NetSession.setPinOn(app(), false);
        NetSession.hostPin = null;
        netState(LOBBY.replace("\"canStart\":false", "\"canStart\":true").replace("\"you\":2", "\"you\":1"), true);
        String off = launch(NetLobbyActivity.class, null, 600);
        assertTrue("by default the host lobby says anyone can join: " + off, off.contains("Game password: off (anyone on this Wi-Fi can join)"));
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": game password off by default ok");
    }

    private static android.widget.EditText nameField(View v) {
        if (v instanceof android.widget.EditText && "Player name".contentEquals(((android.widget.EditText) v).getHint())) return (android.widget.EditText) v;
        if (v instanceof android.view.ViewGroup) {
            for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) {
                android.widget.EditText r = nameField(((android.view.ViewGroup) v).getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    @Test
    public void netLobbyActivity_defaultNameIsRandom_andTheDiceChangesIt() throws Exception {
        TestSupport.installData(app());
        java.util.List<String> names = PlayerNames.load(app());
        assertTrue("the names asset ships with the app: " + names.size(), names.size() > 1);
        Activity a = Robolectric.buildActivity(NetLobbyActivity.class, null).create().start().postCreate(null).resume().visible().get();
        android.view.View root = a.getWindow().getDecorView();
        android.widget.EditText f = nameField(root);
        assertTrue("the name field exists", f != null);
        String first = f.getText().toString();
        assertTrue("the name is not empty and comes from the list: " + first, !first.isEmpty() && names.contains(first));
        assertTrue("it is remembered", first.equals(app().getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE).getString("netname", "")));
        android.view.View dice = TestSupport.find(root, "Pick another name");
        assertTrue("the dice button exists", dice instanceof android.widget.Button && "\uD83C\uDFB2".contentEquals(((android.widget.Button) dice).getText()));
        dice.performClick();
        String second = f.getText().toString();
        assertTrue("the dice picks a different name from the list: " + first + " -> " + second, !second.equals(first) && names.contains(second));
        assertTrue("and saves it", second.equals(app().getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE).getString("netname", "")));
        a.finish();
        Activity b = Robolectric.buildActivity(NetLobbyActivity.class, null).create().start().postCreate(null).resume().visible().get();
        assertTrue("the name stays the same on the next launch", second.equals(nameField(b.getWindow().getDecorView()).getText().toString()));
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": default name and dice ok");
    }

    @Test
    public void netLobbyActivity_upgraderWithStoredDeviceModelGetsARandomName_andTypingIsSavedAtOnce() throws Exception {
        TestSupport.installData(app());
        java.util.List<String> names = PlayerNames.load(app());
        android.content.SharedPreferences sp = app().getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().remove("netname_chosen").putString("netname", android.os.Build.MODEL).apply();    // tv12 saved this on pause
        Activity a = Robolectric.buildActivity(NetLobbyActivity.class, null).create().start().postCreate(null).resume().visible().get();
        android.widget.EditText f = nameField(a.getWindow().getDecorView());
        assertTrue("a stored device model is replaced by a name from the list: " + f.getText(), names.contains(f.getText().toString()));
        assertTrue("and it is not marked as chosen", !sp.getBoolean("netname_chosen", true));
        f.setText("Typed Name");
        assertTrue("a typed name is saved on the change, without a pause", "Typed Name".equals(sp.getString("netname", "")));
        assertTrue("and marked as the player's own", sp.getBoolean("netname_chosen", false));
        f.setText("Typed Name 2");
        android.view.View dice = TestSupport.find(a.getWindow().getDecorView(), "Pick another name");
        dice.performClick();
        assertTrue("the dice result is saved as chosen", names.contains(sp.getString("netname", "")) && sp.getBoolean("netname_chosen", false));
        a.finish();
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": upgrader name and typed-name save ok");
    }

    @Test
    public void netLobbyActivity_refusedRememberedPasswordSaysItChanged_typedOneSaysWrong() throws Exception {
        TestSupport.installData(app());
        for (boolean remembered : new boolean[] { true, false }) {
            NetSession.lastHostName = "192.0.2.7";
            NetSession.lastPort = 46633;
            NetSession.sentPin = "1234";
            NetSession.sentPinKey = "192.0.2.7:46633";
            NetSession.sentPinRemembered = remembered;
            NetSession.refusedCode = "password";
            Activity a = Robolectric.buildActivity(NetLobbyActivity.class, null).create().start().postCreate(null).resume().visible().get();
            TestSupport.layout(a);
            String text = TestSupport.texts(a.getWindow().getDecorView());
            java.util.List<android.widget.EditText> nums = new java.util.ArrayList<>();
            numericFields(a.getWindow().getDecorView(), nums);
            if (remembered) assertTrue("a refused remembered password: " + text, text.contains("The game password has changed. Ask the host for the new one.") && !text.contains("Wrong password"));
            else assertTrue("a refused typed password: " + text, text.contains("Wrong password. Try again.") && !text.contains("has changed"));
            assertTrue("the entry is empty", nums.size() == 1 && nums.get(0).getText().length() == 0);
            a.finish();
        }
        NetSession.sentPin = "";
        NetSession.sentPinRemembered = false;
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": remembered password refusal ok");
    }

    private static void numericFields(android.view.View v, java.util.List<android.widget.EditText> out) {
        if (v instanceof android.widget.EditText && (((android.widget.EditText) v).getInputType() & android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_NUMBER)
            out.add((android.widget.EditText) v);
        if (v instanceof android.view.ViewGroup) {
            for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) numericFields(((android.view.ViewGroup) v).getChildAt(i), out);
        }
    }

    @Test
    public void netLobbyActivity_hostWithPassword_asksForTheFourDigits() throws Exception {
        TestSupport.installData(app());
        String digest = NetSession.dataDigest(app());
        String ver = Diag.versionName(app());
        java.util.Map<String, String> t = org.hedgewars.android.net.HostAdvert.txt(ver, org.hedgewars.android.net.Compat.VALUE, 60, "Locked", 8766, digest, true);
        java.util.Map<String, byte[]> raw = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, String> e : t.entrySet()) raw.put(e.getKey(), e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        org.hedgewars.android.net.HostAdvert.Found h = org.hedgewars.android.net.HostAdvert.parse("Locked", "127.0.0.1", 1, raw);
        assertTrue("the advert says a password is asked", h.pinRequired);
        fakeHosts(h);
        Activity a = Robolectric.buildActivity(NetLobbyActivity.class, null).create().start().postCreate(null).resume().visible().get();
        TestSupport.settle(800);
        TestSupport.layout(a);
        android.view.View root = a.getWindow().getDecorView();
        assertTrue("the list marks the host: " + TestSupport.texts(root), TestSupport.texts(root).contains("Asks for a game password"));
        java.util.List<android.widget.Button> play = new java.util.ArrayList<>();
        buttons(root, "Join to play", play);
        play.get(1).performClick();                     // the host row (the typed-address button comes first)
        TestSupport.settle(300);
        TestSupport.layout(a);
        root = a.getWindow().getDecorView();
        java.util.List<android.widget.EditText> nums = new java.util.ArrayList<>();
        numericFields(root, nums);
        assertTrue("a numeric 4-digit entry appears instead of connecting: " + TestSupport.texts(root), nums.size() == 1
                && TestSupport.texts(root).contains("This host asks for a game password."));
        assertTrue("it takes at most 4 characters", nums.get(0).getFilters().length > 0);
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": join asks for the game password ok");
    }

    /** Back asks before it leaves a lobby, and leaves at once when there is nothing to leave. */
    @Test
    public void netLobbyActivity_backPressed() throws Exception {
        TestSupport.installData(app());
        netState(LOBBY, false);
        org.robolectric.android.controller.ActivityController<NetLobbyActivity> ctl =
                Robolectric.buildActivity(NetLobbyActivity.class, null);
        NetLobbyActivity a = ctl.create().start().postCreate(null).resume().visible().get();
        TestSupport.layout(a);
        a.onBackPressed();
        TestSupport.settle(200);
        assertFalse("the screen stays open while the question is up", a.isFinishing());
        assertNotNull("a confirmation dialog is shown", org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
        ctl.pause().stop().destroy();
        TestSupport.resetStatics();
        NetLobbyActivity b = Robolectric.buildActivity(NetLobbyActivity.class, null).create().start().resume().visible().get();
        b.onBackPressed();
        assertTrue("without a connection Back closes the screen", b.isFinishing());
    }

    /** The host screen shows what the ticker found: a TV address, and for each TV its round trip and queue depth. */
    @Test
    public void netLobbyActivity_hostState_tvSection() throws Exception {
        TestSupport.installData(app());
        netState(LOBBY.replace("\"you\":2", "\"you\":1"), true);
        java.util.List<String> lines = java.util.Arrays.asList(
                "/192.168.1.106:36617: round trip 12.3 ms (average 15.0, best 9.0, worst 41.0, 5 pongs of 5 pings), 3 frames behind (deepest 7), watching for 12 s");
        NetSession.hostInfo = new NetSession.HostInfo(java.util.Arrays.asList("192.168.1.57"), 8766, 1, "", "Game 1: 4 turns, log 120 messages", lines);
        String text = launch(NetLobbyActivity.class, null, 600);
        assertTrue("TV address is shown: " + text, text.contains("ws://192.168.1.57:8766") && text.contains("1 TV watching"));
        assertTrue("per-TV round trip and queue depth are shown: " + text,
                text.contains("192.168.1.106") && text.contains("round trip 12.3 ms") && text.contains("3 frames behind"));
        // Without a snapshot (before the first tick) the screen says so instead of claiming there is no Wi-Fi.
        NetSession.hostInfo = null;
        String early = launch(NetLobbyActivity.class, null, 300);
        assertTrue("before the first snapshot: " + early, early.contains("Looking for the Wi-Fi address") && !early.contains("No Wi-Fi"));
        // A TV viewer port that could not be opened is shown as a problem.
        NetSession.hostInfo = new NetSession.HostInfo(java.util.Arrays.asList("192.168.1.57"), -1, 0, "The TV viewer port 8766 could not be opened", "", new java.util.ArrayList<String>());
        String bad = launch(NetLobbyActivity.class, null, 300);
        assertTrue("viewer port error shown: " + bad, bad.contains("could not be opened"));
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": NetLobbyActivity TV section ok");
    }

    /** The question Back asks in a network game: both states (joined phone, host), both answers, and the role read from net.cfg. */
    @Test
    public void gameBackConfirm_dialogStates() throws Exception {
        TestSupport.installData(app());
        Activity a = Robolectric.buildActivity(NetLobbyActivity.class, null).create().start().resume().visible().get();
        for (boolean host : new boolean[] { false, true }) {
            final boolean[] yes = { false, false };            // leave, end for everyone
            android.app.AlertDialog d = BackConfirm.show(a, host, () -> yes[0] = true, () -> yes[1] = true);
            org.robolectric.shadows.ShadowAlertDialog sd = org.robolectric.Shadows.shadowOf(d);
            String title = String.valueOf(sd.getTitle()), msg = String.valueOf(sd.getMessage());
            assertTrue("title for " + (host ? "host" : "player") + ": " + title, title.equals("Leave the game?"));
            assertTrue("message: " + msg, msg.contains("rejoin") && msg.contains("2 minutes") && (host ? msg.contains("every phone") && msg.contains("TV") : msg.contains("carry on")));
            assertTrue(msg.contains("keeps running"));
            assertTrue(d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).getText().toString().equals("Leave the game"));
            assertTrue(d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).getText().toString().equals("Keep playing"));
            android.widget.Button endAll = d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL);
            if (host) assertTrue("the host's third answer: " + endAll.getText(), endAll.getVisibility() == android.view.View.VISIBLE && endAll.getText().toString().equals("End the game for everyone"));
            else assertTrue("a joining phone has no end-for-everyone answer", endAll.getVisibility() != android.view.View.VISIBLE || endAll.getText().length() == 0);
            d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick();
            TestSupport.settle(100);
            assertFalse("Keep playing does not quit", yes[0] || yes[1]);
            android.app.AlertDialog d2 = BackConfirm.show(a, host, () -> yes[0] = true, () -> yes[1] = true);
            d2.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
            TestSupport.settle(100);                          // the dialog hands the click to its listener through a message
            assertTrue("Leave the game leaves, and only leaves", yes[0] && !yes[1]);
            if (host) {
                yes[0] = false;
                android.app.AlertDialog d3 = BackConfirm.show(a, host, () -> yes[0] = true, () -> yes[1] = true);
                d3.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).performClick();
                TestSupport.settle(100);
                assertTrue("End the game for everyone ends it, and only ends it", yes[1] && !yes[0]);
            }
        }
        java.io.File dir = app().getFilesDir();
        new java.io.File(dir, "net.cfg").delete();
        assertTrue("no net.cfg is a local game", BackConfirm.role(dir) == BackConfirm.LOCAL);
        java.nio.file.Files.write(new java.io.File(dir, "net.cfg").toPath(), "host=1.2.3.4\nport=46633\ntoken=x\nrole=player\n".getBytes("UTF-8"));
        assertTrue(BackConfirm.role(dir) == BackConfirm.PLAYER);
        java.nio.file.Files.write(new java.io.File(dir, "net.cfg").toPath(), "host=1.2.3.4\nport=46633\ntoken=x\nrole=host\n".getBytes("UTF-8"));
        assertTrue(BackConfirm.role(dir) == BackConfirm.HOST);
        java.nio.file.Files.write(new java.io.File(dir, "net.cfg").toPath(), "host=1.2.3.4\nport=46633\ntoken=x\n".getBytes("UTF-8"));
        assertTrue("a net.cfg from an older build is a joining phone", BackConfirm.role(dir) == BackConfirm.PLAYER);
        // writeGameFiles records the role
        org.hedgewars.android.net.RelayClient.StartInfo si = new org.hedgewars.android.net.RelayClient.StartInfo();
        si.host = "127.0.0.1"; si.port = 46633; si.token = "t"; si.nick = "n"; si.asHost = true;
        org.hedgewars.android.net.RelayClient.writeGameFiles(new java.io.File(dir, "game.ipc"), new java.io.File(dir, "net.cfg"), si, "/x");
        assertTrue("the host's net.cfg says role=host", BackConfirm.role(dir) == BackConfirm.HOST);
        new java.io.File(dir, "net.cfg").delete();
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": Back confirmation dialog states ok");
    }

    /**
     * Device test of 0.3.0-tv4: Back in a network game showed no dialog, because SDLActivity consumed the key and sent it to the
     * engine. A real KEYCODE_BACK KeyEvent (down, then up) is dispatched to GameActivity in network mode (role in net.cfg): the
     * dialog must appear, and the key must not reach SDL (whose native key calls would throw here, there is no native library).
     */
    @Test
    public void gameBackKey_opensDialogInNetworkGame() throws Exception {
        TestSupport.installData(app());
        java.io.File dir = app().getFilesDir();
        for (String role : new String[] { "player", "host" }) {
            java.nio.file.Files.write(new java.io.File(dir, "net.cfg").toPath(), ("host=1.2.3.4\nport=46633\ntoken=x\nrole=" + role + "\n").getBytes("UTF-8"));
            org.robolectric.android.controller.ActivityController<GameActivity> c = Robolectric.buildActivity(GameActivity.class);
            GameActivity a;
            try {
                a = c.create().get();
            } catch (Throwable e) {
                a = c.get();                              // SDL's onCreate needs its native library; the key handling does not
            }
            android.view.KeyEvent down = new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK);
            android.view.KeyEvent up = new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK);
            assertTrue("Back down is consumed", a.dispatchKeyEvent(down));
            assertTrue("Back up is consumed", a.dispatchKeyEvent(up));
            TestSupport.settle(100);
            android.app.AlertDialog d = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            assertTrue("the Back dialog is showing for the " + role, d != null && d.isShowing());
            assertTrue(String.valueOf(org.robolectric.Shadows.shadowOf(d).getTitle()).equals("Leave the game?"));
            d.dismiss();
        }
        new java.io.File(dir, "net.cfg").delete();
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": Back key opens the dialog in a network game ok");
    }

    private static GameActivity newGame() {
        ActivityController<GameActivity> c = Robolectric.buildActivity(GameActivity.class);
        try {
            return c.create().get();
        } catch (Throwable e) {
            return c.get();                               // SDL's onCreate needs its native library; the Back handling does not
        }
    }

    /** A local game (no net.cfg): Back asks "Quit the game?" by the key and by the Android 13 gesture callback, and nothing else. */
    @Test
    public void gameBack_localGameAsksToQuit_keyAndGesture() throws Exception {
        TestSupport.installData(app());
        java.io.File dir = app().getFilesDir();
        new java.io.File(dir, "net.cfg").delete();
        GameActivity a = newGame();
        assertTrue("Back down is consumed", a.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK)));
        assertTrue("Back up is consumed", a.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK)));
        TestSupport.settle(100);
        android.app.AlertDialog d = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("the local quit question is up", d != null && d.isShowing());
        org.robolectric.shadows.ShadowAlertDialog sd = org.robolectric.Shadows.shadowOf(d);
        assertTrue(String.valueOf(sd.getTitle()).equals("Quit the game?"));
        assertTrue(String.valueOf(sd.getMessage()).contains("setup screen"));
        assertTrue(d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).getText().toString().equals("Quit the game"));
        assertTrue(d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).getText().toString().equals("Keep playing"));
        d.dismiss();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // a fresh game screen: the gesture callback is registered and asks the same question
            predictiveBack(true);
            try {
                GameActivity g = newGame();
                java.util.List<Object> callbacks = registeredBackCallbacks(g.getOnBackInvokedDispatcher());
                assertTrue("the Back gesture callback is registered on Android 13 and later", !callbacks.isEmpty());
                ((android.window.OnBackInvokedCallback) callbacks.get(0)).onBackInvoked();
                TestSupport.settle(100);
                android.app.AlertDialog gd = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
                assertTrue("the gesture asks the local quit question", gd != null && gd.isShowing()
                        && String.valueOf(org.robolectric.Shadows.shadowOf(gd).getTitle()).equals("Quit the game?"));
                gd.dismiss();
            } finally {
                predictiveBack(false);
            }
        }
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": local game Back asks to quit ok");
    }

    /**
     * Robolectric keeps Back callbacks only with predictive back switched on (its shadow's setting; the framework's own check is
     * the app's manifest flag, which the test does not go through). Off restores the default with the shadow's reset.
     */
    private static void predictiveBack(boolean on) throws Exception {
        Class<?> shadow = Class.forName("org.robolectric.shadows.ShadowWindowOnBackInvokedDispatcher");
        if (on) {
            java.lang.reflect.Method m = shadow.getDeclaredMethod("setEnablePredictiveBack", boolean.class);
            m.setAccessible(true);
            m.invoke(null, true);
        } else {
            shadow.getMethod("reset").invoke(null);
        }
        // The framework also asks the context (Robolectric does not read the activity's flag there): this makes it accept the callback.
        org.robolectric.util.ReflectionHelpers.setStaticField(Class.forName("android.window.WindowOnBackInvokedDispatcher"),
                "ALWAYS_ENFORCE_PREDICTIVE_BACK", on);
    }

    /**
     * The callbacks registered on a window's Back dispatcher (Robolectric's proxy keeps them until the window takes them over,
     * then the real dispatcher holds them by priority in the public mOnBackInvokedCallbacks map).
     */
    private static java.util.List<Object> registeredBackCallbacks(Object dispatcher) throws Exception {
        java.util.List<Object> out = new java.util.ArrayList<>();
        java.lang.reflect.Field fp = dispatcher.getClass().getDeclaredField("mCallbacks");
        fp.setAccessible(true);
        for (Object pair : (java.util.List<?>) fp.get(dispatcher)) out.add(((android.util.Pair<?, ?>) pair).first);
        java.lang.reflect.Field fa = dispatcher.getClass().getDeclaredField("mActualDispatcher");
        fa.setAccessible(true);
        Object actual = fa.get(dispatcher);
        if (actual != null) {
            java.util.Map<?, ?> byPriority = (java.util.Map<?, ?>) actual.getClass().getField("mOnBackInvokedCallbacks").get(actual);
            for (Object list : byPriority.values()) out.addAll((java.util.Collection<?>) list);
        }
        return out;
    }

    private static String teamNameField(android.view.View v) {
        if (v instanceof android.widget.EditText && "Team name".contentEquals(((android.widget.EditText) v).getHint())) {
            return ((android.widget.EditText) v).getText().toString();
        }
        if (v instanceof android.view.ViewGroup) {
            for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) {
                String r = teamNameField(((android.view.ViewGroup) v).getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    /** The dice beside the team name: a new name each time, never the name of another team or the same name, and nothing else changes. */
    @Test
    public void teamEditActivity_diceChangesOnlyTheName_neverAnotherTeamsName() throws Exception {
        TestSupport.installData(app());
        app().getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE).edit().putString("setup", SETUP_0_2_0).commit();
        GameData data = Store.data(app());
        SetupModel before = Store.loadSetup(app(), data);
        // The 0.2.0 setup has no hedgehog names, so loading fills them at random: save them, so the editor loads the same ones.
        Store.saveSetup(app(), before);
        SetupModel.Team start = before.teams.get(0);
        String previous = start.name;
        Intent in = new Intent(app(), TeamEditActivity.class).putExtra("team", 0);
        ActivityController<TeamEditActivity> ctl = Robolectric.buildActivity(TeamEditActivity.class, in);
        TeamEditActivity a = ctl.create().start().postCreate(null).resume().visible().get();
        a.onWindowFocusChanged(true);
        TestSupport.layout(a);
        TestSupport.settle(300);
        for (int i = 0; i < 25; i++) {
            android.view.View dice = TestSupport.find(a.getWindow().getDecorView(), "Pick another team name");
            assertTrue("the dice button is there at step " + i, dice instanceof android.widget.Button
                    && "🎲".contentEquals(((android.widget.Button) dice).getText()));
            dice.performClick();
            TestSupport.settle(50);
            String now = teamNameField(a.getWindow().getDecorView());
            assertTrue("the name field shows a name at step " + i, now != null && !now.isEmpty());
            assertFalse("the dice changes the name at step " + i + ": " + now, now.equalsIgnoreCase(previous));
            assertFalse("the dice never picks another team's name at step " + i + ": " + now, now.equalsIgnoreCase(before.teams.get(1).name));
            previous = now;
        }
        ctl.pause().stop().destroy();                     // leaving the editor saves the setup, as after a typed name
        SetupModel after = Store.loadSetup(app(), data);
        SetupModel.Team t = after.teams.get(0);
        assertTrue("the last name is saved: " + previous, previous.equals(t.name));
        assertTrue("the other team keeps its name", before.teams.get(1).name.equals(after.teams.get(1).name));
        assertTrue("colour, level, hogs, fort, grave and voice are kept", t.color == start.color && t.level == start.level && t.hogs == start.hogs
                && t.fort.equals(start.fort) && t.grave.equals(start.grave) && t.voice.equals(start.voice));
        // the setup file keeps only the team's own hedgehogs, so the slots past its hog count are not compared
        for (int h = 0; h < t.hogs; h++) {
            assertTrue("hedgehog " + h + " keeps its hat and name", java.util.Objects.equals(t.hogHat[h], start.hogHat[h])
                    && java.util.Objects.equals(t.hogName[h], start.hogName[h]));
        }
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": team dice changes only the name ok");
    }

    /** What each phone is told when the game ends or it leaves, and the interface filter. */
    @Test
    public void netSession_wordingAndInterfaces() {
        assertTrue(NetSession.endNotice("The host ended the game", true).equals("You ended the game."));
        assertTrue(NetSession.endNotice("The host ended the game", false).equals("The host ended the game."));
        assertTrue("a game that simply finished says nothing", NetSession.endNotice("every engine finished", false).isEmpty());
        assertTrue(NetSession.endNotice("Desync between A and B", false).startsWith("The game ended: Desync"));
        assertTrue(NetSession.isLanInterface("wlan0") && NetSession.isLanInterface("ap0") && NetSession.isLanInterface("swlan0") && NetSession.isLanInterface("eth0"));
        assertFalse("apn0 is not a hotspot", NetSession.isLanInterface("apn0"));
        assertTrue("hotspot (softap), USB tether (rndis, usb) are LAN", NetSession.isLanInterface("softap0") && NetSession.isLanInterface("rndis0") && NetSession.isLanInterface("usb0"));
        assertFalse("cellular, VPN and Wi-Fi Direct stay out", NetSession.isLanInterface("rmnet0") || NetSession.isLanInterface("p2p0"));
        assertFalse(NetSession.isLanInterface("ap") || NetSession.isLanInterface("rmnet_data0") || NetSession.isLanInterface("tun0") || NetSession.isLanInterface("p2p-wlan0-0") || NetSession.isLanInterface(null));
    }
}
