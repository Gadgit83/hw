package org.hedgewars.android.net;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Resolves discovered services one at a time, with a time limit and retries. Android's NsdManager.resolveService is known to be
 * unreliable: a second resolve while one is running fails with FAILURE_ALREADY_ACTIVE (on many versions), a resolve can fail
 * with FAILURE_INTERNAL_ERROR and succeed a moment later, and on some versions its callback never comes. So: one worker thread,
 * one resolve at a time, each waits at most {@code attemptMs}; a failure or a timeout is retried after a growing pause, up to
 * {@code maxAttempts}; then the service is given up (reported once) until it is announced again.
 * Plain Java; the Android side supplies the {@link Resolver}.
 */
public final class ResolveQueue {
    /** Starts one resolve of {@code key} and reports exactly once through {@code done}; may be called from any thread. */
    public interface Resolver {
        void resolve(String key, Done done);
    }

    public interface Done {
        /** Resolved. */
        void ok(Object result);

        /** Failed; the queue retries. */
        void fail(String why);
    }

    public interface Sink {
        void resolved(String key, Object result);

        void gaveUp(String key, String why);
    }

    private final Resolver resolver;
    private final Sink sink;
    private final long attemptMs, retryMs;
    private final int maxAttempts;
    private final Set<String> pending = new LinkedHashSet<>();
    private final Set<String> known = new LinkedHashSet<>();
    private Thread worker;
    private boolean stopped;
    /** The resolve that is running: only its own callback counts. */
    private Object currentToken;
    private Object currentResult;
    private String currentFail;
    private boolean currentDone;
    private int resolveCalls;

    public ResolveQueue(Resolver resolver, Sink sink, long attemptMs, long retryMs, int maxAttempts) {
        this.resolver = resolver;
        this.sink = sink;
        this.attemptMs = attemptMs;
        this.retryMs = retryMs;
        this.maxAttempts = maxAttempts;
    }

    /** Adds a service to resolve; ignored if it is already queued or already resolved. */
    public synchronized void add(String key) {
        if (stopped || known.contains(key)) return;
        known.add(key);
        pending.add(key);
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(this::loop, "nsd-resolve");
            worker.setDaemon(true);
            worker.start();
        }
        notifyAll();
    }

    /** The service went away: forget it, so that it is resolved again when it comes back. */
    public synchronized void remove(String key) {
        known.remove(key);
        pending.remove(key);
    }

    public synchronized void stop() {
        stopped = true;
        pending.clear();
        notifyAll();
    }

    public synchronized int resolveCalls() {
        return resolveCalls;
    }

    private synchronized String next() throws InterruptedException {
        while (!stopped && pending.isEmpty()) wait(2000);
        if (stopped) return null;
        String k = pending.iterator().next();
        pending.remove(k);
        return k;
    }

    private void loop() {
        try {
            for (;;) {
                String key = next();
                if (key == null) return;
                String why = "";
                boolean ok = false;
                for (int attempt = 1; attempt <= maxAttempts && !isStopped() && stillWanted(key); attempt++) {
                    Object token = new Object();
                    synchronized (this) {
                        currentToken = token;
                        currentDone = false;
                        currentResult = null;
                        currentFail = "";
                        resolveCalls++;
                    }
                    try {
                        resolver.resolve(key, new Done() {
                            @Override public void ok(Object r) { finish(token, r, null); }
                            @Override public void fail(String w) { finish(token, null, w == null ? "failed" : w); }
                        });
                    } catch (RuntimeException e) {
                        finish(token, null, "resolve threw " + e);
                    }
                    Object result;
                    synchronized (this) {
                        long end = System.currentTimeMillis() + attemptMs;
                        while (!currentDone && !stopped && System.currentTimeMillis() < end) wait(Math.max(1, end - System.currentTimeMillis()));
                        if (!currentDone) { currentFail = "no answer in " + attemptMs + " ms"; }
                        result = currentResult;
                        why = currentFail;
                        currentToken = null;
                    }
                    if (result != null) {
                        if (stillWanted(key)) sink.resolved(key, result);
                        ok = true;
                        break;
                    }
                    if (attempt < maxAttempts) Thread.sleep(retryMs * attempt);
                }
                if (!ok && stillWanted(key) && !isStopped()) {
                    synchronized (this) { known.remove(key); }
                    sink.gaveUp(key, why);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized boolean isStopped() {
        return stopped;
    }

    private synchronized boolean stillWanted(String key) {
        return known.contains(key);
    }

    private synchronized void finish(Object token, Object result, String fail) {
        if (token != currentToken || currentDone) return;     // a late answer of an attempt that was already abandoned
        currentDone = true;
        currentResult = result;
        currentFail = fail == null ? "" : fail;
        notifyAll();
    }
}
