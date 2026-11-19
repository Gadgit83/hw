package org.hedgewars.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.hedgewars.android.net.Json;
import org.hedgewars.android.net.Relay;
import org.hedgewars.android.net.RelayClient;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The network session as the phones showed it (device report on issue #1, items 4, 6 and 7): the rejoin note of a joiner that stayed in the
 * lobby across games, a refused join that left the phone "joined", and the "away too long" notice that outlived its game.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = TestSupport.QUALIFIERS)
public class NetSessionDeviceFixTest {
    private static final String HOST = "192.168.1.20";

    @Before
    public void freshProcess() throws Exception {
        TestSupport.resetStatics();
        NetSession.clearRejoin(app());
    }

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    /** The relay's handler for this phone's link (it is private; the relay delivers to it the same way). */
    private static RelayClient.Handler handler() throws Exception {
        Field f = NetSession.class.getDeclaredField("HANDLER");
        f.setAccessible(true);
        return (RelayClient.Handler) f.get(null);
    }

    private static Map<String, Object> state(String phase, boolean gaveUp) {
        List<Object> players = new ArrayList<>();
        players.add(Json.obj("id", 1L, "name", "Me", "host", false, "ready", false, "away", false, "gaveUp", gaveUp));
        return Json.obj("state", phase, "you", 1L, "players", players);
    }

    private static RelayClient.StartInfo startForGameTwo() {
        RelayClient.StartInfo s = new RelayClient.StartInfo();
        s.you = 2;
        s.game = 2;
        s.token = "game-2-token";
        s.nick = "Joiner";
        s.host = HOST;
        s.port = Relay.PHONE_PORT;
        return s;
    }

    // 4. The rejoin note is kept for a game started while the joiner stays in the lobby -----------

    @Test
    public void theRejoinNoteIsKeptForGameTwoWhenTheJoinerStayedInTheLobby() throws Exception {
        NetSession.lastHostName = HOST;
        NetSession.lastPort = Relay.PHONE_PORT;
        handler().onJoined(1, "Joiner", "token-1");          // the first join, before game 1
        assertNotNull("the note is kept at the join", NetSession.loadRejoin(app()));

        handler().onState(state("playing", false));          // the relay's state of game 1, as before its summary
        handler().onSummary(Json.obj("t", "summary", "reason", "every engine finished", "text", "Game 1 over"));
        assertNull("game 1 ended: the note goes", NetSession.loadRejoin(app()));

        handler().onStart(startForGameTwo());                // game 2 starts; the phone never joined again
        NetSession.RejoinNote n = NetSession.loadRejoin(app());
        assertNotNull("game 2 starts: the note is kept again, so a relaunched phone can come back", n);
        assertEquals("the note keeps the token the relay gave this phone at its join", "token-1", n.token);
    }

    // 6. A refused join ends in its message, not in "joined" -------------------------------------

    @Test
    public void aRefusedJoinNeverLeavesThePhoneJoinedToADeadLink() throws Exception {
        TestSupport.installData(app());
        final String reason = "A game is running on this host. Try again when it has ended.";
        int stuck = 0;
        for (int i = 0; i < 25; i++) {
            try (ServerSocket relay = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
                relay.setSoTimeout(5000);
                Thread host = new Thread(() -> {
                    try (Socket s = relay.accept()) {
                        new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();   // the hello
                        s.getOutputStream().write(("{\"t\":\"refuse\",\"reason\":\"" + reason + "\"}\n").getBytes(StandardCharsets.UTF_8));
                        s.getOutputStream().flush();
                    } catch (IOException ignored) {
                        // the phone went away first
                    }
                });
                host.start();
                try {
                    NetSession.connect(app(), "127.0.0.1", relay.getLocalPort(), "Joiner", false);
                } catch (IOException refused) {
                    // the refusal may come back as the connect's own error: either way the phone must not be joined below
                }
                host.join(5000);
                TestSupport.settle(50);
                if (NetSession.joined) stuck++;
                NetSession.leave();
                NetSession.closedReason = "";
            }
        }
        assertEquals("a refused join never leaves the phone joined to a link the host closed (stuck in " + stuck + " of 25 tries)", 0, stuck);
    }

    // 7. The "away too long" notice ends with its game ------------------------------------------

    @Test
    public void theAwayTooLongNoticeEndsWhenThisSeatIsNoLongerGivenUp() throws Exception {
        NetSession.notice = Relay.GAVE_UP_HOST_TEXT;
        handler().onState(state("setup", false));            // the game is over; this seat was never given up in the new game
        assertEquals("the notice about a game that is over goes", "", NetSession.notice);
    }

    @Test
    public void theAwayTooLongNoticeHoldsWhileThisSeatIsGivenUp() throws Exception {
        NetSession.notice = Relay.GAVE_UP_TEXT;
        handler().onState(state("playing", true));
        assertEquals("while the seat is given up the notice still holds", Relay.GAVE_UP_TEXT, NetSession.notice);
    }

    // 8. A phone that did not come back is a banner in the host's lobby, whatever screen was in front ----------

    private static Map<String, Object> twoPhones(String phase, boolean joinerGaveUp, boolean joinerAway) {
        List<Object> players = new ArrayList<>();
        players.add(Json.obj("id", 1L, "name", "Me", "host", true, "ready", true, "away", false, "gaveUp", false));
        players.add(Json.obj("id", 2L, "name", "FP3Joiner", "host", false, "ready", true, "away", joinerAway, "gaveUp", joinerGaveUp));
        return Json.obj("state", phase, "you", 1L, "players", players);
    }

    private static final String SITTING_OUT = "FP3Joiner did not come back; their teams sit out";

    @Test
    public void aPhoneThatDidNotComeBackIsABannerWhileTheGameRuns() {
        assertEquals(java.util.Collections.singletonList(SITTING_OUT),
                NetSession.sittingOutBanners(twoPhones("playing", true, false), true));
    }

    @Test
    public void the_banner_survives_a_later_notice_in_the_single_notice_slot() throws Exception {
        handler().onNotice(SITTING_OUT);
        handler().onNotice("You left the game. You can rejoin within 2 minutes.");   // the host's own notice takes the slot
        assertEquals("the banner comes from the relay's state, not from the notice slot",
                java.util.Collections.singletonList(SITTING_OUT),
                NetSession.sittingOutBanners(twoPhones("playing", true, false), true));
    }

    @Test
    public void the_banner_ends_when_the_phone_is_back() {
        assertEquals(java.util.Collections.emptyList(), NetSession.sittingOutBanners(twoPhones("playing", false, false), true));
    }

    @Test
    public void the_banner_ends_with_the_game() {
        assertEquals(java.util.Collections.emptyList(), NetSession.sittingOutBanners(twoPhones("setup", true, false), false));
    }

    // 9. The rejoin-window note goes with its game -----------------------------------------------

    @Test
    public void theRejoinWindowNoticeEndsWithItsGame() throws Exception {
        NetSession.notice = "You left the game. You can rejoin within 2 minutes.";
        handler().onSummary(Json.obj("t", "summary", "reason", "every engine finished", "text", "Game over"));
        assertEquals("the game is over: 'You can rejoin within' is stale and goes", "", NetSession.notice);
    }
}
