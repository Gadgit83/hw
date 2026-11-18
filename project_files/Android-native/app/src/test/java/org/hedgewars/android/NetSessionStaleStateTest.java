package org.hedgewars.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotSame;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The relay state a phone caches must not outlive the game it describes (tv17 device report, RESULT 27: a dropped joiner's lobby said
 * "game running" after the host had ended the game). NetSession.withPhase is what the summary and the closed link use.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {34})
public class NetSessionStaleStateTest {
    private static Map<String, Object> playing() {
        Map<String, Object> m = new HashMap<>();
        m.put("state", "playing");
        m.put("host", "Phone A");
        m.put("players", 2L);
        return m;
    }

    @Test
    public void replacesOnlyThePhase() {
        Map<String, Object> before = playing();
        Map<String, Object> after = NetSession.withPhase(before, "lobby");
        assertEquals("lobby", after.get("state"));
        assertEquals("Phone A", after.get("host"));
        assertEquals(2L, after.get("players"));
    }

    @Test
    public void leavesTheCachedMapUnchanged() {
        Map<String, Object> before = playing();
        Map<String, Object> after = NetSession.withPhase(before, "closed");
        assertNotSame(before, after);
        assertEquals("playing", before.get("state"));
    }

    @Test
    public void nullStaysNull() {
        assertNull(NetSession.withPhase(null, "closed"));
    }
}
