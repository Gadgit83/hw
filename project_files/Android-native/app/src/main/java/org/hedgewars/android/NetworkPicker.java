package org.hedgewars.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;

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
 *   <li>an explicit choice of an interface uses that interface's network, if it is still connected;</li>
 *   <li>Automatic: a target on a VPN's subnet (or a Tailscale address, 100.64.0.0/10, while a VPN is on), or on a subnet a VPN's
 *       routes carry, uses the default network; any other target uses the Wi-Fi or Ethernet network, if there is one;</li>
 *   <li>the Wi-Fi or Ethernet network is the active network when that is one of them, else a connected one (never a network still
 *       CONNECTING after a roam, see {@link #chooseLan});</li>
 *   <li>a socket that cannot be bound to a network (EPERM with a non-bypassable VPN, or any other refusal) is opened on the default
 *       network instead, logged once ({@link #openSocket}); no join or probe fails for that reason alone.</li>
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
    /** The hint when the VPN cannot be bypassed: network games then go through it, whatever network is chosen. */
    static final String VPN_HINT_NOT_BYPASSABLE = "A VPN is on that cannot be bypassed, so network games go through it. "
            + "They work when the VPN reaches this Wi-Fi.";

    /**
     * The hint under "Network to use" when Automatic is shown. It names the Wi-Fi only when a VPN is on and can be bypassed (or it is
     * not known whether it can): then the join can take the Wi-Fi. A VPN that cannot be bypassed carries every join, so the hint says so.
     * {@code bypassable} is null when unknown.
     */
    static String vpnHint(boolean vpnOn, Boolean bypassable) {
        return vpnOn && bypassable != null && !bypassable ? VPN_HINT_NOT_BYPASSABLE : VPN_HINT;
    }

    /**
     * Whether the VPN that is up can be bypassed: true or false from API 34 (VpnTransportInfo.isBypassable), null when it is not known
     * (API 33 and below, no VPN network reports its transport, or the method cannot be read). Not for the UI thread.
     * VpnTransportInfo is not in the public SDK, so the method is read by reflection: a failure is "not known", which keeps today's hint.
     */
    static Boolean vpnBypassable(Context c) {
        if (Build.VERSION.SDK_INT < 34) return null;
        ConnectivityManager cm = connectivity(c);
        if (cm == null) return null;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                Object info = caps.getTransportInfo();
                if (info == null || !"android.net.VpnTransportInfo".equals(info.getClass().getName())) continue;
                Object v = info.getClass().getMethod("isBypassable").invoke(info);
                if (v instanceof Boolean) return (Boolean) v;
            }
        } catch (Exception | LinkageError ignored) {
        }
        return null;
    }

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

    /** One network of this phone as the choice sees it. The Android objects are read into this, so the choice is tested on the JVM. */
    static final class Lan<T> {
        final T network;
        final String iface;
        final boolean wifi, ethernet, connected, validated, ipv4, active;

        Lan(T network, String iface, boolean wifi, boolean ethernet, boolean connected, boolean validated, boolean ipv4, boolean active) {
            this.network = network;
            this.iface = iface;
            this.wifi = wifi;
            this.ethernet = ethernet;
            this.connected = connected;
            this.validated = validated;
            this.ipv4 = ipv4;
            this.active = active;
        }

        /** A Wi-Fi or Ethernet network that is connected and has an IPv4 address. */
        boolean usable() { return (wifi || ethernet) && connected && ipv4; }
    }

    /** One IPv4 route of a VPN: the destination and its prefix length. */
    static final class Route {
        final String dest;
        final int prefix;

        Route(String dest, int prefix) {
            this.dest = dest;
            this.prefix = prefix;
        }
    }

    /** Makes the socket for one network. {@link #openSocket} calls it; the socket factory passes the real binding. */
    interface Binder<T> {
        Socket bind(T network) throws java.io.IOException;
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

    /**
     * The interface whose subnet the search probes (issue #15): the first Wi-Fi interface, else the first Ethernet one, else null.
     * VPN, hotspot and USB addresses are not probed.
     */
    static Iface lanIface(List<Iface> ifaces) {
        Iface eth = null;
        for (Iface i : ifaces) {
            if ("Wi-Fi".equals(i.kind)) return i;
            if (eth == null && "Ethernet".equals(i.kind)) eth = i;
        }
        return eth;
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

    /**
     * The network to use from a choice of networks (issues #2 and #15): the active network when it is a usable Wi-Fi or Ethernet one;
     * else the usable one on {@code preferIface} (if any), then the validated ones, then Wi-Fi before Ethernet, the first in the list
     * last. A network that is not connected (a stale Wi-Fi network still CONNECTING after a roam) is never chosen. Null if none is usable.
     */
    static <T> T chooseLan(List<Lan<T>> nets, String preferIface) {
        for (Lan<T> l : nets) if (l.active && l.usable()) return l.network;
        Lan<T> best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Lan<T> l : nets) {
            if (!l.usable()) continue;
            int r = (preferIface != null && preferIface.equals(l.iface) ? 0 : 4) + (l.validated ? 0 : 2) + (l.wifi ? 0 : 1);
            if (r < bestRank) {
                best = l;
                bestRank = r;
            }
        }
        return best == null ? null : best.network;
    }

    /**
     * True if one of the VPN routes carries {@code host}. Routes shorter than a /8 do not count: the default route and a split default
     * (0.0.0.0/1 with 128.0.0.0/1) send everything, the LAN too, but a join on those routes binds to the Wi-Fi first and falls back to
     * the default network if that is refused ({@link #openSocket}), so they need no special case.
     */
    static boolean routesTo(String host, List<Route> routes) {
        for (Route r : routes) {
            if (r.prefix >= 8 && sameSubnet(host, r.dest, r.prefix)) return true;
        }
        return false;
    }

    /** The IPv4 routes of the VPN networks, read from their LinkProperties. Not for the UI thread. */
    static List<Route> vpnRoutes(Context c) {
        List<Route> out = new ArrayList<>();
        ConnectivityManager cm = connectivity(c);
        if (cm == null) return out;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (RouteInfo r : lp.getRoutes()) {
                    // RouteInfo.getDestination() and IpPrefix exist from API 21; routesTo compares the masked IPv4 bytes itself.
                    android.net.IpPrefix p = r.getDestination();
                    if (p == null || !(p.getAddress() instanceof Inet4Address)) continue;
                    out.add(new Route(p.getAddress().getHostAddress(), p.getPrefixLength()));
                }
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    /** The Wi-Fi or Ethernet network to use (see {@link #chooseLan}), or null. Not for the UI thread. */
    static Network lanNetwork(Context c) { return lanNetwork(c, null); }

    /** As {@link #lanNetwork(Context)}, preferring the network of interface {@code preferIface}. */
    static Network lanNetwork(Context c, String preferIface) {
        ConnectivityManager cm = connectivity(c);
        if (cm == null) return null;
        try {
            return chooseLan(lanCandidates(cm), preferIface);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<Lan<Network>> lanCandidates(ConnectivityManager cm) {
        List<Lan<Network>> out = new ArrayList<>();
        Network active = null;
        try {
            active = cm.getActiveNetwork();
        } catch (RuntimeException ignored) {
        }
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            boolean wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            boolean eth = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
            if (!wifi && !eth) continue;
            LinkProperties lp = cm.getLinkProperties(n);
            out.add(new Lan<>(n, lp != null ? lp.getInterfaceName() : null, wifi, eth, isConnected(cm, n, caps),
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), hasIpv4(lp), n.equals(active)));
        }
        return out;
    }

    /**
     * True only for a network the system reports as CONNECTED (not CONNECTING, DISCONNECTED or suspended) and, on API 28 and up,
     * not suspended by the capabilities. A network whose state is not known is not usable: a stale network after a roam (issue #2,
     * Android 9 included) must be rejected. {@code ConnectivityManager.getNetworkInfo(Network)} exists from API 21, so no version check.
     */
    @SuppressWarnings("deprecation")
    static boolean isConnected(ConnectivityManager cm, Network n, NetworkCapabilities caps) {
        try {
            android.net.NetworkInfo ni = cm.getNetworkInfo(n);
            if (ni == null || ni.getState() != android.net.NetworkInfo.State.CONNECTED) return false;
        } catch (RuntimeException e) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 28 && caps != null) {
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
        }
        return true;
    }

    private static boolean hasIpv4(LinkProperties lp) {
        if (lp == null) return false;
        for (LinkAddress a : lp.getLinkAddresses()) {
            InetAddress addr = a.getAddress();
            if (addr instanceof Inet4Address && !addr.isLinkLocalAddress()) return true;
        }
        return false;
    }

    /** The network of the named interface that is connected, or null if it is not up. */
    static Network networkNamed(Context c, String iface) {
        ConnectivityManager cm = connectivity(c);
        if (cm == null || iface == null || iface.isEmpty()) return null;
        try {
            for (Network n : cm.getAllNetworks()) {
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null && iface.equals(lp.getInterfaceName()) && isConnected(cm, n, cm.getNetworkCapabilities(n))) return n;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /**
     * The network a join to {@code host} uses (see the class comment), or null for the default network. An explicit choice of an
     * interface is used whenever that interface is connected; Automatic is {@link #autoNetwork}.
     */
    static Network networkFor(Context c, String host) {
        if (host == null || host.isEmpty() || host.startsWith("127.") || host.equals("localhost") || host.equals("::1")) return null;
        String choice = saved(c);
        if (!AUTO.equals(choice)) {
            Network n = networkNamed(c, choice);
            if (n != null) return n;              // the chosen interface is gone: Automatic below
        }
        return autoNetwork(c, host, null);
    }

    /**
     * Automatic: the default network (null) when a VPN carries {@code host}, on its subnet or on one of its routes; else the Wi-Fi
     * or Ethernet network ({@code preferIface} first). Used by the joins and by the subnet search.
     */
    static Network autoNetwork(Context c, String host, String preferIface) {
        if (vpnCovers(c, host)) return null;
        return lanNetwork(c, preferIface);
    }

    /** True if a VPN carries {@code host}: on its interface's subnet, a Tailscale address, or on one of its routes. Not for the UI thread. */
    static boolean vpnCovers(Context c, String host) {
        List<Iface> ifaces = list();
        return onVpnRoute(host, ifaces, vpnActive(c, ifaces)) || routesTo(host, vpnRoutes(c));
    }

    /**
     * The network as the search and join log name it: "network 108 (wlan0)". The id is the one in the Network's own text, which the
     * system writes as {@code Network[108]}; the interface comes from its LinkProperties.
     */
    static String describe(Context c, Network n) {
        String id = String.valueOf(n).replaceAll("\\D", "");
        String iface = ifaceOf(c, n);
        return "network " + (id.isEmpty() ? String.valueOf(n) : id) + " (" + (iface == null ? "?" : iface) + ")";
    }

    /** The name of the interface a network runs on (its LinkProperties), or null. Not for the UI thread. */
    static String ifaceOf(Context c, Network n) {
        try {
            ConnectivityManager cm = connectivity(c);
            LinkProperties lp = cm == null ? null : cm.getLinkProperties(n);
            return lp == null ? null : lp.getInterfaceName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Why a join to {@code host} uses the default route, for the log: this phone, a VPN that covers the LAN, or no Wi-Fi or Ethernet. */
    static String defaultRouteWhy(Context c, String host) {
        if (host == null || host.isEmpty() || host.startsWith("127.") || host.equals("localhost") || host.equals("::1")) return "this phone";
        return vpnCovers(c, host) ? "VPN covers the LAN" : "no Wi-Fi or Ethernet network";
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

    private static final java.util.Set<String> noted = Collections.synchronizedSet(new java.util.HashSet<>());

    /** Logs {@code msg} once for the process (a probe of 253 addresses must not write 253 lines). Logging is skipped off the device. */
    static void noteOnce(String msg) {
        if (!noted.add(msg)) return;
        try {
            android.util.Log.w(Diag.TAG, msg);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    /**
     * An unconnected socket on {@code network}, made by {@code bind}. A null network is the default network. If the binding is refused
     * (EPERM when a non-bypassable VPN is on, or any other IOException or SecurityException), the socket is made on the default
     * network instead, so the join or probe still goes out (a VPN that routes the LAN carries it); the refusal is logged once.
     */
    static <T> Socket openSocket(T network, Binder<T> bind) throws java.io.IOException {
        return openSocket(network, bind, Socket::new);
    }

    /** As {@link #openSocket(Object, Binder)}, with the socket made when there is no network or the binding is refused by {@code plain}. */
    static <T> Socket openSocket(T network, Binder<T> bind, java.util.function.Supplier<Socket> plain) throws java.io.IOException {
        if (network == null) return plain.get();
        try {
            return bind.bind(network);
        } catch (java.io.IOException | SecurityException e) {
            noteOnce("network: the chosen network refused the socket (" + e.getMessage() + "), using the default network");
            return plain.get();
        }
    }

    /**
     * The local address as the log prints it: an IPv4-mapped IPv6 address (the form a dual-stack socket reports) as its IPv4 address.
     */
    static String plainAddress(java.net.InetAddress a) {
        if (a instanceof java.net.Inet6Address) {
            byte[] b = a.getAddress();
            boolean mapped = b.length == 16;
            for (int i = 0; mapped && i < 10; i++) mapped = b[i] == 0;
            mapped = mapped && b[10] == (byte) 0xff && b[11] == (byte) 0xff;
            if (mapped) return (b[12] & 0xff) + "." + (b[13] & 0xff) + "." + (b[14] & 0xff) + "." + (b[15] & 0xff);
        }
        return a.getHostAddress();
    }

    /**
     * The line logged once a join's socket is connected: the address and interface it went out on. A socket bound to the Wi-Fi (or
     * Ethernet) network that went out on a VPN's interface is noted: the VPN carries it whatever was chosen, when it cannot be bypassed.
     * {@code iface} is null when no interface was found for the address.
     */
    static String connectedLine(String host, String local, String iface, boolean boundToLan, boolean ifaceIsVpn) {
        String line = "join " + host + ": connected from " + local + " on " + (iface == null ? "an unknown interface" : iface);
        return boundToLan && ifaceIsVpn ? line + ": the VPN carries it (this VPN cannot be bypassed)" : line;
    }

    /**
     * A join's socket. Once it is connected it logs the path the connection really took ({@link #connectedLine}): the socket is bound
     * to a network before the connect, and Android may still send it through a VPN, so the log names the interface it used.
     */
    static final class JoinSocket extends Socket {
        private final String host;
        private final boolean boundToLan;

        /** {@code boundToLan}: the socket was bound to a Wi-Fi or Ethernet network (not to the default network, nor a VPN). */
        JoinSocket(String host, boolean boundToLan) {
            this.host = host;
            this.boundToLan = boundToLan;
        }

        @Override public void connect(java.net.SocketAddress endpoint, int timeout) throws java.io.IOException {
            super.connect(endpoint, timeout);
            logPath();
        }

        private void logPath() {
            try {
                java.net.InetAddress local = getLocalAddress();
                java.net.InetAddress v4 = local instanceof java.net.Inet6Address ? plainV4(local) : local;
                NetworkInterface ni = NetworkInterface.getByInetAddress(v4);
                String iface = ni == null ? null : ni.getName();
                String line = connectedLine(host, plainAddress(local), iface, boundToLan, "VPN".equals(kindOf(iface)));
                android.util.Log.i(Diag.TAG, line);
            } catch (java.io.IOException | RuntimeException | LinkageError ignored) {
                // the log is no reason to fail a join
            }
        }

        /** The IPv4 address of an IPv4-mapped IPv6 address, else the address itself. */
        private static java.net.InetAddress plainV4(java.net.InetAddress a) throws java.net.UnknownHostException {
            String s = plainAddress(a);
            return s.equals(a.getHostAddress()) ? a : java.net.InetAddress.getByName(s);
        }
    }

    /**
     * The factory a join uses: each socket is opened on the network {@link #networkFor} picks at that moment (so a retry follows
     * a network that came back). With no such network the plain default socket is made, as before. Each socket logs its real path
     * once it is connected ({@link JoinSocket}).
     */
    static SocketFactory socketFactory(final Context c, final String host) {
        return new SocketFactory() {
            @Override public Socket createSocket() throws java.io.IOException {
                Network n = networkFor(c, host);
                if (n == null) {
                    android.util.Log.i(Diag.TAG, "join " + host + ": default route (" + defaultRouteWhy(c, host) + ")");
                    return new JoinSocket(host, false);
                }
                android.util.Log.i(Diag.TAG, "join " + host + ": bound to " + describe(c, n));
                String iface = ifaceOf(c, n);
                final boolean lan = "Wi-Fi".equals(kindOf(iface)) || "Ethernet".equals(kindOf(iface));
                return openSocket(n, x -> {
                    JoinSocket s = new JoinSocket(host, lan);
                    x.bindSocket(s);
                    return s;
                }, () -> new JoinSocket(host, false));
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
