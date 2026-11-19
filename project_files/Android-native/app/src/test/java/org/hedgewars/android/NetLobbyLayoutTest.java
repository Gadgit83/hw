package org.hedgewars.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.hedgewars.android.net.HostAdvert;
import org.hedgewars.android.net.Relay;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * The Network screen's layout on a phone held upright (device report, tv22, item 1 to 3): a refused join's message must be where the
 * user looks, the "More below" chip must not cover a Join button, and the host rows must not move while the search runs or ends.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = TestSupport.PORTRAIT_QUALIFIERS)
public class NetLobbyLayoutTest {
    private static final String HOST_NAME = "Host phone";

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

    private static ActivityController<NetLobbyActivity> chooser() {
        return Robolectric.buildActivity(NetLobbyActivity.class).create().start().resume().visible();
    }

    private static HostAdvert.Found found(String host, int port) {
        HostAdvert.Found f = new HostAdvert.Found();
        f.serviceName = "hp-" + host + port;
        f.host = host;
        f.port = port;
        f.name = HOST_NAME;
        f.parsedOk = true;
        f.lobbyProto = Relay.PROTO;
        f.compat = org.hedgewars.android.net.Compat.VALUE;
        f.version = Diag.versionName(org.robolectric.RuntimeEnvironment.getApplication());
        return f;
    }

    /** Lays the screen out at the phone's size, as the window would be drawn. */
    private static void layout(NetLobbyActivity a) {
        View d = a.getWindow().getDecorView();
        d.measure(View.MeasureSpec.makeMeasureSpec(TestSupport.PW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(TestSupport.PH, View.MeasureSpec.EXACTLY));
        d.layout(0, 0, TestSupport.PW, TestSupport.PH);
    }

    /** Lets the main looper and the screen's own background work (the data check, a join's retries) finish. */
    private static void settle(BooleanSupplier done) throws Exception {
        long end = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < end) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (done.getAsBoolean()) return;
            Thread.sleep(10);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static Object field(NetLobbyActivity a, String name) throws Exception {
        Field f = NetLobbyActivity.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(a);
    }

    private static void redraw(NetLobbyActivity a, String name) throws Exception {
        Method m = NetLobbyActivity.class.getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(a);
    }

    /** The window rectangle of a view as {left, top, right, bottom}. */
    private static int[] rect(View v) {
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        return new int[] { loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight() };
    }

    private static boolean overlaps(int[] a, int[] b) {
        return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
    }

    /** The part of a that lies inside b, or null when there is none. */
    private static int[] clip(int[] a, int[] b) {
        int[] r = { Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.min(a[2], b[2]), Math.min(a[3], b[3]) };
        return r[0] < r[2] && r[1] < r[3] ? r : null;
    }

    private static boolean inside(int[] inner, int[] outer) {
        return inner[0] >= outer[0] && inner[2] <= outer[2] && inner[1] >= outer[1] && inner[3] <= outer[3];
    }

    private static void collect(View v, List<View> out) {
        out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    /** The first view on the screen with this text (a TextView or a Button). */
    private static TextView textView(NetLobbyActivity a, String text) {
        List<View> all = new ArrayList<>();
        collect(a.getWindow().getDecorView(), all);
        for (View v : all) if (v instanceof TextView && ((TextView) v).getText().toString().equals(text)) return (TextView) v;
        return null;
    }

    /** The host row (the card) that holds the host's name. */
    private static View hostRow(NetLobbyActivity a) {
        return (View) textView(a, HOST_NAME).getParent();
    }

    /** The Join button of a host row, by its label. */
    private static Button joinButton(View row, String label) {
        List<View> all = new ArrayList<>();
        collect(row, all);
        for (View v : all) if (v instanceof Button && ((Button) v).getText().toString().equals(label)) return (Button) v;
        throw new AssertionError("no " + label + " button in the host row");
    }

    private static int closedPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();          // nothing listens on it once the socket is closed: a join is refused
        }
    }

    // 1. A refused join's message is where the user is looking ----------------------------------

    @Test
    public void aRefusedJoinShowsItsMessageWhereTheUserIsLooking() throws Exception {
        int port = closedPort();
        NetLobbyActivity a = chooser().get();
        layout(a);
        listeners.get(0).hosts(Arrays.asList(found("127.0.0.1", port)));
        layout(a);
        ScrollView sv = (ScrollView) field(a, "scroll");
        // The page is scrolled down to the host row, where the user taps Join.
        int[] row = rect(hostRow(a));
        int[] view = rect(sv);
        sv.scrollTo(0, row[1] - view[1]);
        layout(a);
        Button join = joinButton(hostRow(a), "Join to play");
        assertTrue("the Join button is enabled for this host", join.isEnabled());
        String refused = "127.0.0.1:" + port + " is not hosting any more: nothing answers on its port. Search again to look for it.";
        join.performClick();
        settle(() -> textView(a, refused) != null);
        layout(a);
        TextView message = textView(a, refused);
        assertTrue("the refused join's message is on screen", message != null);
        assertTrue("the message is inside the scrolled view, not above it (message " + Arrays.toString(rect(message))
                + ", view " + Arrays.toString(rect(sv)) + ")", inside(rect(message), rect(sv)));
    }

