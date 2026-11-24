package org.hedgewars.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** The routing rules of issues #14 and #15 that need no Android: which interface is which, and which target is on the VPN. */
public class NetworkPickerTest {
    @Test
    public void interfacesAreClassified() {
        assertEquals("Wi-Fi", NetworkPicker.kindOf("wlan0"));
        assertEquals("Hotspot", NetworkPicker.kindOf("swlan0"));
        assertEquals("Hotspot", NetworkPicker.kindOf("ap0"));
        assertEquals("Hotspot", NetworkPicker.kindOf("softap0"));
        assertEquals("Ethernet", NetworkPicker.kindOf("eth0"));
        assertEquals("USB tether", NetworkPicker.kindOf("rndis0"));
        assertEquals("VPN", NetworkPicker.kindOf("tun0"));
        assertEquals("VPN", NetworkPicker.kindOf("tailscale0"));
        assertNull("cellular is not offered", NetworkPicker.kindOf("rmnet_data0"));
        assertNull("Wi-Fi Direct is not offered", NetworkPicker.kindOf("p2p0"));
        assertNull("apn0 is not a hotspot", NetworkPicker.kindOf("apn0"));
    }

    @Test
    public void subnetsAreCompared() {
        assertTrue(NetworkPicker.sameSubnet("192.168.1.62", "192.168.1.1", 24));
        assertFalse(NetworkPicker.sameSubnet("192.168.2.62", "192.168.1.1", 24));
        assertTrue(NetworkPicker.sameSubnet("100.100.1.1", "100.64.0.4", 10));
        assertFalse(NetworkPicker.sameSubnet("100.128.0.1", "100.64.0.4", 10));
        assertTrue(NetworkPicker.sameSubnet("10.1.2.3", "10.0.0.0", 8));
        assertFalse(NetworkPicker.sameSubnet("10.1.2.3", "10.0.0.0", 33));
        assertFalse("a name is never on a subnet", NetworkPicker.sameSubnet("fairphone.local", "192.168.1.1", 24));
    }

    @Test
    public void vpnRouteIsTheVpnSubnetOrTailscaleWhileAVpnIsOn() {
        List<NetworkPicker.Iface> wifiOnly = Arrays.asList(new NetworkPicker.Iface("wlan0", "192.168.1.62", "Wi-Fi", 24));
        List<NetworkPicker.Iface> withVpn = Arrays.asList(wifiOnly.get(0), new NetworkPicker.Iface("tun0", "100.64.0.4", "VPN", 32));
        // the owner's case: a Wi-Fi address with Tailscale on goes over the Wi-Fi
        assertFalse(NetworkPicker.onVpnRoute("192.168.1.62", withVpn, true));
        // a Tailscale peer goes through the VPN, even when the VPN's own prefix is only the phone's address
        assertTrue(NetworkPicker.onVpnRoute("100.64.0.9", withVpn, true));
        // without a VPN, a 100.x address is an ordinary address
        assertFalse(NetworkPicker.onVpnRoute("100.64.0.9", wifiOnly, false));
    }

    @Test
    public void vpnSubnetTargetsGoThroughTheVpnAndOthersStayOnTheLan() {
        List<NetworkPicker.Iface> withVpn = Arrays.asList(new NetworkPicker.Iface("tun0", "10.8.0.2", "VPN", 24));
        assertTrue(NetworkPicker.onVpnRoute("10.8.0.9", withVpn, true));
        assertFalse(NetworkPicker.onVpnRoute("192.168.43.10", withVpn, true));
    }

    @Test
    public void theVpnHintSaysSoOnlyWhenTheVpnCannotBeBypassed() {
        // not bypassable: network games do go through the VPN, so the hint says that
        assertEquals("A VPN is on that cannot be bypassed, so network games go through it. They work when the VPN reaches this Wi-Fi.",
                NetworkPicker.vpnHint(true, false));
        // bypassable: a join can take the Wi-Fi, so today's hint stays
        assertEquals(NetworkPicker.VPN_HINT, NetworkPicker.vpnHint(true, true));
        // unknown (API 33 and below, or no VPN network reported): today's hint stays
        assertEquals(NetworkPicker.VPN_HINT, NetworkPicker.vpnHint(true, null));
        // no VPN: the hint is hidden, and its text is today's
        assertEquals(NetworkPicker.VPN_HINT, NetworkPicker.vpnHint(false, false));
    }

