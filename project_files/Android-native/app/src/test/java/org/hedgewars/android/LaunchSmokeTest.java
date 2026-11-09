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
 *  - OptionsActivity, all three tabs, and TeamEditActivity for two teams.
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
        ctl.pause().stop().destroy();
        return text;
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
}
