package org.hedgewars.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.net.SocketFactory;

/**
 * Which network a phone uses for network games (GitHub issues #14 and #15). With a VPN such as Tailscale on, the default network
 * is the VPN: a join to a Wi-Fi address then goes into the tunnel and is refused, and the phone's announcement may not reach the
 * Wi-Fi. So a join to a local address is opened on the Wi-Fi (or Ethernet) network, and the choice on the Network screen
 * ("Network to use") can pin one interface instead. The rules, in order:
 * <ul>
 *   <li>a loopback target (this phone's own host) uses the default network, as before;</li>
 *   <li>an explicit choice of an interface uses that interface's network, if it is still there;</li>
 *   <li>Automatic: a target on a VPN's subnet (or a Tailscale address, 100.64.0.0/10, while a VPN is on) uses the default network;
 *       any other target uses the Wi-Fi or Ethernet network, if there is one;</li>
 *   <li>no suitable network: the default network.</li>
 * </ul>
 * Only the choice, the interface list and the route are here; the relay client takes the socket factory from
 * {@link #socketFactory}. Not verified on a device with a VPN (none here): the test steps in docs/multiphone-test.md say what to look for.
 */
final class NetworkPicker {
    private NetworkPicker() { }

    /** The saved choice for automatic (Wi-Fi first). */
    static final String AUTO = "";
    static final String PREF_KEY = "netiface";
    static final String AUTO_LABEL = "Automatic (Wi-Fi first)";
    static final String VPN_HINT = "A VPN is on; this phone uses the Wi-Fi for network games";

    /** One IPv4 address of one interface of this phone, offered on the Network screen and used for routing. */
    static final class Iface {
        final String name;
        final String address;
        final String kind;
        final int prefix;
        final boolean vpn;

        Iface(String name, String address, String kind, int prefix) {
            this.name = name;
            this.address = address;
            this.kind = kind;
            this.prefix = prefix;
            this.vpn = "VPN".equals(kind);
        }

        /** "wlan0: Wi-Fi, 192.168.1.62", for the list of networks. */
        String longLabel() { return name + ": " + kind + ", " + address; }

        /** "Wi-Fi 192.168.1.62", for the button. */
        String shortLabel() { return kind + " " + address; }
    }

    /** Test hook: the interfaces this phone has, instead of the real ones (null: the real ones). */
    static volatile java.util.function.Supplier<List<Iface>> interfaces;

    private static volatile List<Iface> cache = Collections.emptyList();
    private static volatile long cacheAt;
    private static volatile boolean refreshing;
    private static final long CACHE_MS = 3000;

    /** The kind of an interface this app offers, or null for one it does not (cellular, Wi-Fi Direct, and the rest). */
    static String kindOf(String name) {
        if (name == null) return null;
        if (name.startsWith("wlan")) return "Wi-Fi";
        if (name.startsWith("swlan") || name.startsWith("softap")) return "Hotspot";
        if (name.startsWith("ap") && name.length() > 2 && Character.isDigit(name.charAt(2))) return "Hotspot";
        if (name.startsWith("eth")) return "Ethernet";
        if (name.startsWith("rndis") || name.startsWith("usb")) return "USB tether";
        if (name.startsWith("tun") || name.startsWith("tailscale") || name.startsWith("wg") || name.startsWith("ppp")
                || name.startsWith("ipsec")) return "VPN";
        return null;
    }

