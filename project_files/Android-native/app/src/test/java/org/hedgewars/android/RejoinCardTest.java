package org.hedgewars.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.hedgewars.android.net.HostAdvert;
import org.hedgewars.android.net.Json;
import org.hedgewars.android.net.Relay;
import org.hedgewars.android.net.RelayClient;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * The Rejoin card after a force-stop (issue #1): a note from a game that ended must not offer "Rejoin your game". The card is shown only
 * when the host the note names is found and its probe reports the same game still playing. Also the game id the note keeps, and a game
 * summary that arrives before any state (it used to throw).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = TestSupport.QUALIFIERS)
public class RejoinCardTest {
    private static final String HOST = "192.168.1.20";
    private static final int PORT = Relay.PHONE_PORT;

    private final List<HostDiscovery.Listener> listeners = new ArrayList<>();
    private final AtomicInteger probes = new AtomicInteger();
    /** What the probe of the host answers in the test (null: no answer). */
    private volatile HostAdvert.Found answer;

    @Before
    public void freshProcess() throws Exception {
        TestSupport.resetStatics();
        NetSession.clearRejoin(app());
        LocalNetwork.forceGranted = true;
        probes.set(0);
        answer = null;
        NetSession.discoveryFactory = c -> new HostDiscovery() {
            @Override public void start(Listener l) {
                listeners.add(l);
                l.status("Searching for hosts on this Wi-Fi...", false);
            }
            @Override public void stop() { }
        };
        NetSession.hostProbe = (c, host, port) -> {
            probes.incrementAndGet();
            return answer;
        };
    }

    private static Context app() {
        return org.robolectric.RuntimeEnvironment.getApplication();
    }

    // Helpers ------------------------------------------------------------------------------------

    private static HostAdvert.Found found(String host) {
        HostAdvert.Found f = new HostAdvert.Found();
        f.serviceName = "hp-" + host;
        f.host = host;
        f.port = PORT;
        f.name = "Host phone";
        f.parsedOk = true;
        f.lobbyProto = Relay.PROTO;
        return f;
    }

    /** A probe answer as the relay sends it: this version reports its game and state. */
    private static HostAdvert.Found probeAnswer(long game, boolean playing) {
        Map<String, String> rec = HostAdvert.txt("0.3.0-tv21", org.hedgewars.android.net.Compat.VALUE, org.hedgewars.android.net.Compat.ENGINE_PROTOCOL,
                "Host phone", 8766, "abc123", false);
        rec.putAll(HostAdvert.liveFields(game, playing));
        return HostAdvert.parseProbe(HostAdvert.probeReply(rec), HOST, PORT);
    }

    /** An older host: its probe reply has no game or state. */
    private static HostAdvert.Found olderHostAnswer() {
        Map<String, String> rec = HostAdvert.txt("0.3.0-tv17", org.hedgewars.android.net.Compat.VALUE, org.hedgewars.android.net.Compat.ENGINE_PROTOCOL,
                "Host phone", 8766, "abc123", false);
        return HostAdvert.parseProbe(HostAdvert.probeReply(rec), HOST, PORT);
    }

    /** The Network screen with its search running, the host listed, and the screen drawn once as a pass the system would make. */
    private static ActivityController<NetLobbyActivity> chooser() {
        return Robolectric.buildActivity(NetLobbyActivity.class).create().start().resume().visible();
    }

    private void listHost(NetLobbyActivity a) throws Exception {
        listeners.get(0).hosts(Arrays.asList(found(HOST)));
        redraw(a, "render");
    }

    private static void redraw(NetLobbyActivity a, String name) throws Exception {
        Method m = NetLobbyActivity.class.getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(a);
    }

