package org.hedgewars.android.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/** The addresses the fallback search probes (issue #15): prefix handling, the phone's own address left out, the cap. */
public class SubnetScanTest {
    @Test
    public void aSlash24ProbesEveryHostButNetworkBroadcastAndOwn() {
        List<String> a = SubnetScan.addresses("192.168.1.62", 24);
        assertEquals(253, a.size());
        assertEquals("192.168.1.1", a.get(0));
        assertEquals("192.168.1.254", a.get(a.size() - 1));
        assertFalse(a.contains("192.168.1.0"));
        assertFalse(a.contains("192.168.1.255"));
        assertFalse("the phone's own address is skipped", a.contains("192.168.1.62"));
        assertTrue(a.contains("192.168.1.61"));
        assertEquals("ascending, no repeats", a.size(), new java.util.TreeSet<>(a).size());
    }

    @Test
    public void aLargerSubnetIsCutToTheSlash24OfThePhone() {
        assertEquals(SubnetScan.addresses("10.0.5.9", 24), SubnetScan.addresses("10.0.5.9", 16));
        assertEquals(SubnetScan.addresses("10.0.5.9", 24), SubnetScan.addresses("10.0.5.9", 8));
        assertEquals(SubnetScan.addresses("10.0.5.9", 24), SubnetScan.addresses("10.0.5.9", 23));
    }

    @Test
    public void aSmallerSubnetIsProbedWhole() {
        // 192.168.1.70/26: hosts .65 to .126, the phone's .70 left out
        List<String> a = SubnetScan.addresses("192.168.1.70", 26);
        assertEquals(61, a.size());
        assertEquals("192.168.1.65", a.get(0));
        assertEquals("192.168.1.126", a.get(a.size() - 1));
        assertFalse(a.contains("192.168.1.70"));
        assertFalse("nothing outside the /26", a.contains("192.168.1.127"));
        // 192.168.1.200/25: hosts .129 to .254
        assertEquals(125, SubnetScan.addresses("192.168.1.200", 25).size());
        // 192.168.1.5/30: hosts .5 and .6, the phone's .5 left out
        assertEquals(java.util.Collections.singletonList("192.168.1.6"), SubnetScan.addresses("192.168.1.5", 30));
    }

    @Test
    public void aSubnetWithNoHostsGivesNothing() {
        assertTrue(SubnetScan.addresses("192.168.1.5", 31).isEmpty());
        assertTrue(SubnetScan.addresses("192.168.1.5", 32).isEmpty());
    }

    @Test
    public void badInputGivesNothing() {
        assertTrue(SubnetScan.addresses("fairphone.local", 24).isEmpty());
        assertTrue(SubnetScan.addresses("300.1.1.1", 24).isEmpty());
        assertTrue(SubnetScan.addresses("192.168.1", 24).isEmpty());
        assertTrue(SubnetScan.addresses("", 24).isEmpty());
        assertTrue(SubnetScan.addresses(null, 24).isEmpty());
        assertTrue(SubnetScan.addresses("192.168.1.5", -1).isEmpty());
        assertTrue(SubnetScan.addresses("192.168.1.5", 33).isEmpty());
    }

    @Test
    public void theCapHoldsForEveryPrefixAndAddress() {
        String[] addrs = { "192.168.1.62", "10.0.0.1", "172.16.200.9", "0.0.0.1", "255.255.255.254", "100.64.3.3" };
        for (String a : addrs) {
            for (int p = 0; p <= 32; p++) {
                List<String> l = SubnetScan.addresses(a, p);
                assertTrue(a + "/" + p + " has " + l.size() + " addresses", l.size() <= SubnetScan.MAX_ADDRESSES);
                assertFalse(a + "/" + p + " probes the phone itself", l.contains(a));
            }
        }
        assertEquals(254, SubnetScan.MAX_ADDRESSES);
    }

    @Test
    public void aSecondSlowerPassRunsOnlyAfterAFirstPassFoundNothing() {
        assertEquals("the first pass finds nothing: one more pass, with the slower connect timeout",
                SubnetScan.SLOW_CONNECT_MS, SubnetScan.nextPassConnectMs(1, 0));
        assertEquals(1500, SubnetScan.SLOW_CONNECT_MS);
        assertEquals("the first pass found a host: no second pass", -1, SubnetScan.nextPassConnectMs(1, 1));
        assertEquals("the first pass found several hosts: no second pass", -1, SubnetScan.nextPassConnectMs(1, 3));
        assertEquals("the second pass has run: it is the last one", -1, SubnetScan.nextPassConnectMs(2, 0));
        assertEquals("no pass has run yet: nothing to decide", -1, SubnetScan.nextPassConnectMs(0, 0));
    }

    @Test
    public void dottedNumbersRoundTrip() {
        assertEquals("192.168.1.62", SubnetScan.fromLong(SubnetScan.toLong("192.168.1.62")));
        assertEquals(-1, SubnetScan.toLong("1.2.3.256"));
    }
}
