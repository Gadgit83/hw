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
    static final String ACTION_START = "party.hedge.host.HOST_START";
    static final String ACTION_STOP = "party.hedge.host.HOST_STOP";
    private static final String CHANNEL = "hosting";
    private static final int NOTIFICATION_ID = 46633;

    /** The running relay, or null. Set once the port is bound. */
    static volatile Relay relay;
    /** Why hosting failed, or "". */
    static volatile String error = "";
    static volatile boolean starting;

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
            error = "";
            new Thread(this::startRelay, "host-start").start();
        }
        return START_NOT_STICKY;
    }

    private void startRelay() {
        final Context app = getApplicationContext();
        try {
            Relay.Config cfg = new Relay.Config();
            cfg.port = Relay.PHONE_PORT;
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
            Relay r = new Relay(cfg);
            r.start();
            relay = r;
            NetSession.acquireLocks(app);
            List<String> addr = NetSession.localAddresses();
            String where = addr.isEmpty() ? "no Wi-Fi address found" : addr.get(0) + ":" + Relay.PHONE_PORT;
            goForeground("Hosting at " + where);
        } catch (IOException e) {
            error = "Could not host on port " + Relay.PHONE_PORT + ": " + e.getMessage()
                    + (e.getMessage() != null && e.getMessage().contains("in use") ? " (is another host running?)" : "");
            android.util.Log.e(Diag.TAG, error);
            stopSelf();
        } finally {
            starting = false;
            NetSession.ping();
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
        Relay r = relay;
        relay = null;
        if (r != null) r.stop();
        NetSession.leave();
        NetSession.hosting = false;
    }

    @Override
    public void onDestroy() {
        stopHosting();
        super.onDestroy();
        NetSession.ping();
    }
}
