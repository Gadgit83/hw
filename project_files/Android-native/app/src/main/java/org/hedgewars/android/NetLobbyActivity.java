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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Ui(this);
        Ui.edgeToEdge(this);
        android.content.SharedPreferences p = getSharedPreferences(Store.PREFS_NAME, MODE_PRIVATE);
        name = p.getString("netname", Build.MODEL);
        address = p.getString("netaddr", "");
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(16));
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
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
        sendForeground(true);
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
        sendForeground(false);
        saveFields();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
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
        new android.app.AlertDialog.Builder(this)
                .setTitle(host ? "Stop hosting?" : "Leave the network game?")
                .setMessage(host ? "Every phone that has joined is disconnected." : "You are removed from the lobby.")
                .setPositiveButton(host ? "Stop hosting" : "Leave", (d, w) -> {
                    leaveOrStop();
                    finish();
                })
                .setNegativeButton("Stay", null)
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
        content.removeAllViews();
        nameField = null;
        addressField = null;
        TextView title = ui.text(BuildConfig.APP_NAME + " network game", 22, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout top = ui.row();
        top.addView(title, ui.weight(1f));
        top.addView(ui.button("Back", false, v -> onBackPressed()));
        content.addView(top);
        if (!problem.isEmpty()) content.addView(coloured(problem, Ui.DANGER));
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
        LinearLayout card = ui.card();
        card.addView(ui.heading("Before you start"));
        card.addView(ui.hint("All phones on the same Wi-Fi, same app version. Set up your own teams on the Teams tab of the "
                + "setup screen first. The host's Map and Rules tabs decide the game."));
        card.addView(ui.heading("Your name"));
        nameField = ui.field(name, "Player name");
        card.addView(nameField);
        card.addView(ui.heading("Host"));
        card.addView(ui.button(connecting ? "Starting..." : "Host network game", true, v -> host()), Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(52)));
        card.addView(ui.heading("Join"));
        addressField = ui.field(address, "Host address, for example 192.168.1.20:46633");
        addressField.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        card.addView(addressField);
        card.addView(ui.button("Join by address", true, v -> join()), ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(52)), 0, 8, 0, 0));
        content.addView(card, ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 8, 0, 0));
    }

    @SuppressWarnings("unchecked")
    private void lobby() {
        Map<String, Object> st = NetSession.state;
        LinearLayout card = ui.card();
        boolean host = NetSession.hosting;
        if (host) {
            List<String> addr = NetSession.localAddresses();
            StringBuilder a = new StringBuilder();
            for (String s : addr) a.append(a.length() == 0 ? "" : "   or   ").append(s).append(':').append(Relay.PHONE_PORT);
            Relay rl = HostService.relay;
            // The TV address first and biggest: a one-phone host with the TV watching is the main use.
            card.addView(ui.heading("TV viewer address"));
            if (rl != null && rl.viewerPort() > 0) {
                StringBuilder w = new StringBuilder();
                for (String s : addr) w.append(w.length() == 0 ? "" : "\n").append("ws://").append(s).append(':').append(rl.viewerPort());
                TextView tv = ui.text(addr.isEmpty() ? "No Wi-Fi address found. Connect to Wi-Fi." : w.toString(), 28, Color.WHITE);
                tv.setTypeface(Typeface.DEFAULT_BOLD);
                tv.setTextIsSelectable(true);
                card.addView(tv);
                card.addView(ui.hint("TVs watching now: " + rl.viewerCount() + ". The TV viewer connects here (open its page with ?ws="
                        + (addr.isEmpty() ? "ws://IP:" + rl.viewerPort() : "ws://" + addr.get(0) + ":" + rl.viewerPort())
                        + ", or set HW_WS_URL in the page). It retries by itself until a game is started. There is no automatic discovery yet."));
            } else {
                card.addView(coloured(rl == null ? "The TV viewer endpoint is not running." : rl.viewerError(), Ui.DANGER));
            }
            card.addView(ui.heading("Hosting: address for other phones"));
            TextView big = ui.text(addr.isEmpty() ? "No Wi-Fi address found. Connect to Wi-Fi." : a.toString(), 18, Color.WHITE);
            big.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(big);
            card.addView(ui.hint("Other phones type this address (Join by address). Not needed for a one-phone game with the TV."));
        } else {
            card.addView(ui.heading("Joined"));
            card.addView(ui.text("Host " + NetSession.target + (st == null ? "" : " (" + Json.str(st, "host", "") + ")"), 18, Color.WHITE));
        }
        String phase = st == null ? "connecting..." : Json.str(st, "state", "");
        boolean playing = "playing".equals(phase) || NetSession.inGame;
        card.addView(ui.hint("State: " + (playing ? "game running" : "lobby (setting up)")));
        Relay r = HostService.relay;
        if (host && r != null && playing) card.addView(ui.hint(r.liveText()));
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
                String line = Json.str(p, "name", "?") + (me ? " (you)" : "") + (Json.bool(p, "host", false) ? " [host]" : "")
                        + (Json.bool(p, "ready", false) ? "   ready" : "   not ready");
                LinearLayout row = ui.card();
                row.setBackground(ui.round(Ui.PANEL_HI, 10));
                row.addView(ui.text(line, 16, Color.WHITE));
                row.addView(ui.hint(teams.length() == 0 ? "no teams" : teams.toString()));
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
                    new android.app.AlertDialog.Builder(this)
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
        } else {
            final boolean[] ready = { st != null && meReady(st) };
            Button rb = ui.button(ready[0] ? "Not ready" : "Ready", true, v -> {
                RelayClient c = NetSession.client;
                if (c != null) c.setReady(!meReady(NetSession.state));
            });
            rb.setEnabled(!playing);
            buttons.addView(rb, ui.weight(1f));
        }
        buttons.addView(ui.button("My teams again", false, v -> {
            RelayClient c = NetSession.client;
            if (c != null) c.setTeams(Store.loadSetup(this, Store.data(this)).teams);
        }), ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 8, 0, 0, 0));
        buttons.addView(ui.button(host ? "Stop hosting" : "Leave", false, v -> leaveOrStop()),
                ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 8, 0, 0, 0));
        card.addView(buttons, ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 10, 0, 0));
        card.addView(ui.hint(host ? "Your teams and the map and rules come from the setup screen. Leaving the game screen does not stop hosting."
                : "Your teams come from the Teams tab. Wait for the host to press Start."));
        content.addView(card, ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 8, 0, 0));
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

    private void host() {
        saveFields();
        problem = "";
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 7);
            return;
        }
        reallyHost();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        // Hosting works without the permission (the notification is just not shown); go on either way.
        if (code == 7) reallyHost();
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
            } catch (Exception e) {
                problem = "Hosting failed: " + e.getMessage();
                stopService(new Intent(this, HostService.class));
            }
            connecting = false;
            changed();
        }, "host-connect").start();
    }

    private void join() {
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
        final String nick = name.isEmpty() ? Build.MODEL : name;
        connecting = true;
        new Thread(() -> {
            try {
                NetSession.connect(getApplicationContext(), host, port, nick, false);
            } catch (Exception e) {
                problem = "Could not reach " + host + ":" + port + " (" + e.getMessage() + "). Same Wi-Fi? Is the host hosting?";
            }
            connecting = false;
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
