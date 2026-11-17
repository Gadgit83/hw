package org.hedgewars.android.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.SocketFactory;

import org.junit.Test;

/** RelayClient opens its socket with the factory the app gives it (issues #14 and #15), and still works without one. */
public class RelayClientSocketFactoryTest {
    /** Counts the sockets it makes; each is a plain socket, so the connection itself is real. */
    private static final class CountingFactory extends SocketFactory {
        final AtomicInteger made = new AtomicInteger();

        @Override public Socket createSocket() throws IOException {
            made.incrementAndGet();
            return new Socket();
        }

        @Override public Socket createSocket(String host, int port) throws IOException { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { throw new UnsupportedOperationException(); }
    }

    private static RelayClient.Handler quiet() {
        return new RelayClient.Handler() {
            @Override public void onState(java.util.Map<String, Object> state) { }
            @Override public void onStart(RelayClient.StartInfo start) { }
            @Override public void onSummary(java.util.Map<String, Object> summary) { }
            @Override public void onNotice(String text) { }
            @Override public void onClosed(String reason) { }
        };
    }

    @Test
    public void joinUsesTheGivenFactory() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            CountingFactory f = new CountingFactory();
            RelayClient rc = new RelayClient("127.0.0.1", server.getLocalPort(), "tester", "build", false, new ArrayList<>(), quiet())
                    .socketFactory(f);
            rc.connect();
            try (Socket accepted = server.accept()) {
                assertTrue("the connection arrives", accepted.isConnected());
            }
            assertEquals("one socket made by the factory", 1, f.made.get());
            rc.close();
        }
    }

    @Test
    public void joinWithoutFactoryStillConnects() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            RelayClient rc = new RelayClient("127.0.0.1", server.getLocalPort(), "tester", "build", false, new ArrayList<>(), quiet());
            rc.connect();
            try (Socket accepted = server.accept()) {
                assertTrue("the connection arrives", accepted.isConnected());
            }
            rc.close();
        }
    }
}
