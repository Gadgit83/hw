package org.hedgewars.android;

import android.content.Context;
import android.net.Network;
import android.util.Log;

import org.hedgewars.android.net.Frames;
import org.hedgewars.android.net.HostAdvert;
import org.hedgewars.android.net.Relay;
import org.hedgewars.android.net.SubnetScan;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The fallback of the host search (issue #15): probes the phone's own Wi-Fi (or Ethernet) subnet for a Hedge Party relay on the phone
 * port, for phones where NSD finds nothing (a VPN takes the default network, or the phone filters multicast). {@link NsdFinder} starts
 * it when NSD has found nothing for a few seconds, and stops it with the search.
 *
 * <p>Each probe connects on the Wi-Fi network (so a VPN does not take it), waits for the connection (at most {@link #CONNECT_MS} on the
 * first pass), sends the probe hello ({@link HostAdvert#PROBE_HELLO}) and reads the relay's one-line answer. A probe is not a join: the
 * relay makes no player of it and checks no password. If the first pass finds no host, one more pass runs over the same addresses with
 * the longer {@link #CONNECT_SLOW_MS}, for a host that answers late (a busy phone, Wi-Fi power saving); see
 * {@link SubnetScan#nextPassConnectMs}. At most {@link #AT_ONCE} probes run at a time, on a pool of threads that {@link #stop} shuts
 * down and whose sockets it closes. Not run on the build machine: {@link SubnetScan} and {@link HostAdvert#parseProbe} are the tested
 * parts.
 */
final class SubnetFinder {
    /** Receives each host that answers. Called on a probe thread, never with this finder's lock held. */
    interface Sink {
        void found(HostAdvert.Found h);
    }

    static final int CONNECT_MS = 300, READ_MS = 1000, AT_ONCE = 32;
    /** The connect timeout of the second pass (see {@link SubnetScan#SLOW_CONNECT_MS}). */
    static final int CONNECT_SLOW_MS = SubnetScan.SLOW_CONNECT_MS;
    /**
     * The longest wait for the probes to end after the last one was started (a probe takes at most about 1.3 s on the first pass and
     * 2.5 s on the second).
     */
    private static final long END_WAIT_MS = 60000;

    private final Context app;
    private final Object lock = new Object();
    /** The sockets of the probes in progress, closed by {@link #stop}. Guarded by lock. */
    private final Set<Socket> open = new HashSet<>();
    private boolean cancelled;             // guarded by lock
    private ExecutorService pool;          // guarded by lock

    SubnetFinder(Context c) {
        this.app = c.getApplicationContext();
    }

    /** Starts the scan on its own thread. Call once per finder. */
    void start(final Sink sink) {
        Thread t = new Thread(() -> run(sink), "subnet-scan");
        t.setDaemon(true);
        t.start();
    }

    /** Ends the scan: no probe starts after this, the running ones are cut short. Safe from any thread, and more than once. */
    void stop() {
        ExecutorService ex;
        List<Socket> toClose;
        synchronized (lock) {
            cancelled = true;
            ex = pool;
            pool = null;
            toClose = new ArrayList<>(open);
            open.clear();
        }
        if (ex != null) ex.shutdownNow();
        for (Socket s : toClose) closeQuietly(s);
    }

    private void run(Sink sink) {
        long t0 = System.currentTimeMillis();
        NetworkPicker.Iface lan = NetworkPicker.lanIface(NetworkPicker.list());     // reads the system: not on the UI thread
        if (lan == null) {
            Log.i(Diag.TAG, "subnet: no Wi-Fi or Ethernet address on this phone, not scanning");
            return;
        }
        List<String> targets = SubnetScan.addresses(lan.address, lan.prefix);
        // The same Automatic choice as a join: the connected Wi-Fi network (never a stale one), or the default network when a VPN
        // carries this subnet. A network that refuses the socket is replaced by the default one in each probe (NetworkPicker.openSocket).
        final Network net = NetworkPicker.autoNetwork(app, lan.address, lan.name);
        Log.i(Diag.TAG, "subnet: scan started, " + targets.size() + " address(es) on " + lan.kind + " " + lan.address + "/" + lan.prefix
                + ", port " + Relay.PHONE_PORT + ", " + (net != null ? "bound to " + NetworkPicker.describe(app, net)
                : "on the default route (" + NetworkPicker.defaultRouteWhy(app, lan.address) + ")"));
        AtomicInteger found = new AtomicInteger();
        int connectMs = CONNECT_MS;
        for (int pass = 1; ; pass++) {
            long p0 = System.currentTimeMillis();
            int before = found.get();
            boolean stopped = scanPass(targets, net, connectMs, sink, found);
            long ms = System.currentTimeMillis() - p0;
            int passFound = found.get() - before;
            if (stopped) {
                Log.i(Diag.TAG, "subnet: scan stopped after " + (System.currentTimeMillis() - t0) + " ms, " + found.get() + " host(s) found");
                return;
            }
            Log.i(Diag.TAG, "subnet: scan ended, " + targets.size() + " address(es) probed, " + passFound + " host(s) found in " + ms + " ms");
            connectMs = SubnetScan.nextPassConnectMs(pass, found.get());
            if (connectMs < 0) return;
            Log.i(Diag.TAG, "subnet: no host on the first pass, probing again with a " + connectMs + " ms connect timeout");
        }
    }

    /** One pass over the addresses, at most {@link #AT_ONCE} at a time. True if the scan was stopped (before or during the pass). */
    private boolean scanPass(List<String> targets, Network net, int connectMs, Sink sink, AtomicInteger found) {
        ExecutorService ex = Executors.newFixedThreadPool(AT_ONCE, r -> {
            Thread th = new Thread(r, "subnet-probe");
            th.setDaemon(true);
            return th;
        });
        synchronized (lock) {
            if (cancelled) {
                ex.shutdownNow();
                return true;
            }
            pool = ex;
        }
        for (String a : targets) ex.execute(() -> probe(a, net, connectMs, sink, found));
        ex.shutdown();
        try {
            ex.awaitTermination(END_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (lock) {
            return cancelled;
        }
    }

    /** One probe. Any failure (refused, silent, not a Hedge Party relay) just means no host at this address. */
    private void probe(String address, Network net, int connectMs, Sink sink, AtomicInteger found) {
        Socket s = null;
        HostAdvert.Found h = null;
        try {
            s = NetworkPicker.openSocket(net, n -> n.getSocketFactory().createSocket());
            if (!track(s)) return;
            s.connect(new InetSocketAddress(address, Relay.PHONE_PORT), connectMs);
            s.setSoTimeout(READ_MS);
            s.getOutputStream().write((HostAdvert.PROBE_HELLO + "\n").getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            String line = Frames.readLine(s.getInputStream(), HostAdvert.PROBE_MAX_LINE);
            h = HostAdvert.parseProbe(line, address, Relay.PHONE_PORT);
        } catch (IOException | RuntimeException e) {
            // nothing listening, or something that is not a Hedge Party relay: the usual answer for most addresses
        } finally {
            if (s != null) untrackAndClose(s);
        }
        if (h == null) return;
        found.incrementAndGet();
        Log.i(Diag.TAG, "subnet: found " + h.name + " at " + h.address() + ", version " + h.version);
        sink.found(h);
    }

    /** Records a probe's socket so that {@link #stop} can close it. False (and not recorded) once the scan is stopped. */
    private boolean track(Socket s) {
        synchronized (lock) {
            if (cancelled) return false;
            open.add(s);
            return true;
        }
    }

    private void untrackAndClose(Socket s) {
        synchronized (lock) {
            open.remove(s);
        }
        closeQuietly(s);
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException | RuntimeException ignored) {
        }
    }
}
