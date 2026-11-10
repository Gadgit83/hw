package org.hedgewars.android;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;

import org.hedgewars.android.net.Relay;
import org.hedgewars.android.net.RelayClient;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The main process's single network-game session: the phone's lobby connection to the relay (the host's own
 * phone too, over loopback), the latest lobby state and the report of the last game. The lobby screen listens.
 * Hosting itself (the relay) lives in {@link HostService}.
 */
final class NetSession {
    interface Listener {
        void changed();

        /** The relay started the game: write the files and start the game activity. Called on a worker thread. */
        void startGame(RelayClient.StartInfo s);
    }

    private NetSession() { }

    static volatile Listener listener;
    static volatile RelayClient client;
    static volatile boolean hosting, joined;
    /** This phone joined only to watch (no teams): the lobby offers Watch instead of Ready, and the game screen has no controls. */
    static volatile boolean watching;
    static volatile String target = "";
    static volatile Map<String, Object> state;
    static volatile String closedReason = "", notice = "";
    static volatile String relaySummary = "", toYouHash = "", relayLogHash = "";
    static volatile long toYouMsgs;
    static volatile RelayClient.StartInfo pendingStart;
    static volatile boolean inGame;
    /** The Network screen is resumed (in front). Set by that screen. */
    static volatile boolean lobbyVisible;
    /** How hosts are found for the Join list; tests replace it. */
    static volatile java.util.function.Function<Context, HostDiscovery> discoveryFactory = NsdFinder::new;
    /** What the host's announcement on the Wi-Fi did ("" before it was tried); shown on the host screen. */
    static volatile String nsdStatus = "";

    static void setNsdStatus(String s) {
        nsdStatus = s;
        ping();
    }

    /** The fingerprint of this phone's installed game data (cached beside the install marker). Not for the UI thread. */
    static String dataDigest(Context c) {
        return org.hedgewars.android.net.DataDigest.cached(Store.data(c).root);
    }

    private static final String START_CHANNEL = "gamestart";
    private static final int START_NOTIFICATION_ID = 46634;
    private static PowerManager.WakeLock wake;
    private static WifiManager.WifiLock wifi;

    private static final RelayClient.Handler HANDLER = new RelayClient.Handler() {
        @Override public void onState(Map<String, Object> s) {
            state = s;
            ping();
        }

        @Override public void onStart(RelayClient.StartInfo s) {
            inGame = true;
            notice = "";
            Listener l = listener;
            if (l != null && lobbyVisible) {
                l.startGame(s);
            } else {
                // The Network screen is not in front (another app, the home screen, the setup screen): Android does not
                // let a background app open a screen, so keep the start and put a notification up to tap.
                pendingStart = s;
                Context c = HpApp.appContext;
                if (c != null) notifyStart(c);
            }
        }

        @Override public void onSummary(Map<String, Object> m) {
            inGame = false;
            relaySummary = org.hedgewars.android.net.Json.str(m, "text", "");
            toYouHash = org.hedgewars.android.net.Json.str(m, "toYouHash", "");
            toYouMsgs = org.hedgewars.android.net.Json.num(m, "toYouMsgs", 0);
            relayLogHash = org.hedgewars.android.net.Json.str(m, "logHash", "");
            // A phone that was dropped or left earlier keeps that message; otherwise say how the game ended.
            if (notice.isEmpty()) notice = endNotice(org.hedgewars.android.net.Json.str(m, "reason", ""), hosting);
            Context c = HpApp.appContext;
            if (c != null) NetDiag.saveSession(c, relaySummary + "\nStream the relay sent this phone: " + toYouMsgs
                    + " messages, hash " + toYouHash + "\nGame log hash at the relay: " + relayLogHash + "\n");
            ping();
        }

        @Override public void onNotice(String t) {
            notice = t;
            ping();
        }

        @Override public void onClosed(String reason) {
            closedReason = reason;
            joined = false;
            watching = false;
            inGame = false;
            client = null;
            releaseLocks();
            ping();
        }
    };

    /**
     * What a phone is told when the game ends: the host that chose to end it sees "You ended the game", the other phones
     * "The host ended the game"; a game that simply finished says nothing; any other reason (a desync, every engine gone)
     * is shown as it is.
     */
    static String endNotice(String reason, boolean iAmHost) {
        if (reason == null || reason.isEmpty() || reason.equals("every engine finished")) return "";
        if (reason.equals(Relay.HOST_ENDED)) return iAmHost ? "You ended the game." : "The host ended the game.";
        return "The game ended: " + reason;
    }

