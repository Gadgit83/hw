package org.hedgewars.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.hedgewars.android.net.Relay;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

/**
 * Join by address from the Options screen (issue #21): an entry in Options opens a dialog with the same prefilled address box as the
 * Network screen, and Join starts the Network screen with that address, which joins at once. An address the lobby rejects shows the
 * lobby's error in the dialog and starts nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = TestSupport.QUALIFIERS)
public class JoinByAddressTest {
    /** The Wi-Fi prefix of this (fake) phone, and an address on it. */
    private static final String PREFIX = "192.168.7.";
    private static final String OWN = PREFIX + "143";
    private static final String HOST_ADDRESS = PREFIX + "20:" + 46633;

    private Supplier<List<String>> realOwnAddresses;

    @Before
    public void freshProcess() throws Exception {
        TestSupport.resetStatics();
        LocalNetwork.forceGranted = true;
        realOwnAddresses = NetLobbyActivity.ownAddresses;
        NetLobbyActivity.ownAddresses = () -> Arrays.asList(OWN);
        prefs().edit().remove("netaddr").commit();
    }

    @After
    public void restoreOwnAddresses() {
        NetLobbyActivity.ownAddresses = realOwnAddresses;
    }

    // Helpers ------------------------------------------------------------------------------------

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    private static android.content.SharedPreferences prefs() {
        return app().getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** The Options screen on the given tab (0 Sound, 1 Display and controls, 2 Advanced), set up and laid out as the system would. */
    private static OptionsActivity options(int tab) {
        Intent in = new Intent(app(), OptionsActivity.class).putExtra(OptionsActivity.EXTRA_TAB, tab);
        OptionsActivity o = Robolectric.buildActivity(OptionsActivity.class, in).setup().get();
        TestSupport.layout(o);
        return o;
    }

    /** Taps the Join by address entry and lets its background look-up of the Wi-Fi prefix finish; returns the dialog it opened. */
    private static AlertDialog openJoinDialog(OptionsActivity o) throws Exception {
        View entry = TestSupport.find(o.getWindow().getDecorView(), "Join by address");
        assertNotNull("Options has a Join by address entry", entry);
        entry.performClick();
        TestSupport.settle(100);
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("the entry opens a dialog", d);
        assertTrue("the dialog is showing", d.isShowing());
        return d;
    }

    private static EditText firstEdit(View v) {
        if (v instanceof EditText) return (EditText) v;
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                EditText e = firstEdit(((ViewGroup) v).getChildAt(i));
                if (e != null) return e;
            }
        }
        return null;
    }

    private static EditText box(AlertDialog d) {
        EditText e = firstEdit(d.getWindow().getDecorView());
        assertNotNull("the dialog has an address box", e);
        return e;
    }

    private static void tapJoin(AlertDialog d) {
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    }

    /** The address box of a Network screen as the phone shows it, after its own look-up of the Wi-Fi prefix. */
    private static String lobbyAddressBox() throws Exception {
        NetLobbyActivity lobby = Robolectric.buildActivity(NetLobbyActivity.class).create().start().resume().visible().get();
        TestSupport.settle(200);
        Field f = NetLobbyActivity.class.getDeclaredField("addressField");
        f.setAccessible(true);
        return ((EditText) f.get(lobby)).getText().toString();
    }

    /** A local port that nothing listens on: a join to it is refused at once (the way the other tests fake a join). */
    private static int closedPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    // 1. The entry ---------------------------------------------------------------------------------

    @Test
    public void theOptionsScreenShowsAJoinByAddressEntry() throws Exception {
        OptionsActivity o = options(2);
        View entry = TestSupport.find(o.getWindow().getDecorView(), "Join by address");
        assertTrue("the entry is a button", entry instanceof Button);
        assertTrue("the entry is shown on the Advanced tab", TestSupport.shown(entry));
    }

    // 2. The box starts as the Network screen's box does ------------------------------------------------

    @Test
    public void theBoxStartsWithTheLastAddressUsed() throws Exception {
        prefs().edit().putString("netaddr", HOST_ADDRESS).commit();
        String dialog = box(openJoinDialog(options(2))).getText().toString();
        assertEquals("the dialog starts with the address used before", HOST_ADDRESS, dialog);
        assertEquals("and the Network screen's box shows the same text", dialog, lobbyAddressBox());
    }

    @Test
    public void theBoxStartsWithTheWifiPrefixWhenNoAddressWasUsed() throws Exception {
        String dialog = box(openJoinDialog(options(2))).getText().toString();
        assertEquals("the dialog starts with this phone's Wi-Fi prefix", PREFIX, dialog);
        assertEquals("and the Network screen's box shows the same text", dialog, lobbyAddressBox());
    }

    // 3. Join with a valid address opens the Network screen with it -------------------------------------

    @Test
    public void joinWithAValidAddressStartsTheNetworkScreenWithTheExtra() throws Exception {
        OptionsActivity o = options(2);
        AlertDialog d = openJoinDialog(o);
        box(d).setText("  " + HOST_ADDRESS + " ");
        tapJoin(d);

        Intent next = Shadows.shadowOf(o).getNextStartedActivity();
        assertNotNull("Join starts a screen", next);
        assertEquals("the screen is the Network screen", NetLobbyActivity.class.getName(), next.getComponent().getClassName());
        assertEquals("with the address, trimmed", HOST_ADDRESS, next.getStringExtra(NetLobbyActivity.EXTRA_JOIN_ADDRESS));
        assertFalse("the dialog closes", d.isShowing());
    }

    // 4. The Network screen started with the extra joins that address at once ---------------------------

    @Test
    public void theNetworkScreenStartedWithTheExtraJoinsThatAddress() throws Exception {
        String address = "127.0.0.1:" + closedPort();
        Intent in = new Intent(app(), NetLobbyActivity.class).putExtra(NetLobbyActivity.EXTRA_JOIN_ADDRESS, address);
        NetLobbyActivity a = Robolectric.buildActivity(NetLobbyActivity.class, in).create().start().resume().visible().get();
        for (int i = 0; i < 100 && NetSession.goneHost.isEmpty(); i++) TestSupport.settle(100);
        assertEquals("the join went to the address it was given", address, NetSession.goneHost);
        assertNotNull(a);
    }

    @Test
    public void theNetworkScreenWithoutTheExtraStartsNoJoin() throws Exception {
        Robolectric.buildActivity(NetLobbyActivity.class).create().start().resume().visible();
        TestSupport.settle(300);
        assertEquals("nothing was joined", "", NetSession.goneHost);
    }

    // 4b. A game that is running is not left by a join from Options --------------------------------------

    @Test
    public void joinByAddressWhileHostingDoesNotLeaveTheGame() throws Exception {
        NetSession.hosting = true;
        OptionsActivity o = options(2);
        AlertDialog d = openJoinDialog(o);
        box(d).setText(HOST_ADDRESS);
        tapJoin(d);
        assertNull("no screen is started, so the hosted game is not left", Shadows.shadowOf(o).getNextStartedActivity());
        assertTrue("the dialog stays open", d.isShowing());
        assertNotNull("the dialog says why", TestSupport.find(d.getWindow().getDecorView(),
                "This phone is already in a network game. Leave it on the Network screen first."));
        assertTrue("the hosting goes on", NetSession.hosting);
    }

    // 5. An address the lobby rejects shows its error in the dialog and starts nothing ----------------------

    @Test
    public void anAddressTheLobbyRejectsShowsItsErrorInTheDialogAndStartsNothing() throws Exception {
        OptionsActivity o = options(2);
        AlertDialog d = openJoinDialog(o);

        box(d).setText(PREFIX);                              // the last number is missing
        tapJoin(d);
        String lastNumber = "Add the last number of the host's address, for example " + PREFIX + "20.";
        assertNotNull("the lobby's message is shown in the dialog", TestSupport.find(d.getWindow().getDecorView(), lastNumber));
        assertTrue("the dialog stays open", d.isShowing());
        assertNull("no screen is started", Shadows.shadowOf(o).getNextStartedActivity());

        box(d).setText(PREFIX + "20:abc");                   // the port is not a number
        tapJoin(d);
        assertNotNull("the port message is shown in the dialog", TestSupport.find(d.getWindow().getDecorView(),
                "The port after the colon is not a number."));
        assertTrue("the dialog stays open", d.isShowing());
        assertNull("still no screen is started", Shadows.shadowOf(o).getNextStartedActivity());
    }

    @Test
    public void anEmptyBoxShowsTheLobbysMessage() throws Exception {
        OptionsActivity o = options(2);
        AlertDialog d = openJoinDialog(o);
        box(d).setText("");
        tapJoin(d);
        assertNotNull("the lobby's message for an empty box is shown", TestSupport.find(d.getWindow().getDecorView(),
                "Type the host's address first, for example 192.168.1.20:" + Relay.PHONE_PORT + "."));
        assertNull("no screen is started", Shadows.shadowOf(o).getNextStartedActivity());
    }

    // 6. The entry fits the narrow and the upright screens --------------------------------------------------

    @Test
    public void theEntryIsOnScreenUprightOnANarrowPhone() throws Exception {
        RuntimeEnvironment.setQualifiers(TestSupport.PORTRAIT_QUALIFIERS);
        try {
            OptionsActivity o = Robolectric.buildActivity(OptionsActivity.class,
                    new Intent(app(), OptionsActivity.class).putExtra(OptionsActivity.EXTRA_TAB, 2)).setup().get();
            TestSupport.layoutUpright(o);
            assertInside(o, TestSupport.PW, TestSupport.PH);
        } finally {
            RuntimeEnvironment.setQualifiers(TestSupport.QUALIFIERS);
        }
    }

    @Test
    public void theEntryIsOnScreenHeldSideways() throws Exception {
        assertInside(options(2), TestSupport.W, TestSupport.H);
    }

    private static void assertInside(OptionsActivity o, int w, int h) {
        View entry = TestSupport.find(o.getWindow().getDecorView(), "Join by address");
        assertNotNull("the entry is on the screen", entry);
        assertTrue("the entry is shown", TestSupport.shown(entry));
        int[] xy = new int[2];
        entry.getLocationOnScreen(xy);
        assertTrue("the entry lies inside the window: x " + xy[0] + " to " + (xy[0] + entry.getWidth()) + ", y " + xy[1]
                + " to " + (xy[1] + entry.getHeight()),
                xy[0] >= 0 && xy[0] + entry.getWidth() <= w && xy[1] >= 0 && xy[1] + entry.getHeight() <= h);
    }
}