    /** Runs the probes and the screen's own posts until the condition holds (the probe runs on its own thread). */
    private static void settle(BooleanSupplier done) throws Exception {
        for (int i = 0; i < 500 && !done.getAsBoolean(); i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void collect(android.view.View v, List<String> texts) {
        if (v instanceof android.widget.TextView) texts.add(((android.widget.TextView) v).getText().toString());
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), texts);
        }
    }

    private static boolean cardShown(NetLobbyActivity a) {
        List<String> texts = new ArrayList<>();
        collect(a.getWindow().getDecorView(), texts);
        for (String s : texts) if (s.contains("Rejoin your game")) return true;
        return false;
    }

    private static void keepNote(String game) {
        NetSession.saveRejoin(app(), HOST, PORT, "Joiner", "token-1", false, game);
    }

    /** The relay's handler for this phone's link (it is private; the relay delivers to it the same way). */
    private static RelayClient.Handler handler() throws Exception {
        Field f = NetSession.class.getDeclaredField("HANDLER");
        f.setAccessible(true);
        return (RelayClient.Handler) f.get(null);
    }

    // The card rule -----------------------------------------------------------------------------

    @Test
    public void theCardShowsWhenTheHostIsFoundWithTheSameGamePlaying() throws Exception {
        keepNote("2");
        answer = probeAnswer(2, true);
        NetLobbyActivity a = chooser().get();
        listHost(a);
        settle(() -> probes.get() > 0 && cardShown(a));
        assertTrue("the host reports game 2 still playing: the card is offered", cardShown(a));
        assertTrue("the note stays", NetSession.loadRejoin(app()) != null);
    }

    @Test
    public void aGameThatIsOverGivesNoCardAndTheNoteGoesQuietly() throws Exception {
        keepNote("2");
        answer = probeAnswer(2, false);
        NetLobbyActivity a = chooser().get();
        listHost(a);
        settle(() -> probes.get() > 0 && NetSession.loadRejoin(app()) == null);
        assertTrue("the host was asked", probes.get() > 0);
        assertFalse("game 2 is over: no card", cardShown(a));
        assertNull("the note of the finished game is deleted", NetSession.loadRejoin(app()));
    }

    @Test
    public void anotherGameGivesNoCardAndTheNoteGoes() throws Exception {
        keepNote("2");
        answer = probeAnswer(3, true);
        NetLobbyActivity a = chooser().get();
        listHost(a);
        settle(() -> probes.get() > 0 && NetSession.loadRejoin(app()) == null);
        assertFalse("game 3 is not the game the note names: no card", cardShown(a));
        assertNull("the note is deleted", NetSession.loadRejoin(app()));
    }

    @Test
    public void aHostNotFoundYetGivesNoCardAndKeepsTheNote() throws Exception {
        keepNote("2");
        answer = probeAnswer(2, true);
        NetLobbyActivity a = chooser().get();
        redraw(a, "render");
        settle(() -> false);
        assertEquals("nothing is listed, so nothing is asked", 0, probes.get());
        assertFalse("the card appears once the host is found", cardShown(a));
        assertTrue("the note is kept until the host is found", NetSession.loadRejoin(app()) != null);
    }

    @Test
    public void anOlderHostKeepsTodaysBehaviour() throws Exception {
        keepNote("2");
        answer = olderHostAnswer();
        NetLobbyActivity a = chooser().get();
        listHost(a);
        settle(() -> probes.get() > 0 && cardShown(a));
        assertTrue("a host that does not report its game gets the card as before", cardShown(a));
        assertTrue("the note stays", NetSession.loadRejoin(app()) != null);
    }

    /**
     * The search finds the host after the screen was drawn (a relaunch: the list is empty at the first draw, and the search fills it).
     * Nothing else redraws the screen, so the card must be asked for by the list itself (device report, issue #1, item 2).
     */
    @Test
    public void theCardAppearsWhenTheSearchFindsTheHostAfterTheScreenWasDrawn() throws Exception {
        keepNote("2");
        answer = probeAnswer(2, true);
        NetLobbyActivity a = chooser().get();
        assertFalse("nothing is listed yet, so no card", cardShown(a));
        listeners.get(0).hosts(Arrays.asList(found(HOST)));          // the search lists the host; no render() call here
        settle(() -> probes.get() > 0 && cardShown(a));
        assertTrue("the host was asked once it was listed", probes.get() > 0);
        assertTrue("the card appears without any other redraw", cardShown(a));
    }

    /**
     * A probe that gets no answer (a busy Wi-Fi, a late reply) must not hide the card for the rest of the search: it is asked again.
     */
    @Test
    public void aFailedProbeIsAskedAgainAndTheCardAppears() throws Exception {
        keepNote("2");
        answer = null;
        NetLobbyActivity a = chooser().get();
        listeners.get(0).hosts(Arrays.asList(found(HOST)));
        settle(() -> probes.get() > 0);
        assertFalse("the first probe got no answer: no card yet", cardShown(a));
        answer = probeAnswer(2, true);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(30));   // the retry comes after a few seconds
        settle(() -> cardShown(a));
        assertTrue("the host is asked again and its answer shows the card", cardShown(a));
        assertTrue("the host was asked more than once", probes.get() > 1);
    }

    // The game id in the note ------------------------------------------------------------------

    @Test
    public void theNoteKeepsTheGameIdOfTheGameItWasWrittenFor() throws Exception {
        NetSession.lastHostName = HOST;
        NetSession.lastPort = PORT;
        handler().onJoined(1, "Joiner", "token-1");
        RelayClient.StartInfo s = new RelayClient.StartInfo();
        s.you = 1;
        s.game = 4;
        s.token = "game-4";
        s.nick = "Joiner";
        s.host = HOST;
        s.port = PORT;
        handler().onStart(s);
        NetSession.RejoinNote n = NetSession.loadRejoin(app());
        assertTrue("the note is kept for the game that started", n != null);
        assertEquals("the note names game 4", "4", n.game);
    }

    // A summary before any state -------------------------------------------------------------------

    @Test
    public void aSummaryBeforeAnyStateDoesNotThrow() throws Exception {
        keepNote("2");
        NetSession.state = null;
        handler().onSummary(Json.obj("t", "summary", "reason", "every engine finished", "text", "Game over"));
        assertNull("the game is over: the note goes", NetSession.loadRejoin(app()));
        assertEquals("the summary is kept", "Game over", NetSession.relaySummary);
        assertFalse(NetSession.inGame);
    }
}