    @Test
    public void theConnectedLineNamesTheInterfaceAndSaysWhenAVpnCarriesAWifiJoin() {
        assertEquals("join 192.168.1.164: connected from 192.168.1.62 on wlan0",
                NetworkPicker.connectedLine("192.168.1.164", "192.168.1.62", "wlan0", false, false));
        // the owner's case: bound to the Wi-Fi, but the tunnel carried it
        assertEquals("join 192.168.1.164: connected from 100.64.0.4 on tun0: the VPN carries it (this VPN cannot be bypassed)",
                NetworkPicker.connectedLine("192.168.1.164", "100.64.0.4", "tun0", true, true));
        // on the default network (not bound to the Wi-Fi), a VPN carrying it is what was asked for: no note
        assertEquals("join 192.168.1.164: connected from 100.64.0.4 on tun0",
                NetworkPicker.connectedLine("192.168.1.164", "100.64.0.4", "tun0", false, true));
        // bound to the Wi-Fi and it went out on the Wi-Fi: no note
        assertEquals("join 192.168.1.164: connected from 192.168.1.62 on wlan0",
                NetworkPicker.connectedLine("192.168.1.164", "192.168.1.62", "wlan0", true, false));
        // no interface found for the local address
        assertEquals("join 192.168.1.164: connected from 192.168.1.62 on an unknown interface",
                NetworkPicker.connectedLine("192.168.1.164", "192.168.1.62", null, false, false));
    }

    @Test
    public void aJoinSocketConnectsLikeAPlainSocket() throws Exception {
        // the log after the connect must not change the connection (on the JVM there is no Android log: the line is skipped)
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))) {
            java.net.Socket s = new NetworkPicker.JoinSocket("127.0.0.1", false);
            s.connect(new java.net.InetSocketAddress("127.0.0.1", server.getLocalPort()), 2000);
            assertTrue(s.isConnected());
            try (java.net.Socket accepted = server.accept()) {
                assertTrue(accepted.isConnected());
            }
            s.close();
        }
    }

    @Test
    public void anIpv4MappedAddressIsPrintedPlainly() throws Exception {
        java.net.InetAddress mapped = java.net.InetAddress.getByName("::ffff:100.64.0.4");
        assertEquals("100.64.0.4", NetworkPicker.plainAddress(mapped));
        assertEquals("192.168.1.62", NetworkPicker.plainAddress(java.net.InetAddress.getByName("192.168.1.62")));
    }

    @Test
    public void theSubnetSearchProbesTheWifiThenEthernetNeverAVpnOrHotspot() {
        NetworkPicker.Iface vpn = new NetworkPicker.Iface("tun0", "10.8.0.2", "VPN", 24);
        NetworkPicker.Iface hotspot = new NetworkPicker.Iface("swlan0", "192.168.43.1", "Hotspot", 24);
        NetworkPicker.Iface eth = new NetworkPicker.Iface("eth0", "10.2.0.7", "Ethernet", 24);
        NetworkPicker.Iface wifi = new NetworkPicker.Iface("wlan0", "192.168.1.62", "Wi-Fi", 24);
        assertEquals(wifi, NetworkPicker.lanIface(Arrays.asList(vpn, eth, wifi)));
        assertEquals("Ethernet when there is no Wi-Fi", eth, NetworkPicker.lanIface(Arrays.asList(hotspot, vpn, eth)));
        assertNull("nothing to probe with only a VPN and a hotspot", NetworkPicker.lanIface(Arrays.asList(vpn, hotspot)));
        assertNull(NetworkPicker.lanIface(Arrays.asList()));
    }
}
