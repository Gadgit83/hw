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
    static volatile String target = "";
    static volatile Map<String, Object> state;
    static volatile String closedReason = "", notice = "";
    static volatile String relaySummary = "", toYouHash = "", relayLogHash = "";
    static volatile long toYouMsgs;
    static volatile RelayClient.StartInfo pendingStart;
    static volatile boolean inGame;
    private static PowerManager.WakeLock wake;
    private static WifiManager.WifiLock wifi;

    private static final RelayClient.Handler HANDLER = new RelayClient.Handler() {
        @Override public void onState(Map<String, Object> s) {
            state = s;
            ping();
        }

        @Override public void onStart(RelayClient.StartInfo s) {
            inGame = true;
            Listener l = listener;
            if (l != null) l.startGame(s); else pendingStart = s;
        }

        @Override public void onSummary(Map<String, Object> m) {
            inGame = false;
            relaySummary = org.hedgewars.android.net.Json.str(m, "text", "");
            toYouHash = org.hedgewars.android.net.Json.str(m, "toYouHash", "");
            toYouMsgs = org.hedgewars.android.net.Json.num(m, "toYouMsgs", 0);
            relayLogHash = org.hedgewars.android.net.Json.str(m, "logHash", "");
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
            inGame = false;
            client = null;
            releaseLocks();
            ping();
        }
    };

    static void ping() {
        Listener l = listener;
        if (l != null) l.changed();
    }

    /** Connects this phone's lobby link (blocking; call from a worker thread). */
    static void connect(Context c, String host, int port, String name, boolean isHost) throws IOException {
        leave();
        closedReason = "";
        notice = "";
        state = null;
        relaySummary = "";
        NetDiag.clear(c);
        List<SetupModel.Team> teams = Store.loadSetup(c, Store.data(c)).teams;
        RelayClient rc = new RelayClient(host, port, name, Diag.versionName(c), isHost, teams, HANDLER);
        rc.connect();
        client = rc;
        joined = true;
        hosting = isHost;
        target = host + ":" + port;
        acquireLocks(c);
        ping();
    }

    static void leave() {
        RelayClient rc = client;
        client = null;
        joined = false;
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

    static synchronized void releaseLocks() {
        try {
            if (wake != null && wake.isHeld()) wake.release();
            if (wifi != null && wifi.isHeld()) wifi.release();
        } catch (RuntimeException ignored) {
        }
    }

    // Addresses -------------------------------------------------------------------------------

    /** The phone's IPv4 addresses on its networks, Wi-Fi first, for the host screen. */
    static List<String> localAddresses() {
        List<String> wlan = new ArrayList<>(), other = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String s = a.getHostAddress();
                    (ni.getName().startsWith("wlan") ? wlan : other).add(s);
                }
            }
        } catch (Exception ignored) {
        }
        wlan.addAll(other);
        return wlan;
    }
}
