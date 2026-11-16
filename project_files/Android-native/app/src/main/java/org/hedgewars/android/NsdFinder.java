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
 * {@link #SEARCH_MS} so it does not run for ever; the screen offers "Search again". Only IPv4 addresses are used.
 * NSD cannot run on the build machine: only the parsing and the queue are unit-tested; this class is for the device test.
 */
final class NsdFinder implements HostDiscovery {
    static final long SEARCH_MS = 20000;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private ResolveQueue queue;
    private Listener listener;
    private final Map<String, HostAdvert.Found> found = new LinkedHashMap<>();
    private final Map<String, NsdServiceInfo> seen = new LinkedHashMap<>();
    private volatile boolean running;
    /** Held while searching: some phones (Samsung, Android 16 among them) drop multicast DNS answers without it. Not reference-counted. */
    private WifiManager.MulticastLock multicast;
    /** This phone's own addresses: an advert from one of them is this phone's own, stale after it stopped hosting. */
    private volatile java.util.Set<String> own = java.util.Collections.emptySet();
    private final Runnable timeout = () -> {
        if (!running) return;
        stop();
        Listener l = listener;
        int shown;
        synchronized (this) { shown = visible(); }
        Log.i(Diag.TAG, "nsd: search time is up, " + shown + " host(s) listed");
        if (l != null) l.status(shown == 0 ? "No host found on this Wi-Fi." : "Search finished.", true);
    };

    NsdFinder(Context c) {
        this.app = c.getApplicationContext();
    }

    @Override
    public synchronized void start(Listener l) {
        stop();
        listener = l;
        found.clear();
        seen.clear();
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
        Thread ownT = new Thread(() -> {
            own = new java.util.HashSet<>(NetSession.localAddresses());     // lists interfaces: not on the UI thread
            synchronized (NsdFinder.this) { if (running) publish(); }
        }, "nsd-own-addresses");
        ownT.setDaemon(true);
        ownT.start();
        try {
            nsd.discoverServices(HostAdvert.SERVICE_TYPE_BARE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException e) {
            Log.w(Diag.TAG, "nsd: the search could not start: " + e);
            running = false;
            releaseMulticastLock();
            l.status("The search could not start (" + e.getMessage() + "). Type the host's address instead.", true);
            return;
        }
        Log.i(Diag.TAG, "nsd: searching for " + HostAdvert.SERVICE_TYPE_BARE + " for " + (SEARCH_MS / 1000) + " s");
        l.status("Searching for hosts on this Wi-Fi...", false);
        main.postDelayed(timeout, SEARCH_MS);
    }

    /** Lock held. Hosts shown: every one found except this phone's own. */
    private int visible() {
        int n = 0;
        for (HostAdvert.Found h : found.values()) if (!own.contains(h.host)) n++;
        return n;
    }

    /** Lock held. */
    private void publish() {
        Listener l = listener;
        if (l == null) return;
        // A phone cannot join itself; after Stop hosting its own advert can linger in the NSD cache for a few seconds.
        ArrayList<HostAdvert.Found> list = new ArrayList<>();
        for (HostAdvert.Found h : found.values()) if (!own.contains(h.host)) list.add(h);
        l.hosts(list);
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

    @Override
    public synchronized void stop() {
        running = false;
        releaseMulticastLock();
        main.removeCallbacks(timeout);
        if (queue != null) queue.stop();
        queue = null;
        NsdManager m = nsd;
        NsdManager.DiscoveryListener d = discovery;
        discovery = null;
        if (m != null && d != null) {
            try { m.stopServiceDiscovery(d); } catch (RuntimeException ignored) { }
        }
    }
}
