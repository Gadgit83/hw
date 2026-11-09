package org.hedgewars.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

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
 * Fairphone-sized landscape screen under Android 14 and 15, and nothing may throw. It exists because 0.2.1 crashed on
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
        ActivityController<A> ctl = Robolectric.buildActivity(k, in);
        A a = ctl.create().start().postCreate(null).resume().visible().get();
        a.onWindowFocusChanged(true);
        TestSupport.layout(a);
        TestSupport.settle(settleMillis);
        TestSupport.layout(a);
        String text = TestSupport.texts(a.getWindow().getDecorView());
        assertNotNull(a.getWindow().getDecorView());
        if (a instanceof MainActivity) assertNetworkButtonFits(a);
        ctl.pause().stop().destroy();
        return text;
    }

    /** The Network button in the compact header shows its whole word and lies inside the screen. */
    private static void assertNetworkButtonFits(Activity a) {
        android.widget.TextView t = findText(a.getWindow().getDecorView(), "Network");
        assertNotNull("the header has a Network button", t);
        int[] xy = new int[2];
        t.getLocationOnScreen(xy);
        assertTrue("Network is not cut off (ellipsis)", t.getLayout() == null || t.getLayout().getEllipsisCount(0) == 0);
        assertTrue("Network lies inside the screen: x " + xy[0] + " to " + (xy[0] + t.getWidth()), xy[0] >= 0 && xy[0] + t.getWidth() <= TestSupport.W);
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

    @Test
    public void netLobbyActivity_chooser_neitherHostingNorJoined() throws Exception {
        TestSupport.installData(app());
        String text = launch(NetLobbyActivity.class, null, 600);
        assertTrue("chooser shows Host and Join: " + text, text.contains("Host network game") && text.contains("Join by address"));
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": NetLobbyActivity chooser ok");
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

    @Test
    public void netLobbyActivity_hostState() throws Exception {
        TestSupport.installData(app());
        netState(LOBBY.replace("\"canStart\":false", "\"canStart\":true").replace("\"you\":2", "\"you\":1"), true);
        String text = launch(NetLobbyActivity.class, null, 600);
        assertTrue("host lobby shows Start and the address block: " + text,
                text.contains("Hosting") && text.contains("Start game") && text.contains("TV viewer address") && text.contains("Stop hosting"));
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": NetLobbyActivity host state ok");
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
}
