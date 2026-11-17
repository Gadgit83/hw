package org.hedgewars.android;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.hedgewars.android.net.HostAdvert;
import org.hedgewars.android.net.ResolveQueue;

import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovery of {@code _hedgeparty._tcp} hosts with NsdManager (docs/tv-viewer-plan.md section 5.1). Resolving is serialised and
 * retried by {@link ResolveQueue}, because resolveService is unreliable (a second resolve at once fails with
 * FAILURE_ALREADY_ACTIVE, a resolve may fail once and work later, a callback may never come). The search stops by itself after
 * {@link #SEARCH_MS} (20 s in all) so it does not run for ever; the screen offers "Search again". Only IPv4 addresses are used. When NSD has found no
 * host after {@link #FALLBACK_MS}, the phone's Wi-Fi subnet is probed as well ({@link SubnetFinder}, issue #15); its hosts are merged in.
 * When nothing has been found after {@link #RESTART_MS}, NSD is started again once inside the same {@link #SEARCH_MS} (issue #15);
 * the search time does not grow.
 * NSD cannot run on the build machine: only the parsing and the queue are unit-tested; this class is for the device test.
 */
final class NsdFinder implements HostDiscovery {
    /** The whole search, restart included. */
    static final long SEARCH_MS = 20000;
    /** The one restart of NSD, when nothing was found by then (well inside {@link #SEARCH_MS}). */
    static final long RESTART_MS = 9000;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private ResolveQueue queue;
    private Listener listener;
    private final Map<String, HostAdvert.Found> found = new LinkedHashMap<>();
    private final Map<String, NsdServiceInfo> seen = new LinkedHashMap<>();
    /** The fallback (issue #15): NSD found nothing after {@link #FALLBACK_MS}, so the Wi-Fi subnet is probed too. Guarded by this. */
    static final long FALLBACK_MS = 3000;
    private SubnetFinder subnet;
    /** The hosts the subnet probe found, by address and port. Shown with the NSD hosts, each host once. Guarded by this. */
    private final Map<String, HostAdvert.Found> probed = new LinkedHashMap<>();
    private volatile boolean running;
    /** Held while searching: some phones (Samsung, Android 16 among them) drop multicast DNS answers without it. Not reference-counted. */
    private WifiManager.MulticastLock multicast;
    /** This phone's own addresses: an advert from one of them is this phone's own, stale after it stopped hosting. */
    private volatile java.util.Set<String> own = java.util.Collections.emptySet();
    /** True until the one restart has been used (or not needed). Guarded by this. */
    private boolean restartLeft;

    private final Runnable timeout = () -> {
        if (!running) return;
        stop();
        Listener l = listener;
        int shown;
        synchronized (this) { shown = visible(); }
        Log.i(Diag.TAG, "nsd: search time is up, " + shown + " host(s) listed");
        if (l != null) l.status(shown == 0 ? "No host found on this Wi-Fi." : "Search finished.", true);
    };

    /** The fallback check: starts the subnet probe if NSD has still found no host {@link #FALLBACK_MS} after the search began. */
    private final Runnable fallback = () -> {
        synchronized (this) {
            if (!running || subnet != null || !found.isEmpty()) return;
        }
        Log.i(Diag.TAG, "nsd: no host found after " + (FALLBACK_MS / 1000) + " s, probing the Wi-Fi subnet as well");
        startSubnet();
    };

    /**
     * The restart (issue #15): if nothing has been found after {@link #RESTART_MS}, NSD discovery is stopped and started again. The
     * subnet probe and the {@link #SEARCH_MS} timeout are not touched, so the search still ends at {@link #SEARCH_MS}.
     */
    private final Runnable restart = () -> {
        Listener l = listener;
        synchronized (this) {
            if (!running || l == null || !restartLeft || visible() > 0) return;
            restartLeft = false;
            Log.i(Diag.TAG, "nsd: no host after " + (RESTART_MS / 1000) + " s, starting the search again");
            stopNsd();
            startNsd(l, "Searching for hosts on this Wi-Fi again...");
        }
    };

    NsdFinder(Context c) {
        this.app = c.getApplicationContext();
    }

    /** Main thread. Starts the subnet probe unless one is running, the search has ended, or NSD has found a host meanwhile. */
    private void startSubnet() {
        final SubnetFinder scan = new SubnetFinder(app);
        synchronized (this) {
            if (!running || subnet != null || !found.isEmpty()) return;
            subnet = scan;
        }
        scan.start(h -> {
            synchronized (NsdFinder.this) {
                if (!running || subnet != scan) return;      // a scan that was stopped or replaced
                probed.put(h.address(), h);
                publish();
            }
        });
    }

    @Override
    public synchronized void start(Listener l) {
        stop();
        listener = l;
        found.clear();
        seen.clear();
        probed.clear();
        restartLeft = true;
        try {
            nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
        } catch (RuntimeException e) {
            nsd = null;
        }
        if (nsd == null) {
            Log.w(Diag.TAG, "nsd: no NSD service on this phone, the search cannot run");
            l.status("This phone cannot search for hosts (no NSD service).", true);
            return;
        }
        Thread ownT = new Thread(() -> {
            own = new java.util.HashSet<>(NetSession.localAddresses());     // lists interfaces: not on the UI thread
            synchronized (NsdFinder.this) { if (running) publish(); }
        }, "nsd-own-addresses");
        ownT.setDaemon(true);
        ownT.start();
        if (!startNsd(l, "Searching for hosts on this Wi-Fi...")) return;
        main.postDelayed(timeout, SEARCH_MS);
        main.postDelayed(restart, RESTART_MS);
        main.postDelayed(fallback, FALLBACK_MS);
    }

    /**
     * Starts NSD discovery (one round: the first search, or the restart). Lock held. False if the search could not start; the
     * listener has then been told.
     */
    private boolean startNsd(final Listener l, String text) {
        takeMulticastLock();
        queue = new ResolveQueue(this::resolveOne, new ResolveQueue.Sink() {
            @Override public void resolved(String key, Object result) {
                synchronized (NsdFinder.this) {
                    if (!running) return;
                    found.put(key, (HostAdvert.Found) result);
                    publish();
                }
            }

            @Override public void gaveUp(String key, String why) {
                android.util.Log.w(Diag.TAG, "nsd: could not resolve " + key + ": " + why);
                if (!running) return;
                Listener x = listener;
                if (x != null) x.status("Found a host called " + key + " but could not read its address (" + why + "). Type its address instead.", false);
            }
        }, 5000, 700, 3);
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String type, int code) {
                Log.w(Diag.TAG, "nsd: the search could not start, error " + code);
                releaseMulticastLock();
                running = false;
                Listener x = listener;
                if (x != null) x.status("The search could not start (error " + code + "). Type the host's address instead.", true);
            }

            @Override public void onStopDiscoveryFailed(String type, int code) {
                Log.w(Diag.TAG, "nsd: stopping the search failed, error " + code);
            }

            @Override public void onDiscoveryStarted(String type) {
                Log.i(Diag.TAG, "nsd: search started for " + type);
            }

            @Override public void onDiscoveryStopped(String type) {
                Log.i(Diag.TAG, "nsd: search stopped");
            }

            @Override public void onServiceFound(NsdServiceInfo info) {
                String n = info.getServiceName();
                Log.i(Diag.TAG, "nsd: found " + n);
                if (n == null) return;
                synchronized (NsdFinder.this) {
                    if (!running) return;
                    seen.put(n, info);
                }
                queue.add(n);
            }

            @Override public void onServiceLost(NsdServiceInfo info) {
                String n = info.getServiceName();
                Log.i(Diag.TAG, "nsd: lost " + n);
                if (n == null) return;
                synchronized (NsdFinder.this) {
                    seen.remove(n);
                    if (found.remove(n) != null) publish();
                }
                queue.remove(n);
            }
        };
        running = true;
        try {
            nsd.discoverServices(HostAdvert.SERVICE_TYPE_BARE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException e) {
            Log.w(Diag.TAG, "nsd: the search could not start: " + e);
            running = false;
            releaseMulticastLock();
            l.status("The search could not start (" + e.getMessage() + "). Type the host's address instead.", true);
            return false;
        }
        Log.i(Diag.TAG, "nsd: searching for " + HostAdvert.SERVICE_TYPE_BARE + " for " + (SEARCH_MS / 1000) + " s");
        l.status(text, false);
        return true;
    }

    /**
     * Lock held. The hosts shown: the ones NSD found, then the ones the subnet probe found that NSD did not (same address and port),
     * every one except this phone's own. A phone cannot join itself; after Stop hosting its own advert can linger in the NSD cache for
     * a few seconds.
     */
    private List<HostAdvert.Found> shown() {
        Map<String, HostAdvert.Found> all = new LinkedHashMap<>();
        for (HostAdvert.Found h : found.values()) all.putIfAbsent(h.address(), h);
        for (HostAdvert.Found h : probed.values()) all.putIfAbsent(h.address(), h);
        ArrayList<HostAdvert.Found> list = new ArrayList<>();
        for (HostAdvert.Found h : all.values()) if (!own.contains(h.host)) list.add(h);
        return list;
    }

    /** Lock held. */
    private int visible() {
        return shown().size();
    }

    /** Lock held. */
    private void publish() {
        Listener l = listener;
        if (l == null) return;
        l.hosts(shown());
    }

    private void resolveOne(String key, ResolveQueue.Done done) {
        NsdServiceInfo info;
        synchronized (this) { info = seen.get(key); }
        if (info == null || nsd == null) { done.fail("the service is gone"); return; }
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo i, int code) {
                    Log.w(Diag.TAG, "nsd: resolving " + key + " failed, error " + code);
                    done.fail(code == NsdManager.FAILURE_ALREADY_ACTIVE ? "FAILURE_ALREADY_ACTIVE" : "resolve error " + code);
                }

                @Override public void onServiceResolved(NsdServiceInfo i) {
                    Log.i(Diag.TAG, "nsd: resolved " + key + " to " + i.getHost() + ":" + i.getPort());
                    if (!(i.getHost() instanceof Inet4Address)) { done.fail("not an IPv4 address"); return; }
                    done.ok(HostAdvert.parse(key, i.getHost().getHostAddress(), i.getPort(), i.getAttributes()));
                }
            });
        } catch (RuntimeException e) {
            done.fail("resolveService threw " + e);
        }
    }

    /** Holds the multicast lock while a search runs. A failure to take it is logged; the search still runs. */
    private void takeMulticastLock() {
        releaseMulticastLock();
        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            multicast = wm.createMulticastLock("hedgeparty:nsd");
            multicast.setReferenceCounted(false);
            multicast.acquire();
            Log.i(Diag.TAG, "nsd: multicast lock taken for the search");
        } catch (RuntimeException e) {
            multicast = null;
            Log.w(Diag.TAG, "nsd: could not take the multicast lock: " + e);
        }
    }

    private void releaseMulticastLock() {
        WifiManager.MulticastLock m = multicast;
        multicast = null;
        try {
            if (m != null && m.isHeld()) m.release();
        } catch (RuntimeException ignored) {
        }
    }

    /** Stops the NSD discovery and its queue (not the subnet probe, not the timers). Lock held. */
    private void stopNsd() {
        releaseMulticastLock();
        if (queue != null) queue.stop();
        queue = null;
        NsdManager m = nsd;
        NsdManager.DiscoveryListener d = discovery;
        discovery = null;
        if (m != null && d != null) {
            try { m.stopServiceDiscovery(d); } catch (RuntimeException ignored) { }
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        main.removeCallbacks(timeout);
        main.removeCallbacks(fallback);
        main.removeCallbacks(restart);
        if (subnet != null) subnet.stop();
        subnet = null;
        stopNsd();
    }
}
