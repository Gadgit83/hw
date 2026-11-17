package org.hedgewars.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.RouteInfo;

import java.net.InetAddress;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowNetworkInfo;

/**
 * The network choice on the system's own objects, on Android 9 (SDK 28, the Moto G6 of issue #2) and on the current SDK: a stale
 * CONNECTING Wi-Fi network next to a connected one, and a VPN whose routes carry the LAN (issue #14).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34})
public class NetworkRobolectricTest {
    private final Context app = RuntimeEnvironment.getApplication();
    private final ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
    private final ShadowConnectivityManager scm = shadowOf(cm);

    /** The capabilities of a network. The shadow adds the transport (addTransportType is not in the public SDK stubs). */
    private static NetworkCapabilities caps(int transport, boolean notSuspended) {
        NetworkCapabilities c = ShadowNetworkCapabilities.newInstance();
        shadowOf(c).addTransportType(transport);
        if (notSuspended) shadowOf(c).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
        return c;
    }

    /** A LinkAddress; its constructor is hidden from the public SDK, so it is made by reflection (the tests run on android-all). */
    private static LinkAddress linkAddress(String address, int prefix) throws Exception {
        java.lang.reflect.Constructor<LinkAddress> k = LinkAddress.class.getDeclaredConstructor(InetAddress.class, int.class);
        k.setAccessible(true);
        return k.newInstance(InetAddress.getByName(address), prefix);
    }

    /** Calls a method the public SDK stubs hide (addLinkAddress, addRoute): the tests run on android-all, where it exists. */
    private static void call(Object target, String name, Class<?> type, Object arg) throws Exception {
        java.lang.reflect.Method m = target.getClass().getDeclaredMethod(name, type);
        m.setAccessible(true);
        m.invoke(target, arg);
    }

    /**
     * A route to {@code dest/prefix} over {@code iface}. Its constructor is hidden from the public SDK too. The gateway is given as
     * 0.0.0.0 rather than null: with null, the Android 9 runtime reads Inet4Address.ANY, which the test JDK does not have.
     */
    private static RouteInfo route(String dest, int prefix, String iface) throws Exception {
        IpPrefix p = new IpPrefix(InetAddress.getByName(dest), prefix);
        java.lang.reflect.Constructor<RouteInfo> k;
        try {
            k = RouteInfo.class.getDeclaredConstructor(IpPrefix.class, InetAddress.class, String.class);
            k.setAccessible(true);
            return k.newInstance(p, InetAddress.getByName("0.0.0.0"), iface);
        } catch (NoSuchMethodException e) {
            k = RouteInfo.class.getDeclaredConstructor(IpPrefix.class, InetAddress.class, String.class, int.class);
            k.setAccessible(true);
            return k.newInstance(p, InetAddress.getByName("0.0.0.0"), iface, 1);      // RTN_UNICAST
        }
    }

    private static LinkProperties lp(String iface, String address) throws Exception {
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(iface);
        call(lp, "addLinkAddress", LinkAddress.class, linkAddress(address, 24));
        return lp;
    }

    /** A network the system knows: its state, its capabilities, its addresses. */
    private Network add(int netId, int type, NetworkInfo.DetailedState detailed, NetworkInfo.State state, NetworkCapabilities caps,
                        LinkProperties lp) {
        Network n = ShadowNetwork.newInstance(netId);
        scm.addNetwork(n, ShadowNetworkInfo.newInstance(detailed, type, 0, true, state));
        scm.setNetworkCapabilities(n, caps);
        scm.setLinkProperties(n, lp);
        return n;
    }

    @Test
    public void aStaleConnectingWifiIsRejectedNextToTheConnectedOne() throws Exception {
        // Issue #2: network 106 still CONNECTING after a roam, 108 connected. Neither the state nor the address of 106 may win.
        Network stale = add(106, ConnectivityManager.TYPE_WIFI, NetworkInfo.DetailedState.CONNECTING, NetworkInfo.State.CONNECTING,
                caps(NetworkCapabilities.TRANSPORT_WIFI, true), lp("wlan0", "192.168.1.61"));
        Network live = add(108, ConnectivityManager.TYPE_WIFI, NetworkInfo.DetailedState.CONNECTED, NetworkInfo.State.CONNECTED,
                caps(NetworkCapabilities.TRANSPORT_WIFI, true), lp("wlan0", "192.168.1.62"));
        assertFalse("the CONNECTING network is not usable", NetworkPicker.isConnected(cm, stale, cm.getNetworkCapabilities(stale)));
        assertTrue("the connected network is usable", NetworkPicker.isConnected(cm, live, cm.getNetworkCapabilities(live)));
        assertEquals("the connected network is chosen", live, NetworkPicker.lanNetwork(app));
        assertEquals("and on the named interface too", live, NetworkPicker.networkNamed(app, "wlan0"));
    }

    @Test
    public void aSuspendedWifiIsNotUsableOnApi28AndUp() throws Exception {
        // Both tested SDKs (28 and 34) are 28 or later: a CONNECTED network without NET_CAPABILITY_NOT_SUSPENDED is not usable.
        Network suspended = add(110, ConnectivityManager.TYPE_WIFI, NetworkInfo.DetailedState.CONNECTED, NetworkInfo.State.CONNECTED,
                caps(NetworkCapabilities.TRANSPORT_WIFI, false), lp("wlan1", "192.168.2.20"));
        assertTrue("the test runs on API 28 or later", android.os.Build.VERSION.SDK_INT >= 28);
        assertFalse("a suspended network is not usable",
                NetworkPicker.isConnected(cm, suspended, cm.getNetworkCapabilities(suspended)));
    }

    @Test
    public void aStaleNetworkWithNoStateIsNotUsable() throws Exception {
        Network unknown = ShadowNetwork.newInstance(111);
        scm.setNetworkCapabilities(unknown, caps(NetworkCapabilities.TRANSPORT_WIFI, true));
        scm.setLinkProperties(unknown, lp("wlan2", "192.168.3.9"));
        assertNull("no state reported is not a connected network", NetworkPicker.lanNetwork(app));
    }

    @Test
    public void aVpnRouteFromItsLinkPropertiesCarriesTheLan() throws Exception {
        // Issue #14: tun0 has a route for 192.168.1.0/24 (not its own subnet), and a split default that must not count.
        LinkProperties vpn = new LinkProperties();
        vpn.setInterfaceName("tun0");
        call(vpn, "addLinkAddress", LinkAddress.class, linkAddress("100.100.10.4", 32));
        call(vpn, "addRoute", RouteInfo.class, route("192.168.1.0", 24, "tun0"));
        call(vpn, "addRoute", RouteInfo.class, route("0.0.0.0", 1, "tun0"));
        add(120, ConnectivityManager.TYPE_VPN, NetworkInfo.DetailedState.CONNECTED, NetworkInfo.State.CONNECTED,
                caps(NetworkCapabilities.TRANSPORT_VPN, true), vpn);
        assertTrue("the LAN host is routed over the VPN", NetworkPicker.routesTo("192.168.1.143", NetworkPicker.vpnRoutes(app)));
        assertFalse("another LAN is not", NetworkPicker.routesTo("192.168.2.9", NetworkPicker.vpnRoutes(app)));
    }

    @Test
    public void aVpnWithoutTheLanRouteLeavesTheWifiChosen() throws Exception {
        LinkProperties vpn = new LinkProperties();
        vpn.setInterfaceName("tun0");
        call(vpn, "addRoute", RouteInfo.class, route("10.8.0.0", 24, "tun0"));
        add(121, ConnectivityManager.TYPE_VPN, NetworkInfo.DetailedState.CONNECTED, NetworkInfo.State.CONNECTED,
                caps(NetworkCapabilities.TRANSPORT_VPN, true), vpn);
        assertNotNull(NetworkPicker.vpnRoutes(app));
        assertFalse(NetworkPicker.routesTo("192.168.1.143", NetworkPicker.vpnRoutes(app)));
    }
}
