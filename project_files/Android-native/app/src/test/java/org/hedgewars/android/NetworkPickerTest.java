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
