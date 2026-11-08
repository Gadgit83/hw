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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
 *     {@code \0X<text>} the relay ends the game).</li>
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

    /** Settings and callbacks. Fields are read when the relay starts or when a game starts. */
    public static final class Config {
        public int port = PHONE_PORT;
        /** TV viewer WebSocket port; 0 picks a free one, -1 turns the viewer endpoint off. */
        public int viewerPort = VIEWER_PORT;
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
    }

    public interface Listener {
        void log(String line);

        /** The lobby or game state changed (state JSON-like text for the UI is available from {@link Relay#status}). */
        void changed();
    }

    private final Config cfg;
    private final Object lock = new Object();
    private ServerSocket server, viewerServer;
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

    public Relay(Config cfg) {
        this.cfg = cfg;
    }

    // Lifecycle -------------------------------------------------------------------------------

    public void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(cfg.port));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "relay-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        watchThread = new Thread(this::watchLoop, "relay-watch");
        watchThread.setDaemon(true);
        watchThread.start();
        log("relay listening on port " + server.getLocalPort());
        if (cfg.viewerPort >= 0) {
            try {
                viewerServer = new ServerSocket();
                viewerServer.setReuseAddress(true);
                viewerServer.bind(new InetSocketAddress(cfg.viewerPort));
                Thread vt = new Thread(this::viewerAcceptLoop, "relay-viewer-accept");
                vt.setDaemon(true);
                vt.start();
                log("TV viewer endpoint (WebSocket) on port " + viewerServer.getLocalPort());
            } catch (IOException e) {
                viewerServer = null;
                viewerError = "The TV viewer port " + cfg.viewerPort + " could not be opened (" + e.getMessage() + "); phones still work.";
                log(viewerError);
            }
        }
    }

    /** The TV viewer WebSocket port, or -1 when the endpoint is off or could not be opened. */
    public int viewerPort() {
        return viewerServer == null ? -1 : viewerServer.getLocalPort();
    }

    public String viewerError() {
        return viewerError;
    }

    public int viewerCount() {
        synchronized (lock) {
            return viewers.size();
        }
    }

    public int port() {
        return server == null ? -1 : server.getLocalPort();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        try { if (viewerServer != null) viewerServer.close(); } catch (IOException ignored) { }
        synchronized (lock) { closeViewers(); }
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
        String token = Long.toHexString(rnd.nextLong()) + Long.toHexString(rnd.nextLong());
        boolean host, ready;
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
        boolean armed;
        long maxGapMs;
        String endReason = "";
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
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                Thread t = new Thread(() -> connection(s), "relay-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) log("accept failed: " + e);
            }
        }
    }

    private void connection(Socket s) {
        try {
            s.setSoTimeout(10000);
            InputStream in = new java.io.BufferedInputStream(s.getInputStream(), 8192);
            String first = Frames.readLine(in, 65536);
            if (first == null) { s.close(); return; }
            Map<String, Object> hello = Json.parseObject(first);
            if (!"hello".equals(Json.str(hello, "t", ""))) throw new IOException("no hello");
            String role = Json.str(hello, "role", "");
            if ("lobby".equals(role)) lobbyConnection(s, in, hello);
            else if ("engine".equals(role)) engineConnection(s, in, hello);
            else throw new IOException("unknown role " + role);
        } catch (Exception e) {
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
            if (isHost && (!s.getInetAddress().isLoopbackAddress() || hasHost())) {
                refuse(s, "Only the hosting phone can be the host.");
                return;
            }
            if (state != S_SETUP) {
                refuse(s, "A game is running on this host. Try again when it has ended.");
                return;
            }
            List<SetupModel.Team> teams = NetTeams.listFromJson(Json.list(hello, "teams"));
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
                String line = Frames.readLine(in, 1 << 20);
                if (line == null) break;
                me.lobbyRx = System.currentTimeMillis();
                handleLobby(me, Json.parseObject(line));
            }
        } catch (Exception e) {
            log("lobby " + me.name + ": " + e);
        }
        lobbyLost(me);
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
        String base = GameConfigAccess.clean(want, "Player");
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
                    List<SetupModel.Team> teams = NetTeams.listFromJson(Json.list(m, "teams"));
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
                        "teams", NetTeams.listToJson(q.teams), "build", q.build, "linked", q.linked, "dropped", q.dropped));
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
                String base = GameConfigAccess.cleanTeam(t.name, "Team " + (m.teams.size() + 1));
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
        } catch (IOException e) {
            log("engine link " + me.name + ": " + e.getMessage());
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
            g.armed = true;
            if (type == 'N') {
                if (msg.length >= 7) {
                    Digest d = new Digest();
                    d.seq = g.digests.size() + 1;
                    d.player = p.id;
                    d.checksum = Frames.hex(msg, 1, 4);
                    d.tick = ((msg[5] & 0xff) << 8) | (msg[6] & 0xff);
                    g.digests.add(d);
                }
            } else if (type == 'M' && g.mapDigest.isEmpty()) {
                g.mapDigest = new String(msg, 1, msg.length - 1, StandardCharsets.ISO_8859_1);
                g.mapDigestFrom = p.name;
            }
            appendAndForward(g, msg, p, after);
        }
        for (Runnable r : after) r.run();
    }

    /** Lock held. Appends to the log and offers to every other live engine. */
    private void appendAndForward(Game g, byte[] msg, Player from, List<Runnable> after) {
        byte[] f = Frames.frame(msg);
        g.log.write(f, 0, f.length);
        g.logHash.add(f);
        g.logMsgs++;
        if (!viewers.isEmpty()) {
            byte[] wf = Ws.frame(2, f);
            for (Viewer v : new ArrayList<>(viewers)) if (!v.out.offer(wf)) dropViewer(v, "too slow");
        }
        for (final Player q : players.values()) {
            if (q == from || q.dropped || q.finished || q.engineOut == null) continue;
            q.toHash.add(f);
            q.toMsgs++;
            if (!q.engineOut.offer(f)) {
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
            } else {
                dropPlayer(p, p.quit ? "the player quit" : "its engine link closed");
            }
            checkEnd();
        }
    }

    /** Lock held. Sends the unsynced team-gone message F<team> for each of the phone's teams (plan section 2.8). */
    private void dropPlayer(Player p, String why) {
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
        sendLobby(p, Json.obj("t", "dropped", "reason", "You were dropped from the game: " + why));
        if (state == S_STARTING) maybeOpenGate();
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
        endGame(game.events.isEmpty() ? "every engine finished" : "every engine is gone");
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
        s.events.addAll(g.events);
        s.endReason = reason;
        for (Player q : players.values()) {
            s.perPlayer.add("Phone " + q.name + ": sent to it " + q.toMsgs + " messages (hash " + q.toHash.hex()
                    + "), received from it " + q.fromRaw + " (" + q.fromKept + " forwarded, " + q.fromDropped + " not on the whitelist or not its team)"
                    + (q.dropped ? ", dropped" : q.finished ? ", finished" : ""));
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
        closeViewers();
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
            synchronized (lock) {
                if (now - lastPing > 5000) {
                    lastPing = now;
                    for (Player q : players.values()) sendLobby(q, Json.obj("t", "ping"));
                }
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
                if (g == null) continue;
                if (state == S_STARTING && now - g.startedAt > cfg.linkWaitMs) {
                    for (Player q : new ArrayList<>(players.values()))
                        if (!q.linked && !q.dropped) dropPlayer(q, "its engine did not connect in " + cfg.linkWaitMs / 1000 + " s");
                    if (state == S_STARTING) maybeOpenGate();
                    checkEnd();
                } else if (state == S_PLAYING && g.armed && now - g.lastKeptAt > cfg.silenceMs) {
                    silence(g, now);
                }
                if (!g.desyncReporters.isEmpty() && now - g.desyncSince > 5000) g.desyncReporters.clear();
            }
        }
    }

    /**
     * Lock held. Nothing has arrived from any phone for the silence limit. The current team's owner sends an
     * empty tick at least once a second, so someone is stalled. After an N the next owner is unknown to the
     * relay, so with exactly two phones it is the other one; otherwise the last sender is dropped.
     */
    private void silence(Game g, long now) {
        if (now - g.lastKeptAt > g.maxGapMs) g.maxGapMs = now - g.lastKeptAt;
        List<Player> live = new ArrayList<>();
        for (Player q : players.values()) if (q.linkOpen && !q.dropped && !q.finished) live.add(q);
        Player culprit = null;
        if (g.lastType == 'N') {
            for (Player q : live) if (q.id != g.lastSender) { if (culprit == null) culprit = q; else { culprit = null; break; } }
            if (culprit == null && live.size() > 2) {
                log("silence for " + cfg.silenceMs / 1000 + " s after a turn end with " + live.size()
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
        boolean closed;
    }

    private void viewerAcceptLoop() {
        while (running) {
            try {
                final Socket s = viewerServer.accept();
                s.setTcpNoDelay(true);
                Thread t = new Thread(() -> viewerConnection(s), "relay-viewer");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) log("viewer accept failed: " + e);
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
        try {
            s.setSoTimeout(10000);
            InputStream in = new java.io.BufferedInputStream(s.getInputStream(), 4096);
            String head = Ws.readHead(in);
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
                if (game == null || state == S_SETUP) {
                    os.write("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    os.flush();
                    s.close();
                    return;
                }
                os.write(Ws.handshakeResponse(key));
                os.flush();
                me.sock = s;
                me.from = String.valueOf(s.getRemoteSocketAddress());
                me.out = new Outbox("relay-viewer-out", 20000, 16 << 20, false, () -> viewerLost(me));
                me.out.attach(os);
                byte[] first = viewerBacklog();
                me.out.offer(Ws.frame(2, first));
                viewers.add(me);
                viewersEver++;
                game.events.add("TV viewer joined from " + me.from + " (first message " + first.length + " bytes)");
                log("viewer joined from " + me.from + ", first message " + first.length + " bytes, log " + game.logMsgs + " messages");
            }
            v = me;
            s.setSoTimeout(0);
            changed();
            Ws.readClient(in, pong -> me.out.offer(pong));
        } catch (Exception e) {
            log("viewer connection: " + e);
        }
        if (v != null) viewerLost(v);
        else try { s.close(); } catch (IOException ignored) { }
    }

    private void viewerLost(Viewer v) {
        synchronized (lock) {
            if (!viewers.remove(v)) return;
            v.out.close();
            try { v.sock.close(); } catch (IOException ignored) { }
            log("viewer " + v.from + " left");
        }
        changed();
    }

    /** Lock held. */
    private void dropViewer(Viewer v, String why) {
        if (!viewers.remove(v)) return;
        log("viewer " + v.from + " dropped: " + why);
        v.out.close();
        try { v.sock.close(); } catch (IOException ignored) { }
    }

    /** Lock held. Sends a close frame after everything queued and lets go of every viewer. */
    private void closeViewers() {
        for (Viewer v : new ArrayList<>(viewers)) {
            v.out.offer(Ws.frame(8, new byte[0]));
            v.out.closeWhenDrained();
            final Socket vs = v.sock;
            Thread t = new Thread(() -> {
                try { Thread.sleep(1000); vs.close(); } catch (Exception ignored) { }
            });
            t.setDaemon(true);
            t.start();
        }
        viewers.clear();
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

    // Small access helpers for package-private members of the app package -----------------------

    /** GameConfig keeps its cleaners package-private; this mirrors them for names typed by other phones. */
    static final class GameConfigAccess {
        static String clean(String s, String fallback) {
            String t = s == null ? "" : s.replaceAll("[\\p{Cc}\\u2028\\u2029]", " ").trim();
            int cps = t.codePointCount(0, t.length());
            if (cps > 32) t = t.substring(0, t.offsetByCodePoints(0, 32)).trim();
            return t.isEmpty() ? fallback : t;
        }

        static String cleanTeam(String s, String fallback) {
            return clean(s == null ? null : s.replaceAll("[\\\\/:*?\"<>|]", ""), fallback);
        }
    }
}
