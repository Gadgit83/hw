package org.hedgewars.android.net;

import java.util.ArrayList;
import java.util.List;

/**
 * The addresses a phone probes when it looks for hosts on its own subnet (issue #15): the hosts of the Wi-Fi (or Ethernet) subnet of
 * this phone, its own address left out. Plain Java, so it is tested on the JVM. The subnet is cut to the /24 that holds the phone's
 * address when it is larger (a home router's /16 is not probed in full), and a subnet of /31 or /32 has nothing to probe.
 */
public final class SubnetScan {
    /** At most this many addresses in one scan (a /24 has 254 hosts). */
    public static final int MAX_ADDRESSES = 254;
    /**
     * The connect timeout of the second pass, when the first found nothing: a host that is busy or saves Wi-Fi power can answer a
     * connection late, so the quick first pass (300 ms) can miss a listening host.
     */
    public static final int SLOW_CONNECT_MS = 1500;

    private SubnetScan() { }

    /**
     * Whether a further pass over the same addresses runs after {@code passesDone} passes that found {@code foundSoFar} hosts, and if so
     * with what connect timeout. Only a first pass that found nothing is followed by one more pass, the slow one. Returns
     * {@link #SLOW_CONNECT_MS} for that pass, and -1 when the scan ends.
     */
    public static int nextPassConnectMs(int passesDone, int foundSoFar) {
        return passesDone == 1 && foundSoFar == 0 ? SLOW_CONNECT_MS : -1;
    }

    /**
     * The IPv4 hosts to probe, in ascending order, for a phone at {@code address} with a subnet of {@code prefix} bits. Empty for an
     * address that is not an IPv4 literal or a prefix outside 0 to 32.
     */
    public static List<String> addresses(String address, int prefix) {
        List<String> out = new ArrayList<>();
        long a = toLong(address);
        if (a < 0 || prefix < 0 || prefix > 32) return out;
        int p = Math.max(prefix, 24);
        if (p >= 31) return out;
        long mask = (0xFFFFFFFFL << (32 - p)) & 0xFFFFFFFFL;
        long network = a & mask;
        long broadcast = network | (~mask & 0xFFFFFFFFL);
        for (long x = network + 1; x < broadcast && out.size() < MAX_ADDRESSES; x++) {
            if (x != a) out.add(fromLong(x));
        }
        return out;
    }

    /** The dotted address as a number, or -1 if it is not a dotted IPv4 address with four parts of 0 to 255. */
    static long toLong(String s) {
        if (s == null || !s.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) return -1;
        long v = 0;
        for (String part : s.split("\\.")) {
            int n = Integer.parseInt(part);
            if (n > 255) return -1;
            v = (v << 8) | n;
        }
        return v;
    }

    static String fromLong(long v) {
        return ((v >> 24) & 255) + "." + ((v >> 16) & 255) + "." + ((v >> 8) & 255) + "." + (v & 255);
    }
}
