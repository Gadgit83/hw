package org.hedgewars.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.hedgewars.android.net.Relay;

import java.io.IOException;
import java.util.List;

/**
 * Foreground service that keeps the relay alive while this phone hosts (docs/tv-viewer-plan.md section 4). It runs
 * in the main process, so it survives the game process being killed after each game and the host leaving the game
 * screen. The relay itself is plain Java ({@link Relay}); this class only wraps it: notification, foreground
 * service type, wake lock and Wi-Fi lock.
 */
public class HostService extends Service {
    static final String ACTION_START = BuildConfig.APPLICATION_ID + ".HOST_START";
    static final String ACTION_STOP = BuildConfig.APPLICATION_ID + ".HOST_STOP";
    private static final String CHANNEL = "hosting";
    private static final int NOTIFICATION_ID = 46633;

    /** The running relay, or null. Set once the port is bound. */
    static volatile Relay relay;
    /** Why hosting failed, or "". Cleared by {@link #begin()} before a start is requested, so a screen never reads an old failure. */
    static volatile String error = "";
    static volatile boolean starting;
    /** The relay stop that is still running on its own thread, if any. A new start waits for it: both want the same ports. */
    private static volatile Thread stopping;
    /** Counts start requests; a failure of an earlier attempt never overwrites the state of a later one. */
    private static int generation;
    /**
     * The host role is given only to the app itself: a secret made for each hosting session, kept in memory of this
     * process (never written out, never sent over the network except to the relay on loopback by this process).
     */
    static volatile String hostSecret = "";

    /** Counts Stop requests, so a start that is still opening its ports can tell that a Stop arrived meanwhile. */
    private static int stopCount;

    /** Called by the screen, before it starts the service: clears the old error synchronously and numbers the attempt. */
    static synchronized int begin() {
        error = "";
        return ++generation;
    }

    private static synchronized void fail(int gen, String text) {
        if (gen == generation) error = text;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopHosting();
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground("Starting to host...");
        if (relay == null && !starting) {
            starting = true;
            final int gen;
            synchronized (HostService.class) { gen = generation; }
            new Thread(() -> startRelay(gen), "host-start").start();
        }
        return START_NOT_STICKY;
    }

    private void startRelay(int gen) {
        final Context app = getApplicationContext();
        final int stopsAtStart;
        synchronized (HostService.class) { stopsAtStart = stopCount; }
        try {
            Relay.Config cfg = new Relay.Config();
            cfg.port = Relay.PHONE_PORT;
            byte[] sb = new byte[16];
            new java.security.SecureRandom().nextBytes(sb);
            StringBuilder sec = new StringBuilder();
            for (byte x : sb) sec.append(String.format("%02x", x & 0xff));
            cfg.hostSecret = sec.toString();
            cfg.data = Store.data(app);
            cfg.hostSetup = () -> Store.loadSetup(app, cfg.data);
            cfg.build = Diag.versionName(app);
            cfg.hostName = android.os.Build.MODEL;
            cfg.listener = new Relay.Listener() {
                @Override public void log(String line) {
                    android.util.Log.i(Diag.TAG, "relay: " + line);
                }

                @Override public void changed() {
                    NetSession.ping();
                }
            };
            // A stop that was asked for a moment ago closes its sockets on another thread; binding the same ports before
            // it is done fails with "address already in use".
            Thread st = stopping;
            if (st != null) {
                try { st.join(10000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            Relay r = new Relay(cfg);
            startWithRetry(r);
            boolean stale;
            synchronized (HostService.class) {
                stale = stopCount != stopsAtStart;
                if (!stale) {
                    hostSecret = cfg.hostSecret;
                    relay = r;
                }
            }
            if (stale) {
                // Stop arrived while the ports were being opened (relay was still null, so it stopped nothing): do not keep hosting.
                android.util.Log.i(Diag.TAG, "host start cancelled by a stop that arrived meanwhile");
                Thread t = new Thread(r::stop, "host-stop");
                t.setDaemon(true);
                t.start();
                stopSelf();
                return;
            }
            NetSession.acquireLocks(app);
            List<String> addr = NetSession.localAddresses();
            String where = addr.isEmpty() ? "no Wi-Fi address found" : addr.get(0) + ":" + Relay.PHONE_PORT;
            goForeground("Hosting at " + where);
        } catch (IOException e) {
            String text = "Could not host on port " + Relay.PHONE_PORT + ": " + e.getMessage()
                    + (e.getMessage() != null && e.getMessage().contains("in use") ? " (is another host running?)" : "");
            fail(gen, text);
            android.util.Log.e(Diag.TAG, text);
            stopSelf();
        } finally {
            starting = false;
            NetSession.ping();
        }
    }

    /** Opens the phone port; if it is still in use, tries a few more times (the viewer port retries inside the relay). */
    private static void startWithRetry(Relay r) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                r.start();
                return;
            } catch (java.net.BindException e) {
                if (attempt >= 5) throw e;
                try { Thread.sleep(600); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
    }

    private void goForeground(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Hosting a game", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, NetLobbyActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pf = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, pf);
        Intent stop = new Intent(this, HostService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, pf);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle(BuildConfig.APP_NAME + ": hosting a game")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(openPi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_delete, "Stop hosting", stopPi).build());
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void stopHosting() {
        Relay r;
        synchronized (HostService.class) {
            stopCount++;
            r = relay;
            relay = null;
            hostSecret = "";
        }
        NetSession.leave();
        NetSession.hosting = false;
        // Relay.stop() closes sockets (and can block): never on the main thread.
        if (r != null) {
            Thread t = new Thread(r::stop, "host-stop");
            t.setDaemon(true);
            stopping = t;
            t.start();
        }
    }

    @Override
    public void onDestroy() {
        stopHosting();
        super.onDestroy();
        NetSession.ping();
    }
}
