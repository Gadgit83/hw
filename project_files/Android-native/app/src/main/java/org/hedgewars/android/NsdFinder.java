package org.hedgewars.android;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

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
    static final long SEARCH_MS = 45000;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private ResolveQueue queue;
    private Listener listener;
    private final Map<String, HostAdvert.Found> found = new LinkedHashMap<>();
    private final Map<String, NsdServiceInfo> seen = new LinkedHashMap<>();
    private volatile boolean running;
    private final Runnable timeout = () -> {
        if (!running) return;
        stop();
        Listener l = listener;
        if (l != null) l.status(found.isEmpty() ? "No host found on this Wi-Fi." : "Search finished.", true);
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
            l.status("This phone cannot search for hosts (no NSD service).", true);
            return;
        }
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
                Listener x = listener;
                if (x != null) x.status("Found a host called " + key + " but could not read its address (" + why + "). Type its address instead.", false);
            }
        }, 5000, 700, 3);
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String type, int code) {
                running = false;
                Listener x = listener;
                if (x != null) x.status("The search could not start (error " + code + "). Type the host's address instead.", true);
            }

            @Override public void onStopDiscoveryFailed(String type, int code) { }

            @Override public void onDiscoveryStarted(String type) { }

            @Override public void onDiscoveryStopped(String type) { }

            @Override public void onServiceFound(NsdServiceInfo info) {
                String n = info.getServiceName();
                if (n == null) return;
                synchronized (NsdFinder.this) {
                    if (!running) return;
                    seen.put(n, info);
                }
                queue.add(n);
            }

            @Override public void onServiceLost(NsdServiceInfo info) {
                String n = info.getServiceName();
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
            running = false;
            l.status("The search could not start (" + e.getMessage() + "). Type the host's address instead.", true);
            return;
        }
        l.status("Searching for hosts on this Wi-Fi...", false);
        main.postDelayed(timeout, SEARCH_MS);
    }

    /** Lock held. */
    private void publish() {
        Listener l = listener;
        if (l != null) l.hosts(new ArrayList<>(found.values()));
    }

    private void resolveOne(String key, ResolveQueue.Done done) {
        NsdServiceInfo info;
        synchronized (this) { info = seen.get(key); }
        if (info == null || nsd == null) { done.fail("the service is gone"); return; }
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo i, int code) {
                    done.fail(code == NsdManager.FAILURE_ALREADY_ACTIVE ? "FAILURE_ALREADY_ACTIVE" : "resolve error " + code);
                }

                @Override public void onServiceResolved(NsdServiceInfo i) {
                    if (!(i.getHost() instanceof Inet4Address)) { done.fail("not an IPv4 address"); return; }
                    done.ok(HostAdvert.parse(key, i.getHost().getHostAddress(), i.getPort(), i.getAttributes()));
                }
            });
        } catch (RuntimeException e) {
            done.fail("resolveService threw " + e);
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
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
