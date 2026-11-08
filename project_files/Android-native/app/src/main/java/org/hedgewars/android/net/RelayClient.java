package org.hedgewars.android.net;

import org.hedgewars.android.SetupModel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A phone's lobby connection to the relay (the host's own phone uses it too, over loopback). It speaks the
 * JSON line protocol and reports what the relay says to a {@link Handler}. Plain Java, used by the app and by
 * the host tests.
 */
public final class RelayClient {
    /** What the relay sends a phone when the game starts. */
    public static final class StartInfo {
        public int you, game;
        public String token = "", nick = "";
        public List<String> lines = new ArrayList<>();
        public String host = "";
        public int port;
    }

    public interface Handler {
        void onState(Map<String, Object> state);

        void onStart(StartInfo start);

        /** The relay's report on a finished game. */
        void onSummary(Map<String, Object> summary);

        void onNotice(String text);

        /** The connection ended: refused, closed by the host, or lost. */
        void onClosed(String reason);
    }

    private final String host;
    private final int port;
    private final String name, build;
    private final boolean isHost;
    private final Handler handler;
    private Socket socket;
    private OutputStream out;
    private volatile boolean closed;
    private List<SetupModel.Team> teams;

    public RelayClient(String host, int port, String name, String build, boolean isHost, List<SetupModel.Team> teams, Handler h) {
        this.host = host;
        this.port = port;
        this.name = name;
        this.build = build;
        this.isHost = isHost;
        this.teams = teams;
        this.handler = h;
    }

    /** Connects (blocking, 5 s limit) and starts the reader thread. */
    public void connect() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        out = socket.getOutputStream();
        send(Json.obj("t", "hello", "role", "lobby", "proto", (long) Relay.PROTO, "name", name, "build", build,
                "host", isHost, "teams", NetTeams.listToJson(teams)));
        Thread t = new Thread(this::readLoop, "relay-client");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void send(Map<String, Object> m) throws IOException {
        out.write((Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void trySend(Map<String, Object> m) {
        try {
            send(m);
        } catch (IOException e) {
            close("The connection to the host was lost.");
        }
    }

    public void setTeams(List<SetupModel.Team> t) {
        teams = t;
        trySend(Json.obj("t", "teams", "teams", NetTeams.listToJson(t)));
    }

    public void setReady(boolean ready) {
        trySend(Json.obj("t", "ready", "ready", ready));
    }

    public void start() {
        trySend(Json.obj("t", "start"));
    }

    public void close() {
        close(null);
    }

    private void close(String reason) {
        if (closed) return;
        closed = true;
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        if (reason != null) handler.onClosed(reason);
    }

    private void readLoop() {
        String why = "The connection to the host was lost.";
        try {
            InputStream in = new java.io.BufferedInputStream(socket.getInputStream(), 8192);
            for (;;) {
                String line = Frames.readLine(in, 4 << 20);
                if (line == null) break;
                Map<String, Object> m = Json.parseObject(line);
                String t = Json.str(m, "t", "");
                switch (t) {
                    case "state": handler.onState(m); break;
                    case "start": {
                        StartInfo s = new StartInfo();
                        s.you = (int) Json.num(m, "you", 0);
                        s.game = (int) Json.num(m, "game", 0);
                        s.token = Json.str(m, "token", "");
                        s.nick = Json.str(m, "nick", "Player");
                        for (Object o : Json.list(m, "lines")) s.lines.add(String.valueOf(o));
                        s.host = host;
                        s.port = port;
                        handler.onStart(s);
                        break;
                    }
                    case "summary": handler.onSummary(m); break;
                    case "notice": handler.onNotice(Json.str(m, "text", "")); break;
                    case "ping": trySend(Json.obj("t", "pong")); break;
                    case "refuse": why = Json.str(m, "reason", "Refused by the host."); closed = true; handler.onClosed(why); return;
                    case "closed": why = Json.str(m, "reason", "The host left."); closed = true; handler.onClosed(why); return;
                    case "dropped": handler.onNotice(Json.str(m, "reason", "You were dropped.")); break;
                    default: break;
                }
            }
        } catch (Exception e) {
            if (closed) return;
            why = "The connection to the host was lost (" + e.getMessage() + ").";
        }
        if (!closed) {
            closed = true;
            handler.onClosed(why);
        }
    }

    // Starting the engine ---------------------------------------------------------------------

    /**
     * Writes what the game process reads: the setup lines (game.ipc, as for a local game) and a small file
     * with the relay link (host, port, token, nick and where the launcher writes its diagnostics).
     */
    public static void writeGameFiles(File ipc, File netCfg, StartInfo s, String diagPath) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(ipc), StandardCharsets.UTF_8)) {
            for (String l : s.lines) w.write(l + "\n");
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(netCfg), StandardCharsets.UTF_8)) {
            w.write("host=" + s.host + "\n");
            w.write("port=" + s.port + "\n");
            w.write("token=" + s.token + "\n");
            w.write("nick=" + s.nick.replace('\n', ' ') + "\n");
            w.write("diag=" + diagPath + "\n");
        }
    }
}
