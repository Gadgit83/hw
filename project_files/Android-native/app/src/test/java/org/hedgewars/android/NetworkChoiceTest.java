package org.hedgewars.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Issues #2, #14 and #15 without Android: the network choice (a stale CONNECTING Wi-Fi network against a connected one; a VPN that
 * routes the LAN against one that does not), and the fallback when a network refuses the socket (EPERM), which must still connect.
 */
public class NetworkChoiceTest {
    private static NetworkPicker.Lan<String> lan(String name, String iface, boolean connected, boolean validated, boolean active) {
        return new NetworkPicker.Lan<>(name, iface, true, false, connected, validated, true, active);
    }

    @Test
    public void aStaleConnectingWifiNetworkIsNotChosenForTheConnectedOne() {
        // The Fairphone 3 case: network 106 still CONNECTING after a roam, listed first; 108 is connected and the primary one.
        List<NetworkPicker.Lan<String>> nets = Arrays.asList(
                lan("106", "wlan0", false, false, false),
                lan("108", "wlan0", true, true, true));
        assertEquals("108", NetworkPicker.chooseLan(nets, "wlan0"));
        // and without the active flag (an older system), the connected one is still the only choice
        List<NetworkPicker.Lan<String>> noActive = Arrays.asList(
                lan("106", "wlan0", false, false, false),
                lan("108", "wlan0", true, false, false));
        assertEquals("108", NetworkPicker.chooseLan(noActive, "wlan0"));
    }

    @Test
    public void theActiveWifiOrEthernetNetworkWinsOverAnotherConnectedOne() {
        NetworkPicker.Lan<String> eth = new NetworkPicker.Lan<>("eth", "eth0", false, true, true, true, true, true);
        assertEquals("the active Ethernet is used", "eth", NetworkPicker.chooseLan(Arrays.asList(lan("wifi", "wlan0", true, true, false), eth), null));
    }

    @Test
    public void wifiBeforeEthernetAndValidatedBeforeNotWhenNothingIsActive() {
        NetworkPicker.Lan<String> eth = new NetworkPicker.Lan<>("eth", "eth0", false, true, true, true, true, false);
        assertEquals("Wi-Fi first", "wifi", NetworkPicker.chooseLan(Arrays.asList(eth, lan("wifi", "wlan0", true, true, false)), null));
        assertEquals("the validated one first", "wifi2", NetworkPicker.chooseLan(
                Arrays.asList(lan("wifi1", "wlan0", true, false, false), lan("wifi2", "wlan1", true, true, false)), null));
        assertEquals("the chosen interface first", "wifi1", NetworkPicker.chooseLan(
                Arrays.asList(lan("wifi2", "wlan1", true, true, false), lan("wifi1", "wlan0", true, true, false)), "wlan0"));
    }

    @Test
    public void noConnectedNetworkMeansTheDefaultNetwork() {
        assertNull(NetworkPicker.chooseLan(Arrays.asList(lan("106", "wlan0", false, false, false)), null));
        assertNull("a network without an IPv4 address is not usable",
                NetworkPicker.chooseLan(Arrays.asList(new NetworkPicker.Lan<>("v6", "wlan0", true, false, true, true, false, true)), null));
        assertNull(NetworkPicker.chooseLan(new ArrayList<NetworkPicker.Lan<String>>(), null));
    }

    @Test
    public void aVpnRoutingTheLanSendsTheJoinOnTheDefaultNetwork() {
        // the Fairphone 4 case: tun0 carries 192.168.1.0/24, so the host is reached through the tunnel
        List<NetworkPicker.Route> routes = Arrays.asList(new NetworkPicker.Route("192.168.1.0", 24), new NetworkPicker.Route("100.64.0.0", 10));
        assertTrue(NetworkPicker.routesTo("192.168.1.143", routes));
        assertFalse("another LAN is not routed", NetworkPicker.routesTo("192.168.2.9", routes));
        assertFalse("no routes, no VPN route", NetworkPicker.routesTo("192.168.1.143", new ArrayList<NetworkPicker.Route>()));
    }

    @Test
    public void aVpnThatDoesNotRouteTheLanKeepsTheWifi() {
        // only its own tunnel subnet, and a full tunnel's split defaults: neither takes a LAN host onto the tunnel by a specific route
        List<NetworkPicker.Route> own = Arrays.asList(new NetworkPicker.Route("10.8.0.0", 24));
        assertFalse(NetworkPicker.routesTo("192.168.1.143", own));
        List<NetworkPicker.Route> split = Arrays.asList(new NetworkPicker.Route("0.0.0.0", 1), new NetworkPicker.Route("128.0.0.0", 1));
        assertFalse("a split default is not a specific LAN route", NetworkPicker.routesTo("192.168.1.143", split));
        assertFalse("nor is the default route", NetworkPicker.routesTo("192.168.1.143", Arrays.asList(new NetworkPicker.Route("0.0.0.0", 0))));
    }

    @Test
    public void aRefusedBindStillConnectsOnTheDefaultNetwork() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            AtomicInteger asked = new AtomicInteger();
            Socket s = NetworkPicker.openSocket("network-209", net -> {
                asked.incrementAndGet();
                throw new IOException("Binding socket to network 209 failed: EPERM");
            });
            assertEquals("the binding was tried once", 1, asked.get());
            assertNotNull(s);
            assertFalse("the fallback socket is not connected yet", s.isConnected());
            s.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), 2000);
            try (Socket accepted = server.accept()) {
                assertTrue("the connection arrives over the default network", accepted.isConnected());
            }
            s.close();
        }
    }

    @Test
    public void aSecurityExceptionFromTheBindingAlsoFallsBack() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            Socket s = NetworkPicker.openSocket("network-106", net -> { throw new SecurityException("not allowed"); });
            s.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), 2000);
            try (Socket accepted = server.accept()) {
                assertTrue(accepted.isConnected());
            }
            s.close();
        }
    }

    @Test
    public void aWorkingBindingIsUsedAndNoNetworkMeansTheDefaultWithoutBinding() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        Socket made = new Socket();
        Socket got = NetworkPicker.openSocket("network-108", net -> {
            asked.incrementAndGet();
            return made;
        });
        assertTrue("the binder's socket is the one returned", got == made);
        assertEquals(1, asked.get());
        made.close();

        AtomicInteger none = new AtomicInteger();
        Socket plain = NetworkPicker.openSocket(null, net -> {
            none.incrementAndGet();
            return new Socket();
        });
        assertNotNull(plain);
        assertEquals("no network: the binder is not called", 0, none.get());
        plain.close();
    }
}
