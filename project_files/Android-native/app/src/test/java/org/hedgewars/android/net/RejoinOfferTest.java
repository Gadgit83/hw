package org.hedgewars.android.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

/**
 * The Rejoin card's rule (the stale card after a force-stop): the card is shown only for the host the note names when that host is found
 * and reports the same game still playing. A probe reply carries the live game and state; the NSD record does not (it is announced once).
 */
public class RejoinOfferTest {
    private static HostAdvert.Found probed(long game, boolean playing) {
        Map<String, String> rec = HostAdvert.txt("0.3.0-tv21", Compat.VALUE, Compat.ENGINE_PROTOCOL, "Pixel 8", 8766, "abc123", false);
        rec.putAll(HostAdvert.liveFields(game, playing));
        return HostAdvert.parseProbe(HostAdvert.probeReply(rec), "192.168.1.20", 46633);
    }

    @Test
    public void theLiveFieldsAreInTheProbeReplyOnly() {
        Map<String, String> record = HostAdvert.txt("0.3.0-tv21", Compat.VALUE, Compat.ENGINE_PROTOCOL, "Pixel 8", 8766, "abc123", false);
        assertFalse("the NSD record is announced once and cannot be updated, so it carries no game state", record.containsKey("game"));
        assertFalse(record.containsKey("state"));
        HostAdvert.Found f = probed(2, true);
        assertTrue("a reply from this version says what it is doing", f.liveKnown);
        assertEquals(2L, f.game);
        assertEquals(HostAdvert.STATE_PLAYING, f.state);
        assertEquals(HostAdvert.STATE_SETUP, probed(2, false).state);
    }

    @Test
    public void theSameGameStillPlayingShowsTheCard() {
        assertEquals(HostAdvert.RejoinOffer.SHOW, HostAdvert.rejoinOffer("2", probed(2, true)));
    }

    @Test
    public void aGameThatIsOverDropsTheNoteQuietly() {
        assertEquals(HostAdvert.RejoinOffer.DROP, HostAdvert.rejoinOffer("2", probed(2, false)));
    }

    @Test
    public void aDifferentGameDropsTheNote() {
        assertEquals(HostAdvert.RejoinOffer.DROP, HostAdvert.rejoinOffer("2", probed(3, true)));
    }

    @Test
    public void aNoteWithoutAGameCannotMatchAnyGame() {
        assertEquals(HostAdvert.RejoinOffer.DROP, HostAdvert.rejoinOffer("", probed(2, true)));
    }

    @Test
    public void aHostNotFoundYetShowsNothingAndKeepsTheNote() {
        assertEquals("not found, or not probed yet", HostAdvert.RejoinOffer.WAIT, HostAdvert.rejoinOffer("2", null));
    }

    @Test
    public void anOlderHostWithoutTheFieldsKeepsTodaysBehaviour() {
        Map<String, String> old = HostAdvert.txt("0.3.0-tv17", Compat.VALUE, Compat.ENGINE_PROTOCOL, "Old phone", 8766, "abc123", false);
        HostAdvert.Found f = HostAdvert.parseProbe(HostAdvert.probeReply(old), "192.168.1.21", 46633);
        assertFalse("a reply without the fields says nothing about the game", f.liveKnown);
        assertEquals(HostAdvert.RejoinOffer.SHOW, HostAdvert.rejoinOffer("2", f));
        assertEquals(HostAdvert.RejoinOffer.SHOW, HostAdvert.rejoinOffer("", f));
    }

    @Test
    public void theSearchsRecordAloneHasNoLiveState() {
        HostAdvert.Found listed = HostAdvert.parse("HedgeParty Pixel 8", "192.168.1.20", 46633, null);
        assertFalse("a listed host from the NSD record says nothing about its game", listed.liveKnown);
        assertEquals("only a probe decides the card", HostAdvert.RejoinOffer.WAIT, HostAdvert.rejoinOffer("2", null));
    }
}
