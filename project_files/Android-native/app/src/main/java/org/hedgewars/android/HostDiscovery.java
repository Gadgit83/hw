package org.hedgewars.android;

import org.hedgewars.android.net.HostAdvert;

import java.util.List;

/**
 * Finds hosts on the Wi-Fi for the Join list. The real one is {@link NsdFinder}; the screen only knows this interface, so the
 * tests give it a fake (NSD does not run under Robolectric or on the build machine).
 */
interface HostDiscovery {
    interface Listener {
        /** The current list of resolved hosts (every call replaces the previous list). Any thread. */
        void hosts(List<HostAdvert.Found> all);

        /** A line for the user about the search, and whether the search is over (timed out or failed). Any thread. */
        void status(String text, boolean finished);
    }

    void start(Listener l);

    void stop();
}
