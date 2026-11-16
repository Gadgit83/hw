package org.hedgewars.android.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

/** The probe a phone sends to a host's phone port, and the reply it reads (issue #15). Nothing here touches a socket. */
public class ProbeReplyTest {
    private static String reply(boolean pin) {
        return HostAdvert.probeReply(HostAdvert.txt("0.3.0-tv17", Compat.VALUE, Compat.ENGINE_PROTOCOL, "Pixel 8", 8766, "abc123", pin));
    }

    @Test
    public void theProbeIsAHelloOfItsOwnRole() {
        Map<String, Object> hello = Json.parseObject(HostAdvert.PROBE_HELLO);
        assertEquals("hello", Json.str(hello, "t", ""));
        assertEquals(HostAdvert.PROBE_ROLE, Json.str(hello, "role", ""));
    }

    @Test
    public void aReplyIsReadAsTheHostItAdvertises() {
        HostAdvert.Found h = HostAdvert.parseProbe(reply(true), "192.168.1.20", 46633);
        assertTrue(h != null && h.parsedOk);
        assertEquals("192.168.1.20", h.host);
        assertEquals(46633, h.port);
        assertEquals("Pixel 8", h.name);
        assertEquals("HedgeParty Pixel 8", h.serviceName);
        assertEquals("0.3.0-tv17", h.version);
        assertEquals(Compat.VALUE, h.compat);
        assertEquals("abc123", h.data);
        assertEquals(8766, h.tvPort);
        assertEquals(Compat.ENGINE_PROTOCOL, h.protocol);
        assertEquals(org.hedgewars.android.net.Relay.PROTO, h.lobbyProto);
        assertTrue("a reply with pin 1 asks for a game password", h.pinRequired);
        assertEquals("192.168.1.20:46633", h.address());
        assertNull("a reply from this app's own version is joinable", h.incompatibility(Compat.VALUE, "0.3.0-tv17", "abc123"));
    }

    @Test
    public void noPinMeansNoPassword() {
        assertFalse(HostAdvert.parseProbe(reply(false), "10.0.0.5", 46633).pinRequired);
    }

    @Test
    public void anythingElseIsNotAHost() {
        assertNull(HostAdvert.parseProbe(null, "10.0.0.5", 46633));
        assertNull(HostAdvert.parseProbe("", "10.0.0.5", 46633));
        assertNull("not JSON", HostAdvert.parseProbe("HTTP/1.1 400 Bad Request", "10.0.0.5", 46633));
        assertNull("JSON null", HostAdvert.parseProbe("null", "10.0.0.5", 46633));
        assertNull("a JSON array", HostAdvert.parseProbe("[]", "10.0.0.5", 46633));
        assertNull("a number", HostAdvert.parseProbe("17", "10.0.0.5", 46633));
        assertNull("a refusal is not a host", HostAdvert.parseProbe("{\"t\":\"refuse\",\"reason\":\"Protocol mismatch\"}", "10.0.0.5", 46633));
        assertNull("another service's JSON without the marker", HostAdvert.parseProbe("{\"t\":\"here\",\"lp\":\"1\",\"compat\":\"x\"}", "10.0.0.5", 46633));
        assertNull("a marker with the wrong value", HostAdvert.parseProbe("{\"t\":\"here\",\"app\":\"other\",\"lp\":\"1\",\"compat\":\"x\"}", "10.0.0.5", 46633));
        assertNull("a reply without the lobby protocol is not read as a host", HostAdvert.parseProbe("{\"t\":\"here\",\"app\":\"hedgeparty\",\"compat\":\"x\"}", "10.0.0.5", 46633));
        assertNull("a reply without the compatibility string is not read as a host", HostAdvert.parseProbe("{\"t\":\"here\",\"app\":\"hedgeparty\",\"lp\":\"1\"}", "10.0.0.5", 46633));
        assertNull("a number where a string is expected", HostAdvert.parseProbe("{\"t\":\"here\",\"app\":\"hedgeparty\",\"lp\":1,\"compat\":\"x\"}", "10.0.0.5", 46633));
    }

    @Test
    public void aLineOverTheLimitIsRefused() {
        StringBuilder big = new StringBuilder("{\"t\":\"here\",\"app\":\"hedgeparty\",\"lp\":\"1\",\"compat\":\"x\",\"name\":\"");
        while (big.length() < 5000) big.append('a');
        big.append("\"}");
        assertNull(HostAdvert.parseProbe(big.toString(), "10.0.0.5", 46633));
    }

    @Test
    public void controlCharactersInTheNameAreDropped() {
        String line = "{\"t\":\"here\",\"app\":\"hedgeparty\",\"lp\":\"1\",\"compat\":\"x\",\"name\":\"Bad\\u202ename\\n\"}";
        HostAdvert.Found h = HostAdvert.parseProbe(line, "10.0.0.5", 46633);
        assertEquals("Badname", h.name);
        assertFalse(h.name.contains("\n"));
    }

    @Test
    public void aGarbageLineNeverThrows() {
        String[] junk = { "{", "{\"t\":", "\"here\"", "{\"t\":\"here\",\"app\":\"hedgeparty\",\"lp\":\"1\",\"compat\":\"x\",\"name\":\"\\ud800\"}", "[[[[" };
        for (String j : junk) {
            try {
                HostAdvert.parseProbe(j, "10.0.0.5", 46633);
            } catch (RuntimeException e) {
                throw new AssertionError("'" + j + "' threw " + e, e);
            }
        }
    }
}
