package org.hedgewars.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
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

    @Test
    public void netLobbyActivity_chooser_neitherHostingNorJoined() throws Exception {
        TestSupport.installData(app());
        String text = launch(NetLobbyActivity.class, null, 600);
        assertTrue("chooser shows Host and Join: " + text, text.contains("Host network game") && text.contains("Join by address") && text.contains("Join to play") && text.contains("Join to watch"));
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": NetLobbyActivity chooser ok");
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
        NetSession.saveRejoin(app(), "192.168.1.20", 46633, "Fairphone", "0123456789abcdef0123456789abcdef");
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