    /** A notification that opens the Network screen, which then starts the waiting game. */
    static void notifyStart(Context c) {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new android.app.NotificationChannel(START_CHANNEL, "Game starting",
                        android.app.NotificationManager.IMPORTANCE_HIGH));
            }
            android.content.Intent open = new android.content.Intent(c, NetLobbyActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int pf = android.app.PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(c, 2, open, pf);
            android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(c, START_CHANNEL) : new android.app.Notification.Builder(c);
            b.setContentTitle(BuildConfig.APP_NAME + ": the game is starting")
                    .setContentText("Tap to join the network game now.")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(START_NOTIFICATION_ID, b.build());
        } catch (RuntimeException e) {
            android.util.Log.w(Diag.TAG, "could not post the game-start notification: " + e);
        }
    }

    static void cancelStartNotification(Context c) {
        try {
            ((android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(START_NOTIFICATION_ID);
        } catch (RuntimeException ignored) {
        }
    }

    static void ping() {
        Listener l = listener;
        if (l != null) l.changed();
    }

    /** Connects this phone's lobby link (blocking; call from a worker thread). */
    static void connect(Context c, String host, int port, String name, boolean isHost) throws IOException {
        connect(c, host, port, name, isHost, false);
    }

    /** Shown on the Network screen while a join retries ("Trying again (2 of 3)..."); empty otherwise. */
    static volatile String connectProgress = "";

    static void connect(Context c, String host, int port, String name, boolean isHost, boolean watch) throws IOException {
        leave();
        final int gen;
        synchronized (NetSession.class) { gen = ++connectGen; }
        closedReason = "";
        notice = "";
        state = null;
        relaySummary = "";
        NetDiag.clear(c);
        BackConfirm.takeLeave(c.getFilesDir());       // a note left by an earlier game must not end this session
        List<SetupModel.Team> teams = watch ? new ArrayList<SetupModel.Team>() : Store.loadSetup(c, Store.data(c)).teams;
        RelayClient rc = new RelayClient(host, port, name, Diag.versionName(c), isHost, teams, HANDLER).watch(watch)
                .identity(org.hedgewars.android.net.Compat.VALUE, dataDigest(c));
        if (isHost) rc.hostSecret(HostService.hostSecret);
        synchronized (NetSession.class) {
            // The phone left, or another join started, while this one was being set up: give up before connecting.
            if (gen != connectGen) throw new Cancelled();
            pending = rc;                        // leave() and cancelPending() close it, which ends a join still retrying
            pendingIsHost = isHost;
        }
        try {
            if (isHost) rc.connect();            // loopback: no reason to retry
            else rc.connect(3, 1500, 15000, (n, of) -> {
                if (gen != connectGen) return;
                connectProgress = "Trying again (" + n + " of " + of + ")...";
                ping();
            });
        } catch (IOException e) {
            synchronized (NetSession.class) {
                if (pending == rc) pending = null;
                if (gen != connectGen) throw new Cancelled();      // a failure caused by the cancel is not "could not reach"
            }
            throw e;
        } finally {
            synchronized (NetSession.class) { if (gen == connectGen) connectProgress = ""; }
        }
        synchronized (NetSession.class) {
            if (gen != connectGen || pending != rc) {
                // The phone left, or another join started, while this one connected: it must not take a seat.
                rc.close();
                throw new Cancelled();
            }
            pending = null;
            client = rc;                         // in the same lock, so a leave() from now on sees it
            joined = true;
            watching = watch;
            hosting = isHost;
            target = host + ":" + port;
        }
        acquireLocks(c);
        if (isHost) startTicker();
        ping();
    }

    /** A join still connecting (retries can take up to 15 s), and a counter that makes an overtaken one give up. */
    private static RelayClient pending;
    private static boolean pendingIsHost;
    private static int connectGen;

    /** A join that was given up on purpose (the phone left, or a newer join started): not an error to show. */
    static final class Cancelled extends IOException {
        Cancelled() { super("The join was cancelled."); }
    }

    /** Gives up a join that is still connecting; a session already joined is left alone. */
    static void cancelPending() {
        RelayClient p;
        synchronized (NetSession.class) {
            if (pendingIsHost) return;           // the host's own connect carries on: hosting runs in the service
            p = pending;
            pending = null;
            connectGen++;
        }
        if (p != null) p.close();
    }

    static void leave() {
        RelayClient p, rc;
        synchronized (NetSession.class) {
            p = pending;
            pending = null;
            connectGen++;
            rc = client;
            client = null;
            joined = false;
            watching = false;
        }
        if (p != null) p.close();
        if (rc != null) rc.close();
        releaseLocks();
    }

    /** Writes game.ipc and net.cfg for the game process. */
    static void writeGameFiles(Context c, RelayClient.StartInfo s) throws IOException {
        RelayClient.writeGameFiles(new File(c.getFilesDir(), "game.ipc"), new File(c.getFilesDir(), "net.cfg"), s,
                NetDiag.launcherFile(c).getAbsolutePath());
    }

    // Locks -----------------------------------------------------------------------------------

    static synchronized void acquireLocks(Context c) {
        try {
            Context app = c.getApplicationContext();
            if (wake == null) {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hedgeparty:net");
                wake.setReferenceCounted(false);
            }
            if (!wake.isHeld()) wake.acquire();
            if (wifi == null) {
                WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                wifi = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hedgeparty:net");
                wifi.setReferenceCounted(false);
            }
            if (!wifi.isHeld()) wifi.acquire();
        } catch (RuntimeException e) {
            android.util.Log.w(Diag.TAG, "could not take the wake or Wi-Fi lock: " + e);
        }
    }

    /**
     * Releases the wake lock and the Wi-Fi lock, unless this phone is still hosting: a host keeps both for as long as its relay runs
     * (its own lobby link ending, for example, must not let the Wi-Fi go to sleep under the other phones). HostService.stopHosting
     * clears the relay first, so stopping hosting does release them.
     */
    static synchronized void releaseLocks() {
        if (HostService.relay != null) return;
        try {
            if (wake != null && wake.isHeld()) wake.release();
            if (wifi != null && wifi.isHeld()) wifi.release();
        } catch (RuntimeException ignored) {
        }
    }

    // Addresses -------------------------------------------------------------------------------

    /**
     * True for Wi-Fi, hotspot, USB tether and Ethernet interfaces (wlan*, swlan*, eth*, the hotspot's ap0, ap1, ..., and the hosts
     * that share a connection by USB or Wi-Fi hotspot: softap*, rndis*, usb*); not cellular (rmnet*), VPN (tun*), Wi-Fi Direct
     * (p2p*) or anything else that merely starts with "ap" (apn0, appp0).
     */
    static boolean isLanInterface(String name) {
        if (name == null) return false;
        if (name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("eth")) return true;
        if (name.startsWith("softap") || name.startsWith("rndis") || name.startsWith("usb")) return true;
        return name.length() > 2 && name.startsWith("ap") && Character.isDigit(name.charAt(2));
    }

    /** The phone's IPv4 addresses on Wi-Fi, hotspot or Ethernet, for the host screen. Empty when there is none. Not for the UI thread. */
    static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        java.util.Enumeration<NetworkInterface> all;
        try {
            all = NetworkInterface.getNetworkInterfaces();
        } catch (Exception e) {
            return out;
        }
        if (all == null) return out;
        for (NetworkInterface ni : Collections.list(all)) {
            // One interface that cannot be read (it vanished, or the platform refuses) must not hide the others.
            try {
                if (!ni.isUp() || ni.isLoopback() || !isLanInterface(ni.getName())) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    out.add(a.getHostAddress());
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // Host screen data, read by a background ticker --------------------------------------------

    /**
     * What the host screen shows about the machine and the relay. Made on a background thread (listing the network
     * interfaces and taking the relay's lock are not for the UI thread); the screen only reads the last snapshot.
     */
    static final class HostInfo {
        final List<String> addresses;
        final int viewerPort, viewers;
        /** TVs that polled the host's status page in the last 10 s: waiting for a game to start. */
        int waiting;
        final String viewerError, live;
        final List<String> viewerLines;

        HostInfo(List<String> addresses, int viewerPort, int viewers, String viewerError, String live, List<String> viewerLines) {
            this.addresses = addresses;
            this.viewerPort = viewerPort;
            this.viewers = viewers;
            this.viewerError = viewerError;
            this.live = live;
            this.viewerLines = viewerLines;
        }

        String key() {
            return addresses + "|" + viewerPort + "|" + viewers + "|" + waiting + "|" + viewerError + "|" + live + "|" + viewerLines;
        }
    }

    /** The last snapshot, or null before the first one is made. */
    static volatile HostInfo hostInfo;
    /** Tests switch the ticker off and set {@link #hostInfo} themselves. */
    static volatile boolean tickerOff;
    private static Thread ticker;

    /** Starts the ticker if it is not running. It ends by itself when this phone no longer hosts. */
    static synchronized void startTicker() {
        if (tickerOff || (ticker != null && ticker.isAlive())) return;
        ticker = new Thread(() -> {
            List<String> addr = new ArrayList<>();
            long addrAt = 0;
            String last = "";
            while (hosting || joined) {
                long now = System.currentTimeMillis();
                if (hosting) {
                    if (now - addrAt > 5000 || addrAt == 0) {
                        addr = localAddresses();
                        addrAt = now;
                    }
                    Relay r = HostService.relay;
                    HostInfo info;
                    if (r == null) info = new HostInfo(addr, -1, 0, "", "", new ArrayList<>());
                    else {
                        List<String> vl = r.viewerLines();
                        info = new HostInfo(addr, r.viewerPort(), vl.size(), r.viewerError(), r.liveText(), vl);
                        info.waiting = r.viewersWaiting();
                    }
                    hostInfo = info;
                    String k = info.key();
                    if (!k.equals(last)) {
                        last = k;
                        ping();
                    }
                }
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
            hostInfo = null;
        }, "net-ticker");
        ticker.setDaemon(true);
        ticker.start();
    }
}
