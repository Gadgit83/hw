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

/**
 * Foreground service for a phone that has joined a network game, as a player or to watch (docs/network.md, Hidden phones). A joiner's
 * lobby link lives in the app process; without a foreground service Android freezes that process within seconds of the app going to
 * the background, the host then sees the phone as gone. While this service runs the process is not frozen. It runs in the main process,
 * like {@link HostService}. The wake lock and the Wi-Fi lock are taken by {@link NetSession} from the join until the phone leaves, so
 * they cover the same time as this service.
 *
 * It runs from the join until the phone leaves the session (the lobby link closes or the phone leaves it, see {@link NetSession}). A
 * game that ends does not stop it: the phone goes back to the lobby, and the lobby link must stay alive there too.
 */
public class JoinService extends Service {
    static final String ACTION_START = BuildConfig.APPLICATION_ID + ".JOIN_START";
    static final String ACTION_STOP = BuildConfig.APPLICATION_ID + ".JOIN_STOP";
    private static final String EXTRA_HOST = "host";
    private static final String CHANNEL = "joining";
    private static final int NOTIFICATION_ID = 46635;

    /**
     * Starts the service for a join to {@code host}. Called once the relay has accepted the seat (see {@link NetSession}): a join it refuses
     * must never start the service. Never throws.
     */
    static void start(Context c, String host) {
        Intent i = new Intent(c, JoinService.class).setAction(ACTION_START).putExtra(EXTRA_HOST, host);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (RuntimeException e) {
            // Android can refuse a start from the background; the join itself still works, only the freeze protection is lost.
            android.util.Log.w(Diag.TAG, "could not start the joining service: " + e);
        }
    }

    /** Stops the service when the phone leaves the session. Never throws. */
    static void stop(Context c) {
        if (c == null) return;
        try {
            c.stopService(new Intent(c, JoinService.class));
        } catch (RuntimeException e) {
            android.util.Log.w(Diag.TAG, "could not stop the joining service: " + e);
        }
    }

    /**
     * Goes to the foreground as soon as the service exists. Android ends a service that was started with startForegroundService and has
     * not called startForeground within five seconds, with ForegroundServiceDidNotStartInTimeException; a stop that arrives before the
     * first command (a link that closes at once) would otherwise leave that call out. onDestroy always comes after onCreate.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        goForeground("");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String host = intent == null ? null : intent.getStringExtra(EXTRA_HOST);
        goForeground(host == null || host.isEmpty() ? "" : host);
        NetSession.acquireLocks(this);           // already held by the join: this only makes sure they are
        return START_NOT_STICKY;
    }

    private void goForeground(String host) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "In a network game", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, NetLobbyActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pf = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, pf);
        String text = host.isEmpty() ? "In a network game" : "In a network game on " + host;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle(BuildConfig.APP_NAME + ": in a network game")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(openPi)
                .setOngoing(true);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }
}
