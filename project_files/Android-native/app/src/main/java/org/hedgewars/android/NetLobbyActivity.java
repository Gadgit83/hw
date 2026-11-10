package org.hedgewars.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.hedgewars.android.net.HostAdvert;
import org.hedgewars.android.net.Json;
import org.hedgewars.android.net.Relay;
import org.hedgewars.android.net.RelayClient;

import java.util.List;
import java.util.Map;

/**
 * Phase 4 network game screen (docs/tv-viewer-plan.md section 9): Host network game, or Join by typed address,
 * then a lobby listing every phone and its teams. The host presses Start. After a game it shows the report to
 * compare between phones. Deliberately separate from the setup screen; it reads the phone's own teams (and,
 * for the host, the map and rules) from the setup that MainActivity saved.
 */
public class NetLobbyActivity extends Activity implements NetSession.Listener {
    private Ui ui;
    private LinearLayout root, content;
    private ScrollView scroll;
    private EditText nameField, addressField;
    private String name = "";
    private String address = "";
    private boolean connecting;
    private String problem = "";
    private boolean destroyed;
    // Finding hosts (docs/tv-viewer-plan.md section 5.1)
    private HostDiscovery discovery;
    private LinearLayout hostsBox;
    private final List<HostAdvert.Found> hosts = new java.util.ArrayList<>();
    private String searchStatus = "";
    private boolean searchDone, searching, permissionAsked, permissionDenied;
    private volatile String myDigest;
    private boolean digestRunning;
    /** Counts joins started on this screen: only the newest may report a problem or end "connecting". */
    private volatile int joinSeq;
    private String lastHost = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Ui(this);
        Ui.edgeToEdge(this);
        android.content.SharedPreferences p = getSharedPreferences(Store.PREFS_NAME, MODE_PRIVATE);
        name = p.getString("netname", Build.MODEL);
        address = p.getString("netaddr", "");
        lastHost = p.getString("lasthost", "");
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(Ui.GUTTER), ui.dp(2), ui.dp(Ui.GUTTER), ui.dp(8));
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // The same bar as the Options screen: Back on the left, the title beside it, fixed above the scrolling cards.
        LinearLayout top = ui.row();
        top.setPadding(ui.dp(Ui.GUTTER), ui.dp(2), ui.dp(Ui.GUTTER), 0);
        top.addView(ui.button("\u2039 Back", false, v -> onBackPressed()), Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(Ui.TOUCH_DP)));
        TextView title = ui.text(BuildConfig.APP_NAME + " network game", 18, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(title, ui.margins(ui.weight(1f), 12, 0, 0, 0));
        root.addView(top);
        root.addView(ui.scrollWithHint(scroll), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        ui.trackInsets(root, (l, t, r, bt, kb) -> root.setPadding(l, t, r, bt));
        NetSession.listener = this;
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        NetSession.listener = this;
        NetSession.lobbyVisible = true;
        NetSession.cancelStartNotification(this);
        if (NetSession.hosting) NetSession.startTicker();
        sendForeground(true);
        if (BackConfirm.takeLeave(getFilesDir()) && NetSession.joined && NetSession.watching) {
            // "Stop watching" was confirmed in the game screen: leave the relay now and show the chooser.
            NetSession.pendingStart = null;
            NetSession.inGame = false;
            leaveOrStop();
        }
        if (!NetSession.joined && !NetSession.hosting) startSearch(false);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        RelayClient.StartInfo s = NetSession.pendingStart;
        if (s != null) {
            NetSession.pendingStart = null;
            startGame(s);
            return;
        }
        render();
    }

    @Override
    protected void onPause() {
        NetSession.lobbyVisible = false;
        stopSearch();
        sendForeground(false);
        saveFields();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (isFinishing()) NetSession.cancelPending();     // backing out of the screen ends a join that is still trying
        if (NetSession.listener == this) NetSession.listener = null;
        super.onDestroy();
    }

    private static void sendForeground(boolean on) {
        RelayClient c = NetSession.client;
        if (c != null && !NetSession.inGame) c.setForeground(on);
    }

    /**
     * Back leaves the lobby for good: it asks first when this phone is connected, then closes the link and releases the
     * wake lock, the Wi-Fi lock and (for the host) the hosting service. Without a connection it just closes the screen.
     */
    @Override
    public void onBackPressed() {
        if (!NetSession.joined || NetSession.inGame) {
            finish();
            return;
        }
        final boolean host = NetSession.hosting;
        final boolean watch = NetSession.watching;
        Ui.dialog(this)
                .setTitle(host ? "Stop hosting?" : watch ? "Stop watching?" : "Leave the network game?")
                .setMessage(host ? "Every phone that has joined is disconnected." : watch ? "You stop watching and are removed from the lobby." : "You are removed from the lobby.")
                .setPositiveButton(host ? "Stop hosting" : watch ? "Stop watching" : "Leave", (d, w) -> {
                    leaveOrStop();
                    finish();
                })
                .setNegativeButton(watch ? "Keep watching" : "Stay", null)
                .show();
    }

    private void saveFields() {
        if (nameField != null) name = nameField.getText().toString().trim();
        if (addressField != null) address = addressField.getText().toString().trim();
        getSharedPreferences(Store.PREFS_NAME, MODE_PRIVATE).edit().putString("netname", name).putString("netaddr", address).apply();
    }

    // NetSession.Listener -----------------------------------------------------------------------

    @Override
    public void changed() {
        runOnUiThread(() -> {
            if (!destroyed) render();
        });
    }

    @Override
    public void startGame(RelayClient.StartInfo s) {
        runOnUiThread(() -> {
            if (destroyed) return;
            try {
                NetSession.writeGameFiles(this, s);
            } catch (Exception e) {
                problem = "Could not write the game files: " + e;
                render();
                return;
            }
            Diag.clearGameState(this);
            NetDiag.clear(this);
            startActivity(new Intent(this, GameActivity.class));
        });
    }

    // Screens -----------------------------------------------------------------------------------

    private void render() {
        // Keep what has been typed: a rebuild (a state change) must not wipe the name or the address.
        if (nameField != null) name = nameField.getText().toString().trim();
        if (addressField != null) address = addressField.getText().toString().trim();
        content.removeAllViews();
        hostsBox = null;
        nameField = null;
        addressField = null;
        if (!problem.isEmpty()) content.addView(coloured(problem, Ui.DANGER));
        if (connecting && !NetSession.connectProgress.isEmpty()) content.addView(ui.hint(NetSession.connectProgress));
        String closed = NetSession.closedReason;
        if (!closed.isEmpty() && !NetSession.joined) content.addView(coloured(closed, Ui.DANGER));
        if (!NetSession.inGame) {
            // Why the last game stopped, if it did not end cleanly (the launcher records it, as for a local game).
            String err = Diag.readTail(new java.io.File(Diag.logsDir(this), Diag.LAST_ERROR), 800);
            if (err != null && !err.trim().isEmpty()) content.addView(coloured("The last game stopped: " + err.trim(), Ui.DANGER));
        }
        if (NetSession.joined) lobby(); else choose();
        if (!NetSession.relaySummary.isEmpty()) report();
    }

    private TextView coloured(String s, int colour) {
        TextView t = ui.text(s, 14, colour);
        t.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6));
        return t;
    }

    private void choose() {
        boolean wide = getResources().getConfiguration().screenWidthDp >= 700;
        LinearLayout start = ui.card();
        LinearLayout before = ui.card();
        before.addView(ui.heading("Before you start"));
        before.addView(ui.hint("All phones on the same Wi-Fi, same app version. Set up your own teams on the Teams tab of the "
                + "setup screen first. The host's Map and Rules tabs decide the game."));
        start.addView(ui.heading("Your name"));
        nameField = ui.field(name, "Player name");
        start.addView(nameField);
        start.addView(ui.heading("Host"));
        start.addView(ui.button(connecting ? "Starting..." : "Host network game", true, v -> host()), Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(52)));
        // The list of hosts is a card of its own: beside the other two cards when the screen is wide, so Join by address stays in view.
        hostsBox = ui.card();
        renderHosts();
        LinearLayout join = ui.card();
        join.addView(ui.heading("Join by address"));
        addressField = ui.field(address, "Host address, for example 192.168.1.20:46633");
        addressField.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        join.addView(addressField);
        join.addView(ui.hint("Play joins with your teams. Watch joins without any: you see the game on this phone, with no controls, "
                + "and you do not count as a player."));
        LinearLayout jb = ui.row();
        jb.addView(ui.button("Join to play", true, v -> join(false)), ui.weight(1f));
        jb.addView(ui.button("Join to watch", true, v -> join(true)), ui.margins(Ui.lp(0, ui.dp(52), 1f), 8, 0, 0, 0));
        join.addView(jb, ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 8, 0, 0));
        if (wide) {
            LinearLayout cols = new LinearLayout(this);
            cols.setBaselineAligned(false);
            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            left.addView(start, full());
            left.addView(join, ui.margins(full(), 0, 8, 0, 0));
            cols.addView(left, ui.weight(1f));
            LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.addView(hostsBox, full());
            right.addView(before, ui.margins(full(), 0, 8, 0, 0));
            cols.addView(right, ui.margins(Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f), 8, 0, 0, 0));
            content.addView(cols, ui.margins(full(), 0, 6, 0, 0));
        } else {
            content.addView(before, ui.margins(full(), 0, 6, 0, 0));
            content.addView(start, ui.margins(full(), 0, 8, 0, 0));
            content.addView(hostsBox, ui.margins(full(), 0, 8, 0, 0));
            content.addView(join, ui.margins(full(), 0, 8, 0, 0));
        }
    }

    private static LinearLayout.LayoutParams full() {
        return Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // Finding hosts ------------------------------------------------------------------------------

    /** (Re)starts the search for hosts: asks for the local network permission first where this Android has one. */
    private void startSearch(boolean again) {
        if (searching && !again) return;
        if (!LocalNetwork.granted(this)) {
            if (!permissionAsked) {
                permissionAsked = true;
                if (LocalNetwork.request(this)) return;
            }
            permissionDenied = true;
            searchDone = true;
            searchStatus = "Finding hosts needs the \"nearby devices\" (local network) permission, which was not given. "
                    + "Type the host's address below instead; it works the same way.";
            renderHosts();
            return;
        }
        permissionDenied = false;
        searching = true;
        searchDone = false;
        searchStatus = "Searching for hosts on this Wi-Fi...";
        hosts.clear();
        renderHosts();
        final android.content.Context app = getApplicationContext();
        // The search starts at once; the fingerprint of this phone's data (which can take a while the first time) is read
        // off the UI thread meanwhile, and the list is drawn again when it is known. Waiting for it here used to delay the whole search.
        if (myDigest == null && !digestRunning) {
            digestRunning = true;
            new Thread(() -> {
                String d = null;
                try {
                    d = NetSession.dataDigest(app);
                } catch (RuntimeException e) {
                    d = null;                   // left unknown: the host checks the data again at the join
                } finally {
                    final String got = d;
                    runOnUiThread(() -> {
                        if (got != null) myDigest = got;
                        digestRunning = false;  // "Search again" can try once more
                        if (!destroyed) renderHosts();
                    });
                }
            }, "host-digest").start();
        }
        stopDiscovery();
        searchHandler.removeCallbacks(searchBackstop);
        searchHandler.postDelayed(searchBackstop, BACKSTOP_MS);
        final HostDiscovery mine = NetSession.discoveryFactory.apply(app);
        discovery = mine;
        mine.start(new HostDiscovery.Listener() {
            @Override public void hosts(List<HostAdvert.Found> all) {
                runOnUiThread(() -> {
                    if (destroyed || discovery != mine) return;      // a late call from an earlier search
                    hosts.clear();
                    hosts.addAll(all);
                    renderHosts();
                });
            }

            @Override public void status(String text, boolean finished) {
                runOnUiThread(() -> {
                    if (destroyed || discovery != mine) return;
                    searchStatus = text;
                    searchDone = finished;
                    if (finished) {
                        searching = false;
                        searchHandler.removeCallbacks(searchBackstop);
                    }
                    renderHosts();
                });
            }
        });
    }

    private static final long BACKSTOP_MS = 50000;
    private final android.os.Handler searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Ends a search that the finder did not end itself (its own timeout is 45 s), so the screen never says "Searching" for ever. */
    private final Runnable searchBackstop = () -> {
        if (destroyed || !searching) return;
        stopDiscovery();
        searching = false;
        searchDone = true;
        searchStatus = hosts.isEmpty() ? "No host found on this Wi-Fi." : "Search finished.";
        renderHosts();
    };

    private void stopDiscovery() {
        HostDiscovery d = discovery;
        discovery = null;
        if (d != null) d.stop();
    }

    private void stopSearch() {
        searching = false;
        searchHandler.removeCallbacks(searchBackstop);
        stopDiscovery();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == LocalNetwork.REQUEST && hostWaitsForLocalNetwork) {
            hostWaitsForLocalNetwork = false;
            reallyHost();                    // granted or not: hosting goes on, NsdHost says what is limited
            return;
        }
        if (code == LocalNetwork.REQUEST) {
            if (LocalNetwork.granted(this) || (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)) {
                LocalNetwork.forceGranted = null;
                startSearch(false);
            } else {
                permissionDenied = true;
                searchDone = true;
                searchStatus = "Finding hosts needs the \"nearby devices\" (local network) permission, which was not given. "
                        + "Type the host's address below instead; it works the same way.";
                renderHosts();
            }
            return;
        }
        // Hosting works without the notification permission (the notification is just not shown); go on either way.
        if (code == 7) hostAfterPermissions();
    }

    /** Hosts that cannot be joined come last; the one picked last time comes first. */
    private List<HostAdvert.Found> sortedHosts() {
        List<HostAdvert.Found> l = new java.util.ArrayList<>(hosts);
        final String d = myDigest;
        java.util.Collections.sort(l, (a, b) -> {
            boolean ba = a.incompatibility(org.hedgewars.android.net.Compat.VALUE, Diag.versionName(this), d != null ? d : a.data) == null;
            boolean bb = b.incompatibility(org.hedgewars.android.net.Compat.VALUE, Diag.versionName(this), d != null ? d : b.data) == null;
            if (ba != bb) return ba ? -1 : 1;
            boolean la = a.serviceName.equals(lastHost), lb = b.serviceName.equals(lastHost);
            if (la != lb) return la ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return l;
    }

    private void renderHosts() {
        LinearLayout box = hostsBox;
        if (box == null) return;
        box.removeAllViews();
        box.addView(ui.heading("Hosts on this Wi-Fi"));
        if (!searchStatus.isEmpty()) box.addView(ui.hint(searchStatus));
        final String digest = myDigest;
        // Until this phone's own data fingerprint is known, the data check is left out (the host checks it again at the join).
        if (digest == null && !hosts.isEmpty()) box.addView(ui.hint("Checking this phone's game data..."));
        for (final HostAdvert.Found h : sortedHosts()) {
            String why = h.incompatibility(org.hedgewars.android.net.Compat.VALUE, Diag.versionName(this), digest != null ? digest : h.data);
            LinearLayout row = ui.card();
            row.setBackground(ui.round(Ui.PANEL_HI, 10));
            row.addView(ui.text(h.name + (h.serviceName.equals(lastHost) ? "   (last used)" : ""), 16, why == null ? Color.WHITE : Ui.DANGER));
            row.addView(ui.hint(h.address() + (h.version.isEmpty() ? "" : "   version " + h.version)));
            if (why != null) {
                row.addView(coloured("Cannot join: " + why, Ui.DANGER));
            }
            LinearLayout bt = ui.row();
            Button play = ui.button("Join to play", true, v -> pickHost(h, false));
            play.setEnabled(why == null);
            Button watch = ui.button("Join to watch", false, v -> pickHost(h, true));
            watch.setBackground(ui.inset(ui.pressable(Ui.FIELD, Color.rgb(40, 60, 46), 9), 0, (Ui.TOUCH_DP - Ui.LOOK_DP) / 2));   // the row is as light as a plain button
            watch.setEnabled(why == null);
            bt.addView(play, ui.weight(1f));
            bt.addView(watch, ui.margins(Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f), 8, 0, 0, 0));
            row.addView(bt);
            box.addView(row, ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 4, 0, 0));
        }
        if (searchDone) {
            box.addView(ui.button("Search again", false, v -> startSearch(true)), ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 6, 0, 0));
        }
    }

    private void pickHost(HostAdvert.Found h, boolean watch) {
        lastHost = h.serviceName;
        getSharedPreferences(Store.PREFS_NAME, MODE_PRIVATE).edit().putString("lasthost", lastHost).apply();
        saveFields();
        problem = "";
        connectTo(h.host, h.port, watch);
    }

    private static final String NO_WIFI = "No Wi-Fi: connect the phone to the same Wi-Fi as the TV";

    @SuppressWarnings("unchecked")
    private void lobby() {
        Map<String, Object> st = NetSession.state;
        LinearLayout card = ui.card();
        boolean host = NetSession.hosting;
        // The long explanations are collected and shown at the end of the card, so the addresses, the TVs and the phones come first.
        List<String> notes = new java.util.ArrayList<>();
        if (host) {
            // All of this comes from a snapshot that a background ticker makes: listing the interfaces and reading the
            // relay take locks and must not run on the UI thread.
            NetSession.HostInfo hi = NetSession.hostInfo;
            List<String> addr = hi == null ? new java.util.ArrayList<String>() : hi.addresses;
            StringBuilder a = new StringBuilder();
            for (String s : addr) a.append(a.length() == 0 ? "" : "   or   ").append(s).append(':').append(Relay.PHONE_PORT);
            // The TV address first: a one-phone host with the TV watching is the main use.
            card.addView(ui.heading("TV viewer address"));
            if (hi == null) {
                card.addView(ui.hint("Looking for the Wi-Fi address..."));
            } else if (hi.viewerPort > 0) {
                StringBuilder w = new StringBuilder();
                for (String s : addr) w.append(w.length() == 0 ? "" : "\n").append("ws://").append(s).append(':').append(hi.viewerPort);
                TextView tv = ui.text(addr.isEmpty() ? NO_WIFI : w.toString(), 20, Color.WHITE);
                tv.setTypeface(Typeface.DEFAULT_BOLD);
                tv.setTextIsSelectable(true);
                card.addView(tv, ui.margins(full(), 2, 0, 0, 2));
                // Watching and waiting TVs look different: green with a count for those on the game, amber for those still waiting.
                LinearLayout chips = ui.row();
                chips.addView(ui.chip(hi.viewers + (hi.viewers == 1 ? " TV watching" : " TVs watching"),
                        hi.viewers > 0 ? Color.rgb(46, 125, 80) : Ui.PANEL_HI, Color.WHITE));
                chips.addView(ui.chip(hi.waiting + " waiting for a game", hi.waiting > 0 ? Color.rgb(150, 100, 20) : Ui.PANEL_HI, Color.WHITE),
                        ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 8, 0, 0, 0));
                card.addView(chips, ui.margins(full(), 0, 4, 0, 2));
                for (String vl : hi.viewerLines) {
                    TextView t = ui.text("Watching: TV " + vl, 12, Color.rgb(160, 225, 180));
                    card.addView(t, ui.margins(full(), 2, 2, 0, 0));
                }
                notes.add("The TV finds this phone by itself on the same Wi-Fi. The ws:// address is the fallback: open the TV page with ?ws="
                        + (addr.isEmpty() ? "ws://IP:" + hi.viewerPort : "ws://" + addr.get(0) + ":" + hi.viewerPort)
                        + " (or set HW_WS_URL in the page) if it does not. The TV keeps trying until a game is started.");
                if (!hi.viewerLines.isEmpty()) {
                    notes.add("Round trip is relay to TV and back (a WebSocket ping every 2 s). Frames behind is the TV's queue at the relay: "
                            + "it grows when the TV cannot keep up. It does not include the time the TV needs to draw.");
                }
            } else {
                card.addView(coloured(hi.viewerError.isEmpty() ? "The TV viewer endpoint is not running." : hi.viewerError, Ui.DANGER));
            }
            card.addView(ui.heading("Hosting: address for other phones"));
            TextView big = ui.text(hi == null ? "..." : addr.isEmpty() ? NO_WIFI : a.toString(), 16, Color.WHITE);
            big.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(big, ui.margins(full(), 2, 0, 0, 0));
            if (!NetSession.nsdStatus.isEmpty()) card.addView(ui.hint(NetSession.nsdStatus));
            notes.add("Other phones normally find this phone by themselves on the same Wi-Fi (Join list). If they do not, they type this address (Join to play or Join to watch). Not needed for a one-phone game with the TV.");
        } else {
            card.addView(ui.heading(NetSession.watching ? "Joined as a watcher" : "Joined"));
            card.addView(ui.text("Host " + NetSession.target + (st == null ? "" : " (" + Json.str(st, "host", "") + ")"), 18, Color.WHITE));
        }
        String phase = st == null ? "connecting..." : Json.str(st, "state", "");
        boolean playing = "playing".equals(phase) || NetSession.inGame;
        card.addView(ui.hint("State: " + (playing ? "game running" : "lobby (setting up)")));
        NetSession.HostInfo live = NetSession.hostInfo;
        if (host && live != null && playing && !live.live.isEmpty()) card.addView(ui.hint(live.live));
        long watchingN = st == null ? 0 : Json.num(st, "watching", 0);
        if (host) card.addView(ui.hint("Phones watching: " + watchingN));
        card.addView(ui.heading("Phones and teams"));
        if (st != null) {
            long you = Json.num(st, "you", -1);
            for (Object o : Json.list(st, "players")) {
                Map<String, Object> p = (Map<String, Object>) o;
                StringBuilder teams = new StringBuilder();
                for (Object to : Json.list(p, "teams")) {
                    Map<String, Object> t = (Map<String, Object>) to;
                    int lvl = (int) Json.num(t, "level", 0);
                    teams.append(teams.length() == 0 ? "" : ", ").append(Json.str(t, "name", "?"))
                            .append(lvl > 0 ? " (computer " + lvl + ")" : "");
                }
                boolean me = Json.num(p, "id", 0) == you;
                boolean watcher = Json.bool(p, "watching", false);
                String line = Json.str(p, "name", "?") + (me ? " (you)" : "") + (Json.bool(p, "host", false) ? " [host]" : "")
                        + (watcher ? "   Watching" : Json.bool(p, "ready", false) ? "   ready" : "   not ready");
                LinearLayout row = ui.card();
                row.setBackground(ui.round(Ui.PANEL_HI, 10));
                row.addView(ui.text(line, 16, Color.WHITE));
                row.addView(ui.hint(watcher ? "watching only, no teams" : teams.length() == 0 ? "no teams" : teams.toString()));
                card.addView(row, ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 4, 0, 0));
            }
        }
        if (!NetSession.notice.isEmpty()) card.addView(coloured(NetSession.notice, Ui.ACCENT));
        LinearLayout buttons = ui.row();
        if (host) {
            boolean can = st != null && Json.bool(st, "canStart", false) && !playing;
            Button start = ui.button("Start game", true, v -> {
                String away = phonesAway(NetSession.state);
                if (away.isEmpty()) {
                    startNow();
                } else {
                    Ui.dialog(this)
                            .setTitle("Not everyone is waiting")
                            .setMessage(away + " not on the Network screen. A phone that is somewhere else gets a notification to tap, "
                                    + "but may be late. Start anyway?")
                            .setPositiveButton("Start anyway", (d, w) -> startNow())
                            .setNegativeButton("Wait", null)
                            .show();
                }
            });
            start.setEnabled(can);
            buttons.addView(start, ui.weight(1f));
        } else if (NetSession.watching) {
            // A watching phone has no teams to ready. Between games it simply waits for the host's Start; if a game runs and this phone
            // is not in it (it stopped watching, or joined while the host's game was starting), it can watch the running game.
            Button wb = ui.button(playing && !NetSession.inGame ? "Watch the game" : "Watching", true, v -> {
                RelayClient c = NetSession.client;
                if (c != null) c.watchAgain();
            });
            wb.setEnabled(playing && !NetSession.inGame);
            buttons.addView(wb, ui.weight(1f));
        } else {
            final boolean[] ready = { st != null && meReady(st) };
            Button rb = ui.button(ready[0] ? "Not ready" : "Ready", true, v -> {
                RelayClient c = NetSession.client;
                if (c != null) c.setReady(!meReady(NetSession.state));
            });
            rb.setEnabled(!playing);
            buttons.addView(rb, ui.weight(1f));
        }
        if (!NetSession.watching) buttons.addView(ui.button("My teams again", false, v -> {
            RelayClient c = NetSession.client;
            if (c != null) c.setTeams(Store.loadSetup(this, Store.data(this)).teams);
        }), ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 8, 0, 0, 0));
        buttons.addView(ui.button(host ? "Stop hosting" : NetSession.watching ? "Stop watching" : "Leave", false, v -> leaveOrStop()),
                ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 8, 0, 0, 0));
        card.addView(buttons, ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 10, 0, 0));
        card.addView(ui.hint(host ? "Your teams and the map and rules come from the setup screen. Leaving the game screen does not stop hosting."
                : NetSession.watching ? "You are watching: no teams, no controls. The game starts on this phone when the host presses Start, or at once if one is running."
                : "Your teams come from the Teams tab. Wait for the host to press Start."));
        for (String n : notes) card.addView(ui.hint(n), ui.margins(full(), 0, 4, 0, 0));
        content.addView(card, ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 6, 0, 0));
    }

    private static void startNow() {
        RelayClient c = NetSession.client;
        if (c != null) c.start();
    }

    /** Names of the joining phones that are not showing their Network screen, as a phrase ("B is" / "B and C are"), or "". */
    @SuppressWarnings("unchecked")
    private static String phonesAway(Map<String, Object> st) {
        if (st == null) return "";
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Object o : Json.list(st, "players")) {
            Map<String, Object> p = (Map<String, Object>) o;
            if (Json.bool(p, "host", false) || Json.bool(p, "fg", true)) continue;
            b.append(n++ == 0 ? "" : ", ").append(Json.str(p, "name", "?"));
        }
        return n == 0 ? "" : b + (n == 1 ? " is" : " are");
    }

    @SuppressWarnings("unchecked")
    private static boolean meReady(Map<String, Object> st) {
        if (st == null) return false;
        long you = Json.num(st, "you", -1);
        for (Object o : Json.list(st, "players")) {
            Map<String, Object> p = (Map<String, Object>) o;
            if (Json.num(p, "id", 0) == you) return Json.bool(p, "ready", false);
        }
        return false;
    }

    private void report() {
        LinearLayout card = ui.card();
        card.addView(ui.heading("Last game: compare with the other phone"));
        TextView mono = ui.text(NetDiag.summary(this), 12, Color.WHITE);
        mono.setTypeface(Typeface.MONOSPACE);
        mono.setTextIsSelectable(true);
        card.addView(mono);
        card.addView(ui.heading("What the relay saw"));
        TextView rel = ui.text(NetSession.relaySummary + "\nStream sent to this phone: " + NetSession.toYouMsgs
                + " messages, hash " + NetSession.toYouHash, 12, Color.WHITE);
        rel.setTypeface(Typeface.MONOSPACE);
        rel.setTextIsSelectable(true);
        card.addView(rel);
        card.addView(ui.button("Share log", false, v -> LogShare.share(this, msg -> problem = msg)));
        content.addView(card, ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 8, 0, 0));
    }

    // Actions -----------------------------------------------------------------------------------

    /** True while hosting waits for the answer to the local network permission prompt. */
    private boolean hostWaitsForLocalNetwork;

    private void host() {
        saveFields();
        problem = "";
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 7);
            return;
        }
        hostAfterPermissions();
    }

    /**
     * Android 16 and later: asks for the local network permission before the phone announces itself, so other phones can find it.
     * A refusal does not stop hosting (the typed address still works); NsdHost says that discovery may be limited.
     */
    private void hostAfterPermissions() {
        if (!LocalNetwork.granted(this) && LocalNetwork.request(this)) {
            hostWaitsForLocalNetwork = true;
            return;
        }
        reallyHost();
    }

    private void reallyHost() {
        connecting = true;
        render();
        HostService.begin();                 // clears any earlier failure now, before the service can race with the wait below
        Intent svc = new Intent(this, HostService.class).setAction(HostService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
        } catch (RuntimeException e) {
            connecting = false;
            problem = "Could not start the hosting service: " + e.getMessage();
            render();
            return;
        }
        final String nick = name.isEmpty() ? Build.MODEL : name;
        new Thread(() -> {
            long end = System.currentTimeMillis() + 8000;
            while (HostService.relay == null && HostService.error.isEmpty() && System.currentTimeMillis() < end) {
                try { Thread.sleep(100); } catch (InterruptedException e) { return; }
            }
            Relay r = HostService.relay;
            try {
                if (r == null) throw new java.io.IOException(HostService.error.isEmpty() ? "The relay did not start." : HostService.error);
                NetSession.connect(getApplicationContext(), "127.0.0.1", r.port(), nick, true);
            } catch (NetSession.Cancelled e) {
                // hosting was stopped while connecting: the service is already stopping
            } catch (Exception e) {
                problem = "Hosting failed: " + e.getMessage();
                stopService(new Intent(this, HostService.class));
            }
            connecting = false;
            changed();
        }, "host-connect").start();
    }

    private void join(final boolean watch) {
        saveFields();
        problem = "";
        String a = address.trim();
        if (a.isEmpty()) {
            problem = "Type the host's address first, for example 192.168.1.20:" + Relay.PHONE_PORT + ".";
            render();
            return;
        }
        final String host;
        final int port;
        int colon = a.lastIndexOf(':');
        try {
            host = colon > 0 ? a.substring(0, colon) : a;
            port = colon > 0 ? Integer.parseInt(a.substring(colon + 1).trim()) : Relay.PHONE_PORT;
        } catch (NumberFormatException e) {
            problem = "The port after the colon is not a number.";
            render();
            return;
        }
        connectTo(host, port, watch);
    }

    private void connectTo(final String host, final int port, final boolean watch) {
        final String nick = name.isEmpty() ? Build.MODEL : name;
        connecting = true;
        problem = "";
        stopSearch();
        final int seq = ++joinSeq;
        new Thread(() -> {
            try {
                NetSession.connect(getApplicationContext(), host, port, nick, false, watch);
            } catch (NetSession.Cancelled e) {
                // given up on purpose (Back, or a newer join): nothing to report
            } catch (Exception e) {
                if (seq == joinSeq) problem = "Could not reach " + host + ":" + port + " (" + e.getMessage() + "). Same Wi-Fi? Is the host hosting?";
            }
            if (seq == joinSeq) connecting = false;
            changed();
        }, "join").start();
    }

    private void leaveOrStop() {
        if (NetSession.hosting) {
            startService(new Intent(this, HostService.class).setAction(HostService.ACTION_STOP));
        }
        NetSession.leave();
        NetSession.hosting = false;
        NetSession.closedReason = "";
        render();
    }
}