    // 2. The "More below" chip never covers a Join button ----------------------------------------

    @Test
    public void theMoreBelowChipNeverCoversAJoinButton() throws Exception {
        NetLobbyActivity a = chooser().get();
        layout(a);
        List<HostAdvert.Found> many = new ArrayList<>();
        for (int i = 1; i <= 6; i++) many.add(found("192.168.1." + (20 + i), Relay.PHONE_PORT));
        listeners.get(0).hosts(many);
        layout(a);
        ScrollView sv = (ScrollView) field(a, "scroll");
        TextView chip = textView(a, "More below ▾");
        assertTrue("the chip is on the screen", chip != null);
        int bottom = sv.getChildAt(0).getHeight() - sv.getHeight();
        assertTrue("the page is longer than the screen, so the chip has a reason to show", bottom > 0);
        for (int y : new int[] { 0, bottom / 2, bottom }) {
            sv.scrollTo(0, y);
            layout(a);
            if (chip.getVisibility() == View.VISIBLE) {
                int[] c = rect(chip);
                List<View> all = new ArrayList<>();
                collect(a.getWindow().getDecorView(), all);
                for (View v : all) {
                    if (!(v instanceof Button) || v.getVisibility() != View.VISIBLE) continue;
                    String label = ((Button) v).getText().toString();
                    if (!label.startsWith("Join")) continue;
                    // Only the part of a button that the scrolled view shows can be covered (a button scrolled out of it is clipped).
                    int[] shown = clip(rect(v), rect(sv));
                    if (shown == null) continue;
                    assertFalse("at scroll " + y + ", the chip covers the button \"" + label + "\"", overlaps(c, shown));
                }
                assertFalse("the chip sits below the scrolled view, not over it", overlaps(c, rect(sv)));
            }
        }
    }

    // 3. The host row does not move while the search runs or after it ends ----------------------

    @Test
    public void theHostRowDoesNotMoveWhenTheSearchEnds() throws Exception {
        NetLobbyActivity a = chooser().get();
        layout(a);
        listeners.get(0).hosts(Arrays.asList(found("192.168.1.21", Relay.PHONE_PORT)));
        settle(() -> dataCheckDone(a));
        layout(a);
        int whileSearching = rect(hostRow(a))[1];
        Button again = searchAgain(a);
        assertTrue("Search again is on the screen while the search runs", again != null);
        assertFalse("Search again is disabled while the search runs", again.isEnabled());

        listeners.get(0).status("Search finished.", true);
        layout(a);
        int afterSearch = rect(hostRow(a))[1];
        assertEquals("the host row stays where it was when the search ends", whileSearching, afterSearch);
        assertTrue("Search again is enabled once the search has ended", searchAgain(a).isEnabled());
    }

    /** The data check keeps its line whether or not it is still running, so the rows do not move when it ends. */
    @Test
    public void theHostRowDoesNotMoveWhileTheDataCheckRuns() throws Exception {
        NetLobbyActivity a = chooser().get();
        layout(a);
        listeners.get(0).hosts(Arrays.asList(found("192.168.1.21", Relay.PHONE_PORT)));
        settle(() -> dataCheckDone(a));
        layout(a);
        int known = rect(hostRow(a))[1];

        Field digest = NetLobbyActivity.class.getDeclaredField("myDigest");
        digest.setAccessible(true);
        Object knownDigest = digest.get(a);
        digest.set(a, null);                    // the data check is running again
        redraw(a, "renderHosts");
        layout(a);
        int checking = rect(hostRow(a))[1];
        assertEquals("the row does not move while the data check is shown", known, checking);
        digest.set(a, knownDigest);            // the check has ended
        redraw(a, "renderHosts");
        layout(a);
        assertEquals("the row does not move when the data check ends", known, rect(hostRow(a))[1]);
    }

    private static boolean dataCheckDone(NetLobbyActivity a) {
        try {
            return field(a, "myDigest") != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static Button searchAgain(NetLobbyActivity a) {
        List<View> all = new ArrayList<>();
        collect(a.getWindow().getDecorView(), all);
        for (View v : all) if (v instanceof Button && ((Button) v).getText().toString().equals("Search again")) return (Button) v;
        return null;
    }
}
