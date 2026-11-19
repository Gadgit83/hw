package org.hedgewars.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.ActivityInfo;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.hedgewars.android.net.HostAdvert;
import org.hedgewars.android.net.Json;
import org.hedgewars.android.net.Relay;
import org.hedgewars.android.net.RelayClient;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * The Network screen after a game, as the phones showed it (issue #1, RESULT 27): stale connection banners on the host lobby, a
 * greyed host row that promises a "Search again" the screen does not have, the red "The host left." banner that outlives the host
 * coming back, and the screen staying landscape after a game.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = TestSupport.QUALIFIERS)
public class NetLobbyDeviceFixTest {
    private static final String HOST = "192.168.1.20";
    private static final String HOST_ADDRESS = HOST + ":" + Relay.PHONE_PORT;

    /** The listeners of the searches the screen started (the discovery is faked, NSD does not run under Robolectric). */
    private final List<HostDiscovery.Listener> listeners = new ArrayList<>();

    @Before
    public void freshProcess() throws Exception {
        TestSupport.resetStatics();
        LocalNetwork.forceGranted = true;
        NetSession.discoveryFactory = c -> new HostDiscovery() {
            @Override public void start(Listener l) {
                listeners.add(l);
                l.status("Searching for hosts on this Wi-Fi...", false);
            }
            @Override public void stop() { }
        };
    }

    // Helpers ------------------------------------------------------------------------------------

    /** Hands a lobby state to the session the way the relay link does (its handler is private). */
    private static void deliverState(Map<String, Object> s) throws Exception {
        Field f = NetSession.class.getDeclaredField("HANDLER");
        f.setAccessible(true);
        ((RelayClient.Handler) f.get(null)).onState(s);
    }

    private static Map<String, Object> player(String name, boolean away, boolean gaveUp) {
        return Json.obj("id", 2L, "name", name, "host", false, "ready", true, "away", away, "gaveUp", gaveUp);
    }

    private static Map<String, Object> lobby(Map<String, Object>... players) {
        List<Object> pl = new ArrayList<>(Arrays.asList((Object[]) players));
        return Json.obj("state", "playing", "you", 1L, "players", pl);
    }

    private static HostAdvert.Found found(String host) {
        HostAdvert.Found f = new HostAdvert.Found();
        f.serviceName = "hp-" + host;
        f.host = host;
        f.port = Relay.PHONE_PORT;
        f.name = "Host phone";
        f.parsedOk = true;
        f.lobbyProto = Relay.PROTO;
        return f;
    }

    /** The Network screen as the chooser, with its search running. */
    private static ActivityController<NetLobbyActivity> chooser() {
        return Robolectric.buildActivity(NetLobbyActivity.class).create().start().resume().visible();
    }

    /** Runs a private method of the screen (render, renderHosts) as a pass that the system would make. */
    private static void redraw(NetLobbyActivity a, String name) throws Exception {
        Method m = NetLobbyActivity.class.getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(a);
    }

