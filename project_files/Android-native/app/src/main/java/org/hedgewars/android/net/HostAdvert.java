package org.hedgewars.android.net;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a host advertises over mDNS/NSD (service type {@code _hedgeparty._tcp}, on the phone port) and how a joining phone reads it.
 * Plain Java so the records and the parsing are tested on the JVM; the Android side (NsdHost, NsdFinder) only moves them.
 *
 * <p>TXT keys (each value at most 255 bytes, the whole record far below the 1300 bytes that mDNS allows):
 * {@code v} app version, {@code compat} {@link Compat#VALUE}, {@code proto} the engine's network protocol, {@code name} the host's
 * display name (the device model for now: the player name is an open owner decision), {@code tv} the TV WebSocket port,
 * {@code data} the game data fingerprint ({@link DataDigest}), {@code lp} the lobby protocol number ({@link Relay#PROTO}).
 * The players and the state (setup or playing) are not in the record: Android cannot update a registered record in place, so
 * the list would show stale values; the relay refuses a join while a game runs, with its own message.
 */
public final class HostAdvert {
    private HostAdvert() { }

    /** The service type as NsdManager wants it (with the trailing dot). */
    public static final String SERVICE_TYPE = "_hedgeparty._tcp.";
    public static final String SERVICE_TYPE_BARE = "_hedgeparty._tcp";

    public static Map<String, String> txt(String version, String compat, int protocol, String hostName, int tvPort, String dataDigest) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("v", cut(version));
        m.put("compat", cut(compat));
        m.put("proto", Integer.toString(protocol));
        m.put("name", cut(hostName));
        m.put("tv", Integer.toString(tvPort));
        m.put("data", cut(dataDigest));
        m.put("lp", Integer.toString(Relay.PROTO));
        return m;
    }

    private static String cut(String s) {
        if (s == null) return "";
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= 100) return s;
        return new String(b, 0, 100, StandardCharsets.UTF_8).replace("�", "");
    }

    /** A host as found on the network. */
    public static final class Found {
        public String serviceName = "", host = "", name = "", version = "", compat = "", data = "";
        public int port, protocol, tvPort, lobbyProto;
        public boolean parsedOk;

        /** Why this phone cannot join, or null. */
        public String incompatibility(String myCompat, String myVersion, String myData) {
            if (!parsedOk) return "This host does not say which version it runs (an older app, or not this game).";
            if (lobbyProto != Relay.PROTO) {
                return "The host speaks lobby protocol " + lobbyProto + ", this phone " + Relay.PROTO + ". Install the same app version on both phones.";
            }
            return Compat.refusal(compat, version, data, myCompat, myVersion, myData);
        }

        public String address() {
            return host + ":" + port;
        }
    }

    /**
     * Reads a resolved service. {@code attrs} are the TXT records as NsdServiceInfo gives them (value bytes, null for a key without
     * value). A record that is missing or garbled gives a Found with {@code parsedOk} false and empty fields, which is listed as
     * incompatible ("not a Hedge Party host of this kind") rather than ignored.
     */
    public static Found parse(String serviceName, String host, int port, Map<String, byte[]> attrs) {
        Found f = new Found();
        f.serviceName = serviceName == null ? "" : serviceName;
        f.host = host == null ? "" : host;
        f.port = port;
        if (attrs == null) return f;
        f.version = str(attrs, "v");
        f.compat = str(attrs, "compat");
        f.data = str(attrs, "data");
        f.name = str(attrs, "name");
        f.protocol = num(attrs, "proto");
        f.tvPort = num(attrs, "tv");
        f.lobbyProto = num(attrs, "lp");
        if (f.name.isEmpty()) f.name = f.serviceName;
        f.parsedOk = !f.compat.isEmpty() && f.lobbyProto > 0;
        return f;
    }

    private static String str(Map<String, byte[]> a, String k) {
        byte[] b = a.get(k);
        if (b == null) return "";
        String s = new String(b, StandardCharsets.UTF_8);
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    private static int num(Map<String, byte[]> a, String k) {
        String s = str(a, k).trim();
        if (s.isEmpty() || s.length() > 9) return 0;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return 0;
        return Integer.parseInt(s);
    }
}
