package org.hedgewars.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The host search keeps its 20 s (an earlier owner decision, tv11). The one automatic restart of NSD (issue #15) must fall inside
 * that time, and the lobby's backstop must come after the search ends (the "No host found" line is the search's own timeout).
 */
public class SearchTimingTest {
    @Test
    public void theWholeSearchIsTwentySecondsWithTheRestartInside() {
        assertEquals("the search ends at 20 s", 20000L, NsdFinder.SEARCH_MS);
        assertTrue("the restart comes after about 8 to 10 s", NsdFinder.RESTART_MS >= 8000 && NsdFinder.RESTART_MS <= 10000);
        assertTrue("the restarted search has time to find a host", NsdFinder.SEARCH_MS - NsdFinder.RESTART_MS >= 8000);
    }

    @Test
    public void theLobbyBackstopComesAfterTheSearchEnds() {
        assertEquals("the backstop is 3 s after the search, as before", 23000L, NetLobbyActivity.BACKSTOP_MS);
        assertTrue(NetLobbyActivity.BACKSTOP_MS > NsdFinder.SEARCH_MS);
    }
}
