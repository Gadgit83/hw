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
        /** This phone is the one that hosts (its Quit ends the game for everyone). */
        public boolean asHost;
        /** This phone only watches (it owns no team): its launcher sends the relay nothing and its screen has no playing controls. */
        public boolean asWatcher;
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
    private String hostSecret = "";
    private boolean watch;
    private String compat = Compat.VALUE, digest = "";
    private final Handler handler;
    private Socket socket;
    private OutputStream out;
    private volatile boolean closed;
    /** Taken only to decide who closes; never while writing, so close() never waits for a stalled write. */
    private final Object closeLock = new Object();
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

    /** The host's own phone presents the secret that only the app knows; without it the relay refuses the host role. */
    public RelayClient hostSecret(String secret) {
        hostSecret = secret == null ? "" : secret;
        return this;
    }

    /** What the relay checks at join: the compat string (default {@link Compat#VALUE}) and the game data fingerprint ({@link DataDigest}). */
    public RelayClient identity(String compat, String digest) {
        this.compat = compat == null ? "" : compat;
        this.digest = digest == null ? "" : digest;
        return this;
    }

    /** A phone that only watches: no teams, a spectator at the relay. Call before connect(). */
    public RelayClient watch(boolean on) {
        watch = on;
        return this;
    }

    /** Connects (blocking, 5 s limit) and starts the reader thread. */
    public void connect() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        out = socket.getOutputStream();
        send(Json.obj("t", "hello", "role", "lobby", "proto", (long) Relay.PROTO, "name", name, "build", build,
                "compat", compat, "digest", digest, "host", isHost, "secret", hostSecret, "watch", watch, "teams", NetTeams.listToJson(watch ? new ArrayList<SetupModel.Team>() : teams)));
        Thread t = new Thread(this::readLoop, "relay-client");
        t.setDaemon(true);
        t.start();
    }

    /** Every write after connect() runs on this one thread, so callers (the UI thread too) never touch the socket and the order is kept. */
    private final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "relay-writer");
        t.setDaemon(true);
        return t;
    });
    /** Test hook: the thread that last wrote to the socket. */
    private volatile Thread lastWriteThread;

    public Thread lastWriteThread() {
        return lastWriteThread;
    }

    /** Test hook: blocks until every write queued so far has been done. */
    public void flushWrites() throws InterruptedException {
        try {
            writer.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private void send(Map<String, Object> m) throws IOException {
        byte[] b = (Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (this) {
            lastWriteThread = Thread.currentThread();
            out.write(b);
            out.flush();
        }
    }

    /** Queues the message for the writer thread; never blocks, never does I/O on the caller. */
    private void trySend(Map<String, Object> m) {
        if (closed) return;
        try {
            writer.execute(() -> {
                if (closed) return;
                try {
                    send(m);
                } catch (Throwable e) {
                    // Any failure (an IOException, a null stream after a failed connect, an Error) ends the client: a writer that
                    // died silently would leave the screen believing it is still connected.
                    close("The connection to the host was lost.");
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    public void setTeams(List<SetupModel.Team> t) {
        teams = t;
        trySend(Json.obj("t", "teams", "teams", NetTeams.listToJson(t)));
    }

    public void setReady(boolean ready) {
        trySend(Json.obj("t", "ready", "ready", ready));
    }

    /** Tells the relay whether this phone is showing the Network screen (so the host can be warned before it starts). */
    public void setForeground(boolean on) {
        trySend(Json.obj("t", "fg", "on", on));
    }

    public void start() {
        trySend(Json.obj("t", "start"));
    }

    /** A watching phone asks for the running game again (after it stopped watching): the relay answers with a new start. */
    public void watchAgain() {
        trySend(Json.obj("t", "watch"));
    }

    public void close() {
        close(null);
    }

    /** Idempotent: the first call closes and (when {@code reason} is not null) tells the handler; later calls do nothing. */
    private void close(String reason) {
        synchronized (closeLock) {
            if (closed) return;
            closed = true;
        }
        writer.shutdown();
        final Socket sock = socket;
        // Closing a socket can block on some platforms, and this may be called from the UI thread: close it on its own thread.
        if (sock != null) {
            Thread t = new Thread(() -> { try { sock.close(); } catch (IOException ignored) { } }, "relay-close");
            t.setDaemon(true);
            t.start();
        }
        if (reason != null) handler.onClosed(reason);
    }

    private void readLoop() {
        String why = "The connection to the host was lost.";
        try {
            InputStream in = new java.io.BufferedInputStream(socket.getInputStream(), 8192);
            for (;;) {
                String line = Frames.readLine(in, 1 << 20);
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
                        s.asHost = isHost;
                        s.asWatcher = watch;
                        handler.onStart(s);
                        break;
                    }
                    case "summary": handler.onSummary(m); break;
                    case "notice": handler.onNotice(Json.str(m, "text", "")); break;
                    case "ping": trySend(Json.obj("t", "pong")); break;
                    case "refuse": close(Json.str(m, "reason", "Refused by the host.")); return;
                    case "closed": close(Json.str(m, "reason", "The host left.")); return;
                    case "dropped": handler.onNotice(Json.str(m, "reason", "You were dropped.")); break;
                    default: break;
                }
            }
        } catch (Throwable e) {
            if (closed) return;
            why = "The connection to the host was lost (" + e + ").";
        }
        close(why);
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
            // read by GameActivity (Back, the controls) and by the launcher (the host alone gets the pause button, a watcher sends nothing, see hp_ipc.c)
            w.write("role=" + (s.asWatcher ? "watch" : s.asHost ? "host" : "player") + "\n");
        }
    }
}
