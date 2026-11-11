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
import java.util.Iterator;
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
 *     {@code \0X<text>} the relay ends the game, {@code \0Q} the player confirmed leaving: same as the engine's Q (the seat stays away for
 *     {@link Config#rejoinMs}, the host's too), {@code \0Z} the host chose "End the game for everyone" (host's link only);
 *     {@code \0P} / {@code \0R} from the host's launcher: pause / resume the game, which the relay answers by sending
 *     {@code \0P} / {@code \0R} to every engine link. Accepted only from the host's link while the game runs; never forwarded
 *     as an engine message, never in the game log or the viewers' stream).</li>
 * <li>{@code {"t":"hello","role":"lobby","watch":true,...}}: a phone that only watches (no teams). It is a spectator, not a player: it
 *     has its own cap ({@link #MAX_SPECTATORS}), is never part of the map-digest, silence, dropout or desync rules, and cannot send
 *     game messages: whatever its engine link sends is ignored and logged (only {@code \0E}, {@code \0Q} and the finished message
 *     {@code q} about the spectator itself are read). It joins at the start, or at any time during a game: its engine link is answered
 *     with the whole log so far (its setup lines end with {@code e$spectate 1}, so the engine fast-forwards, as the TV's does), then the live stream.</li>
 * </ul>
 * Threads: an accept thread; one reader thread per connection; one writer thread per recipient (bounded
 * queue, so a slow recipient never blocks anyone: see {@link Outbox}); a watchdog thread (lobby pings, link
 * wait, silence rule, desync window).
 *
 * Forwarding: every whitelisted engine message (see {@link Frames}) goes, in arrival order and unchanged, to
 * every other engine and is appended to the log; the sender does not get its own messages back. When a phone
 * is lost the relay injects the unsynced team-gone message {@code F<team>} (never {@code f}) for each of its
 * teams to the others and into the log (plan section 2.8).
 *
 * Rejoining (0.3.0-tv14, docs/network.md): such a phone's seat stays "away" for {@link Config#rejoinMs}. The same phone (the rejoin token the relay gave
 * it at join, and its name) may come back: it is sent the setup again with every team driven from outside and {@code e$spectate 1}, then the whole log,
 * and its teams are handed back with the engine's own messages: the relay puts the unsynced {@code G<team>} into the log right after a live phone has
 * sent something from inside its turn, that phone's engine answers with the synced {@code g<team>}, and the rejoining engine (whose team has
 * {@code eowner <its nick>}) takes the team back. After the window the seat is given up. The host leaving does not end the game; only {@code \0Z} does.
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
    /** The end reason when every player is away and the last window has run out: nobody is left to play (docs/network.md, Rejoining). */
    public static final String NOBODY_LEFT = "Every player left the game and nobody came back";
    /** What a phone is told when it asks to come back after its window ran out. */
    public static final String GAVE_UP_TEXT = "You were away too long; the game went on without your teams. You can watch.";
    public static final int MAX_PLAYERS = 8, MAX_CONNECTIONS = 32, MAX_VIEWERS = 16;
    /** Phones that only watch (they play no team, so they are not players): their own cap, apart from MAX_PLAYERS. */
    public static final int MAX_SPECTATORS = 8;
    /** How long a watching phone's engine link stays open after a game that simply finished, so a phone that is still catching up can see the end. */
    private static final long WATCH_END_LINGER_MS = 120000;
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
        /** The host's simulation-compatibility string ({@link Compat#VALUE}); a joining phone must send exactly this. */
        public String compat = Compat.VALUE;
        /** The host's game data fingerprint ({@link DataDigest}); "" skips the data check (host tests without data). */
        public String dataDigest = "";
        /** How often the relay pings each engine link, and how long a link may be silent in a running game before its phone is dropped; 0 turns the check off. */
        public long linkPingMs = 5000;
        public long linkDeadMs = 20000;
        /**
         * Asked about once a second (outside the relay's lock) whether the host still has a LAN address; null turns the check off.
         * When it has been false for {@code networkLossMs} the running game is ended for everyone with a clear reason.
         */
        public java.util.function.BooleanSupplier networkUp;
        public long networkLossMs = 10000;
        /** Engine identification for the status GET (the app sets the engine commit), free text. */
        public String engine = "";
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
        /** The game password (exactly 4 digits) or null for none. The host can change it while hosting ({@link Relay#setPin}). */
        public String pin;
        /** Extra time before the first turn ends, on top of the silence limit (the first turn can start late on a slow phone). */
        public long graceMs = 40000;
        /** How long after the start gate a phone may take to send its map digest before it is dropped. */
        public long mapWaitMs = 180000;
        /** The longest the host's pause may last: after this the relay resumes everyone itself (the silence rule is off while paused). */
        public long pauseMaxMs = 600000;
        /**
         * How long a player's seat stays "away" after the connection was lost or the player left (the Back dialog's "Leave the game"):
         * its teams are gone (turns skipped) and the same phone may rejoin inside this time. After it the seat is given up. 0 turns rejoining off.
         */
        public long rejoinMs = 120000;
        /** A phone that has rejoined has this long to get its teams back (the relay asks the other engines for it) before the relay stops trying. */
        public long backWaitMs = 90000;
        /** How soon the relay asks again for a team to come back when the first ask did not take (no engine had a local team to play at that moment). */
        public long backResendMs = 2500;
        /** Write the away and back notices into the game as chat lines (the phones' engines show them); the lobby notices are always sent. */
        public boolean chatNotices = true;
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
    /** Phones that only watch. Ids come from the same counter as the players', so the lobby and the report can name either. */
    private final Map<Integer, Spectator> spectators = new LinkedHashMap<>();
    private int nextId = 1;
    private Game game;
    private GameSummary lastSummary;
    private int gameNo;
    private final Random rnd = new Random();
    private final SecureRandom secure = new SecureRandom();
    private final AtomicInteger connCount = new AtomicInteger(), viewerConns = new AtomicInteger();

    public Relay(Config cfg) {
        this.cfg = cfg;
        this.pin = isPin(cfg.pin) ? cfg.pin : null;
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

    /** TVs that polled the status GET in the last 10 s (they wait for a game to start): remote address to the time of its last poll. */
    private final Map<String, Long> pollers = new java.util.concurrent.ConcurrentHashMap<>();

    public int viewersWaiting() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Iterator<Map.Entry<String, Long>> it = pollers.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() <= 10000) n++; else it.remove();
        }
        return n;
    }

    /**
     * The value of query parameter {@code name} in an HTTP request line ("GET /?a=1&b=2 HTTP/1.1"), or null when it is absent.
     * The name must start right after the '?' or an '&', so "xcompat=" is not "compat=". The first occurrence wins.
     */
    public static String queryParam(String requestLine, String name) {
        if (requestLine == null) return null;
        int qm = requestLine.indexOf('?');
        if (qm < 0) return null;
        int end = requestLine.indexOf(' ', qm);
        String query = requestLine.substring(qm + 1, end < 0 ? requestLine.length() : end);
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name)) return part.substring(eq + 1);
        }
        return null;
    }

    /**
     * The body of the status GET on the viewer port. Keeps the fields of the e2e-host bridge (state, game, viewers, viewersEver)
     * and adds what the TV page reads: host (this phone's name), players (one object per phone in the lobby: name, teams, host),
     * compatible and reason (false when the TV page named a protocol in the request, ?proto=N, that this relay does not speak),
     * engine (the app version and the engine commit: informational), compat (string, {@link Compat#VALUE}) and protocol (number, the engine's
     * network protocol); the TV joins only when its own compat equals this exactly, and ?compat=X that differs makes compatible false.
     */
    String statusJson(String requestLine) {
        String reason = "";
        String pv = queryParam(requestLine, "proto");
        if (pv != null) {
            boolean digits = !pv.isEmpty() && pv.length() <= 9 && pv.chars().allMatch(Character::isDigit);
            if (!digits || Integer.parseInt(pv) != PROTO)
                reason = "The TV page speaks protocol " + (digits ? pv : "?") + ", this host speaks " + PROTO + ".";
        }
        String cv = queryParam(requestLine, "compat");
        if (cv != null && reason.isEmpty()) {
            String v = cv.length() > 64 ? cv.substring(0, 64) : cv;
            if (!v.equals(Compat.VALUE)) reason = "The TV page is \"" + v.replaceAll("[^A-Za-z0-9._-]", "?") + "\", this host is \"" + Compat.VALUE + "\": the games would not agree.";
        }
        synchronized (lock) {
            List<Object> pl = new ArrayList<>();
            for (Player p : players.values()) pl.add(Json.obj("name", p.name, "teams", (long) p.teams.size(), "host", p.host, "away", p.away));
            Map<String, Object> out = Json.obj("state", state == S_SETUP ? "setup" : "playing", "game", game == null ? 0L : (long) game.no,
                    "viewers", (long) viewers.size(), "viewersEver", (long) viewersEver, "watching", (long) spectators.size(),
                    "host", cfg.hostName, "players", pl, "compatible", reason.isEmpty(), "reason", reason,
                    "compat", Compat.VALUE, "protocol", (long) Compat.ENGINE_PROTOCOL, "pin", getPin() != null,
                    "engine", (cfg.build + (cfg.engine.isEmpty() ? "" : ", engine " + cfg.engine)).trim());
            if (lastGameEnded != null) out.put("lastGame", Json.obj("n", (long) lastGameNo, "ended", lastGameEnded, "atMs", lastGameAt));
            return Json.write(out);
        }
    }

    // Game password ------------------------------------------------------------------------------

    /** The 5th wrong or missing password from one address locks it for {@link #pinLockMs}. */
    public static final int PIN_MAX_WRONG = 5;
    /** Lock length in milliseconds. Not final so the tests can shorten it; the app never changes it. */
    public static volatile long pinLockMs = 30000;
    /** A monotonic clock for the password lock, in ms since this relay started (always positive; a changed phone clock does not matter). */
    private final long pinClock0 = System.nanoTime();
    private long pinNow() { return (System.nanoTime() - pinClock0) / 1_000_000L + 1; }
    private static final long PIN_FORGET_MS = 5 * 60000L;
    private static final int PIN_MAX_ADDRESSES = 512;
    static final int PIN_OK = 0, PIN_WRONG = 1, PIN_LOCKED = 2;

    private volatile String pin;
    private final Object pinGuard = new Object();
    /** Per source address: wrong attempts so far, the end of a lock and the last time it was seen. Guarded by pinGuard. */
    private final Map<String, long[]> pinTries = new LinkedHashMap<>();

    /** Sets the game password for connections from now on (null or "" turns it off). Anything that is not exactly 4 digits is refused. */
    public void setPin(String p) {
        if (p != null && p.isEmpty()) p = null;
        if (p != null && !isPin(p)) throw new IllegalArgumentException("The game password is exactly 4 digits.");
        pin = p;
        log(p == null ? "game password off" : "game password set");
    }

    /** The game password, or null when none is set. */
    public String getPin() {
        return pin;
    }

    /** True for exactly 4 ASCII digits. */
    public static boolean isPin(String p) {
        if (p == null || p.length() != 4) return false;
        for (int i = 0; i < 4; i++) if (p.charAt(i) < '0' || p.charAt(i) > '9') return false;
        return true;
    }

    /**
     * Checks a given password from {@code addr}. PIN_OK when no password is set or it is right (the address's count is cleared),
     * PIN_LOCKED while the address is locked (a lock is never extended, and a right password during it is refused too),
     * otherwise PIN_WRONG, counting the attempt: the 5th wrong one is itself answered wrong and locks the address for the next ones.
     */
    int checkPin(String addr, String given) {
        String want = pin;
        if (want == null) return PIN_OK;
        long now = pinNow();
        synchronized (pinGuard) {
            long[] t = pinTries.get(addr);
            if (t != null && t[1] > now) { t[2] = now; return PIN_LOCKED; }
            boolean right = given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), want.getBytes(StandardCharsets.UTF_8));
            if (right) {
                pinTries.remove(addr);
                return PIN_OK;
            }
            if (pinTries.size() >= PIN_MAX_ADDRESSES) prunePins(now);
            if (t == null) { t = new long[3]; pinTries.put(addr, t); }
            t[2] = now;
            if (++t[0] >= PIN_MAX_WRONG) { t[0] = 0; t[1] = now + pinLockMs; log("game password: " + addr + " locked for " + pinLockMs / 1000 + " s"); }
            return PIN_WRONG;
        }
    }

    /** pinGuard held. Forgets addresses not seen for a few minutes and not locked; if the map is still full, the oldest. */
    private void prunePins(long now) {
        for (Iterator<long[]> it = pinTries.values().iterator(); it.hasNext(); ) {
            long[] t = it.next();
            if (t[1] <= now && now - t[2] > PIN_FORGET_MS) it.remove();
        }
        while (pinTries.size() >= PIN_MAX_ADDRESSES) {
            Iterator<long[]> it = pinTries.values().iterator();
            it.next();
            it.remove();
        }
    }

    private static String addrOf(Socket s) {
        java.net.InetAddress a = s.getInetAddress();
        return a == null ? "?" : a.getHostAddress();
    }

    /** The lobby refusal text for a PIN_WRONG / PIN_LOCKED result, as {code, reason}. */
    private static String[] pinRefusal(int r) {
        return r == PIN_LOCKED
                ? new String[] { "password-locked", "Too many wrong passwords. Try again in " + pinLockMs / 1000 + " s." }
                : new String[] { "password", "Wrong password." };
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
        synchronized (lock) {
            if (game != null) noteLastGame(game.no, "stopped");
            closeViewers("the host stopped hosting", CLOSE_STOPPED, "relay-stopped");
        }
        List<Player> all;
        synchronized (lock) {
            all = new ArrayList<>(players.values());
        }
        for (Player p : all) {
            sendLobby(p, Json.obj("t", "closed", "reason", "The host stopped hosting."));
            p.closeAll(true);
        }
        List<Spectator> allSp;
        synchronized (lock) { allSp = new ArrayList<>(spectators.values()); }
        for (Spectator sp : allSp) {
            sendLobby(sp, Json.obj("t", "closed", "reason", "The host stopped hosting."));
            sp.closeAll(true);
        }
        synchronized (lock) {
            players.clear();
            spectators.clear();
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
        /** The engine link's token; a new one is made for each rejoin. */
        String token = newToken();
        /** Issued at join, sent to the phone, which keeps it: with the name it proves this is the same phone coming back (see {@link #rejoinRefusal}). */
        final String rejoinToken = newToken();
        boolean host, ready;
        /** Seat state in a running game (all under the lock): away = connection lost or left, inside the window; gaveUp = the window ran out. */
        boolean away, gaveUp, awayOnPurpose, rejoinPending, endAll;
        long awayAt, rejoinAt, linkUpAt;
        /** Set from the moment the engine link of a rejoining phone is up until its teams are back: what it sends is not forwarded. */
        boolean catchUp;
        /** Teams of this phone the relay still has to get back (it has not yet seen the synced team-back message for them), and when it last asked. */
        final List<String> backPending = new ArrayList<>();
        long lastBackSent;
        int rejoins;
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
        /** When anything last arrived on the engine link (a game message or a control frame, the pong included). */
        long lastRx = System.currentTimeMillis();
        // game statistics
        Fnv toHash = new Fnv();
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

    /**
     * A phone that only watches. It has a lobby link like a player and an engine link of its own, but no teams: it is not in
     * {@link #players}, so no rule that looks at players (start, map digest, silence, dropout, desync, game end) can see it.
     */
    private final class Spectator {
        final int id;
        String name;
        String token = newToken();
        String build = "";
        boolean fg = true;
        Socket lobbySock;
        Outbox lobbyOut;
        long lobbyRx = System.currentTimeMillis();
        // engine link (one per "start": a phone that stops watching and watches again gets a new token)
        Socket engineSock;
        Outbox engineOut;
        boolean tokenUsed, linkOpen, finished, quit, sessionOpen;
        long lastRx = System.currentTimeMillis();
        String fatal;
        long sessionStart;
        Fnv toHash = new Fnv();
        long toMsgs, ignored;
        final Map<Character, Integer> ignoredByType = new LinkedHashMap<>();

        Spectator(int id, String name) {
            this.id = id;
            this.name = name;
        }

        /** Closes this phone's engine link at once (the phone is being cut off, or starts again with a new token). */
        void closeEngineNow() {
            Outbox o = engineOut;
            Socket es = engineSock;
            engineOut = null;
            engineSock = null;
            linkOpen = false;
            if (o != null) o.close();
            try { if (es != null) es.close(); } catch (IOException ignored2) { }
        }

        /** After the game: let the last messages out, then close the engine link after {@code lingerMs}. */
        void closeEngineLater(long lingerMs) {
            final Socket es = engineSock;
            if (engineOut != null) engineOut.closeWhenDrained();
            engineOut = null;
            engineSock = null;
            if (es != null) {
                Thread t = new Thread(() -> {
                    try { Thread.sleep(lingerMs); es.close(); } catch (Exception ignored2) { }
                });
                t.setDaemon(true);
                t.start();
            }
        }

        void closeAll(boolean lobbToo) {
            closeEngineNow();
            if (lobbToo) {
                if (lobbyOut != null) lobbyOut.closeWhenDrained();
                final Socket ls = lobbySock;
                if (ls != null) {
                    Thread t = new Thread(() -> {
                        try { Thread.sleep(300); ls.close(); } catch (Exception ignored2) { }
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
        long lastLinkPing;
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
        /** When a live phone last sent a message from inside its own turn (not a turn end or a map digest) and which one: team-back is asked for only then. */
        long midTurnAt;
        int midTurnSender;
        /** The host's pause: while it lasts the silence and map-wait rules are off (nobody is sending anything, by design). */
        boolean paused;
        long pausedAt, pausedTotalMs;
        int pauseCount;
        /** One line for each TV viewer that has left (or was still watching at the end): round trip, queue depth, why it ended. */
        final List<String> viewerReport = new ArrayList<>();
        /** One line for each watching phone's engine link that has ended (or was still up at the end); and the events kept, as for the TVs. */
        final List<String> watcherReport = new ArrayList<>();
        int watcherReportDropped, watcherEventsKept, watcherEventsDropped;
        int watcherDesyncs;
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
        /** One line for each engine link of a watching phone (a phone that watched twice has two). */
        public final List<String> watchers = new ArrayList<>();
        public int watcherDesyncs;
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
            b.append("Compatibility: ").append(Compat.VALUE).append('\n');
            for (String s : perPlayer) b.append(s).append('\n');
            if (watchers.isEmpty()) b.append("Watching phones: none\n");
            else {
                b.append("Watching phones (they play no team and send no game messages; each saw the same stream as a player, desync reports from them: ").append(watcherDesyncs).append("):\n");
                for (String w : watchers) b.append("  ").append(w).append('\n');
            }
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
        refuse(s, reason, null);
    }

    /** As above with a machine-readable {@code code} in the message ("password", "password-locked"); null leaves it out. */
    private void refuse(Socket s, String reason, String code) {
        try {
            Map<String, Object> m = Json.obj("t", "refuse", "reason", reason);
            if (code != null) m.put("code", code);
            s.getOutputStream().write((Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            s.close();
        } catch (IOException ignored) { }
        log("refused " + s.getRemoteSocketAddress() + ": " + reason);
    }

    // Lobby -----------------------------------------------------------------------------------

    private void lobbyConnection(Socket s, InputStream in, Map<String, Object> hello) throws IOException {
        if (Json.bool(hello, "watch", false)) { spectatorLobby(s, in, hello); return; }
        if (!Json.str(hello, "rejoin", "").isEmpty()) { rejoinLobby(s, in, hello); return; }
        Player p;
        synchronized (lock) {
            if (Json.num(hello, "proto", 0) != PROTO) {
                refuse(s, "Protocol mismatch: the host speaks " + PROTO + ", this phone " + Json.num(hello, "proto", 0)
                        + ". Install the same app version on both phones.");
                return;
            }
            String why = joinRefusal(hello);
            if (why != null) { refuse(s, why); return; }
            boolean isHost = Json.bool(hello, "host", false);
            if (isHost && (!s.getInetAddress().isLoopbackAddress() || hasHost() || !secretOk(Json.str(hello, "secret", "")))) {
                refuse(s, "Only the hosting phone can be the host.");
                return;
            }
            if (!isHost) {
                int pr = checkPin(addrOf(s), Json.str(hello, "pin", ""));
                if (pr != PIN_OK) { String[] r = pinRefusal(pr); refuse(s, r[1], r[0]); return; }
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
        me.lobbyOut = new Outbox("relay-lobby-out-" + me.id, 200, 1 << 20, false, () -> lobbyLost(me, s));
        me.lobbyOut.attach(s.getOutputStream());
        log("lobby: " + me.name + " joined (id " + me.id + (me.host ? ", host" : "") + ", " + me.teams.size() + " teams) from "
                + s.getRemoteSocketAddress() + ", build " + me.build);
        // The phone keeps this (with its name) so that it can come back into a running game after a lost connection or a restart.
        sendLobby(me, Json.obj("t", "joined", "you", (long) me.id, "name", me.name, "rejoin", me.rejoinToken));
        broadcastState();
        lobbyReadLoop(me, s, in);
    }

    private void lobbyReadLoop(Player me, Socket s, InputStream in) {
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
        lobbyLost(me, s);
    }

    /** Why a phone with this hello may not join (different game version, app version or game data), or null. Naming both sides. */
    private String joinRefusal(Map<String, Object> hello) {
        String why = Compat.refusal(cfg.compat, cfg.build, cfg.dataDigest,
                Json.str(hello, "compat", ""), Json.str(hello, "build", ""), Json.str(hello, "digest", ""));
        if (why != null) log("join check failed: " + why);
        return why;
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
        for (Spectator q : spectators.values()) if (q.id != exceptId && q.name.equalsIgnoreCase(n)) return true;
        return false;
    }

    private void lobbyLost(Player p, Socket which) {
        boolean removed = false;
        synchronized (lock) {
            if (!players.containsKey(p.id)) return;
            // A phone that came back on a new lobby link: the end of the old link's reader or writer is not the end of the new one.
            if (p.lobbySock != which) { try { if (which != null) which.close(); } catch (IOException ignored) { } return; }
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
                    for (Spectator q : new ArrayList<>(spectators.values())) {
                        sendLobby(q, Json.obj("t", "closed", "reason", "The host left."));
                        q.closeAll(true);
                    }
                    spectators.clear();
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
                case "rejoin": {
                    // A phone whose lobby link is still up (it left the game on purpose with Back) asks for its seat back.
                    String[] why = rejoinRefusal(p, System.currentTimeMillis());
                    if (why != null) {
                        sendLobby(p, Json.obj("t", "rejoin-refused", "code", why[0], "reason", why[1]));
                        return;
                    }
                    startRejoin(p, System.currentTimeMillis());
                    break;
                }
                default:
                    return;
            }
        }
        broadcastState();
    }

    // Rejoining -------------------------------------------------------------------------------

    /** "2 minutes", "1 minute" or "4 s" for the notices. */
    private String windowText() {
        long ms = cfg.rejoinMs;
        if (ms >= 60000 && ms % 60000 == 0) return (ms / 60000) + (ms == 60000 ? " minute" : " minutes");
        return Math.max(1, ms / 1000) + " s";
    }

    /** Lock held. How many other phones are playing right now: they are the ones whose engines can hand a team back. */
    private int liveOthers(Player p) {
        int n = 0;
        for (Player q : players.values()) if (q != p && q.linkOpen && !q.dropped && !q.finished && !q.catchUp) n++;
        return n;
    }

    /** Lock held. Why this seat cannot be taken back now as {code, reason}, or null when it can. */
    private String[] rejoinRefusal(Player p, long now) {
        if (game == null || state == S_SETUP) return new String[] { "rejoin-none", "There is no game to come back to." };
        if (state != S_PLAYING || cfg.rejoinMs <= 0) return new String[] { "rejoin-none", "The game has not started playing yet. Try again in a moment." };
        if (p.away && now - p.awayAt > cfg.rejoinMs) giveUp(p);
        if (p.gaveUp) return new String[] { "rejoin-late", GAVE_UP_TEXT };
        if (p.finished) return new String[] { "rejoin-none", "Your game is over." };
        if (p.dropped && !p.away) return new String[] { "rejoin-none", "You were dropped from this game and cannot come back to it. You can watch." };
        if (liveOthers(p) == 0)
            return new String[] { "rejoin-alone", "Nobody else is playing at the moment, so your teams cannot come back. You can watch." };
        return null;
    }

    /** A new lobby connection that brings the rejoin token: the same phone coming back after its connection was lost, or the app was restarted. */
    private void rejoinLobby(Socket s, InputStream in, Map<String, Object> hello) throws IOException {
        final Player me;
        synchronized (lock) {
            if (Json.num(hello, "proto", 0) != PROTO) {
                refuse(s, "Protocol mismatch: the host speaks " + PROTO + ", this phone " + Json.num(hello, "proto", 0)
                        + ". Install the same app version on both phones.");
                return;
            }
            String why = joinRefusal(hello);
            if (why != null) { refuse(s, why); return; }
            byte[] tok = Json.str(hello, "rejoin", "").getBytes(StandardCharsets.UTF_8);
            Player p = null;
            for (Player q : players.values()) if (MessageDigest.isEqual(tok, q.rejoinToken.getBytes(StandardCharsets.UTF_8))) p = q;
            String nm = GameConfig.clean(Json.str(hello, "name", ""), "Player");
            if (p == null || p.host || Json.bool(hello, "host", false) || !p.name.equalsIgnoreCase(nm)) {
                // The same words for a wrong token, another name and a seat that no longer exists: nothing to learn about other seats.
                refuse(s, "This seat is not yours, or the game it belonged to is over.", "rejoin-unknown");
                return;
            }
            long now = System.currentTimeMillis();
            String[] no = rejoinRefusal(p, now);
            if (no != null) { refuse(s, no[1], no[0]); return; }
            me = p;
            Socket old = me.lobbySock;
            Outbox oldOut = me.lobbyOut;
            me.lobbySock = s;
            me.lobbyOut = new Outbox("relay-lobby-out-" + me.id, 200, 1 << 20, false, () -> lobbyLost(me, s));
            me.lobbyOut.attach(s.getOutputStream());
            me.lobbyRx = now;
            if (oldOut != null) oldOut.close();
            try { if (old != null) old.close(); } catch (IOException ignored) { }
            startRejoin(me, now);
        }
        s.setSoTimeout(0);
        log("lobby: " + me.name + " is back on a new lobby link from " + s.getRemoteSocketAddress());
        sendLobby(me, Json.obj("t", "joined", "you", (long) me.id, "name", me.name, "rejoin", me.rejoinToken));
        broadcastState();
        lobbyReadLoop(me, s, in);
    }

    /**
     * Lock held; {@link #rejoinRefusal} said yes. A phone that has not been noticed as gone yet (its old links are half open) is made away
     * first. Then it gets the start message of the running game: the same setup, every team driven from outside (so the engine replays the
     * game so far from the log, its own teams included), {@code e$spectate 1} to fast-forward, and a new token for its engine link. Its teams
     * come back once the engine link is up and an engine with a team of its own in play has answered the team-back message.
     */
    private void startRejoin(Player p, long now) {
        Game g = game;
        if (!p.away) dropPlayer(p, "it connected again from a new link", null, true, false);
        p.token = newToken();
        p.rejoinPending = true;
        p.rejoinAt = now;
        List<String> lines = new ArrayList<>(GameConfig.forRejoin(g.canonical, p.ownedIdx, p.name));
        lines.add("e$spectate 1");
        List<Object> out = new ArrayList<>(lines);
        sendLobby(p, Json.obj("t", "start", "you", (long) p.id, "token", p.token, "nick", p.name,
                "game", (long) g.no, "lines", out, "rejoin", true));
        g.events.add(p.name + " asked to rejoin (" + (now - p.awayAt) / 1000 + " s after it was lost); sent the game again with " + g.logMsgs + " messages to replay");
        log(p.name + " rejoining: start message sent, " + g.logMsgs + " messages to replay");
    }

    /** Lock held. The window ran out: the seat is given up for the rest of the game. */
    private void giveUp(Player p) {
        Game g = game;
        if (g == null || !p.away) return;
        p.away = false;
        p.gaveUp = true;
        p.rejoinPending = false;
        g.events.add(p.name + " did not come back within " + windowText() + "; their teams sit out");
        log(p.name + " did not come back within " + windowText());
        tellOthers(p, p.name + " did not come back; their teams sit out");
        sendLobby(p, Json.obj("t", "gaveup", "reason", GAVE_UP_TEXT));
        tvOverlay(Json.obj("t", "gaveup", "name", p.name));
        broadcastStateLocked();
    }

    /** Lock held. A notice for every other phone and watcher, also as a chat line inside the game. */
    private void tellOthers(Player except, String text) {
        for (Player q : players.values()) if (q != except) sendLobby(q, Json.obj("t", "notice", "text", text));
        for (Spectator q : spectators.values()) sendLobby(q, Json.obj("t", "notice", "text", text));
        injectChat(text);
    }

    /**
     * Lock held. Writes a chat line into the running game (message {@code s} of the engine protocol: text, then the two bytes of a timestamp
     * the engine cuts off). It goes through the log, so every engine, a TV and a late joiner show it. The launchers let it through (hp_ipc.c).
     */
    private void injectChat(String text) {
        Game g = game;
        if (g == null || !cfg.chatNotices || state != S_PLAYING) return;
        String t = "* " + text;
        if (t.length() > 200) t = t.substring(0, 200);
        byte[] tb = t.getBytes(StandardCharsets.UTF_8);
        byte[] f = new byte[tb.length + 3];
        f[0] = 's';
        System.arraycopy(tb, 0, f, 1, tb.length);
        List<Runnable> after = new ArrayList<>();
        appendAndForward(g, f, null, after);
        for (Runnable r : after) r.run();
    }

    private void sendLobby(Player p, Map<String, Object> m) {
        Outbox o = p.lobbyOut;
        if (o != null) o.offer((Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private void sendLobby(Spectator p, Map<String, Object> m) {
        Outbox o = p.lobbyOut;
        if (o != null) o.offer((Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** The state every connected phone sees (what the lobby screen shows). */
    public Map<String, Object> status(int forPlayer) {
        synchronized (lock) {
            List<Object> pl = new ArrayList<>();
            for (Player q : players.values()) {
                pl.add(Json.obj("id", (long) q.id, "name", q.name, "host", q.host, "ready", q.ready,
                        "teams", NetTeams.listToJson(q.teams), "build", q.build, "linked", q.linked, "dropped", q.dropped, "fg", q.fg,
                        "watching", false, "away", q.away, "gaveUp", q.gaveUp,
                        "awayLeftMs", q.away ? Math.max(0L, cfg.rejoinMs - (System.currentTimeMillis() - q.awayAt)) : 0L));
            }
            // Watching phones are listed after the players, marked "watching"; they have no teams and are always "ready".
            for (Spectator q : spectators.values()) {
                pl.add(Json.obj("id", (long) q.id, "name", q.name, "host", false, "ready", true,
                        "teams", new ArrayList<Object>(), "build", q.build, "linked", q.linkOpen, "dropped", false, "fg", q.fg,
                        "watching", true));
            }
            return Json.obj("t", "state", "state", state == S_SETUP ? "setup" : "playing", "host", cfg.hostName,
                    "proto", (long) PROTO, "build", cfg.build, "you", (long) forPlayer, "players", pl,
                    "canStart", canStart(), "game", game == null ? 0L : (long) game.no, "watching", (long) spectators.size());
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
        List<Spectator> allSp;
        synchronized (lock) { allSp = new ArrayList<>(spectators.values()); }
        for (Player q : all) sendLobby(q, status(q.id));
        for (Spectator q : allSp) sendLobby(q, status(q.id));
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
            final Outbox[] box = new Outbox[1];
            box[0] = new Outbox("relay-engine-out-" + q.id, 20000, 8 << 20, true, () -> engineWriteFailed(q, box[0]));
            q.engineOut = box[0];
            q.ready = q.host;
            q.away = q.gaveUp = q.rejoinPending = q.catchUp = q.endAll = q.awayOnPurpose = false;
            q.backPending.clear();
            q.rejoins = 0;
        }
        log("game " + g.no + " starting: " + g.teams.size() + " teams, " + players.size() + " phones");
        for (Player q : players.values()) {
            List<Object> lines = new ArrayList<>(GameConfig.forRecipient(g.canonical, q.ownedIdx));
            sendLobby(q, Json.obj("t", "start", "you", (long) q.id, "token", q.token, "nick", q.name,
                    "game", (long) g.no, "lines", lines));
        }
        for (Spectator q : new ArrayList<>(spectators.values())) startSpectator(q);
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
        for (Spectator q : spectators.values()) sendLobby(q, status(q.id));
        changed();
    }

    private static boolean containsIgnoreCase(List<String> l, String s) {
        for (String x : l) if (x.equalsIgnoreCase(s)) return true;
        return false;
    }

    // Watching phones -------------------------------------------------------------------------

    /** Lock held. Like {@link #addViewerEvent}: a phone that keeps coming and going must not grow the report without bound. */
    private void addWatchEvent(Game g, String ev) {
        g.events.add(ev);
        if (++g.watcherEventsKept > MAX_VIEWER_LINES) {
            for (int i = 0; i < g.events.size(); i++) {
                if (g.events.get(i).startsWith("Watcher ")) { g.events.remove(i); break; }
            }
            g.watcherEventsKept--;
            g.watcherEventsDropped++;
        }
    }

    private void spectatorLobby(Socket s, InputStream in, Map<String, Object> hello) throws IOException {
        Spectator sp;
        synchronized (lock) {
            if (Json.num(hello, "proto", 0) != PROTO) {
                refuse(s, "Protocol mismatch: the host speaks " + PROTO + ", this phone " + Json.num(hello, "proto", 0)
                        + ". Install the same app version on both phones.");
                return;
            }
            String why = joinRefusal(hello);
            if (why != null) { refuse(s, why); return; }
            if (Json.bool(hello, "host", false)) {
                refuse(s, "The hosting phone cannot be a watcher.");
                return;
            }
            int pr = checkPin(addrOf(s), Json.str(hello, "pin", ""));
            if (pr != PIN_OK) { String[] r = pinRefusal(pr); refuse(s, r[1], r[0]); return; }
            if (spectators.size() >= MAX_SPECTATORS) {
                refuse(s, "The host has enough watchers: at most " + MAX_SPECTATORS + " phones can watch.");
                return;
            }
            sp = new Spectator(nextId++, uniqueName(Json.str(hello, "name", "Watcher"), -1));
            sp.build = Json.str(hello, "build", "");
            sp.lobbySock = s;
            spectators.put(sp.id, sp);
        }
        s.setSoTimeout(0);
        final Spectator me = sp;
        me.lobbyOut = new Outbox("relay-lobby-out-" + me.id, 200, 1 << 20, false, () -> spectatorLobbyLost(me));
        me.lobbyOut.attach(s.getOutputStream());
        log("lobby: " + me.name + " joined as a watcher (id " + me.id + ") from " + s.getRemoteSocketAddress() + ", build " + me.build);
        broadcastState();
        // A watcher may join while a game runs: it gets the game at once.
        synchronized (lock) {
            if (state != S_SETUP && game != null && spectators.containsKey(me.id)) startSpectator(me);
        }
        try {
            for (;;) {
                String line = Frames.readLine(in, MAX_LOBBY_LINE);
                if (line == null) break;
                me.lobbyRx = System.currentTimeMillis();
                handleSpectatorLobby(me, Json.parseObject(line));
            }
        } catch (Throwable e) {
            log("lobby " + me.name + ": " + e);
        }
        spectatorLobbyLost(me);
    }

    private void spectatorLobbyLost(Spectator sp) {
        boolean removed = false;
        synchronized (lock) {
            if (!spectators.containsKey(sp.id)) return;
            sp.lobbyOut.close();
            try { if (sp.lobbySock != null) sp.lobbySock.close(); } catch (IOException ignored) { }
            sp.lobbySock = null;
            // While its engine link is up the phone goes on watching; the end of that link (or of the game) removes it.
            if (!sp.linkOpen) {
                if (game != null) retireWatchLink(sp, "its lobby link closed");
                spectators.remove(sp.id);
                sp.closeEngineNow();
                removed = true;
            }
        }
        if (removed) {
            log("lobby: watcher " + sp.name + " left");
            broadcastState();
        }
    }

    private void handleSpectatorLobby(Spectator sp, Map<String, Object> m) {
        String t = Json.str(m, "t", "");
        synchronized (lock) {
            switch (t) {
                case "fg":
                    sp.fg = Json.bool(m, "on", true);
                    break;
                case "name":
                    if (state != S_SETUP) return;
                    sp.name = uniqueName(Json.str(m, "name", sp.name), sp.id);
                    break;
                case "watch":
                    // "Watch again": a phone that stopped watching (or whose engine link ended) gets the running game afresh.
                    if (state != S_SETUP && game != null && !sp.linkOpen) startSpectator(sp);
                    return;
                case "start":
                    sendLobby(sp, Json.obj("t", "notice", "text", "Only the host can start the game."));
                    return;
                case "teams":
                case "ready":
                    sendLobby(sp, Json.obj("t", "notice", "text", "A watching phone has no teams to ready. Leave and join with Play to take part."));
                    return;
                case "ping":
                    sendLobby(sp, Json.obj("t", "pong"));
                    return;
                default:
                    return;
            }
        }
        broadcastState();
    }

    /**
     * Lock held; a game exists and is not in setup. Sends the watcher the game: the canonical setup lines with every team driven
     * from outside (the phone owns none), a new single-use token for its engine link.
     */
    private void startSpectator(Spectator sp) {
        Game g = game;
        if (g == null) return;
        sp.closeEngineNow();
        retireWatchLink(sp, "it started again");
        sp.token = newToken();
        sp.tokenUsed = sp.linkOpen = sp.finished = sp.quit = false;
        sp.fatal = null;
        List<Object> lines = new ArrayList<>(GameConfig.forRecipient(g.canonical, new boolean[g.teams.size()]));
        // Always asked for, also at the start: whatever has been said by the time its engine link is up is replayed fast, then it plays live.
        lines.add("e$spectate 1");
        sendLobby(sp, Json.obj("t", "start", "you", (long) sp.id, "token", sp.token, "nick", sp.name,
                "game", (long) g.no, "lines", lines, "watch", true));
        addWatchEvent(g, "Watcher " + sp.name + " was sent the game (" + (g.log.size() > 0 ? "late: it catches up with " + g.logMsgs + " messages" : "from the start") + ")");
        log("watcher " + sp.name + " sent the game " + g.no + " (" + g.logMsgs + " messages so far)");
    }

    private static String typeList(Map<Character, Integer> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Character, Integer> e : m.entrySet()) {
            char c = e.getKey();
            b.append(b.length() == 0 ? "" : ", ").append(c > 0xff ? "control " + Frames.typeName(c - 0x100) : Frames.typeName(c)).append(" x").append(e.getValue());
        }
        return b.toString();
    }

    /** Lock held. Closes the books on this phone's engine link for the report; nothing if it had none. */
    private void retireWatchLink(Spectator sp, String why) {
        Game g = game;
        if (!sp.sessionOpen) return;
        sp.sessionOpen = false;
        if (g == null) return;
        StringBuilder b = new StringBuilder(sp.name).append(": watched for ").append((System.currentTimeMillis() - sp.sessionStart) / 1000)
                .append(" s, sent to it ").append(sp.toMsgs).append(" messages (hash ").append(sp.toHash.hex()).append(")");
        if (sp.ignored > 0) b.append(", it tried to send ").append(sp.ignored).append(" messages, all ignored (").append(typeList(sp.ignoredByType)).append(")");
        else b.append(", it sent no game messages");
        if (sp.fatal != null) b.append(", engine error: ").append(sp.fatal);
        b.append("; ended: ").append(why);
        g.watcherReport.add(b.toString());
        if (g.watcherReport.size() > MAX_VIEWER_LINES) { g.watcherReport.remove(0); g.watcherReportDropped++; }
    }

    private void spectatorEngineConnection(Socket s, InputStream in, Spectator sp) throws IOException {
        synchronized (lock) {
            if (game == null || state == S_SETUP || sp.linkOpen || sp.tokenUsed || !spectators.containsKey(sp.id)) {
                refuse(s, "No game is waiting for this engine.");
                return;
            }
            sp.tokenUsed = true;
            sp.engineSock = s;
        }
        s.setSoTimeout(0);
        OutputStream os = s.getOutputStream();
        os.write((Json.write(Json.obj("t", "ok", "game", (long) game.no)) + "\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
        final Spectator me = sp;
        synchronized (lock) {
            Game g = game;
            if (g == null || !spectators.containsKey(me.id)) { try { s.close(); } catch (IOException ignored) { } return; }
            me.linkOpen = true;
            me.sessionOpen = true;
            me.lastRx = System.currentTimeMillis();
            me.sessionStart = System.currentTimeMillis();
            me.toHash = new Fnv();
            me.toMsgs = me.ignored = 0;
            me.ignoredByType.clear();
            me.fatal = null;
            me.engineOut = new Outbox("relay-watch-out-" + me.id, cfg.viewerQueueItems, 16 << 20, false, () -> spectatorWriteFailed(me));
            me.engineOut.attach(os);
            boolean ok = true;
            if (g.log.size() > 0) {
                // Joined late: the whole log so far. The phone's engine was told e$spectate 1 by its setup lines (a local command: the
                // launcher drops an 'e' frame from the relay), so it fast-forwards until it catches up and then plays on live.
                // Snapshot and registration happen under this one lock, so nothing is lost or doubled.
                byte[] all = g.log.toByteArray();
                for (int off = 0; ok && off < all.length; off += 32768) {
                    int n = Math.min(32768, all.length - off);
                    ok = me.engineOut.offer(java.util.Arrays.copyOfRange(all, off, off + n));
                }
                me.toHash.add(all);
                me.toMsgs += g.logMsgs;
            }
            if (g.paused) me.engineOut.offer(Frames.frame(new byte[] { 0, 'P' }));
            log("watcher engine link up: " + me.name + ", backlog " + g.log.size() + " bytes");
            if (!ok) dropSpectator(me, "the game so far is too large to send it");
        }
        broadcastState();
        try {
            for (;;) {
                byte[] msg = Frames.read(in);
                if (msg == null) break;
                onSpectatorMessage(me, msg);
            }
        } catch (Throwable e) {
            log("watcher engine link " + me.name + ": " + e);
        }
        spectatorEngineClosed(me);
    }

    private void spectatorWriteFailed(Spectator sp) {
        synchronized (lock) {
            if (game == null || !sp.linkOpen) return;
            log("writing to watcher " + sp.name + " failed");
            dropSpectator(sp, "the link to it failed");
        }
    }

    /** Lock held. Cuts the watcher's engine link only; the game and the other phones never notice. The phone can watch again. */
    private void dropSpectator(Spectator sp, String why) {
        Game g = game;
        if (g == null || !sp.linkOpen) return;
        retireWatchLink(sp, why);
        addWatchEvent(g, "Watcher " + sp.name + " dropped (" + why + ")");
        log("watcher " + sp.name + " dropped (" + why + ")");
        sp.closeEngineNow();
        sendLobby(sp, Json.obj("t", "dropped", "reason", "You stopped watching: " + why + "."));
        broadcastStateLocked();
    }

    /** What a watching phone's engine link may say: nothing that reaches the game. Only the phone's own state is read. */
    private void onSpectatorMessage(Spectator sp, byte[] msg) {
        synchronized (lock) {
            Game g = game;
            if (g == null || !sp.linkOpen || msg.length == 0) return;
            sp.lastRx = System.currentTimeMillis();
            if (msg[0] == 0) {
                if (msg.length == 2 && msg[1] == 'K') {
                    return;                      // the answer to the relay's link ping
                } else if (msg.length > 1 && msg[1] == 'E') {
                    sp.fatal = new String(msg, 2, msg.length - 2, StandardCharsets.UTF_8);
                    addWatchEvent(g, "Watcher " + sp.name + " reported an engine error: " + sp.fatal);
                    log("watcher " + sp.name + " engine error: " + sp.fatal);
                    if (sp.fatal.toLowerCase().contains("desync")) g.watcherDesyncs++;
                } else if (msg.length > 1 && msg[1] == 'Q') {
                    sp.quit = true;
                    log("watcher " + sp.name + " stopped watching (control frame)");
                } else {
                    String what = msg.length == 2 && msg[1] == 'P' ? "pause" : msg.length == 2 && msg[1] == 'R' ? "resume" : "control frame " + Frames.typeName(msg.length > 1 ? msg[1] : 0);
                    noteIgnored(g, sp, (char) (0x100 + (msg.length > 1 ? msg[1] & 0xff : 0)),   // control frames count apart from game messages of the same letter
                             "asked to " + what + ": ignored, a watching phone has no say");
                }
                return;
            }
            char type = (char) (msg[0] & 0xff);
            if (type == 'q') { sp.finished = true; return; }            // its own engine saw the game over
            if (type == 'Q' || type == 'H') { sp.quit = true; return; }  // its own engine's quit: it stops watching
            noteIgnored(g, sp, type, "sent a '" + Frames.typeName(type) + "' message (" + msg.length + " bytes): ignored, a watching phone cannot send game messages");
        }
    }

    /** Lock held. Counts and logs a message from a watcher that is thrown away; the first of each type is also an event in the report. */
    private void noteIgnored(Game g, Spectator sp, char type, String what) {
        sp.ignored++;
        boolean first = sp.ignoredByType.merge(type, 1, Integer::sum) == 1;
        if (first) {
            addWatchEvent(g, "Watcher " + sp.name + " " + what);
            log("watcher " + sp.name + " " + what);
        } else if (sp.ignored <= 50) {
            log("watcher " + sp.name + " " + what);
        }
    }

    private void spectatorEngineClosed(Spectator sp) {
        boolean removed = false;
        synchronized (lock) {
            if (!sp.linkOpen) return;              // dropped or reset by the relay already
            sp.linkOpen = false;
            Game g = game;
            if (g != null) {
                String why = sp.finished ? "it saw the game over" : sp.fatal != null ? "engine error: " + sp.fatal : sp.quit ? "it stopped watching" : "its engine link closed";
                retireWatchLink(sp, why);
                addWatchEvent(g, "Watcher " + sp.name + " " + (sp.finished ? "finished watching" : sp.quit ? "stopped watching" : "lost its engine link"));
            }
            sp.closeEngineNow();
            if (sp.lobbySock == null && spectators.remove(sp.id) != null) removed = true;
            else broadcastStateLocked();
        }
        if (removed) broadcastState();
    }

    // Engine links ----------------------------------------------------------------------------

    private void engineConnection(Socket s, InputStream in, Map<String, Object> hello) throws IOException {
        Player p = null;
        Spectator watcher = null;
        synchronized (lock) {
            String token = Json.str(hello, "token", "");
            for (Spectator q : spectators.values()) if (q.token.equals(token)) watcher = q;
        }
        if (watcher != null) { spectatorEngineConnection(s, in, watcher); return; }
        synchronized (lock) {
            String token = Json.str(hello, "token", "");
            for (Player q : players.values()) if (q.token.equals(token)) p = q;
            if (p == null || game == null || state == S_SETUP || p.linkOpen || (p.dropped && !p.rejoinPending)) {
                refuse(s, "No game is waiting for this engine.");
                return;
            }
            p.engineSock = s;
        }
        final Socket sock = s;
        s.setSoTimeout(0);
        OutputStream os = s.getOutputStream();
        os.write((Json.write(Json.obj("t", "ok", "game", (long) game.no)) + "\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
        final Player me = p;
        synchronized (lock) {
            if (me.rejoinPending && me.engineSock == sock) {
                attachRejoined(me, os);
            } else if (me.engineSock != sock) {
                return;                  // overtaken by a newer link while the ok was being written
            } else {
                me.linked = me.linkOpen = true;
                me.lastMsg = System.currentTimeMillis();
                me.lastRx = me.lastMsg;
                me.engineOut.attach(os);
                log("engine link up: " + me.name);
                maybeOpenGate();
            }
        }
        broadcastState();
        try {
            for (;;) {
                byte[] msg = Frames.read(in);
                if (msg == null) break;
                onEngineMessage(me, msg, sock);
            }
        } catch (Throwable e) {
            log("engine link " + me.name + ": " + e);
        }
        engineClosed(me, sock);
    }

    /**
     * Lock held. The engine link of a phone that rejoins is up. It gets the whole log so far in one go (as a watcher does; its setup lines
     * ended with e$spectate 1, so its engine fast-forwards) and then the live stream. What it sends is not forwarded until its teams are back
     * ({@link #tryInjectBack}, {@link #confirmBack}): until then it is a viewer of its own game. Snapshot and registration happen under the
     * lock, so nothing is lost or doubled.
     */
    private void attachRejoined(Player me, OutputStream os) {
        Game g = game;
        long now = System.currentTimeMillis();
        long awayFor = now - me.awayAt;
        me.rejoinPending = false;
        me.away = false;
        me.gaveUp = false;
        me.dropped = false;
        me.linked = me.linkOpen = true;
        me.finished = me.quit = me.endAll = false;
        me.fatal = null;
        me.lastMsg = me.lastRx = now;
        me.linkUpAt = now;
        me.rejoins++;
        me.toHash = new Fnv();
        me.toMsgs = 0;
        final Outbox[] box = new Outbox[1];
        box[0] = new Outbox("relay-engine-out-" + me.id + "-r" + me.rejoins, cfg.viewerQueueItems, 16 << 20, false, () -> engineWriteFailed(me, box[0]));
        me.engineOut = box[0];
        me.engineOut.attach(os);
        boolean ok = true;
        if (g.log.size() > 0) {
            byte[] all = g.log.toByteArray();
            for (int off = 0; ok && off < all.length; off += 32768) {
                int n = Math.min(32768, all.length - off);
                ok = me.engineOut.offer(java.util.Arrays.copyOfRange(all, off, off + n));
            }
            me.toHash.add(all);
            me.toMsgs += g.logMsgs;
        }
        if (g.paused) me.engineOut.offer(Frames.frame(new byte[] { 0, 'P' }));
        me.backPending.clear();
        me.backPending.addAll(me.teamNames);
        me.catchUp = !me.backPending.isEmpty();
        if (!me.catchUp) tvOverlay(Json.obj("t", "back", "name", me.name));      // no team to wait for
        me.lastBackSent = 0;
        g.events.add(me.name + " rejoined after " + awayFor / 1000 + " s; its engine replays " + g.logMsgs + " messages");
        log("engine link up (rejoin): " + me.name + ", backlog " + g.log.size() + " bytes, away for " + awayFor / 1000 + " s");
        if (!ok) { dropPlayer(me, "the game so far is too large to send it", "The game so far is too large to send to your phone.", false, false); checkEnd(); return; }
        tellOthers(me, me.name + " is back");
        updateArmed(g, now);
        broadcastStateLocked();
        List<Runnable> after = new ArrayList<>();
        tryInjectBack(g, now, after);
        for (Runnable r : after) r.run();
    }

    /**
     * Lock held. Asks for the teams of rejoined phones to come back: the unsynced team-back message {@code G<team>} goes into the log, so every
     * engine reads it at the same place. The engine that has a team of its own in play answers with the synced {@code g}, which hands the team to
     * the phone it belongs to (uTeams.pas, chTeamBack). While every team in play is a team that is gone nobody can answer, so this is done only
     * right after a live phone sent something from inside its own turn, and asked again after {@link Config#backResendMs} if no {@code g} came.
     */
    private void tryInjectBack(Game g, long now, List<Runnable> after) {
        // The give-up check runs on every call, not only when a turn message arrived, so a TV is never left showing "away".
        for (Player q : new ArrayList<>(players.values())) {
            if (!q.linkOpen || q.dropped || q.backPending.isEmpty() || now - q.linkUpAt <= cfg.backWaitMs) continue;
            g.events.add(q.name + "'s teams did not come back within " + cfg.backWaitMs / 1000 + " s; the relay stopped asking");
            log(q.name + "'s teams did not come back; giving up asking");
            q.backPending.clear();
            q.catchUp = false;
            tvOverlay(Json.obj("t", "gaveup", "name", q.name));
        }
        if (state != S_PLAYING || g.paused || now - g.midTurnAt > 700) return;
        Player s = players.get(g.midTurnSender);
        if (s == null || !s.linkOpen || s.dropped || s.finished || s.catchUp) return;
        for (Player q : new ArrayList<>(players.values())) {
            if (q == s || !q.linkOpen || q.dropped || q.backPending.isEmpty()) continue;
            if (q.lastBackSent != 0 && now - q.lastBackSent < cfg.backResendMs) continue;
            // backPending holds only the teams for which no synced g has been seen, so a team that is back is never asked for again.
            boolean first = q.lastBackSent == 0;
            q.lastBackSent = now;
            for (String team : q.backPending) {
                byte[] n = team.getBytes(StandardCharsets.UTF_8);
                byte[] f = new byte[n.length + 1];
                f[0] = 'G';
                System.arraycopy(n, 0, f, 1, n.length);
                if (first) g.events.add("injected G " + team + " (unsynced team back, no timestamp)");
                appendAndForward(g, f, null, after);
            }
            log("asked for " + q.name + "'s teams to come back" + (first ? "" : " (again)"));
        }
    }

    /** Lock held. A synced team-back message {@code g<team>} came from an engine: that team is back. */
    private void confirmBack(Game g, String team) {
        for (Player q : players.values()) {
            if (!q.backPending.remove(team)) continue;
            g.events.add(q.name + "'s team " + team + " is back (synced team-back seen)");
            if (q.backPending.isEmpty()) {
                q.catchUp = false;
                log(q.name + "'s teams are back in the game");
                tvOverlay(Json.obj("t", "back", "name", q.name));
            }
        }
    }

    private void engineWriteFailed(Player p, Outbox which) {
        synchronized (lock) {
            if (game == null || p.dropped || p.engineOut != which) return;
            log("writing to " + p.name + " failed");
            dropPlayer(p, "the link to it failed", "You were dropped from the game: the link to it failed.", true, false);
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
        for (Player q : players.values()) q.lastRx = game.gateOpenAt;
        for (Spectator q : spectators.values()) q.lastRx = game.gateOpenAt;
        game.lastLinkPing = game.gateOpenAt;
        for (Player q : players.values()) if (q.engineOut != null) q.engineOut.release();
        log("all engine links up; game " + game.no + " running");
        updateArmed(game, game.gateOpenAt);
        broadcastStateLocked();
    }

    // Forwarding ------------------------------------------------------------------------------

    private void onEngineMessage(Player p, byte[] msg, Socket from) {
        List<Runnable> after = new ArrayList<>();
        synchronized (lock) {
            Game g = game;
            if (g == null || p.dropped || p.engineSock != from) return;
            p.lastRx = System.currentTimeMillis();
            if (msg.length == 0) return;
            if (msg[0] == 0) {
                // control frame from the phone
                if (msg.length == 2 && msg[1] == 'K') {
                    return;                      // the answer to the relay's link ping: it only shows the phone is alive
                } else if (msg.length > 1 && msg[1] == 'E') {
                    p.fatal = new String(msg, 2, msg.length - 2, StandardCharsets.UTF_8);
                    g.events.add(p.name + " reported an engine error: " + p.fatal);
                    log(p.name + " engine error: " + p.fatal);
                    if (p.fatal.toLowerCase().contains("desync")) noteDesync(p);
                } else if (msg.length > 1 && msg[1] == 'Q') {
                    // The player confirmed "leave the game" in the app: the same as the engine's own Q, but sent by the
                    // launcher, so it does not depend on the engine's UI state (an open chat swallows Escape and Y).
                    // The seat stays away for the rejoin window, the host's too: the game goes on.
                    p.quit = true;
                    log(p.name + " confirmed leaving the game (control frame)");
                } else if (msg.length > 1 && msg[1] == 'Z') {
                    // The host chose "End the game for everyone" (its engine quits next; engineClosed ends the game then).
                    if (p.host) {
                        p.quit = p.endAll = true;
                        log(p.name + " (the host) confirmed ending the game for everyone (control frame)");
                    } else {
                        g.events.add(p.name + " asked to end the game for everyone: ignored (only the host can)");
                        log(p.name + " asked to end the game for everyone: ignored, only the host can");
                    }
                } else if (msg.length == 2 && (msg[1] == 'P' || msg[1] == 'R')) {
                    pauseControl(g, p, msg[1] == 'P', System.currentTimeMillis());
                }
                return;
            }
            char type = (char) (msg[0] & 0xff);
            p.fromRaw++;
            g.rawByType.merge(type, 1L, Long::sum);
            if (type == 'q') { p.finished = true; return; }
            if (type == 'Q' || type == 'H') { p.quit = true; return; }
            if (p.catchUp) {
                // A phone that has rejoined is still replaying the game and its teams are not back: it has nothing to say to the game yet.
                // Its map digest is checked against the game's (a different map would mean different data), the rest is ignored.
                if (type == 'M') {
                    String d = new String(msg, 1, msg.length - 1, StandardCharsets.ISO_8859_1);
                    if (!g.mapDigest.isEmpty() && !d.equals(g.mapDigest)) {
                        g.events.add(p.name + "'s map digest after rejoining (" + d + ") differs from the game's (" + g.mapDigest + ")");
                        dropPlayer(p, "its map digest after rejoining differs from the game's", "Your phone made a different map; it cannot rejoin this game.", false, false);
                        checkEnd();
                    } else {
                        g.mSeen.add(p.id);      // a rejoiner must not hold back arming of the silence rule
                        updateArmed(g, System.currentTimeMillis());
                    }
                } else {
                    p.fromDropped++;
                }
                return;
            }
            if (!Frames.valid(msg, p.ownedIdx)) {
                p.fromDropped++;
                return;
            }
            p.fromKept++;
            p.lastMsg = System.currentTimeMillis();     // only a kept game message counts as the phone being alive for the silence rule
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
            if (type == 'g' && msg.length > 3) confirmBack(g, new String(msg, 1, msg.length - 3, StandardCharsets.UTF_8));
            appendAndForward(g, msg, p, after);
            if (type == 'M') updateArmed(g, p.lastMsg);
            if (impliesOwnTurn(type)) {
                g.midTurnAt = p.lastMsg;
                g.midTurnSender = p.id;
                tryInjectBack(g, p.lastMsg, after);
            }
        }
        for (Runnable r : after) r.run();
    }

    /**
     * Message types only the phone that has the current turn sends: movement, aim, precise aim and attack. Not chat, turn end or
     * digests, and not taunt (t), weapon (w) or timer (1 to 5), which the engine also sends from chat commands on any phone.
     */
    private static boolean impliesOwnTurn(char type) {
        return "LlRrUuDdAaSjJ,pZz".indexOf(type) >= 0;
    }

    /**
     * Lock held. The host's launcher asks to pause or resume. Only the host's own engine link may (a joiner's request is
     * logged and ignored), only while the game runs. The relay answers every engine link with the same control frame, host
     * included, so all phones pause the same way and the host's engine does not pause on its own tap.
     */
    private void pauseControl(Game g, Player p, boolean pause, long now) {
        if (!p.host || state != S_PLAYING) {
            String why = !p.host ? "only the host can pause" : "the game is not running";
            g.events.add(p.name + " asked to " + (pause ? "pause" : "resume") + " the game: ignored (" + why + ")");
            log(p.name + " asked to " + (pause ? "pause" : "resume") + ": ignored, " + why);
            return;
        }
        if (pause == g.paused) return;
        if (pause) {
            g.paused = true;
            g.pausedAt = now;
            g.pauseCount++;
            g.events.add(p.name + " (the host) paused the game");
            log(p.name + " (the host) paused the game; the silence rule is off until it resumes (at most " + cfg.pauseMaxMs / 1000 + " s)");
        } else {
            endPause(g, now, p.name + " (the host) resumed the game");
        }
        broadcastPause(pause);
    }

    /** Lock held. Ends the pause in the relay's books: the silence and map-wait clocks start again from now. */
    private void endPause(Game g, long now, String what) {
        long len = now - g.pausedAt;
        g.paused = false;
        g.pausedTotalMs += len;
        g.lastKeptAt = now;
        g.gateOpenAt += len;
        g.events.add(what + " after " + len / 1000 + " s");
        log(what + " after " + len / 1000 + " s");
    }

    /** Lock held. Control frame {@code \0P} or {@code \0R} to every live engine link. */
    private void broadcastPause(boolean pause) {
        byte[] f = Frames.frame(new byte[] { 0, (byte) (pause ? 'P' : 'R') });
        for (Player q : players.values()) {
            if (q.dropped || q.finished || q.engineOut == null) continue;
            q.engineOut.offer(f);
        }
        for (Spectator q : spectators.values()) {
            if (!q.linkOpen || q.engineOut == null) continue;
            q.engineOut.offer(f);
        }
    }

    /** Lock held. A pause nobody can end (the host is gone) is ended by the relay. */
    private void resumeIfHostGone(Game g) {
        if (!g.paused) return;
        for (Player q : players.values()) if (q.host && !q.dropped && !q.finished && q.linkOpen) return;
        endPause(g, System.currentTimeMillis(), "The host is gone: the relay resumed the game");
        broadcastPause(false);
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
        for (final Spectator sp : spectators.values()) {
            if (!sp.linkOpen || sp.engineOut == null) continue;
            sp.toHash.add(f);
            sp.toMsgs++;
            if (!sp.engineOut.offer(f)) {
                after.add(() -> {
                    synchronized (lock) {
                        if (game != null) dropSpectator(sp, "too slow: its queue overflowed");
                    }
                });
            }
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
                            dropPlayer(q, "too slow: its queue overflowed", "You were dropped from the game: too slow: its queue overflowed." + rejoinHint(), true, false);
                            checkEnd();
                        }
                    }
                });
            }
        }
    }

    // Leaving, dropout, end -------------------------------------------------------------------

    private void engineClosed(Player p, Socket which) {
        synchronized (lock) {
            if (game == null || p.engineSock != which) return;       // a link that was replaced (the phone rejoined) ends without a word
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
            } else if (p.host && p.endAll) {
                // The host chose "End the game for everyone" on its game screen: the game ends on every phone.
                game.events.add(p.name + " (the host) ended the game");
                log(p.name + " (the host) quit: ending the game for everyone");
                endGame(HOST_ENDED);
                return;
            } else if (p.quit) {
                // Leave the game (any phone, the host's too): the game goes on, the seat stays away for the rejoin window.
                dropPlayer(p, "the player quit", "You left the game." + rejoinHint(), true, true);
            } else {
                dropPlayer(p, "its engine link closed", "You were dropped from the game: its engine link closed." + rejoinHint(), true, false);
            }
            checkEnd();
        }
    }

    /** Lock held. Sends the unsynced team-gone message F<team> for each of the phone's teams (plan section 2.8). */
    private void dropPlayer(Player p, String why) {
        dropPlayer(p, why, "You were dropped from the game: " + why, false, false);
    }

    /** {@code toPlayer} is what the dropped phone itself is told (a phone that quit is not "dropped"). */
    private void dropPlayer(Player p, String why, String toPlayer) {
        dropPlayer(p, why, toPlayer, false, false);
    }

    private String rejoinHint() {
        return cfg.rejoinMs > 0 ? " You can rejoin within " + windowText() + "." : "";
    }

    /**
     * Lock held. The phone is out of the game: its engine link is closed and its teams are marked gone with F (turns skipped). With
     * {@code rejoinable} (a lost connection or a phone that left, in a running game) its seat stays "away" for {@link Config#rejoinMs}: the same phone may
     * come back ({@link #rejoinRefusal}); then the others are told. Without it (desync, a map that differs, never linked) the seat is gone for good.
     * {@code toPlayer} null says nothing to the phone.
     */
    private void dropPlayer(Player p, String why, String toPlayer, boolean rejoinable, boolean onPurpose) {
        Game g = game;
        if (g == null || p.dropped) return;
        p.dropped = true;
        boolean away = rejoinable && state == S_PLAYING && cfg.rejoinMs > 0;
        String ev = p.name + " dropped (" + why + ")";
        g.events.add(ev);
        log(ev);
        p.catchUp = false;
        p.backPending.clear();
        p.rejoinPending = false;
        p.closeAll(false);
        resumeIfHostGone(g);
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
        if (away) {
            p.away = true;
            p.gaveUp = false;
            p.awayAt = System.currentTimeMillis();
            p.awayOnPurpose = onPurpose;
            g.events.add(p.name + (onPurpose ? " left the game" : " lost connection") + "; waiting up to " + windowText() + " for them to rejoin");
            tellOthers(p, p.name + (onPurpose ? " left the game" : " lost connection") + "; waiting up to " + windowText() + " for them to rejoin");
            tvOverlay(awayMsg(p, cfg.rejoinMs));
        }
        if (toPlayer != null) sendLobby(p, Json.obj("t", "dropped", "reason", toPlayer));
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
        // A phone that dropped earlier does not turn a normal end into "gone": the game is over when every phone still
        // in it saw the game over (its engine sent q). Only when no phone is left that finished is every engine gone.
        boolean anyDropped = false, anyLive = false, liveAllFinished = true, anyAway = false, anyGaveUp = false;
        for (Player q : players.values()) {
            if (q.away) anyAway = true;
            if (q.gaveUp) anyGaveUp = true;
            if (q.dropped) { anyDropped = true; continue; }
            anyLive = true;
            if (!q.finished) liveAllFinished = false;
        }
        boolean over = !anyDropped || (anyLive && liveAllFinished);
        // Nobody is connected but a window is still open: the game is kept until the windows run out. A phone cannot actually rejoin meanwhile
        // (rejoinRefusal says rejoin-alone while no other phone plays: no engine could hand its teams back), so this only gives a phone
        // that is merely slow to notice its own loss a moment. When the last window has run out, or the phones that were left finished, the game ends.
        if (!over && anyAway && state == S_PLAYING) return;
        endGame(over ? "every engine finished" : anyGaveUp || anyAway ? NOBODY_LEFT : "every engine is gone");
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
        String endedAs = endedAs(reason);
        noteLastGame(g.no, endedAs);
        closeViewers(reason.equals(HOST_ENDED) ? "the host ended the game" : "the game ended",
                endedAs.equals("host") ? CLOSE_HOST_ENDED : endedAs.equals("over") ? CLOSE_GAME_OVER
                        : endedAs.equals("host-lost") ? CLOSE_HOST_LOST : CLOSE_OTHER,
                endedAs.equals("host") ? "host-ended" : endedAs.equals("over") ? "game-over"
                        : endedAs.equals("host-lost") ? "host-lost" : "ended");
        for (Spectator sp : spectators.values()) retireWatchLink(sp, "the game ended");
        if (g.watcherReportDropped > 0) s.watchers.add("(" + g.watcherReportDropped + " earlier watcher lines not listed; the last " + MAX_VIEWER_LINES + " follow)");
        s.watchers.addAll(g.watcherReport);
        s.watcherDesyncs = g.watcherDesyncs;
        if (g.watcherEventsDropped > 0) s.events.add("(" + g.watcherEventsDropped + " earlier watcher events not listed; the last " + MAX_VIEWER_LINES + " are kept)");
        if (g.viewerReportDropped > 0) s.viewers.add("(" + g.viewerReportDropped + " earlier TV viewer lines not listed; the last " + MAX_VIEWER_LINES + " follow)");
        s.viewers.addAll(g.viewerReport);
        if (g.viewerEventsDropped > 0) s.events.add("(" + g.viewerEventsDropped + " earlier TV viewer events not listed; the last " + MAX_VIEWER_LINES + " are kept)");
        s.events.addAll(g.events);
        s.endReason = reason;
        for (Player q : players.values()) {
            s.perPlayer.add("Phone " + q.name + ": sent to it " + q.toMsgs + " messages (hash " + q.toHash.hex()
                    + "), received from it " + q.fromRaw + " (" + q.fromKept + " forwarded, " + q.fromDropped + " not on the whitelist or not its team)"
                    + (q.rejoins > 0 ? ", rejoined " + q.rejoins + "x" : "")
                    + (q.gaveUp ? ", did not come back" : q.away ? ", away" : q.dropped ? ", dropped" : q.finished ? ", finished" : q.quit ? ", quit" : ""));
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
        // A watching phone is cut off like a player if the game was ended (the host quit, a desync, every engine gone). When the game simply
        // finished, its engine link stays open for a while: it may still be catching up and must be able to see the end.
        boolean finishedNormally = reason.equals("every engine finished");
        for (Spectator sp : new ArrayList<>(spectators.values())) {
            Map<String, Object> sum = Json.obj("t", "summary", "game", (long) g.no, "text", s.text(),
                    "logHash", s.logHash, "logMsgs", s.logMsgs, "toYouHash", sp.toHash.hex(), "toYouMsgs", sp.toMsgs, "reason", reason);
            s.toHashByPlayer.put(sp.id, sp.toHash.hex());
            s.toMsgsByPlayer.put(sp.id, sp.toMsgs);
            if (sp.linkOpen && sp.engineOut != null && !finishedNormally) {
                sp.engineOut.offer(Frames.frame(("\0X" + reason).getBytes(StandardCharsets.UTF_8)));
            }
            sendLobby(sp, sum);
            if (sp.linkOpen) sp.closeEngineLater(finishedNormally ? WATCH_END_LINGER_MS : 1000);
        }
        game = null;
        state = S_SETUP;
        // Drop phones whose lobby link is gone; everyone else goes back to the lobby, not ready.
        long nowMs = System.currentTimeMillis();
        for (Player q : new ArrayList<>(players.values())) {
            q.ready = q.host;
            q.lobbyRx = nowMs;                       // a phone whose lobby link sat idle in a game must not look silent
            q.linked = q.linkOpen = q.dropped = q.finished = false;
            q.away = q.gaveUp = q.rejoinPending = q.catchUp = q.endAll = false;
            q.backPending.clear();
            if (q.lobbySock == null) players.remove(q.id);
        }
        for (Spectator sp : new ArrayList<>(spectators.values())) {
            sp.lobbyRx = nowMs;
            sp.linkOpen = false;
            if (sp.lobbySock == null) spectators.remove(sp.id);
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
            return "Game " + g.no + ": " + g.digests.size() + " turns, log " + g.logMsgs + " messages" + (g.paused ? ", paused by the host" : "")
                    + (spectators.isEmpty() ? "" : ", " + spectators.size() + " watching");
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
            probeNetwork(now);
            try {
                lastPing = watchOnce(now, lastPing);
            } catch (Throwable e) {
                log("watchdog error (carrying on): " + e);
            }
        }
    }

    /** Outside the lock: asks the host's network probe (listing interfaces is not for the relay's lock) at most once a second. */
    private void probeNetwork(long now) {
        if (cfg.networkUp == null || now - lastProbe < 1000) return;
        lastProbe = now;
        boolean up;
        try { up = cfg.networkUp.getAsBoolean(); } catch (RuntimeException e) { return; }
        synchronized (lock) {
            if (up) { netDownSince = 0; netLossHandled = false; }
            else if (netDownSince == 0) netDownSince = now;
        }
    }

    private long lastProbe, netDownSince;
    private boolean netLossHandled;
    public static final String WIFI_LOST = "The host lost its Wi-Fi connection";

    /**
     * WebSocket close codes the TV viewer is sent when a game ends or hosting stops (docs/multiphone-test.md, "How the TV is told the
     * game ended"). 4005 is any other end: a desync, the pause limit, every phone dropped.
     */
    public static final int CLOSE_HOST_ENDED = 4001, CLOSE_GAME_OVER = 4002, CLOSE_HOST_LOST = 4003, CLOSE_STOPPED = 4004, CLOSE_OTHER = 4005;
    private int lastGameNo;
    private String lastGameEnded;
    private long lastGameAt;

    /** Lock held. */
    private void noteLastGame(int n, String ended) {
        lastGameNo = n;
        lastGameEnded = ended;
        lastGameAt = System.currentTimeMillis();
    }

    /**
     * "host" (the host ended it: Back, End the game, the host's engine quit), "over" (a normal game over), "host-lost" (the host's
     * Wi-Fi went, or every engine is gone), "other" (anything else the relay decides: a desync, the pause limit, every phone dropped).
     */
    static String endedAs(String reason) {
        if (reason.equals(HOST_ENDED)) return "host";
        if (reason.equals("every engine finished")) return "over";
        if (reason.equals(WIFI_LOST) || reason.equals("every engine is gone")) return "host-lost";
        return "other";
    }

    /** Lock held. The host has had no LAN address for the limit: end the running game for everyone, say why to the lobby. */
    private void hostNetworkLost(long now) {
        netLossHandled = true;
        log("host network down for " + (now - netDownSince) / 1000 + " s");
        if (game != null) {
            endGame(WIFI_LOST);
        } else {
            for (Player q : players.values()) if (!q.host) sendLobby(q, Json.obj("t", "notice", "text", WIFI_LOST + ". Phones may not reach it until it is back."));
        }
    }

    private long watchOnce(long now, long lastPing) {
            synchronized (lock) {
                if (netDownSince != 0 && !netLossHandled && now - netDownSince > cfg.networkLossMs) hostNetworkLost(now);
                if (now - lastPing > 5000) {
                    lastPing = now;
                    for (Player q : players.values()) sendLobby(q, Json.obj("t", "ping"));
                    for (Spectator q : spectators.values()) sendLobby(q, Json.obj("t", "ping"));
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
                    for (Spectator q : new ArrayList<>(spectators.values())) {
                        if (now - q.lobbyRx > cfg.lobbySilenceMs) {
                            log("lobby: watcher " + q.name + " silent for " + (now - q.lobbyRx) / 1000 + " s, removed");
                            q.closeAll(true);
                            spectators.remove(q.id);
                            broadcastStateLocked();
                        }
                    }
                }
                if (state != S_SETUP) {
                    // In a game a watcher's engine link carries it; one without an engine link whose lobby went silent is gone.
                    for (Spectator q : new ArrayList<>(spectators.values())) {
                        if (!q.linkOpen && now - q.lobbyRx > cfg.lobbySilenceMs) {
                            log("lobby: watcher " + q.name + " silent for " + (now - q.lobbyRx) / 1000 + " s, removed");
                            q.closeAll(true);
                            spectators.remove(q.id);
                            broadcastStateLocked();
                        }
                    }
                }
                Game g = game;
                if (g == null) return lastPing;
                if (state == S_PLAYING) {
                    for (Player q : new ArrayList<>(players.values())) {
                        if (q.rejoinPending && now - q.rejoinAt > cfg.linkWaitMs) {
                            q.rejoinPending = false;
                            g.events.add(q.name + "'s engine did not connect after the rejoin in " + cfg.linkWaitMs / 1000 + " s; still away");
                            log(q.name + " rejoin: its engine did not connect");
                        }
                        // The window is judged by when the rejoin request arrived: an accepted rejoin keeps its seat for the engine link wait.
                        if (q.away && now - q.awayAt > cfg.rejoinMs && !(q.rejoinPending && now - q.rejoinAt <= cfg.linkWaitMs)) giveUp(q);
                    }
                    checkEnd();
                    if (g != game) return lastPing;
                    List<Runnable> afterAsk = new ArrayList<>();
                    tryInjectBack(g, now, afterAsk);
                    for (Runnable r : afterAsk) r.run();
                }
                if (state == S_PLAYING && cfg.linkDeadMs > 0) linkLiveness(g, now);
                if (g != game) return lastPing;
                if (state == S_STARTING && now - g.startedAt > cfg.linkWaitMs) {
                    for (Player q : new ArrayList<>(players.values()))
                        if (!q.linked && !q.dropped) dropPlayer(q, "its engine did not connect in " + cfg.linkWaitMs / 1000 + " s");
                    if (state == S_STARTING) maybeOpenGate();
                    checkEnd();
                } else if (state == S_PLAYING && g.paused) {
                    // Nobody sends anything while the host has paused; a pause is not unlimited.
                    if (now - g.pausedAt > cfg.pauseMaxMs) {
                        endPause(g, now, "Pause limit of " + cfg.pauseMaxMs / 1000 + " s reached: the relay resumed the game");
                        broadcastPause(false);
                    }
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
     * Lock held. Pings every engine link of the running game and drops a phone whose link has been silent too long. The silence rule
     * above watches the current player only; this finds a half-open link (Wi-Fi gone without a close) of a phone that is not
     * playing right now, before its turn comes and stalls everyone. The launcher answers a ping with the same control frame,
     * on a thread of its own, so a phone that is generating a map or paused still answers.
     */
    private void linkLiveness(Game g, long now) {
        if (now - g.lastLinkPing >= cfg.linkPingMs) {
            g.lastLinkPing = now;
            byte[] k = Frames.frame(new byte[] { 0, 'K' });
            for (Player q : players.values())
                if (q.linkOpen && !q.dropped && q.engineOut != null) q.engineOut.offer(k);
            for (Spectator q : spectators.values())
                if (q.linkOpen && q.engineOut != null) q.engineOut.offer(k);
        }
        for (Player q : new ArrayList<>(players.values())) {
            if (q.linkOpen && !q.dropped && !q.finished && now - q.lastRx > cfg.linkDeadMs) {
                dropPlayer(q, "its engine link was silent for " + (now - q.lastRx) / 1000 + " s and did not answer the link pings (half-open link)",
                        "You were dropped from the game: your phone did not answer for " + (now - q.lastRx) / 1000 + " s." + rejoinHint(), true, false);
                checkEnd();
                if (game != g) return;
            }
        }
        for (Spectator q : new ArrayList<>(spectators.values()))
            if (q.linkOpen && now - q.lastRx > cfg.linkDeadMs) dropSpectator(q, "its engine link was silent for " + (now - q.lastRx) / 1000 + " s");
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
        for (Player q : players.values()) if (q.linkOpen && !q.dropped && !q.finished && !q.catchUp) live.add(q);
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
            dropPlayer(culprit, "no messages for " + cfg.silenceMs / 1000 + " s while it was the one expected to play",
                    "You were dropped from the game: no messages for " + cfg.silenceMs / 1000 + " s while it was your turn." + rejoinHint(), true, false);
            checkEnd();
        }
    }


    // TV viewers (WebSocket) -------------------------------------------------------------------

    /**
     * Lock held. Sends an overlay message to every TV as a WebSocket text frame (opcode 1, JSON), through the viewer's own queue so it arrives
     * in stream order. Never to phones' engine links and never into the game log.
     */
    private void tvOverlay(Map<String, Object> msg) {
        if (viewers.isEmpty()) return;
        byte[] wf = Ws.text(Json.write(msg));
        for (Viewer v : new ArrayList<>(viewers)) if (!v.out.offer(wf)) dropViewer(v, whyRefused(v));
    }

    private static Map<String, Object> awayMsg(Player p, long leftMs) {
        return Json.obj("t", "away", "name", p.name, "leftMs", leftMs, "onPurpose", p.awayOnPurpose);
    }

    private final class Viewer {
        Socket sock;
        Outbox out;
        String from = "";
        final long joinedAt = System.currentTimeMillis();
        /** WebSocket ping statistics (the pings wait behind the frames already queued, so a long queue shows up as a long round trip). */
        long pingsSent, pongs;
        double lastRttMs = -1, sumRttMs, minRttMs = -1, maxRttMs = -1;
        int maxQueue;
        /** The relay sent its close frame: nothing may follow it, not even an echo of the TV's close reply. */
        volatile boolean closing;
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
                pollers.put(String.valueOf(s.getInetAddress()), System.currentTimeMillis());
                viewersWaiting();                       // prunes the entries older than 10 s
                int nl = head.indexOf('\n');
                String body = statusJson(nl < 0 ? head : head.substring(0, nl));
                os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\nContent-Length: "
                        + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8));
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
                // The game password never applies to TV viewers (only to phones joining); a pin parameter is ignored.
                os.write(Ws.handshakeResponse(key));
                os.flush();
                me.sock = s;
                me.from = String.valueOf(s.getRemoteSocketAddress());
                me.out = new Outbox("relay-viewer-out", cfg.viewerQueueItems, 16 << 20, false,
                        () -> viewerLost(me, "connection lost (write failed: " + me.out.failure() + ")"));
                me.out.attach(os);
                byte[] first = viewerBacklog();
                me.out.offer(Ws.frame(2, first));
                long nowMs = System.currentTimeMillis();
                for (Player q : players.values())
                    if (q.away) me.out.offer(Ws.text(Json.write(awayMsg(q, Math.max(0L, cfg.rejoinMs - (nowMs - q.awayAt))))));
                viewers.add(me);
                viewersEver++;
                addViewerEvent(game, "TV viewer joined from " + me.from + " (first message " + first.length + " bytes)");
                log("viewer joined from " + me.from + ", first message " + first.length + " bytes, log " + game.logMsgs + " messages");
            }
            v = me;
            s.setSoTimeout(0);
            changed();
            boolean closeFrame = Ws.readClient(in, reply -> { if (!me.closing) me.out.offer(reply); }, pong -> {
                synchronized (lock) { onPong(me, pong); }
            });
            if (me.closing) {
                // The relay closed first and the TV has answered (or gone): the closing handshake is complete, so the
                // server ends the TCP connection now. Holding it open makes older WebViews report 1006 without the code.
                finishClose(me);
                return;
            }
            why = closeFrame ? "the TV closed the connection" : "the TV disconnected (end of stream)";
            // Record why first: during the drain below a game frame would find the box closing and log "connection closed".
            synchronized (lock) { retire(me, why); }
            changed();
            // The client closed (and its close frame was echoed): let the echo out before the socket goes.
            me.out.closeWhenDrained();
            try { Thread.sleep(200); } catch (InterruptedException ignored) { }
            finishClose(me);
            return;
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
    private void closeViewers(String why, int code, String reasonText) {
        byte[] rb = reasonText.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + rb.length];
        payload[0] = (byte) (code >> 8);
        payload[1] = (byte) code;
        System.arraycopy(rb, 0, payload, 2, rb.length);
        for (Viewer v : new ArrayList<>(viewers)) {
            retire(v, why);
            v.closing = true;                       // first: no reply may be queued after the close frame
            v.out.offer(Ws.frame(8, payload));      // behind every game frame already queued: the close code is the last thing the TV reads
            v.out.closeWhenDrained();
            final Viewer cv = v;
            Thread t = new Thread(() -> {
                // Fallback only: normally the TV's close reply ends the connection at once (finishClose). This covers a
                // TV that never answers; it waits longer when many game frames are still queued ahead of the close.
                try { Thread.sleep(VIEWER_CLOSE_WAIT_MS); } catch (InterruptedException ignored) { }
                finishClose(cv);
            });
            t.setDaemon(true);
            t.start();
        }
    }

    /** How long the relay waits for a TV to answer its close frame before ending the connection anyway. */
    static final long VIEWER_CLOSE_WAIT_MS = 5000;

    /** After the relay's close frame: let it out (bounded wait), then end the TCP connection with a FIN. */
    private static void finishClose(Viewer v) {
        long until = System.currentTimeMillis() + 1000;
        while (!v.out.isClosed() && System.currentTimeMillis() < until) {
            try { Thread.sleep(10); } catch (InterruptedException e) { break; }
        }
        v.out.close();
        try { v.sock.shutdownOutput(); } catch (IOException ignored) { }
        try { v.sock.close(); } catch (IOException ignored) { }
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