    private static void collect(View v, List<String> texts, List<String> buttons) {
        if (v instanceof Button) buttons.add(((TextView) v).getText().toString());
        else if (v instanceof TextView) texts.add(((TextView) v).getText().toString());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), texts, buttons);
        }
    }

    private static List<String> texts(NetLobbyActivity a) {
        List<String> t = new ArrayList<>(), b = new ArrayList<>();
        collect(a.getWindow().getDecorView(), t, b);
        return t;
    }

    private static List<String> buttons(NetLobbyActivity a) {
        List<String> t = new ArrayList<>(), b = new ArrayList<>();
        collect(a.getWindow().getDecorView(), t, b);
        return b;
    }

    private static boolean anyContains(List<String> all, String part) {
        for (String s : all) if (s.contains(part)) return true;
        return false;
    }

    // 1. Connection banners follow the phone they describe -----------------------------------------

    @Test
    public void aLostConnectionNoticeClearsWhenThePhoneIsBack() throws Exception {
        NetSession.notice = "Joiner lost connection; waiting up to 2 minutes for them to rejoin";
        deliverState(lobby(player("Joiner", true, false)));
        assertEquals("the phone is still away, so the notice still holds",
                "Joiner lost connection; waiting up to 2 minutes for them to rejoin", NetSession.notice);

        deliverState(lobby(player("Joiner", false, false)));
        assertEquals("the phone is back and ready: the banner goes", "", NetSession.notice);
    }

    @Test
    public void aDidNotComeBackNoticeHoldsWhileTheSeatSitsOut() throws Exception {
        NetSession.notice = "Joiner did not come back; their teams sit out";
        deliverState(lobby(player("Joiner", false, true)));
        assertEquals("the seat is given up, so the notice still holds", "Joiner did not come back; their teams sit out", NetSession.notice);

        deliverState(lobby(player("Joiner", false, false)));
        assertEquals("the seat is not given up any more", "", NetSession.notice);
    }

    @Test
    public void aNoticeAboutAPhoneThatIsGoneEndsWithIt() throws Exception {
        NetSession.notice = "Joiner left the game; waiting up to 2 minutes for them to rejoin";
        deliverState(lobby(player("Someone else", false, false)));
        assertEquals("the phone is no longer in the game", "", NetSession.notice);
    }

    @Test
    public void otherNoticesAreNotTouched() throws Exception {
        NetSession.notice = "Only the host can start the game.";
        deliverState(lobby(player("Joiner", false, false)));
        assertEquals("a notice about no phone stays until the next game", "Only the host can start the game.", NetSession.notice);
    }

    // 2. The greyed host row offers only what the screen offers ---------------------------------------

    @Test
    public void theGreyedHostRowDoesNotPromiseASearchButtonTheScreenLacks() throws Exception {
        NetLobbyActivity a = chooser().get();
        listeners.get(0).hosts(Arrays.asList(found(HOST)));
        // The host has left (its join is greyed out) while the search is still running: no "Search again" button is on screen.
        NetSession.goneHost = HOST_ADDRESS;
        redraw(a, "renderHosts");

        List<String> texts = texts(a);
        assertTrue("the greyed row is shown", anyContains(texts, "stopped hosting"));
        assertFalse("the search is running, so there is no Search again button", buttons(a).contains("Search again"));
        assertFalse("the row must not tell the user to press a button the screen does not show", anyContains(texts, "Search again"));
    }

    // 2b. Search again stays above the host rows, so it is in view once hosts are listed (device report, item 3) ----------

    @Test
    public void searchAgainIsAboveTheHostRows() throws Exception {
        NetLobbyActivity a = chooser().get();
        listeners.get(0).hosts(Arrays.asList(found(HOST)));
        listeners.get(0).status("Search finished.", true);
        Field f = NetLobbyActivity.class.getDeclaredField("hostsBox");
        f.setAccessible(true);
        android.view.ViewGroup box = (android.view.ViewGroup) f.get(a);
        int button = -1, firstRow = -1;
        for (int i = 0; i < box.getChildCount(); i++) {
            android.view.View v = box.getChildAt(i);
            if (v instanceof android.widget.Button && ((android.widget.Button) v).getText().toString().equals("Search again")) button = i;
            if (firstRow < 0 && v instanceof android.widget.LinearLayout) firstRow = i;
        }
        assertTrue("the search is finished, so Search again is shown", button >= 0);
        assertTrue("a host row is listed", firstRow >= 0);
        assertTrue("Search again comes before the host rows, so a listed host does not push it below the fold (button " + button
                + ", first row " + firstRow + ")", button < firstRow);
    }

    // 3. The red "The host left." banner ends when the host is found again -------------------------------

    @Test
    public void theHostLeftBannerAndTheGreyOutEndWhenTheHostIsFoundAgain() throws Exception {
        NetLobbyActivity a = chooser().get();
        NetSession.goneHost = HOST_ADDRESS;
        NetSession.closedReason = "The host left.";
        redraw(a, "render");
        assertTrue("the banner is up after the host left", anyContains(texts(a), "The host left."));

        listeners.get(0).hosts(Arrays.asList(found(HOST)));      // the host is in the search again

        assertEquals("the grey-out ends", "", NetSession.goneHost);
        assertEquals("the banner's reason is cleared", "", NetSession.closedReason);
        assertFalse("the banner is no longer on screen", anyContains(texts(a), "The host left."));
    }

    // 4. The Network screen follows the device again after a game -----------------------------------

    @Test
    public void theNetworkScreenReturnsToTheDeviceOrientationAfterAGame() throws Exception {
        ActivityController<NetLobbyActivity> c = chooser();
        // The game screen asks for sensor landscape; the screen it returns to must not keep that.
        c.get().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        c.pause().resume();
        assertEquals("the Network screen follows the device again", ActivityInfo.SCREEN_ORIENTATION_USER,
                c.get().getRequestedOrientation());
    }

    // 5. The chooser keeps a search running, so it ends in "Search again" (device report, item 1) -----

    @Test
    public void aChooserShownAfterAJoinStartsASearchAgain() throws Exception {
        NetLobbyActivity a = chooser().get();
        assertEquals("the screen searches on start", 1, listeners.size());
        call(a, "stopSearch");                          // what a join does when it starts
        NetSession.goneHost = HOST_ADDRESS;             // then the host left and the lobby closed
        NetSession.closedReason = "The host left.";
        redraw(a, "render");                            // the chooser is drawn again
        assertEquals("the chooser searches again, so the search ends with a Search again button", 2, listeners.size());
    }

    // 6. A fresh search that finds the host again ends the "The host left." banner (device report, item 2) -----

    @Test
    public void theHostLeftBannerEndsWhenAFreshSearchFindsTheHost() throws Exception {
        NetLobbyActivity a = chooser().get();
        NetSession.goneHost = HOST_ADDRESS;
        NetSession.closedReason = "The host left.";
        call(a, "startSearch", true);                   // Search again: a new search
        listeners.get(listeners.size() - 1).hosts(Arrays.asList(found(HOST)));
        assertEquals("the grey-out ends when the new search lists the host", "", NetSession.goneHost);
        assertEquals("the banner's reason is cleared", "", NetSession.closedReason);
    }

    // 7. A refused join marks the host as stopped and says so (device report, item 3) -----

    @Test
    public void aRefusedJoinGreysTheHostOutWithAClearMessage() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = s.getLocalPort();                    // nothing listens on this port any more: the join is refused
        }
        NetLobbyActivity a = chooser().get();
        listeners.get(0).hosts(Arrays.asList(foundAt("127.0.0.1", port)));
        call(a, "connectTo", "127.0.0.1", port, false, "");
        for (int i = 0; i < 100 && NetSession.goneHost.isEmpty(); i++) TestSupport.settle(100);
        assertEquals("the refused host is marked as having stopped hosting", "127.0.0.1:" + port, NetSession.goneHost);
        TestSupport.settle(200);
        String problem = (String) field(a, "problem");
        assertTrue("the message says the host is not hosting any more: " + problem, problem.contains("not hosting"));
        assertTrue("the row is greyed out", anyContains(texts(a), "stopped hosting"));
    }

    private static HostAdvert.Found foundAt(String host, int port) {
        HostAdvert.Found f = found(host);
        f.port = port;
        return f;
    }

    /** Calls a private method of the screen by name (the overload with the given number of arguments). */
    private static Object call(NetLobbyActivity a, String name, Object... args) throws Exception {
        for (Method m : NetLobbyActivity.class.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == args.length) {
                m.setAccessible(true);
                return m.invoke(a, args);
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Object field(NetLobbyActivity a, String name) throws Exception {
        Field f = NetLobbyActivity.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(a);
    }
}
