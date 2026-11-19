package org.hedgewars.android;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * The joining service (issue #1, item 1). Android throws ForegroundServiceDidNotStartInTimeException when a service started with
 * startForegroundService is stopped, or simply not made foreground, within five seconds, so a join that the relay refused must never
 * start it, and a service that exists must be in the foreground at once. Robolectric does not enforce the five seconds, so the tests
 * check what the five seconds need: the service is made foreground when it is created, and a refused join starts nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = TestSupport.QUALIFIERS)
public class JoinServiceTest {
    private static final String HOST = "127.0.0.1";

    @Before
    public void freshProcess() throws Exception {
        TestSupport.resetStatics();
    }

    private static android.app.Application app() {
        return RuntimeEnvironment.getApplication();
    }

    /** Runs the main looper and the relay client's own threads until the condition holds, or about five seconds pass. */
    private static void settle(BooleanSupplier done) throws Exception {
        for (int i = 0; i < 500 && !done.getAsBoolean(); i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** A relay that reads the hello, then sends {@code reply} (one line) and, if {@code keepOpen}, waits until the phone leaves. */
    private static Thread fakeRelay(ServerSocket relay, String reply, boolean keepOpen) {
        Thread t = new Thread(() -> {
            try (Socket s = relay.accept()) {
                new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();   // the hello
                s.getOutputStream().write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().flush();
                if (keepOpen) Thread.sleep(3000);
            } catch (IOException | InterruptedException ignored) {
                // the phone went away first
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    public void aServiceStartedAndStoppedAtOnceIsInTheForegroundWhenItExists() throws Exception {
        JoinService.start(app(), HOST);
        JoinService.stop(app());
        JoinService svc = Robolectric.buildService(JoinService.class).create().get();
        assertNotNull("the service calls startForeground when it is created, so a stop that comes before its first command cannot end it "
                + "in the five seconds Android allows", shadowOf(svc).getLastForegroundNotification());
    }

    @Test
    public void aJoinTheRelayRefusesNeverStartsTheService() throws Exception {
        try (ServerSocket relay = new ServerSocket(0, 4, InetAddress.getByName(HOST))) {
            relay.setSoTimeout(5000);
            fakeRelay(relay, "{\"t\":\"refuse\",\"reason\":\"A game is running on this host. Try again when it has ended.\"}", false);
            try {
                NetSession.connect(app(), HOST, relay.getLocalPort(), "Joiner", false);
            } catch (IOException refused) {
                // the refusal may come back as the connect's own error: either way no service may start
            }
            settle(() -> NetSession.closedReason.length() > 0);
            assertTrue("the refusal reached the phone", NetSession.closedReason.length() > 0);
            Intent started = shadowOf(app()).getNextStartedService();
            assertNull("a refused join starts no joining service (started: " + started + ")", started);
        } finally {
            NetSession.leave();
        }
    }

    @Test
    public void anAcceptedJoinStartsTheService() throws Exception {
        try (ServerSocket relay = new ServerSocket(0, 4, InetAddress.getByName(HOST))) {
            relay.setSoTimeout(5000);
            fakeRelay(relay, "{\"t\":\"joined\",\"you\":2,\"name\":\"Joiner\",\"rejoin\":\"tok\"}", true);
            NetSession.connect(app(), HOST, relay.getLocalPort(), "Joiner", false);
            Intent[] started = new Intent[1];
            settle(() -> {
                if (started[0] == null) started[0] = shadowOf(app()).getNextStartedService();   // taken once: the shadow pops it
                return started[0] != null;
            });
            assertNotNull("an accepted join starts the joining service", started[0]);
        } finally {
            NetSession.leave();
        }
    }
}