    /** The interfaces with their IPv4 addresses. Reads the system: not for the UI thread (the Network screen uses {@link #cached}). */
    static List<Iface> list() {
        java.util.function.Supplier<List<Iface>> hook = interfaces;
        if (hook != null) return hook.get();
        List<Iface> out = new ArrayList<>();
        try {
            java.util.Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            if (all == null) return out;
            for (NetworkInterface ni : Collections.list(all)) {
                try {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    String kind = kindOf(ni.getName());
                    if (kind == null) continue;
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        InetAddress a = ia.getAddress();
                        if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                        out.add(new Iface(ni.getName(), a.getHostAddress(), kind, ia.getNetworkPrefixLength()));
                    }
                } catch (Exception ignored) {
                    // one interface that cannot be read must not hide the others
                }
            }
        } catch (Exception e) {
            android.util.Log.w(Diag.TAG, "nsd: could not list the network interfaces: " + e);
        }
        return out;
    }

    /** The last list, at most a few seconds old (the Network screen draws from it on the UI thread). */
    static List<Iface> cached() { return cache; }

    /** Refreshes the cached list on a background thread, then calls {@code done} (which must go to the UI thread itself). */
    static synchronized void refresh(final Runnable done) {
        long now = System.currentTimeMillis();
        if (interfaces != null) { cache = list(); cacheAt = now; return; }     // a test hook answers at once
        if (refreshing || now - cacheAt < CACHE_MS) return;
        refreshing = true;
        cacheAt = now;                       // set before the read, so a render during it does not start another one
        Thread t = new Thread(() -> {
            try {
                cache = list();
            } finally {
                refreshing = false;
            }
            if (done != null) done.run();
        }, "net-interfaces");
        t.setDaemon(true);
        t.start();
    }

    /** True if this phone has a VPN up: a VPN interface, or a VPN network. */
    static boolean vpnActive(Context c, List<Iface> ifaces) {
        for (Iface i : ifaces) if (i.vpn) return true;
        ConnectivityManager cm = connectivity(c);
        if (cm == null) return false;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    // The saved choice -------------------------------------------------------------------------

    /** The interface name the user chose, or {@link #AUTO}. */
    static String saved(Context c) {
        try {
            return c.getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE).getString(PREF_KEY, AUTO);
        } catch (RuntimeException e) {
            return AUTO;
        }
    }

    static void save(Context c, String choice) {
        try {
            c.getSharedPreferences(Store.PREFS_NAME, Context.MODE_PRIVATE).edit().putString(PREF_KEY, choice == null ? AUTO : choice).apply();
        } catch (RuntimeException ignored) {
        }
    }

    // Networks ---------------------------------------------------------------------------------

    private static ConnectivityManager connectivity(Context c) {
        try {
            return (ConnectivityManager) c.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The Wi-Fi network (or, failing that, Ethernet) of this phone, or null. Not for the UI thread. */
    static Network lanNetwork(Context c) {
        ConnectivityManager cm = connectivity(c);
        if (cm == null) return null;
        try {
            Network wifi = null, eth = null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && wifi == null) wifi = n;
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) && eth == null) eth = n;
            }
            return wifi != null ? wifi : eth;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The network of the named interface, or null if it is not up. */
    static Network networkNamed(Context c, String iface) {
        ConnectivityManager cm = connectivity(c);
        if (cm == null || iface == null || iface.isEmpty()) return null;
        try {
            for (Network n : cm.getAllNetworks()) {
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null && iface.equals(lp.getInterfaceName())) return n;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /** The network a join to {@code host} uses (see the class comment), or null for the default network. */
    static Network networkFor(Context c, String host) {
        if (host == null || host.isEmpty() || host.startsWith("127.") || host.equals("localhost") || host.equals("::1")) return null;
        String choice = saved(c);
        if (!AUTO.equals(choice)) {
            Network n = networkNamed(c, choice);
            if (n != null) return n;              // the chosen interface is gone: Automatic below
        }
        List<Iface> ifaces = list();
        if (onVpnRoute(host, ifaces, vpnActive(c, ifaces))) return null;
        return lanNetwork(c);
    }

    /** True if {@code host} is reached through a VPN: on a VPN interface's subnet, or a Tailscale address while a VPN is on. */
    static boolean onVpnRoute(String host, List<Iface> ifaces, boolean vpnOn) {
        for (Iface i : ifaces) {
            if (i.vpn && sameSubnet(host, i.address, i.prefix)) return true;
        }
        return vpnOn && sameSubnet(host, "100.64.0.0", 10);
    }

    /** True if both are IPv4 literals and agree on the first {@code prefix} bits. A name that is not a literal is never on a subnet. */
    static boolean sameSubnet(String a, String b, int prefix) {
        byte[] x = ipv4(a), y = ipv4(b);
        if (x == null || y == null || prefix < 0 || prefix > 32) return false;
        int bits = prefix;
        for (int i = 0; i < 4 && bits > 0; i++, bits -= 8) {
            int m = bits >= 8 ? 0xff : (0xff << (8 - bits)) & 0xff;
            if (((x[i] ^ y[i]) & m) != 0) return false;
        }
        return true;
    }

    private static byte[] ipv4(String s) {
        if (s == null || !s.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) return null;
        String[] p = s.split("\\.");
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            int v = Integer.parseInt(p[i]);
            if (v > 255) return null;
            out[i] = (byte) v;
        }
        return out;
    }

    // Sockets ----------------------------------------------------------------------------------

    /**
     * The factory a join uses: each socket is opened on the network {@link #networkFor} picks at that moment (so a retry follows
     * a network that came back). With no such network the plain default socket is made, as before.
     */
    static SocketFactory socketFactory(final Context c, final String host) {
        return new SocketFactory() {
            @Override public Socket createSocket() throws java.io.IOException {
                Network n = networkFor(c, host);
                if (n == null) {
                    android.util.Log.i(Diag.TAG, "join " + host + ": default network");
                    return new Socket();
                }
                android.util.Log.i(Diag.TAG, "join " + host + ": on the network " + n);
                return n.getSocketFactory().createSocket();
            }

            @Override public Socket createSocket(String h, int port) throws java.io.IOException {
                Socket s = createSocket();
                s.connect(new java.net.InetSocketAddress(h, port));
                return s;
            }

            @Override public Socket createSocket(String h, int port, InetAddress local, int localPort) throws java.io.IOException {
                Socket s = createSocket();
                s.bind(new java.net.InetSocketAddress(local, localPort));
                s.connect(new java.net.InetSocketAddress(h, port));
                return s;
            }

            @Override public Socket createSocket(InetAddress h, int port) throws java.io.IOException {
                Socket s = createSocket();
                s.connect(new java.net.InetSocketAddress(h, port));
                return s;
            }

            @Override public Socket createSocket(InetAddress h, int port, InetAddress local, int localPort) throws java.io.IOException {
                Socket s = createSocket();
                s.bind(new java.net.InetSocketAddress(local, localPort));
                s.connect(new java.net.InetSocketAddress(h, port));
                return s;
            }
        };
    }

    /**
     * Puts the Wi-Fi and Ethernet interfaces first (then the others, in their order): the first address is the one the host
     * screen shows. Stable.
     */
    static List<NetworkInterface> lanFirst(List<NetworkInterface> in) {
        List<NetworkInterface> out = new ArrayList<>(in);
        Collections.sort(out, Comparator.comparingInt((NetworkInterface ni) -> {
            String n = ni.getName();
            if (n == null) return 3;
            if (n.startsWith("wlan")) return 0;
            if (n.startsWith("eth")) return 1;
            return 2;
        }));
        return out;
    }
}
