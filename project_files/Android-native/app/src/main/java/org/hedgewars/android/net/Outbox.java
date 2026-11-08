package org.hedgewars.android.net;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;

/**
 * A bounded queue with its own writer thread, so a slow or stalled recipient can never block the relay
 * (plan section 4: each recipient has a bounded queue and its own writer). {@link #offer} never blocks;
 * when the queue is full it returns false and the owner treats the recipient as lost. The writer waits
 * until it is attached to a stream and, if {@link #hold} is set, until released (the start gate: nothing is
 * sent before every phone's engine link is up).
 */
final class Outbox {
    interface Overflow { void tooSlow(); }

    private final ArrayDeque<byte[]> q = new ArrayDeque<>();
    private final int maxItems, maxBytes;
    private long bytes;
    private OutputStream out;
    private boolean held;
    private boolean closed;
    private final Thread thread;
    private final Runnable onError;
    long sentItems, sentBytes;

    Outbox(String name, int maxItems, int maxBytes, boolean held, Runnable onError) {
        this.maxItems = maxItems;
        this.maxBytes = maxBytes;
        this.held = held;
        this.onError = onError;
        thread = new Thread(this::run, name);
        thread.setDaemon(true);
        thread.start();
    }

    synchronized boolean offer(byte[] b) {
        if (closed) return false;
        if (q.size() >= maxItems || bytes + b.length > maxBytes) return false;
        q.add(b);
        bytes += b.length;
        notifyAll();
        return true;
    }

    synchronized void attach(OutputStream o) {
        out = o;
        notifyAll();
    }

    synchronized void release() {
        held = false;
        notifyAll();
    }

    synchronized void close() {
        closed = true;
        notifyAll();
    }

    /** Close after everything queued has been written (or the stream failed). */
    synchronized void closeWhenDrained() {
        closeAfterDrain = true;
        notifyAll();
    }

    private boolean closeAfterDrain;

    synchronized int queued() {
        return q.size();
    }

    private void run() {
        try {
            for (;;) {
                byte[] b;
                OutputStream o;
                synchronized (this) {
                    while (!closed && (out == null || held || q.isEmpty())) {
                        if (closeAfterDrain && q.isEmpty() && out != null) {
                            closed = true;
                            break;
                        }
                        wait();
                    }
                    if (closed) {
                        try { if (out != null) out.flush(); } catch (IOException ignored) { }
                        return;
                    }
                    b = q.poll();
                    bytes -= b.length;
                    o = out;
                }
                o.write(b);
                sentItems++;
                sentBytes += b.length;
                boolean more;
                synchronized (this) { more = !q.isEmpty(); }
                if (!more) o.flush();
            }
        } catch (IOException | InterruptedException e) {
            synchronized (this) { closed = true; }
            if (onError != null) onError.run();
        }
    }
}
