package org.hedgewars.android.net;

import org.hedgewars.android.GameConfig;
import org.hedgewars.android.GameData;
import org.hedgewars.android.SetupModel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * The relay: lobby, setup builder, engine-message forwarder and game log of a network game (docs/tv-viewer-plan.md
 * sections 2.6, 2.8 and 4). Plain Java with no Android classes, so the same code runs in the app's foreground
 * service and on a JVM for the host tests.
 *
 * One TCP port serves everything phones do. The first line on a connection is a JSON object:
 * <ul>
 * <li>{@code {"t":"hello","role":"lobby",...}}: the lobby protocol, one JSON object per line each way.</li>
 * <li>{@code {"t":"hello","role":"engine","token":...}}: an engine link. The relay answers {@code {"t":"ok"}}
 *     and from then on both sides speak the engine's IPC framing (one length byte, then that many bytes).
 *     A frame whose first byte is 0 is a control frame ({@code \0E<text>} engine error from the phone,
 *     {@code \0X<text>} the relay ends the game, {@code \0Q} the player confirmed leaving: same as the engine's Q).</li>
 * </ul>
 * Threads: an accept thread; one reader thread per connection; one writer thread per recipient (bounded
 * queue, so a slow recipient never blocks anyone: see {@link Outbox}); a watchdog thread (lobby pings, link
 * wait, silence rule, desync window).
 *
 * Forwarding: every whitelisted engine message (see {@link Frames}) goes, in arrival order and unchanged, to
 * every other engine and is appended to the log; the sender does not get its own messages back. When a phone
 * is lost the relay injects the unsynced team-gone message {@code F<team>} (never {@code f}) for each of its
 * teams to the others and into the log (plan section 2.8).
 */
public final class Relay {
    public static final int PROTO = 1;
    public static final int PHONE_PORT = 46633;
    /** The TV viewer page's port today (tv/viewer/index.html, HW_WS_URL ws://host:8766), as served by the e2e-host bridge. */
    public static final int VIEWER_PORT = 8766;

    private static final int S_SETUP = 0, S_STARTING = 1, S_PLAYING = 2;
    /** Phones in one lobby, connections in their handshake or lobby, and TV viewers, at most. */
    /** The end reason when the host's player leaves the game screen with Quit: the game ends for everyone. */
    /** The most TV viewer report lines and viewer events kept per game; older ones are only counted. */
    private static final int MAX_VIEWER_LINES = 50;
    public static final String HOST_ENDED = "The host ended the game";
    public static final int MAX_PLAYERS = 8, MAX_CONNECTIONS = 32, MAX_VIEWERS = 16;
    /** A line of the setup is one engine message: a length byte, so at most 255 bytes; the viewer's first message counts them in one byte. */
    public static final int MAX_SETUP_LINE = 255, MAX_SETUP_LINES = 255;
    /** Longest lobby line a phone may send, and how long the first line of a connection may take. */
    private static final int MAX_LOBBY_LINE = 1 << 18, MAX_HELLO_LINE = 1 << 15;
    private static final long HELLO_DEADLINE_MS = 15000;

    /** Settings and callbacks. Fields are read when the relay starts or when a game starts. */
    public static final class Config {
        public int port = PHONE_PORT;
        /** TV viewer WebSocket port; 0 picks a free one, -1 turns the viewer endpoint off. */
        public int viewerPort = VIEWER_PORT;
        /** If the viewer port is still in use (a stop that is not finished yet), how many more times to try, and how long between tries. */
        public int viewerBindRetries = 10;
        public long viewerBindRetryMs = 1000;
        /** How often each viewer is sent a WebSocket ping to measure the round trip. */
        public long viewerPingMs = 2000;
        /** Frames that may wait in one viewer's outbound queue before the viewer is dropped as too slow. */
        public int viewerQueueItems = 20000;
        public GameData data;
        public Supplier<SetupModel> hostSetup;
        public String build = "dev";
        public String hostName = "Host";
        /** Silence of the current team's owner before the relay drops it. */
        public long silenceMs = 20000;
        /** How long to wait for every engine link after Start. */
        public long linkWaitMs = 45000;
        public long lobbySilenceMs = 25000;
        public Listener listener;
        /**
         * A secret that only the app itself knows (the hosting service makes one per session and keeps it in memory):
         * the host role is given only to a connection from this device that presents it. Null: any loopback connection.
         */
        public String hostSecret;
        /** Extra time before the first turn ends, on top of the silence limit (the first turn can start late on a slow phone). */
        public long graceMs = 40000;
        /** How long after the start gate a phone may take to send its map digest before it is dropped. */
        public long mapWaitMs = 180000;
        /** Test hook, never set by the app: called for each frame about to go to a phone; returns the frame to send or null to lose it. */
        public BiFunction<Integer, byte[], byte[]> fault;
    }

    public interface Listener {
        void log(String line);

        /** The lobby or game state changed (state JSON-like text for the UI is available from {@link Relay#status}). */
        void changed();
    }

    private final Config cfg;
    private final Object lock = new Object();
    private ServerSocket server;
    private volatile ServerSocket viewerServer;
    private final List<Viewer> viewers = new ArrayList<>();
    private String viewerError = "";
    private int viewersEver;
    private volatile boolean running;
    private Thread acceptThread, watchThread;
    private int state = S_SETUP;
    private final Map<Integer, Player> players = new LinkedHashMap<>();
    private int nextId = 1;
    private Game game;
    private GameSummary lastSummary;
    private int gameNo;
    private final Random rnd = new Random();
    private final SecureRandom secure = new SecureRandom();
    private final AtomicInteger connCount = new AtomicInteger(), viewerConns = new AtomicInteger();

    public Relay(Config cfg) {
        this.cfg = cfg;
    }

    // Lifecycle -------------------------------------------------------------------------------

    public void start() throws IOException {
        server = new ServerSocket();
        try {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(cfg.port));
        } catch (IOException e) {
            try { server.close(); } catch (IOException ignored) { }
            server = null;
            throw e;
        }
        running = true;
        acceptThread = new Thread(this::acceptLoop, "relay-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        watchThread = new Thread(this::watchLoop, "relay-watch");
        watchThread.setDaemon(true);
        watchThread.start();
        log("relay listening on port " + server.getLocalPort());
        if (cfg.viewerPort >= 0 && !bindViewer()) {
            // The port is often still held for a moment by the relay that was just stopped: try again for a while
            // instead of giving up for the whole hosting session.
            Thread rt = new Thread(this::viewerBindRetry, "relay-viewer-bind");
            rt.setDaemon(true);
            rt.start();
        }
    }

    /** Tries to open the TV viewer port once. On failure viewerError says why. */
    private boolean bindViewer() {
        ServerSocket vs = null;
        try {
            vs = new ServerSocket();
            vs.setReuseAddress(true);
            vs.bind(new InetSocketAddress(cfg.viewerPort));
            if (!running) { vs.close(); return true; }
            viewerServer = vs;
            viewerError = "";
            final ServerSocket accepting = vs;
            Thread vt = new Thread(() -> viewerAcceptLoop(accepting), "relay-viewer-accept");
            vt.setDaemon(true);
            vt.start();
            log("TV viewer endpoint (WebSocket) on port " + vs.getLocalPort());
            return true;
        } catch (IOException e) {
            try { if (vs != null) vs.close(); } catch (IOException ignored) { }
            viewerError = "The TV viewer port " + cfg.viewerPort + " could not be opened (" + e.getMessage() + "); trying again.";
            log(viewerError);
            return false;
        }
    }

    private void viewerBindRetry() {
        for (int i = 1; i <= cfg.viewerBindRetries && running; i++) {
            try { Thread.sleep(cfg.viewerBindRetryMs); } catch (InterruptedException e) { return; }
            if (!running) return;
            if (bindViewer()) { log("TV viewer port opened on try " + (i + 1)); return; }
        }
        if (running) {
            viewerError = "The TV viewer port " + cfg.viewerPort + " could not be opened after " + (cfg.viewerBindRetries + 1)
                    + " tries (is another host or app using it?); phones still work.";
            log(viewerError);
        }
    }

    /** The TV viewer WebSocket port, or -1 when the endpoint is off or could not be opened. */
    public int viewerPort() {
        ServerSocket vs = viewerServer;
        return vs == null || vs.isClosed() ? -1 : vs.getLocalPort();
    }

    public String viewerError() {
        return viewerError;
    }

    public int viewerCount() {
        synchronized (lock) {
            return viewers.size();
        }
    }

    /** One line per TV watching now: its address, round trip to the relay (from the WebSocket pings) and how many frames wait in its outbound queue. */
    public List<String> viewerLines() {
        synchronized (lock) {
            List<String> out = new ArrayList<>();
            for (Viewer v : viewers) out.add(viewerLine(v));
            return out;
        }
    }

    public int port() {
        return server == null ? -1 : server.getLocalPort();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        try { ServerSocket vs = viewerServer; if (vs != null) vs.close(); } catch (IOException ignored) { }
        synchronized (lock) { closeViewers("the host stopped hosting"); }
        List<Player> all;
        synchronized (lock) {
            all = new ArrayList<>(players.values());
        }
        for (Player p : all) {
            sendLobby(p, Json.obj("t", "closed", "reason", "The host stopped hosting."));
            p.closeAll(true);
        }
        synchronized (lock) {
            players.clear();
            game = null;
            state = S_SETUP;
        }
        log("relay stopped");
    }

    private String newToken() {
        byte[] b = new byte[16];
        secure.nextBytes(b);
        StringBuilder t = new StringBuilder();
        for (byte x : b) t.append(String.format("%02x", x & 0xff));
        return t.toString();
    }

    /**
     * Starts a daemon thread for one connection. Whatever goes wrong inside it, even an Error, ends only that
     * connection: the handler logs and closes its socket, it never reaches the process's default handler.
     */
    private Thread spawn(String name, final Socket s, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((th, e) -> {
            log(th.getName() + " died with " + e + "; only its connection is closed");
            try { if (s != null) s.close(); } catch (IOException ignored) { }
        });
        t.start();
        return t;
    }

    private void log(String s) {
        if (cfg.listener != null) cfg.listener.log(s);
    }

    private void changed() {
        if (cfg.listener != null) cfg.listener.changed();
    }

    // Model -----------------------------------------------------------------------------------

    private final class Player {
        final int id;
        String name;
        final String token = newToken();
        boolean host, ready;
        /** The phone says it is showing its Network screen (a joiner elsewhere may miss the start). */
        boolean fg = true;
        final List<SetupModel.Team> teams = new ArrayList<>();
        String build = "";
        // lobby link
        Socket lobbySock;
        Outbox lobbyOut;
        long lobbyRx = System.currentTimeMillis();
        // engine link
        Socket engineSock;
        Outbox engineOut;
        boolean linked, linkOpen, dropped, finished, quit;
        String fatal;
        long lastMsg;
        // game statistics
        final Fnv toHash = new Fnv();
        long toMsgs, fromRaw, fromKept, fromDropped;
        final Map<Character, Integer> keptByType = new LinkedHashMap<>();
        boolean[] ownedIdx = new boolean[0];
        final List<String> teamNames = new ArrayList<>();

        Player(int id, String name) {
            this.id = id;
            this.name = name;
        }

        /** After the game: let the last messages (and a control frame) out, then close the engine link. */
        void closeEngineLater() {
            final Socket es = engineSock;
            if (engineOut != null) engineOut.closeWhenDrained();
            engineOut = null;
            engineSock = null;
            if (es != null) {
                Thread t = new Thread(() -> {
                    try { Thread.sleep(1000); es.close(); } catch (Exception ignored) { }
                });
                t.setDaemon(true);
                t.start();
            }
        }

        void closeAll(boolean lobbToo) {
            if (engineOut != null) engineOut.close();
            try { if (engineSock != null) engineSock.close(); } catch (IOException ignored) { }
            if (lobbToo) {
                if (lobbyOut != null) lobbyOut.closeWhenDrained();
                final Socket ls = lobbySock;
                if (ls != null) {
                    Thread t = new Thread(() -> {
                        try { Thread.sleep(300); ls.close(); } catch (Exception ignored) { }
                    });
                    t.setDaemon(true);
                    t.start();
                }
            }
        }
    }

    private static final class TeamRef {
        String name;
        int owner, color, level, index;
    }

    private static final class Digest {
        int seq, player;
        String checksum;
        int tick;
    }

    private final class Game {
        final int no = ++gameNo;
        final List<TeamRef> teams = new ArrayList<>();
        List<String> canonical;
        List<String> viewerLines;
        int viewerReportDropped, viewerEventsDropped, viewerEventsKept;
        final ByteArrayOutputStream log = new ByteArrayOutputStream();
        final Fnv logHash = new Fnv();
        long logMsgs;
        final List<Digest> digests = new ArrayList<>();
        String mapDigest = "";
        String mapDigestFrom = "";
        final long startedAt = System.currentTimeMillis();
        long gateOpenAt, lastKeptAt;
        int lastSender, lastType;
        final Map<Character, Long> rawByType = new LinkedHashMap<>();
        final Map<Character, Long> keptByType = new LinkedHashMap<>();
        final List<String> events = new ArrayList<>();
        final List<Integer> desyncReporters = new ArrayList<>();
        long desyncSince;
        int desyncActiveAtStart;
        /** The silence rule is armed once every live phone has sent its map digest M (plan section 2.8). */
        boolean armed;
        long armedAt;
        final Set<Integer> mSeen = new HashSet<>();
        long maxGapMs;
        String endReason = "";
        /** One line for each TV viewer that has left (or was still watching at the end): round trip, queue depth, why it ended. */
        final List<String> viewerReport = new ArrayList<>();
    }

    /** What happened in a finished game, in one object that the app shows and the tests compare. */
    public static final class GameSummary {
        public int gameNo;
        public String logHash = "";
        public long logMsgs, logBytes, keptTotal, rawTotal;
        public String mapDigest = "";
        public long maxGapMs;
        public final List<String> digests = new ArrayList<>();
        public final List<String> events = new ArrayList<>();
        public final List<String> perPlayer = new ArrayList<>();
        public final List<String> viewers = new ArrayList<>();
        public String endReason = "";
        public final Map<Integer, String> toHashByPlayer = new HashMap<>();
        public final Map<Integer, Long> toMsgsByPlayer = new HashMap<>();

        public String text() {
            StringBuilder b = new StringBuilder();
            b.append("Relay report, game ").append(gameNo).append('\n');
            b.append("End: ").append(endReason).append('\n');
            b.append("Game log: ").append(logMsgs).append(" messages, ").append(logBytes).append(" bytes, hash ")
                    .append(logHash).append('\n');
            b.append("Messages from engines: ").append(rawTotal).append(" received, ").append(keptTotal).append(" kept\n");
            b.append("Longest gap between two forwarded messages: ").append(maxGapMs).append(" ms\n");
            b.append("Map digest M: ").append(mapDigest.isEmpty() ? "(none seen)" : mapDigest).append('\n');
            b.append("Turn checksums (N), ").append(digests.size()).append(":\n");
            for (String d : digests) b.append("  ").append(d).append('\n');
            for (String s : perPlayer) b.append(s).append('\n');
            if (viewers.isEmpty()) b.append("TV viewers: none\n");
            else {
                b.append("TV viewers (round trip is relay to TV and back, measured with a WebSocket ping every 2 s that waits behind the queued frames; \"behind\" is frames waiting in that TV's outbound queue):\n");
                for (String v : viewers) b.append("  ").append(v).append('\n');
            }
            if (events.isEmpty()) b.append("Events: none (no dropout, no desync report)\n");
            else {
                b.append("Events:\n");
                for (String e : events) b.append("  ").append(e).append('\n');
            }
            return b.toString();
        }
    }

    // Accept and handshake --------------------------------------------------------------------

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                try {
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);
                    if (connCount.incrementAndGet() > MAX_CONNECTIONS) {
                        connCount.decrementAndGet();
                        log("too many connections, closed " + s.getRemoteSocketAddress());
                        s.close();
                        continue;
                    }
                    spawn("relay-conn", s, () -> {
                        try { connection(s); } finally { connCount.decrementAndGet(); }
                    });
                } catch (Throwable e) {
                    log("accept handling failed: " + e);
                    try { s.close(); } catch (IOException ignored) { }
                }
            } catch (IOException e) {
                if (running) log("accept failed: " + e);
            } catch (Throwable e) {
                log("accept failed: " + e);
            }
        }
    }

    private void connection(Socket s) {
        try {
            s.setSoTimeout(10000);
            InputStream in = new java.io.BufferedInputStream(s.getInputStream(), 8192);
            String first = Frames.readLine(in, MAX_HELLO_LINE, System.currentTimeMillis() + HELLO_DEADLINE_MS);
            if (first == null) { s.close(); return; }
            Map<String, Object> hello = Json.parseObject(first);
            if (!"hello".equals(Json.str(hello, "t", ""))) throw new IOException("no hello");
            String role = Json.str(hello, "role", "");
            if ("lobby".equals(role)) lobbyConnection(s, in, hello);
            else if ("engine".equals(role)) engineConnection(s, in, hello);
            else throw new IOException("unknown role " + role);
        } catch (Throwable e) {
            // Throwable, not Exception: a StackOverflowError or OutOfMemoryError caused by one phone's input must end
            // that connection only, never the process.
            log("connection from " + s.getRemoteSocketAddress() + " ended: " + e);
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private void refuse(Socket s, String reason) {
        try {
            s.getOutputStream().write((Json.write(Json.obj("t", "refuse", "reason", reason)) + "\n").getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            s.close();
        } catch (IOException ignored) { }
        log("refused " + s.getRemoteSocketAddress() + ": " + reason);
    }

    // Lobby -----------------------------------------------------------------------------------

    private void lobbyConnection(Socket s, InputStream in, Map<String, Object> hello) throws IOException {
        Player p;
        synchronized (lock) {
            if (Json.num(hello, "proto", 0) != PROTO) {
                refuse(s, "Protocol mismatch: the host speaks " + PROTO + ", this phone " + Json.num(hello, "proto", 0)
                        + ". Install the same app version on both phones.");
                return;
            }
            boolean isHost = Json.bool(hello, "host", false);
            if (isHost && (!s.getInetAddress().isLoopbackAddress() || hasHost() || !secretOk(Json.str(hello, "secret", "")))) {
                refuse(s, "Only the hosting phone can be the host.");
                return;
            }
            if (players.size() >= MAX_PLAYERS) {
                refuse(s, "The host is full: at most " + MAX_PLAYERS + " phones.");
                return;
            }
            if (state != S_SETUP) {
                refuse(s, "A game is running on this host. Try again when it has ended.");
                return;
            }
            List<SetupModel.Team> teams = NetTeams.listFromJson(Json.list(hello, "teams"), cfg.data);
            if (totalTeams() + teams.size() > SetupModel.MAX_TEAMS) {
                refuse(s, "The host has no room for " + teams.size() + " more teams (at most " + SetupModel.MAX_TEAMS + ").");
                return;
            }
            p = new Player(nextId++, uniqueName(Json.str(hello, "name", "Player"), -1));
            p.host = isHost;
            p.build = Json.str(hello, "build", "");
            p.teams.addAll(teams);
            p.lobbySock = s;
            players.put(p.id, p);
            if (isHost) p.ready = true;
        }
        s.setSoTimeout(0);
        final Player me = p;
        me.lobbyOut = new Outbox("relay-lobby-out-" + me.id, 200, 1 << 20, false, () -> lobbyLost(me));
        me.lobbyOut.attach(s.getOutputStream());
        log("lobby: " + me.name + " joined (id " + me.id + (me.host ? ", host" : "") + ", " + me.teams.size() + " teams) from "
                + s.getRemoteSocketAddress() + ", build " + me.build);
        broadcastState();
        try {
            for (;;) {
                String line = Frames.readLine(in, MAX_LOBBY_LINE);
                if (line == null) break;
                me.lobbyRx = System.currentTimeMillis();
                handleLobby(me, Json.parseObject(line));
            }
        } catch (Throwable e) {
            log("lobby " + me.name + ": " + e);
        }
        lobbyLost(me);
    }

    private boolean secretOk(String given) {
        if (cfg.hostSecret == null) return true;
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), cfg.hostSecret.getBytes(StandardCharsets.UTF_8));
    }

    private boolean hasHost() {
        for (Player q : players.values()) if (q.host) return true;
        return false;
    }

    private int totalTeams() {
        int n = 0;
        for (Player q : players.values()) n += q.teams.size();
        return n;
    }

    private String uniqueName(String want, int exceptId) {
        String base = GameConfig.clean(want, "Player");
        String name = base;
        for (int k = 2; nameTaken(name, exceptId); k++) name = base + " (" + k + ")";
        return name;
    }

    private boolean nameTaken(String n, int exceptId) {
        for (Player q : players.values()) if (q.id != exceptId && q.name.equalsIgnoreCase(n)) return true;
        return false;
    }

    private void lobbyLost(Player p) {
        boolean removed = false;
        synchronized (lock) {
            if (!players.containsKey(p.id)) return;
            p.lobbyOut.close();
            // The reader has ended (the phone closed, or sent something unreadable): close our side too.
            try { if (p.lobbySock != null) p.lobbySock.close(); } catch (IOException ignored) { }
            if (state == S_SETUP) {
                players.remove(p.id);
                removed = true;
                if (p.host) {
                    // The host's own client went away: the host has stopped hosting.
                    log("host client left; closing every player");
                    for (Player q : new ArrayList<>(players.values())) {
                        sendLobby(q, Json.obj("t", "closed", "reason", "The host left."));
                        q.closeAll(true);
                    }
                    players.clear();
                }
            } else {
                // In a game the engine link decides who is gone; the lobby link is only for the screens.
                p.lobbySock = null;
                if (p.host && game != null) game.events.add("host's lobby link closed during the game");
            }
        }
        if (removed) {
            log("lobby: " + p.name + " left");
            broadcastState();
        }
    }

    private void handleLobby(Player p, Map<String, Object> m) {
        String t = Json.str(m, "t", "");
        synchronized (lock) {
            switch (t) {
                case "teams": {
                    if (state != S_SETUP) return;
                    List<SetupModel.Team> teams = NetTeams.listFromJson(Json.list(m, "teams"), cfg.data);
                    int others = totalTeams() - p.teams.size();
                    if (others + teams.size() > SetupModel.MAX_TEAMS) {
                        sendLobby(p, Json.obj("t", "notice", "text", "Too many teams: at most " + SetupModel.MAX_TEAMS + " in all."));
                        return;
                    }
                    p.teams.clear();
                    p.teams.addAll(teams);
                    p.ready = p.host;
                    break;
                }
                case "fg":
                    p.fg = Json.bool(m, "on", true);
                    break;
                case "name":
                    if (state != S_SETUP) return;
                    p.name = uniqueName(Json.str(m, "name", p.name), p.id);
                    break;
                case "ready":
                    if (state != S_SETUP) return;
                    p.ready = p.host || Json.bool(m, "ready", false);
                    break;
                case "start":
                    if (!p.host) { sendLobby(p, Json.obj("t", "notice", "text", "Only the host can start the game.")); return; }
                    startGame();
                    return;
                case "ping":
                    sendLobby(p, Json.obj("t", "pong"));
                    return;
                case "pong":
                    return;
                case "leave":
                    // handled by closing
                    return;
                default:
                    return;
            }
        }
        broadcastState();
    }

    private void sendLobby(Player p, Map<String, Object> m) {
        Outbox o = p.lobbyOut;
        if (o != null) o.offer((Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** The state every connected phone sees (what the lobby screen shows). */
    public Map<String, Object> status(int forPlayer) {
        synchronized (lock) {
            List<Object> pl = new ArrayList<>();
            for (Player q : players.values()) {
                pl.add(Json.obj("id", (long) q.id, "name", q.name, "host", q.host, "ready", q.ready,
                        "teams", NetTeams.listToJson(q.teams), "build", q.build, "linked", q.linked, "dropped", q.dropped, "fg", q.fg));
            }
            return Json.obj("t", "state", "state", state == S_SETUP ? "setup" : "playing", "host", cfg.hostName,
                    "proto", (long) PROTO, "build", cfg.build, "you", (long) forPlayer, "players", pl,
                    "canStart", canStart(), "game", game == null ? 0L : (long) game.no);
        }
    }

    private boolean canStart() {
        if (state != S_SETUP || totalTeams() < 2) return false;
        for (Player q : players.values()) if (!q.ready) return false;
        return true;
    }

    private void broadcastState() {
        List<Player> all;
        synchronized (lock) {
            all = new ArrayList<>(players.values());
        }
        for (Player q : all) sendLobby(q, status(q.id));
        changed();
    }

    // Start -----------------------------------------------------------------------------------

    /** Called with the lock held, from the host's start message. */
    private void startGame() {
        if (!canStart()) {
            for (Player q : players.values()) if (q.host) sendLobby(q, Json.obj("t", "notice",
                    "text", "Cannot start yet: every phone must be ready and there must be at least two teams."));
            return;
        }
        SetupModel hostModel = cfg.hostSetup.get();
        SetupModel m = SetupModel.fromText(hostModel.toText(), cfg.data, rnd);
        m.teams.clear();
        Game g = new Game();
        // Canonical order: phones in join order (host first), each phone's teams in their order.
        // Colours: a team keeps its colour unless another phone's team already has it.
        boolean[] usedColor = new boolean[SetupModel.COLORS.length];
        Map<Integer, List<Integer>> ownerColors = new HashMap<>();
        List<String> usedNames = new ArrayList<>();
        for (Player q : players.values()) {
            q.teamNames.clear();
            for (SetupModel.Team src : q.teams) {
                SetupModel.Team t = src.copy();
                List<Integer> mine = ownerColors.computeIfAbsent(q.id, k -> new ArrayList<>());
                if (usedColor[t.color] && !mine.contains(t.color)) {
                    for (int c = 0; c < usedColor.length; c++) if (!usedColor[c]) { t.color = c; break; }
                }
                usedColor[t.color] = true;
                mine.add(t.color);
                String base = GameConfig.cleanTeamName(t.name, "Team " + (m.teams.size() + 1));
                String n = base;
                for (int k = 2; containsIgnoreCase(usedNames, n); k++) n = base + " (" + k + ")";
                usedNames.add(n);
                t.name = n;
                TeamRef r = new TeamRef();
                r.name = n;
                r.owner = q.id;
                r.color = t.color;
                r.level = t.level;
                r.index = m.teams.size();
                g.teams.add(r);
                q.teamNames.add(n);
                m.teams.add(t);
            }
        }
        g.canonical = GameConfig.build(m, cfg.data, rnd);
        String bad = checkSetupLines(g.canonical);
        if (bad != null) {
            // Nothing has changed yet: refuse cleanly, like the viewer's 503, and say why in the lobby.
            if (gameNo == g.no) gameNo--;
            log("game refused: " + bad);
            for (Player q : players.values()) sendLobby(q, Json.obj("t", "notice", "text", "The game cannot start: " + bad));
            return;
        }
        for (Player q : players.values()) {
            boolean[] owned = new boolean[g.teams.size()];
            for (TeamRef r : g.teams) owned[r.index] = r.owner == q.id;
            q.ownedIdx = owned;
        }
        // The TV viewer page (tv/viewer/viewer.js) turns TL into TN and adds erdriven after every eaddteam itself, as the
        // e2e-host bridge's viewers are served the unmodified lines; so the viewer gets the canonical lines unchanged.
        g.viewerLines = new ArrayList<>(g.canonical);
        game = g;
        state = S_STARTING;
        for (Player q : players.values()) {
            q.linked = q.linkOpen = q.dropped = q.finished = q.quit = false;
            q.fatal = null;
            q.lastMsg = 0;
            q.toMsgs = q.fromRaw = q.fromKept = q.fromDropped = 0;
            q.keptByType.clear();
            q.engineOut = new Outbox("relay-engine-out-" + q.id, 20000, 8 << 20, true, () -> engineWriteFailed(q));
            q.ready = q.host;
        }
        log("game " + g.no + " starting: " + g.teams.size() + " teams, " + players.size() + " phones");
        for (Player q : players.values()) {
            List<Object> lines = new ArrayList<>(GameConfig.forRecipient(g.canonical, q.ownedIdx));
            sendLobby(q, Json.obj("t", "start", "you", (long) q.id, "token", q.token, "nick", q.name,
                    "game", (long) g.no, "lines", lines));
        }
        broadcastStateLocked();
    }

    /**
     * The setup lines are engine messages: one length byte, so at most 255 bytes each (the launcher would skip a longer
     * line and the phones would then disagree), no control characters (a newline would be a second command), and at most
     * 255 of them (the TV viewer's first message counts them in one byte). Null when all is well, otherwise the reason.
     */
    public static String checkSetupLines(List<String> lines) {
        if (lines.size() > MAX_SETUP_LINES) {
            return "the setup has " + lines.size() + " lines but the TV viewer link counts at most " + MAX_SETUP_LINES
                    + ". Use fewer teams or hedgehogs.";
        }
        for (int k = 0; k < lines.size(); k++) {
            String l = lines.get(k);
            int n = l.getBytes(StandardCharsets.UTF_8).length;
            String head = l.length() > 40 ? l.substring(0, 40) + "..." : l;
            if (n > MAX_SETUP_LINE) {
                return "setup line " + (k + 1) + " (\"" + head.replaceAll("\\p{Cc}", "?") + "\") is " + n
                        + " bytes but the engine link takes at most " + MAX_SETUP_LINE
                        + ". Shorten the long name, seed or script parameter.";
            }
            for (int c = 0; c < l.length(); c++) {
                if (l.charAt(c) < 0x20 || l.charAt(c) == 0x7f) {
                    return "setup line " + (k + 1) + " (\"" + head.replaceAll("\\p{Cc}", "?") + "\") contains a control character.";
                }
            }
        }
        return null;
    }

    private void broadcastStateLocked() {
        for (Player q : players.values()) sendLobby(q, status(q.id));
        changed();
    }

    private static boolean containsIgnoreCase(List<String> l, String s) {
        for (String x : l) if (x.equalsIgnoreCase(s)) return true;
        return false;
    }

    // Engine links ----------------------------------------------------------------------------

    private void engineConnection(Socket s, InputStream in, Map<String, Object> hello) throws IOException {
        Player p = null;
        synchronized (lock) {
            String token = Json.str(hello, "token", "");
            for (Player q : players.values()) if (q.token.equals(token)) p = q;
            if (p == null || game == null || state == S_SETUP || p.linkOpen || p.dropped) {
                refuse(s, "No game is waiting for this engine.");
                return;
            }
            p.engineSock = s;
        }
        s.setSoTimeout(0);
        OutputStream os = s.getOutputStream();
        os.write((Json.write(Json.obj("t", "ok", "game", (long) game.no)) + "\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
        final Player me = p;
        synchronized (lock) {
            me.linked = me.linkOpen = true;
            me.lastMsg = System.currentTimeMillis();
            me.engineOut.attach(os);
            log("engine link up: " + me.name);
            maybeOpenGate();
        }
        broadcastState();
        try {
            for (;;) {
                byte[] msg = Frames.read(in);
                if (msg == null) break;
                onEngineMessage(me, msg);
            }
        } catch (Throwable e) {
            log("engine link " + me.name + ": " + e);
        }
        engineClosed(me);
    }

    private void engineWriteFailed(Player p) {
        synchronized (lock) {
            if (game == null || p.dropped) return;
            log("writing to " + p.name + " failed");
            dropPlayer(p, "the link to it failed");
            checkEnd();
        }
    }

    /** Lock held. Opens the gate when every phone that can still join has linked. */
    private void maybeOpenGate() {
        if (state != S_STARTING) return;
        for (Player q : players.values()) if (!q.linked && !q.dropped) return;
        openGate();
    }

    private void openGate() {
        state = S_PLAYING;
        game.gateOpenAt = System.currentTimeMillis();
        game.lastKeptAt = game.gateOpenAt;
        for (Player q : players.values()) if (q.engineOut != null) q.engineOut.release();
        log("all engine links up; game " + game.no + " running");
        updateArmed(game, game.gateOpenAt);
        broadcastStateLocked();
    }

    // Forwarding ------------------------------------------------------------------------------

    private void onEngineMessage(Player p, byte[] msg) {
        List<Runnable> after = new ArrayList<>();
        synchronized (lock) {
            Game g = game;
            if (g == null || p.dropped) return;
            p.lastMsg = System.currentTimeMillis();
            if (msg.length == 0) return;
            if (msg[0] == 0) {
                // control frame from the phone
                if (msg.length > 1 && msg[1] == 'E') {
                    p.fatal = new String(msg, 2, msg.length - 2, StandardCharsets.UTF_8);
                    g.events.add(p.name + " reported an engine error: " + p.fatal);
                    log(p.name + " engine error: " + p.fatal);
                    if (p.fatal.toLowerCase().contains("desync")) noteDesync(p);
                } else if (msg.length > 1 && msg[1] == 'Q') {
                    // The player confirmed "leave the game" in the app: the same as the engine's own Q, but sent by the
                    // launcher, so it does not depend on the engine's UI state (an open chat swallows Escape and Y).
                    p.quit = true;
                    log(p.name + " confirmed leaving the game (control frame)");
                }
                return;
            }
            char type = (char) (msg[0] & 0xff);
            p.fromRaw++;
            g.rawByType.merge(type, 1L, Long::sum);
            if (type == 'q') { p.finished = true; return; }
            if (type == 'Q' || type == 'H') { p.quit = true; return; }
            if (!Frames.valid(msg, p.ownedIdx)) {
                p.fromDropped++;
                return;
            }
            p.fromKept++;
            p.keptByType.merge(type, 1, Integer::sum);
            g.keptByType.merge(type, 1L, Long::sum);
            if (g.armed && p.lastMsg - g.lastKeptAt > g.maxGapMs) g.maxGapMs = p.lastMsg - g.lastKeptAt;
            g.lastKeptAt = p.lastMsg;
            g.lastSender = p.id;
            g.lastType = type;
            if (type == 'N') {
                if (msg.length >= 7) {
                    Digest d = new Digest();
                    d.seq = g.digests.size() + 1;
                    d.player = p.id;
                    d.checksum = Frames.hex(msg, 1, 4);
                    d.tick = ((msg[5] & 0xff) << 8) | (msg[6] & 0xff);
                    g.digests.add(d);
                }
            } else if (type == 'M') {
                if (g.mapDigest.isEmpty()) {
                    g.mapDigest = new String(msg, 1, msg.length - 1, StandardCharsets.ISO_8859_1);
                    g.mapDigestFrom = p.name;
                }
                g.mSeen.add(p.id);
            }
            appendAndForward(g, msg, p, after);
            if (type == 'M') updateArmed(g, p.lastMsg);
        }
        for (Runnable r : after) r.run();
    }

    /** Lock held. Arms the silence rule once every live phone has sent its map digest: until then nobody can be blamed. */
    private void updateArmed(Game g, long now) {
        if (g.armed || state != S_PLAYING) return;
        for (Player q : players.values()) if (!q.dropped && !q.finished && !g.mSeen.contains(q.id)) return;
        g.armed = true;
        g.armedAt = now;
        g.lastKeptAt = now;
        log("every live phone has sent its map digest; the silence rule is armed");
    }

    /** Lock held. Appends to the log and offers to every other live engine. */
    private void appendAndForward(Game g, byte[] msg, Player from, List<Runnable> after) {
        byte[] f = Frames.frame(msg);
        g.log.write(f, 0, f.length);
        g.logHash.add(f);
        g.logMsgs++;
        if (!viewers.isEmpty()) {
            byte[] wf = Ws.frame(2, f);
            for (Viewer v : new ArrayList<>(viewers)) if (!v.out.offer(wf)) dropViewer(v, whyRefused(v));
        }
        for (final Player q : players.values()) {
            if (q == from || q.dropped || q.finished || q.engineOut == null) continue;
            q.toHash.add(f);
            q.toMsgs++;
            byte[] out = cfg.fault == null ? f : cfg.fault.apply(q.id, f);
            if (out == null) continue;
            if (!q.engineOut.offer(out)) {
                after.add(() -> {
                    synchronized (lock) {
                        if (game != null && !q.dropped) {
                            dropPlayer(q, "too slow: its queue overflowed");
                            checkEnd();
                        }
                    }
                });
            }
        }
    }

    // Leaving, dropout, end -------------------------------------------------------------------

    private void engineClosed(Player p) {
        synchronized (lock) {
            if (game == null) return;
            p.linkOpen = false;
            if (p.dropped) { checkEnd(); return; }
            if (p.finished) {
                game.events.add(p.name + " finished the game");
            } else if (p.fatal != null && p.fatal.toLowerCase().contains("desync")) {
                int active = 0;
                for (Player q : players.values()) if (!q.dropped && !q.finished && q != p) active++;
                if (active <= 1) {
                    p.dropped = true;
                    String other = "";
                    for (Player q : players.values()) if (q != p && !q.dropped) other = q.name;
                    endGame("Desync between " + p.name + " and " + other + " (" + p.fatal + "). With two phones the relay cannot tell which is wrong, so the game ended for both.");
                    return;
                }
                dropPlayer(p, "desync reported (" + p.fatal + ")");
            } else if (p.host && p.quit) {
                // The host chose "End the game for everyone" on its game screen: the game ends on every phone.
                game.events.add(p.name + " (the host) ended the game");
                log(p.name + " (the host) quit: ending the game for everyone");
                endGame(HOST_ENDED);
                return;
            } else if (p.quit) {
                dropPlayer(p, "the player quit", "You left the game.");
            } else {
                dropPlayer(p, "its engine link closed");
            }
            checkEnd();
        }
    }

    /** Lock held. Sends the unsynced team-gone message F<team> for each of the phone's teams (plan section 2.8). */
    private void dropPlayer(Player p, String why) {
        dropPlayer(p, why, "You were dropped from the game: " + why);
    }

    /** {@code toPlayer} is what the dropped phone itself is told (a phone that quit is not "dropped"). */
    private void dropPlayer(Player p, String why, String toPlayer) {
        Game g = game;
        if (g == null || p.dropped) return;
        p.dropped = true;
        String ev = p.name + " dropped (" + why + ")";
        g.events.add(ev);
        log(ev);
        p.closeAll(false);
        List<Runnable> after = new ArrayList<>();
        if (state == S_PLAYING || state == S_STARTING) {
            for (String team : p.teamNames) {
                byte[] f = new byte[team.getBytes(StandardCharsets.UTF_8).length + 1];
                byte[] n = team.getBytes(StandardCharsets.UTF_8);
                f[0] = 'F';
                System.arraycopy(n, 0, f, 1, n.length);
                g.events.add("injected F " + team + " (unsynced team gone, no timestamp)");
                appendAndForward(g, f, p, after);
            }
        }
        for (Runnable r : after) r.run();
        sendLobby(p, Json.obj("t", "dropped", "reason", toPlayer));
        if (state == S_STARTING) maybeOpenGate();
        else updateArmed(g, System.currentTimeMillis());
        broadcastStateLocked();
    }

    private void noteDesync(Player p) {
        Game g = game;
        if (g.desyncReporters.isEmpty()) {
            g.desyncSince = System.currentTimeMillis();
            int n = 0;
            for (Player q : players.values()) if (!q.dropped && !q.finished) n++;
            g.desyncActiveAtStart = n;
        }
        if (!g.desyncReporters.contains(p.id)) g.desyncReporters.add(p.id);
        // With three or more phones: if every receiver of the turn's checksum reports, the sender is wrong
        // and the game ends; if one does, that phone is the odd one out and engineClosed drops it.
        if (g.desyncActiveAtStart >= 3 && g.desyncReporters.size() >= g.desyncActiveAtStart - 1) {
            String sender = "?";
            Player s = players.get(g.lastSender);
            if (s != null) sender = s.name;
            endGame("Desync reported by every other phone; the active phone (" + sender + ") is the odd one out. The game ended for everyone.");
        }
    }

    /** Lock held. */
    private void checkEnd() {
        if (game == null) return;
        for (Player q : players.values()) if (q.linkOpen && !q.dropped) return;
        if (state == S_STARTING) {
            // Nobody linked yet: keep waiting (the watchdog applies the link timeout).
            for (Player q : players.values()) if (!q.dropped && !q.linked) return;
        }
        boolean anyDropped = false;
        for (Player q : players.values()) if (q.dropped) anyDropped = true;
        endGame(anyDropped ? "every engine is gone" : "every engine finished");
    }

    /** Lock held. */
    private void endGame(String reason) {
        Game g = game;
        if (g == null) return;
        g.endReason = reason;
        GameSummary s = new GameSummary();
        s.gameNo = g.no;
        s.logMsgs = g.logMsgs;
        s.maxGapMs = g.maxGapMs;
        s.logBytes = g.log.size();
        s.logHash = sha256(g.log.toByteArray()) + " (fnv " + g.logHash.hex() + ")";
        s.mapDigest = g.mapDigest.isEmpty() ? "" : g.mapDigest + " (from " + g.mapDigestFrom + ")";
        for (long v : g.rawByType.values()) s.rawTotal += v;
        for (long v : g.keptByType.values()) s.keptTotal += v;
        for (Digest d : g.digests) {
            Player pl = players.get(d.player);
            s.digests.add("turn " + d.seq + ": " + d.checksum + " tick " + d.tick + " from " + (pl == null ? "?" : pl.name));
        }
        closeViewers(reason.equals(HOST_ENDED) ? "the host ended the game" : "the game ended");
        if (g.viewerReportDropped > 0) s.viewers.add("(" + g.viewerReportDropped + " earlier TV viewer lines not listed; the last " + MAX_VIEWER_LINES + " follow)");
        s.viewers.addAll(g.viewerReport);
        if (g.viewerEventsDropped > 0) s.events.add("(" + g.viewerEventsDropped + " earlier TV viewer events not listed; the last " + MAX_VIEWER_LINES + " are kept)");
        s.events.addAll(g.events);
        s.endReason = reason;
        for (Player q : players.values()) {
            s.perPlayer.add("Phone " + q.name + ": sent to it " + q.toMsgs + " messages (hash " + q.toHash.hex()
                    + "), received from it " + q.fromRaw + " (" + q.fromKept + " forwarded, " + q.fromDropped + " not on the whitelist or not its team)"
                    + (q.dropped ? ", dropped" : q.finished ? ", finished" : q.quit ? ", quit" : ""));
            s.toHashByPlayer.put(q.id, q.toHash.hex());
            s.toMsgsByPlayer.put(q.id, q.toMsgs);
        }
        lastSummary = s;
        log("game " + g.no + " over: " + reason + "; log " + s.logMsgs + " messages, hash " + s.logHash);
        for (Player q : new ArrayList<>(players.values())) {
            Map<String, Object> sum = Json.obj("t", "summary", "game", (long) g.no, "text", s.text(),
                    "logHash", s.logHash, "logMsgs", s.logMsgs, "toYouHash", q.toHash.hex(), "toYouMsgs", q.toMsgs,
                    "reason", reason);
            if (!q.finished && q.engineOut != null && q.linkOpen) {
                // tell a still-running engine why it is being cut off
                byte[] x = ("\0X" + reason).getBytes(StandardCharsets.UTF_8);
                q.engineOut.offer(Frames.frame(x));
            }
            sendLobby(q, sum);
            q.closeEngineLater();
        }
        game = null;
        state = S_SETUP;
        // Drop phones whose lobby link is gone; everyone else goes back to the lobby, not ready.
        long nowMs = System.currentTimeMillis();
        for (Player q : new ArrayList<>(players.values())) {
            q.ready = q.host;
            q.lobbyRx = nowMs;                       // a phone whose lobby link sat idle in a game must not look silent
            q.linked = q.linkOpen = q.dropped = q.finished = false;
            if (q.lobbySock == null) players.remove(q.id);
        }
        broadcastStateLocked();
    }

    private static String sha256(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder s = new StringBuilder();
            for (int k = 0; k < 8; k++) s.append(String.format("%02x", d[k] & 0xff));
            return s.toString();
        } catch (Exception e) {
            return "n/a";
        }
    }

    public GameSummary lastSummary() {
        synchronized (lock) {
            return lastSummary;
        }
    }

    /** Turn checksums (N) seen so far in the running game, or in the last one. */
    public int turns() {
        synchronized (lock) {
            return game != null ? game.digests.size() : lastSummary != null ? lastSummary.digests.size() : 0;
        }
    }

    /** A running game's counters, for the host's screen. */
    public String liveText() {
        synchronized (lock) {
            if (game == null) return state == S_SETUP ? "Lobby" : "";
            Game g = game;
            return "Game " + g.no + ": " + g.digests.size() + " turns, log " + g.logMsgs + " messages";
        }
    }

    // Watchdog --------------------------------------------------------------------------------

    private void watchLoop() {
        long lastPing = 0;
        while (running) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            try {
                lastPing = watchOnce(now, lastPing);
            } catch (Throwable e) {
                log("watchdog error (carrying on): " + e);
            }
        }
    }

    private long watchOnce(long now, long lastPing) {
            synchronized (lock) {
                if (now - lastPing > 5000) {
                    lastPing = now;
                    for (Player q : players.values()) sendLobby(q, Json.obj("t", "ping"));
                }
                pingViewers(now);
                if (state == S_SETUP) {
                    for (Player q : new ArrayList<>(players.values())) {
                        if (!q.host && now - q.lobbyRx > cfg.lobbySilenceMs) {
                            log("lobby: " + q.name + " silent for " + (now - q.lobbyRx) / 1000 + " s, removed");
                            q.closeAll(true);
                            players.remove(q.id);
                            broadcastStateLocked();
                        }
                    }
                }
                Game g = game;
                if (g == null) return lastPing;
                if (state == S_STARTING && now - g.startedAt > cfg.linkWaitMs) {
                    for (Player q : new ArrayList<>(players.values()))
                        if (!q.linked && !q.dropped) dropPlayer(q, "its engine did not connect in " + cfg.linkWaitMs / 1000 + " s");
                    if (state == S_STARTING) maybeOpenGate();
                    checkEnd();
                } else if (state == S_PLAYING && !g.armed) {
                    // The silence rule is off until every live phone has sent its map digest. A phone that is still
                    // generating the map must not be blamed; one that never finishes is dropped after a long wait.
                    if (now - g.gateOpenAt > cfg.mapWaitMs) {
                        for (Player q : new ArrayList<>(players.values()))
                            if (!q.dropped && !q.finished && !g.mSeen.contains(q.id))
                                dropPlayer(q, "no map digest in " + cfg.mapWaitMs / 1000 + " s: it never finished generating the map");
                        updateArmed(g, now);
                        checkEnd();
                    }
                } else if (state == S_PLAYING) {
                    // Before the first turn ends there is extra grace: the first turn can start late on a slow phone.
                    long limit = cfg.silenceMs + (g.digests.isEmpty() ? cfg.graceMs : 0);
                    if (now - g.lastKeptAt > limit) silence(g, now);
                }
                if (!g.desyncReporters.isEmpty() && now - g.desyncSince > 5000) g.desyncReporters.clear();
            }
            return lastPing;
    }

    /**
     * Lock held. Nothing has arrived from any phone for the silence limit (the rule is armed: every live phone has
     * sent its map digest). The current team's owner sends an empty tick at least once a second, so someone is
     * stalled. After an N, or an M (the first turn has not started), the next owner is unknown to the relay, so
     * with exactly two phones it is the other one; otherwise the last sender is dropped.
     */
    private void silence(Game g, long now) {
        if (now - g.lastKeptAt > g.maxGapMs) g.maxGapMs = now - g.lastKeptAt;
        List<Player> live = new ArrayList<>();
        for (Player q : players.values()) if (q.linkOpen && !q.dropped && !q.finished) live.add(q);
        Player culprit = null;
        if (g.lastType == 'N' || g.lastType == 'M') {
            for (Player q : live) if (q.id != g.lastSender) { if (culprit == null) culprit = q; else { culprit = null; break; } }
            if (culprit == null && live.size() > 2) {
                log("silence for " + (now - g.lastKeptAt) / 1000 + " s after a turn end or map digest with " + live.size()
                        + " phones: cannot tell who is stalled");
                g.lastKeptAt = now;
                return;
            }
        } else {
            culprit = players.get(g.lastSender);
        }
        g.lastKeptAt = now;
        if (culprit != null && !culprit.dropped) {
            dropPlayer(culprit, "no messages for " + cfg.silenceMs / 1000 + " s while it was the one expected to play");
            checkEnd();
        }
    }


    // TV viewers (WebSocket) -------------------------------------------------------------------

    private final class Viewer {
        Socket sock;
        Outbox out;
        String from = "";
        final long joinedAt = System.currentTimeMillis();
        /** WebSocket ping statistics (the pings wait behind the frames already queued, so a long queue shows up as a long round trip). */
        long pingsSent, pongs;
        double lastRttMs = -1, sumRttMs, minRttMs = -1, maxRttMs = -1;
        int maxQueue;
    }

    /** Lock held. */
    private String viewerLine(Viewer v) {
        int q = v.out.queued();
        if (q > v.maxQueue) v.maxQueue = q;
        StringBuilder b = new StringBuilder(v.from).append(": ");
        if (v.pongs == 0) b.append("round trip not measured yet (").append(v.pingsSent).append(" pings sent)");
        else b.append(String.format(java.util.Locale.ROOT, "round trip %.1f ms (average %.1f, best %.1f, worst %.1f, %d pongs of %d pings)",
                v.lastRttMs, v.sumRttMs / v.pongs, v.minRttMs, v.maxRttMs, v.pongs, v.pingsSent));
        b.append(", ").append(q).append(" frames behind (deepest ").append(v.maxQueue).append("), watching for ")
                .append((System.currentTimeMillis() - v.joinedAt) / 1000).append(" s");
        return b.toString();
    }

    /** Lock held. Why a frame could not be queued for the viewer: a connection that failed or closed is not a slow one. */
    private String whyRefused(Viewer v) {
        String f = v.out.failure();
        if (f != null) return "connection lost (write failed: " + f + ")";
        if (v.out.isClosed()) return "connection closed";
        return "too slow: its outbound queue overflowed at " + v.out.queued() + " frames";
    }

    /** Lock held. The pong for the ping sent at {@code sentNanos}. */
    private void onPong(Viewer v, byte[] payload) {
        if (payload.length != 8) return;
        long sent = 0;
        for (byte x : payload) sent = (sent << 8) | (x & 0xff);
        double ms = (System.nanoTime() - sent) / 1e6;
        if (ms < 0 || ms > 3600_000) return;
        v.pongs++;
        v.lastRttMs = ms;
        v.sumRttMs += ms;
        if (v.minRttMs < 0 || ms < v.minRttMs) v.minRttMs = ms;
        if (ms > v.maxRttMs) v.maxRttMs = ms;
    }

    private long lastViewerPing;

    /** Lock held. Called by the watchdog: pings every viewer once per {@code viewerPingMs}. */
    private void pingViewers(long now) {
        if (viewers.isEmpty() || now - lastViewerPing < cfg.viewerPingMs) return;
        lastViewerPing = now;
        for (Viewer v : viewers) {
            byte[] p = new byte[8];
            long t = System.nanoTime();
            for (int k = 7; k >= 0; k--) { p[k] = (byte) t; t >>>= 8; }
            v.out.offer(Ws.frame(9, p));
            v.pingsSent++;
            int q = v.out.queued();
            if (q > v.maxQueue) v.maxQueue = q;
        }
    }

    private void viewerAcceptLoop(ServerSocket vsock) {
        while (running && !vsock.isClosed()) {
            try {
                final Socket s = vsock.accept();
                try {
                    s.setTcpNoDelay(true);
                    if (viewerConns.incrementAndGet() > MAX_VIEWERS) {
                        viewerConns.decrementAndGet();
                        log("too many viewer connections, closed " + s.getRemoteSocketAddress());
                        s.close();
                        continue;
                    }
                    spawn("relay-viewer", s, () -> {
                        try { viewerConnection(s); } finally { viewerConns.decrementAndGet(); }
                    });
                } catch (Throwable e) {
                    log("viewer accept handling failed: " + e);
                    try { s.close(); } catch (IOException ignored) { }
                }
            } catch (IOException e) {
                if (running && !vsock.isClosed()) log("viewer accept failed: " + e);
            } catch (Throwable e) {
                log("viewer accept failed: " + e);
            }
        }
    }

    /**
     * A TV viewer joins. Behaviour of the e2e-host bridge: no game yet means 503 (the page retries every 3 s); a plain
     * GET returns status JSON; a WebSocket upgrade gets one binary message with the setup lines, then, when the log is
     * not empty, e$spectate 1 and the backlog, then one binary frame per live engine message. Snapshot and
     * registration happen under one lock, so no message is lost or duplicated. Viewers are read-only.
     */
    private void viewerConnection(Socket s) {
        Viewer v = null;
        String why = "connection ended";
        try {
            s.setSoTimeout(10000);
            InputStream in = new java.io.BufferedInputStream(s.getInputStream(), 4096);
            String head = Ws.readHead(in, System.currentTimeMillis() + HELLO_DEADLINE_MS);
            if (head == null) { s.close(); return; }
            OutputStream os = s.getOutputStream();
            String key = Ws.header(head, "sec-websocket-key");
            if (key == null) {
                String body;
                synchronized (lock) {
                    body = Json.write(Json.obj("state", state == S_SETUP ? "setup" : "playing", "game", game == null ? 0L : (long) game.no,
                            "viewers", (long) viewers.size(), "viewersEver", (long) viewersEver));
                }
                os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\nContent-Length: "
                        + body.length() + "\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8));
                os.flush();
                s.close();
                return;
            }
            final Viewer me = new Viewer();
            synchronized (lock) {
                if (game == null || state == S_SETUP || game.viewerLines.size() > MAX_SETUP_LINES) {
                    os.write("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    os.flush();
                    s.close();
                    return;
                }
                os.write(Ws.handshakeResponse(key));
                os.flush();
                me.sock = s;
                me.from = String.valueOf(s.getRemoteSocketAddress());
                me.out = new Outbox("relay-viewer-out", cfg.viewerQueueItems, 16 << 20, false,
                        () -> viewerLost(me, "connection lost (write failed: " + me.out.failure() + ")"));
                me.out.attach(os);
                byte[] first = viewerBacklog();
                me.out.offer(Ws.frame(2, first));
                viewers.add(me);
                viewersEver++;
                addViewerEvent(game, "TV viewer joined from " + me.from + " (first message " + first.length + " bytes)");
                log("viewer joined from " + me.from + ", first message " + first.length + " bytes, log " + game.logMsgs + " messages");
            }
            v = me;
            s.setSoTimeout(0);
            changed();
            boolean closeFrame = Ws.readClient(in, reply -> me.out.offer(reply), pong -> {
                synchronized (lock) { onPong(me, pong); }
            });
            why = closeFrame ? "the TV closed the connection" : "the TV disconnected (end of stream)";
            // The client closed (and its close frame was echoed): let the echo out before the socket goes.
            me.out.closeWhenDrained();
            try { Thread.sleep(200); } catch (InterruptedException ignored) { }
        } catch (Throwable e) {
            // A reset or a half frame is the TV going away (killed, Wi-Fi lost), not slowness.
            String m = String.valueOf(e.getMessage());
            boolean gone = e instanceof java.net.SocketException || e instanceof java.io.EOFException || m.contains("stream ended inside a frame");
            why = gone ? "connection lost (" + m + ")" : "error: " + e;
            if (v == null) log("viewer connection from " + s.getRemoteSocketAddress() + " ended: " + e);
        }
        if (v != null) viewerLost(v, why);
        else try { s.close(); } catch (IOException ignored) { }
    }

    /** The viewer's connection ended (read side, or the writer failed). */
    private void viewerLost(Viewer v, String why) {
        synchronized (lock) {
            if (!retire(v, why)) return;
            v.out.close();
            try { v.sock.close(); } catch (IOException ignored) { }
        }
        changed();
    }

    /** Lock held. */
    private void dropViewer(Viewer v, String why) {
        if (!retire(v, why)) return;
        v.out.close();
        try { v.sock.close(); } catch (IOException ignored) { }
    }

    /** Lock held. A TV that keeps reconnecting must not grow the report without bound: keep the last MAX_VIEWER_LINES viewer events and count the rest. */
    private void addViewerEvent(Game g, String ev) {
        g.events.add(ev);
        if (++g.viewerEventsKept > MAX_VIEWER_LINES) {
            for (int i = 0; i < g.events.size(); i++) {
                if (g.events.get(i).startsWith("TV viewer ")) { g.events.remove(i); break; }
            }
            g.viewerEventsKept--;
            g.viewerEventsDropped++;
        }
    }

    /** Lock held. Takes the viewer out of the list and records its statistics and why it ended; false if it was already gone. */
    private boolean retire(Viewer v, String why) {
        if (!viewers.remove(v)) return false;
        String line = viewerLine(v) + "; ended: " + why;
        Game g = game;
        if (g != null) {
            g.viewerReport.add(line);
            if (g.viewerReport.size() > MAX_VIEWER_LINES) { g.viewerReport.remove(0); g.viewerReportDropped++; }
            addViewerEvent(g, "TV viewer " + v.from + " left (" + why + ")");
        }
        log("viewer " + v.from + " left: " + why);
        return true;
    }

    /** Lock held. Sends a close frame after everything queued and lets go of every viewer. */
    private void closeViewers(String why) {
        for (Viewer v : new ArrayList<>(viewers)) {
            retire(v, why);
            v.out.offer(Ws.frame(8, new byte[0]));
            v.out.closeWhenDrained();
            final Socket vs = v.sock;
            Thread t = new Thread(() -> {
                try { Thread.sleep(1000); vs.close(); } catch (Exception ignored) { }
            });
            t.setDaemon(true);
            t.start();
        }
    }

    // Viewer stream (framing of plan section 4, for the TV that joins later) ------------------

    /**
     * The first binary message of a viewer joining a running game: a one-byte count of setup lines, the setup
     * lines (all teams externally driven) as length-prefixed messages, then, when the log is not empty,
     * {@code e$spectate 1} and the whole log. Live messages follow as separate frames. Null when no game runs.
     */
    public byte[] viewerBacklog() {
        synchronized (lock) {
            if (game == null) return null;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(game.viewerLines.size());
            for (String l : game.viewerLines) {
                byte[] f = Frames.frame(l);
                b.write(f, 0, f.length);
            }
            if (game.log.size() > 0) {
                byte[] f = Frames.frame("e$spectate 1");
                b.write(f, 0, f.length);
                b.write(game.log.toByteArray(), 0, game.log.size());
            }
            return b.toByteArray();
        }
    }
}
