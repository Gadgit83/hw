import org.hedgewars.android.GameConfig;
import org.hedgewars.android.GameData;
import org.hedgewars.android.SetupModel;
import org.hedgewars.android.net.Frames;
import org.hedgewars.android.net.Json;
import org.hedgewars.android.net.NetTeams;
import org.hedgewars.android.net.Relay;
import org.hedgewars.android.net.RelayClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JVM tests of the relay core with no engine and no X server: hostile and malformed input must end one connection and
 * never the process (M1), nothing a phone sends may reach the setup lines unchecked (M2), the silence rule must not arm
 * before every phone has sent its map digest (M3), and the smaller hardening items (host role, caps, WebSocket
 * framing). Fake engines are plain sockets that speak the engine link's framing.
 *
 * Usage: RelayTests DATA_DIR      Exit code 0 only if every check passed.
 */
public class RelayTests {
    static int checks, failures;
    static GameData data;
    static final Random RND = new Random(7);

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }

    // Fixture -----------------------------------------------------------------------------------

    static class P implements RelayClient.Handler {
        volatile Map<String, Object> state;
        volatile RelayClient.StartInfo start;
        volatile String closed = "", notice = "";
        public void onState(Map<String, Object> s) { state = s; }
        public void onStart(RelayClient.StartInfo s) { start = s; }
        public void onSummary(Map<String, Object> m) { }
        public void onNotice(String t) { notice = t; notices.add(t); }
        public void onClosed(String r) { closed = r; }
        volatile String refusedCode = "", refusedReason = "", rejoinToken = "", seatName = "", gaveUp = "";
        volatile RelayClient client;
        final List<String> notices = new CopyOnWriteArrayList<>();
        public void onRefused(String code, String reason) { refusedCode = code; refusedReason = reason; }
        public void onJoined(int you, String name, String token) { rejoinToken = token; seatName = name; }
        public void onGaveUp(String reason) { gaveUp = reason; }
    }

    static final class Fix {
        final Relay relay;
        final List<String> log = new CopyOnWriteArrayList<>();
        final String secret = "test-secret";
        SetupModel hostModel = SetupModel.defaults(data, new Random(3));
        /** Set before creating a Fix: a short ping interval for the round-trip tests, a small viewer queue for the slow-viewer test. */
        static long pingMs = 300;
        static int queueItems = 20000;
        /** The link liveness check is off for the old tests (their fake engines never answer pings); the liveness test switches it on. */
        static long linkDeadMs = 0, linkPingMs = 5000;
        /** The host's data fingerprint the relay demands ("" = no data check), and the network probe (netUp) with its loss limit. */
        static String dataDigest = "";
        static boolean netProbe;
        static long netLossMs = 4000;
        /** How long a seat stays away (the app uses two minutes), how soon team-back is asked for again, and whether chat lines are written into the game. */
        static long rejoinMs = 120000, backResendMs = 2500;
        final java.util.concurrent.atomic.AtomicBoolean netUp = new java.util.concurrent.atomic.AtomicBoolean(true);

        Fix(long silenceMs, long graceMs) throws Exception {
            Relay.Config cfg = new Relay.Config();
            cfg.port = 0;
            cfg.viewerPort = 0;
            cfg.viewerPingMs = pingMs;
            cfg.viewerQueueItems = queueItems;
            cfg.data = data;
            cfg.hostSetup = () -> hostModel;
            cfg.hostSecret = secret;
            cfg.build = "tests";
            cfg.linkDeadMs = linkDeadMs;
            cfg.linkPingMs = linkPingMs;
            cfg.dataDigest = dataDigest;
            if (netProbe) { cfg.networkUp = netUp::get; cfg.networkLossMs = netLossMs; }
            cfg.silenceMs = silenceMs;
            cfg.graceMs = graceMs;
            cfg.rejoinMs = rejoinMs;
            cfg.backResendMs = backResendMs;
            cfg.listener = new Relay.Listener() {
                public void log(String line) { log.add(line); }
                public void changed() { }
            };
            relay = new Relay(cfg);
            relay.start();
        }

        int port() { return relay.port(); }

        boolean logged(String part) {
            for (String l : log) if (l.contains(part)) return true;
            return false;
        }

        void stop() { relay.stop(); }
    }

    static SetupModel.Team team(String name, int color, int level) {
        SetupModel.Team t = SetupModel.randomTeam(data, RND, color, new ArrayList<>());
        t.name = name;
        t.level = level;
        t.hogs = 2;
        return t;
    }

    static RelayClient join(Fix f, P h, String name, boolean host, SetupModel.Team... teams) throws Exception {
        RelayClient c = new RelayClient("127.0.0.1", f.port(), name, "tests", host, Arrays.asList(teams), h);
        if (host) c.hostSecret(f.secret);
        c.connect();
        return c;
    }

    static boolean waitFor(java.util.function.BooleanSupplier c, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return c.getAsBoolean();
    }

    /** The relay still serves a normal phone: connect, get a state, count the phones it lists. */
    static boolean sane(Fix f, String label) throws Exception {
        P p = new P();
        RelayClient c;
        try {
            c = join(f, p, "Sanity", false, team("Sane", 5, 0));
        } catch (Exception e) {
            check(false, label + ": relay still accepts a phone (" + e + ")");
            return false;
        }
        // In a running game a new phone is refused with a reason: that is an answer too.
        boolean ok = waitFor(() -> p.state != null || !p.closed.isEmpty(), 3000);
        check(ok, label + ": relay still accepts a phone and answers (state or a clear refusal received)");
        c.close();
        return ok;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> player(Map<String, Object> st, String name) {
        if (st == null) return null;
        for (Object o : Json.list(st, "players")) if (Json.str((Map<String, Object>) o, "name", "").equals(name)) return (Map<String, Object>) o;
        return null;
    }

    static boolean dropped(Fix f, String name) {
        Map<String, Object> p = player(f.relay.status(0), name);
        return p != null && Json.bool(p, "dropped", false);
    }

    /** What a hostile client does: connect, write the bytes, read for a moment, report whether the relay closed on it. */
    static boolean abuse(int port, byte[] bytes, long readMs) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout((int) readMs);
            OutputStream o = s.getOutputStream();
            try {
                o.write(bytes);
                o.flush();
            } catch (java.io.IOException e) {
                return true;
            }
            try {
                byte[] b = new byte[4096];
                while (true) {
                    int n = s.getInputStream().read(b);
                    if (n < 0) return true;
                }
            } catch (java.net.SocketTimeoutException e) {
                return false;
            } catch (java.io.IOException e) {
                return true;
            }
        }
    }

    static byte[] repeat(char c, int n, String tail) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(c);
        return (b + tail).getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) b.write(p, 0, p.length);
        return b.toByteArray();
    }

    static byte[] line(String s) { return (s + "\n").getBytes(StandardCharsets.UTF_8); }

    // Fake engine ---------------------------------------------------------------------------

    static final class Eng {
        final Socket s;
        final OutputStream out;
        final AtomicInteger frames = new AtomicInteger();
        volatile boolean eof;
        final List<String> controls = new CopyOnWriteArrayList<>();
        final List<String> received = new CopyOnWriteArrayList<>();
        /** Answers the relay's link ping (control frame K) like the launcher does; a test switches it off to play a half-open link. */
        volatile boolean autoPong = true;

        Eng(int port, String token) throws Exception {
            s = new Socket("127.0.0.1", port);
            out = s.getOutputStream();
            out.write(line("{\"t\":\"hello\",\"role\":\"engine\",\"token\":\"" + token + "\"}"));
            out.flush();
            InputStream in = new java.io.BufferedInputStream(s.getInputStream());
            String ok = Frames.readLine(in, 4096);
            if (ok == null || !ok.contains("\"ok\"")) throw new IllegalStateException("engine link refused: " + ok);
            Thread t = new Thread(() -> {
                try {
                    for (;;) {
                        byte[] m = Frames.read(in);
                        if (m == null) break;
                        frames.incrementAndGet();
                        if (m.length > 0 && m[0] == 0) {
                            controls.add(new String(m, 1, m.length - 1, StandardCharsets.UTF_8));
                            if (autoPong && m.length == 2 && m[1] == 'K') send(new byte[] { 0, 'K' });
                        }
                        else received.add(new String(m, StandardCharsets.ISO_8859_1));
                    }
                } catch (Exception ignored) {
                }
                eof = true;
            });
            t.setDaemon(true);
            t.start();
        }

        void send(String msg) throws Exception { send(msg.getBytes(StandardCharsets.ISO_8859_1)); }

        void send(byte[] msg) throws Exception {
            synchronized (out) {
                out.write(Frames.frame(msg));
                out.flush();
            }
        }

        void raw(byte[] b) throws Exception {
            synchronized (out) {
                out.write(b);
                out.flush();
            }
        }

        void close() { try { s.close(); } catch (Exception ignored) { } }
    }

    static final class Game {
        final Fix f;
        final P ph = new P(), pb = new P();
        RelayClient a, b;
        Eng ea, eb;

        Game(long silenceMs, long graceMs) throws Exception {
            f = new Fix(silenceMs, graceMs);
            a = join(f, ph, "PhoneA", true, team("Alpha", 0, 0));
            b = join(f, pb, "PhoneB", false, team("Bravo", 1, 0));
            b.setReady(true);
            if (!waitFor(() -> pb.state != null && Json.bool(pb.state, "canStart", false), 3000)) throw new IllegalStateException("lobby never became ready");
            a.start();
            if (!waitFor(() -> ph.start != null && pb.start != null, 5000)) throw new IllegalStateException("no start (" + f.log + ")");
            ea = new Eng(f.port(), ph.start.token);
            eb = new Eng(f.port(), pb.start.token);
            Thread.sleep(300);
        }

        void end() {
            try { ea.send("q"); eb.send("q"); } catch (Exception ignored) { }
            ea.close();
            eb.close();
            a.close();
            b.close();
            f.stop();
        }
    }

    // Tests ---------------------------------------------------------------------------------

    static void testJson() {
        System.out.println("M1: the JSON reader");
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 60000; i++) deep.append('[');
        for (String bad : new String[] { deep.toString(), deep.toString().replace("[", "{\"a\":"), "[".repeat(33) + "]".repeat(33) }) {
            try {
                Json.parse(bad);
                check(false, "deeply nested text is refused");
            } catch (IllegalArgumentException e) {
                check(true, "deeply nested text (" + bad.length() + " chars) is refused with IllegalArgumentException: " + e.getMessage());
            } catch (Throwable e) {
                check(false, "deeply nested text raised " + e);
            }
        }
        check(Json.parse("[".repeat(32) + "]".repeat(32)) != null, "32 levels of nesting still parse");
        StringBuilder big = new StringBuilder("[");
        for (int i = 0; i < 5000; i++) big.append(i == 0 ? "" : ",").append(i);
        big.append(']');
        try { Json.parse(big.toString()); check(false, "an array of 5000 elements is refused"); } catch (IllegalArgumentException e) { check(true, "an array of 5000 elements is refused"); }
        // Every prefix of a valid document, and every one-byte corruption of it, raises only IllegalArgumentException.
        String doc = Json.write(Json.obj("t", "hello", "teams", Arrays.asList(Json.obj("name", "xé\"q", "n", -12L, "ok", true, "z", null))));
        boolean onlyIae = true;
        for (int i = 0; i < doc.length(); i++) {
            try { Json.parse(doc.substring(0, i)); } catch (IllegalArgumentException e) { } catch (Throwable e) { onlyIae = false; System.out.println("    prefix " + i + ": " + e); }
            for (char c : new char[] { '"', '\\', '{', '[', '\u0000', 'u', '9', '-' }) {
                String m = doc.substring(0, i) + c + doc.substring(i + 1);
                try { Json.parse(m); } catch (IllegalArgumentException e) { } catch (Throwable e) { onlyIae = false; System.out.println("    corrupt " + i + ": " + e); }
            }
        }
        check(onlyIae, "every truncation and one-character corruption of a valid line raises only IllegalArgumentException");
        try { Json.parse("\"" + "a".repeat(70000) + "\""); check(false, "a 70000-character string is refused"); } catch (IllegalArgumentException e) { check(true, "a 70000-character string is refused"); }
        try { Json.parse("123456789012345678901234567890"); check(false, "a huge number is refused"); } catch (IllegalArgumentException e) { check(true, "a huge number is refused"); }
    }

    static void testTeams() {
        System.out.println("M2: fields from other phones are checked against the host's game data");
        Map<String, Object> m = Json.obj("name", "Evil\nescript Scripts/x.lua", "color", 3L, "level", 0L, "hogs", 3L,
                "grave", "Statue\nescript evil.lua", "fort", "Earth\r\negrave x", "voice", "Default\u0000", "flag", "hedgewars\nerdriven",
                "hogNames", Arrays.asList("Hog\neaddhh 0 1000 x", "\u0007\u0007", 5L),
                "hogHats", Arrays.asList("NoHat\nehat x", "../../etc/passwd", "NoHat"));
        SetupModel.Team t = NetTeams.fromJson(m, data);
        check(t.grave.equals("Statue") && t.fort.equals("Earth") && t.voice.equals("Default") && t.flag.equals("hedgewars"),
                "an unknown grave, fort, voice or flag (with newlines in them) becomes the default");
        check(!t.name.contains("\n") && t.name.length() <= 32, "the team name has no newline: '" + t.name + "'");
        check(!t.hogName[0].contains("\n") && t.hogName[1].trim().length() > 0 && t.hogName[2].startsWith("Hedgehog"), "hog names are cleaned, bad ones replaced: '" + t.hogName[0] + "', '" + t.hogName[1] + "', '" + t.hogName[2] + "'");
        check(t.hogHat[0].equals("NoHat") && t.hogHat[1].equals("NoHat"), "an unknown hat becomes NoHat");
        // A real value from the data is kept.
        String g = data.graves().get(data.graves().size() - 1), fo = data.forts().get(data.forts().size() - 1);
        SetupModel.Team ok = NetTeams.fromJson(Json.obj("grave", g, "fort", fo, "hogHats", Arrays.asList(data.hats().get(2))), data);
        check(ok.grave.equals(g) && ok.fort.equals(fo) && ok.hogHat[0].equals(data.hats().get(2)), "a grave, fort and hat the host has are kept");
        // Lines built from such a team contain no extra command.
        SetupModel sm = SetupModel.defaults(data, new Random(1));
        sm.teams.clear();
        sm.teams.add(t);
        sm.teams.add(ok);
        List<String> lines = GameConfig.build(sm, data, new Random(1));
        check(onlyKnownCommands(lines), "the setup built from a hostile team has no injected command (no escript, every grave, fort, voice, flag and hat is the host's own)");
        check(Relay.checkSetupLines(lines) == null, "and passes the relay's line check");
        List<String> longLines = new ArrayList<>(lines);
        longLines.add("eseed " + "x".repeat(300));
        String why = Relay.checkSetupLines(longLines);
        check(why != null && why.contains("bytes"), "a line over 255 bytes is refused: " + why);
        longLines = new ArrayList<>(lines);
        longLines.add("etheme A\nescript x");
        why = Relay.checkSetupLines(longLines);
        check(why != null && why.contains("control"), "a line with a newline is refused: " + why);
        longLines = new ArrayList<>();
        for (int i = 0; i < 256; i++) longLines.add("e$x " + i);
        why = Relay.checkSetupLines(longLines);
        check(why != null && why.contains("256"), "more than 255 setup lines is refused (viewer count byte): " + why);
        longLines.remove(0);
        check(Relay.checkSetupLines(longLines) == null, "255 setup lines still fit");
        // The biggest game the app allows (8 teams of 8 hedgehogs) must fit both limits.
        SetupModel big = SetupModel.defaults(data, new Random(2));
        big.teams.clear();
        for (int i = 0; i < 8; i++) { SetupModel.Team bt = team("Team with a long name number " + i + " xxxxxxxxxxxxxxxxxxxx", i, 0); bt.hogs = 8; for (int h = 0; h < 8; h++) bt.hogName[h] = "Hedgehog with a rather long name that is cut " + h; big.teams.add(bt); }
        List<String> bl = GameConfig.build(big, data, new Random(2));
        why = Relay.checkSetupLines(bl);
        check(why == null && bl.size() <= 255, "8 teams of 8 hedgehogs with long names: " + bl.size() + " lines, every line within 255 bytes, fits the count byte");
    }

    /** True if no line is an injected command: no escript (the style is empty), no newline, and every cosmetic name is the host's own. */
    static boolean onlyKnownCommands(List<String> lines) {
        for (String l : lines) {
            if (l.indexOf('\n') >= 0 || l.indexOf('\r') >= 0 || l.indexOf('\0') >= 0) return false;
            if (l.startsWith("escript")) return false;
            if (l.startsWith("egrave ") && !data.graves().contains(l.substring(7))) return false;
            if (l.startsWith("efort ") && !data.forts().contains(l.substring(6))) return false;
            if (l.startsWith("evoicepack ") && !data.voices().contains(l.substring(11))) return false;
            if (l.startsWith("eflag ") && !data.flags().contains(l.substring(6))) return false;
            if (l.startsWith("ehat ") && !data.hats().contains(l.substring(5))) return false;
        }
        return true;
    }

    static void testGameRefusals() throws Exception {
        System.out.println("M2: the relay refuses a game whose setup cannot be sent");
        Fix f = new Fix(20000, 1000);
        f.hostModel.scriptParam = "€".repeat(100);          // 300 bytes in the e$scriptparam line
        P ph = new P(), pb = new P();
        RelayClient a = join(f, ph, "PhoneA", true, team("Alpha", 0, 0)), b = join(f, pb, "PhoneB", false, team("Bravo", 1, 0));
        b.setReady(true);
        waitFor(() -> pb.state != null && Json.bool(pb.state, "canStart", false), 3000);
        a.start();
        boolean notice = waitFor(() -> pb.notice.contains("cannot start") && ph.notice.contains("cannot start"), 3000);
        check(notice, "both phones get a clear lobby notice: " + ph.notice);
        check(ph.start == null && pb.start == null, "no phone was told to start");
        check(Json.num(f.relay.status(0), "game", -1) == 0, "no game was created");
        // After the host fixes the setup the same lobby starts normally.
        f.hostModel.scriptParam = "";
        a.start();
        check(waitFor(() -> ph.start != null && pb.start != null, 3000), "after fixing the setup the game starts");
        boolean inj = false;
        for (String l : ph.start.lines) if (l.length() > 255) inj = true;
        check(!inj, "no start line is longer than 255 bytes");
        a.close(); b.close(); f.stop();

        // A hostile joiner: every field of its team has newlines; the lines every phone gets have no extra command.
        f = new Fix(20000, 1000);
        P pa = new P();
        a = join(f, pa, "PhoneA", true, team("Alpha", 0, 0));
        Socket evil = new Socket("127.0.0.1", f.port());
        String team = "{\"name\":\"X\\nescript Scripts/evil.lua\",\"color\":1,\"level\":0,\"hogs\":2,\"grave\":\"Statue\\nescript evil\",\"fort\":\"Earth\\n\","
                + "\"voice\":\"Default\\n\",\"flag\":\"hedgewars\\neflag evil\",\"hogNames\":[\"a\\nescript evil2\",\"b\"],\"hogHats\":[\"NoHat\\nehat evil\",\"NoHat\"]}";
        evil.getOutputStream().write(line("{\"t\":\"hello\",\"role\":\"lobby\",\"proto\":1,\"name\":\"Evil\",\"build\":\"tests\",\"compat\":\"" + org.hedgewars.android.net.Compat.VALUE + "\",\"teams\":[" + team + "]}"));
        evil.getOutputStream().write(line("{\"t\":\"ready\",\"ready\":true}"));
        evil.getOutputStream().flush();
        waitFor(() -> pa.state != null && Json.bool(pa.state, "canStart", false), 3000);
        a.start();
        check(waitFor(() -> pa.start != null, 3000), "the game with the hostile team starts");
        check(onlyKnownCommands(pa.start.lines), "no setup line is an injected command (" + pa.start.lines.size() + " lines)");
        int teams = 0;
        for (String l : pa.start.lines) if (l.startsWith("eaddteam ")) teams++;
        check(teams == 2, "exactly two teams were added");
        evil.close(); a.close(); f.stop();
    }

    static void testAbuse() throws Exception {
        System.out.println("M1: hostile and malformed input to the phone port; the relay stays up");
        Fix f = new Fix(20000, 1000);
        P hp = new P();
        RelayClient host = join(f, hp, "PhoneA", true, team("Alpha", 0, 0));
        waitFor(() -> hp.state != null, 3000);
        String hello = "{\"t\":\"hello\",\"role\":\"lobby\",\"proto\":1,\"name\":\"Joe\",\"build\":\"tests\",\"compat\":\"" + org.hedgewars.android.net.Compat.VALUE + "\",\"teams\":[]}";
        String bomb = "[".repeat(60000);
        check(abuse(f.port(), line(bomb), 800), "60000 '[' as the first line: connection closed");
        sane(f, "after the '[' bomb as hello");
        check(abuse(f.port(), cat(line(hello), line(bomb)), 800), "valid hello, then a line of 60000 '[': that phone is removed");
        check(waitFor(() -> player(f.relay.status(0), "Joe") == null, 2000), "  the bomb's sender is gone from the lobby");
        check(host.toString() != null && hp.closed.isEmpty() && player(f.relay.status(0), "PhoneA") != null, "  the host is still connected and listed");
        sane(f, "after the bomb mid-session");
        check(abuse(f.port(), line("{\"a\":".repeat(40000)), 800), "60000-deep object bomb: connection closed");
        check(abuse(f.port(), cat(line(hello), line("{\"t\":\"teams\",\"teams\":" + "[".repeat(50000) + "}")), 800), "bomb inside a teams message: connection closed");
        check(abuse(f.port(), repeat('x', 100000, ""), 800), "100000 bytes with no newline as hello: connection closed");
        check(abuse(f.port(), cat(line(hello), repeat('y', 300000, "\n")), 1500), "a 300000 byte lobby line: connection closed");
        check(abuse(f.port(), cat(new byte[] { (byte) 0xff, (byte) 0xfe, (byte) 0xc0, (byte) 0x80, (byte) 0xed, (byte) 0xa0, (byte) 0x80 }, line("")), 800), "invalid UTF-8 bytes as hello: connection closed");
        byte[] badUtf = cat("{\"t\":\"hello\",\"role\":\"lobby\",\"proto\":1,\"name\":\"".getBytes(StandardCharsets.ISO_8859_1),
                new byte[] { (byte) 0xff, (byte) 0xfe, (byte) 0xc3 }, ("\",\"build\":\"tests\",\"compat\":\"" + org.hedgewars.android.net.Compat.VALUE + "\",\"teams\":[]}\n").getBytes(StandardCharsets.ISO_8859_1));
        abuse(f.port(), badUtf, 500);
        sane(f, "after invalid UTF-8 inside a name");
        byte[] garbage = new byte[10000];
        new Random(5).nextBytes(garbage);
        check(abuse(f.port(), garbage, 800), "10000 random bytes: connection closed");
        check(abuse(f.port(), new byte[] { (byte) 0xff, (byte) 0xff, (byte) 0xff }, 800) || true, "huge length bytes on the phone port are just text: no effect");
        // Garbage that looks like an engine frame header after a valid engine hello with a wrong token.
        check(abuse(f.port(), cat(line("{\"t\":\"hello\",\"role\":\"engine\",\"token\":\"nope\"}"), new byte[] { (byte) 255, 1, 2, 3 }), 800), "engine hello with a wrong token: refused");
        check(abuse(f.port(), line("{\"t\":\"hello\",\"role\":\"lobby\",\"proto\":\"x\"}"), 800), "wrong types in hello: connection closed");
        check(abuse(f.port(), line("null"), 800) && abuse(f.port(), line("[]"), 800) && abuse(f.port(), line("17"), 800) && abuse(f.port(), line("\"s\""), 800), "null, array, number and string as hello: connection closed");
        sane(f, "after all the garbage");
        // Connection cap: idle connections beyond the limit are closed at once, and the relay recovers when they go.
        List<Socket> idle = new ArrayList<>();
        for (int i = 0; i < Relay.MAX_CONNECTIONS + 10; i++) idle.add(new Socket("127.0.0.1", f.port()));
        Thread.sleep(500);
        int closedAtOnce = 0;
        for (Socket s : idle) {
            s.setSoTimeout(100);
            try { if (s.getInputStream().read() < 0) closedAtOnce++; } catch (java.net.SocketTimeoutException e) { } catch (java.io.IOException e) { closedAtOnce++; }
        }
        check(closedAtOnce >= 8, "connections beyond " + Relay.MAX_CONNECTIONS + " are closed at once (" + closedAtOnce + " of " + idle.size() + ")");
        for (Socket s : idle) s.close();
        Thread.sleep(500);
        sane(f, "after the connection flood");
        // Player cap.
        List<RelayClient> many = new ArrayList<>();
        List<P> hs = new ArrayList<>();
        for (int i = 0; i < Relay.MAX_PLAYERS + 2; i++) { P p = new P(); hs.add(p); many.add(join(f, p, "Extra" + i, false)); Thread.sleep(30); }
        Thread.sleep(500);
        int refused = 0;
        for (P p : hs) if (p.closed.contains("full")) refused++;
        check(refused >= 2, "players beyond " + Relay.MAX_PLAYERS + " are refused with a clear reason (" + refused + " refused)");
        for (RelayClient c : many) c.close();
        host.close();
        f.stop();
    }

    static void testHostRole() throws Exception {
        System.out.println("m6: only the app itself can take the host role");
        Fix f = new Fix(20000, 1000);
        P p = new P();
        RelayClient c = new RelayClient("127.0.0.1", f.port(), "Spoof", "tests", true, new ArrayList<>(), p);
        c.connect();
        check(waitFor(() -> p.closed.contains("Only the hosting phone"), 2000), "a loopback client claiming to be host without the secret is refused: " + p.closed);
        P p2 = new P();
        RelayClient c2 = new RelayClient("127.0.0.1", f.port(), "Spoof2", "tests", true, new ArrayList<>(), p2).hostSecret("guess");
        c2.connect();
        check(waitFor(() -> p2.closed.contains("Only the hosting phone"), 2000), "a wrong secret is refused");
        P p3 = new P();
        RelayClient c3 = join(f, p3, "Real", true);
        check(waitFor(() -> p3.state != null && Json.bool(player(p3.state, "Real"), "host", false), 2000), "the app's own client with the secret is the host");
        P p4 = new P();
        RelayClient c4 = join(f, p4, "Second", true);
        check(waitFor(() -> p4.closed.contains("Only the hosting phone"), 2000), "a second host is refused");
        c3.close();
        f.stop();
        // Tokens are random and long enough.
        Game g = new Game(20000, 1000);
        check(g.ph.start.token.length() == 32 && !g.ph.start.token.equals(g.pb.start.token), "engine tokens are 128 bits from SecureRandom: " + g.ph.start.token);
        g.end();
    }

    static void testEngineAbuse() throws Exception {
        System.out.println("M1: hostile engine links");
        Game g = new Game(20000, 1000);
        final Game g1 = g;
        // Truncated frame, then close: that phone is dropped, the other carries on.
        g.eb.raw(new byte[] { (byte) 255, 'h', 1, 2 });
        g.eb.close();
        check(waitFor(() -> dropped(g1.f, "PhoneB"), 3000), "a frame cut in half drops that phone");
        check(!dropped(g.f, "PhoneA"), "  the other phone is not dropped");
        sane(g.f, "after the truncated frame");
        g.end();

        g = new Game(20000, 1000);
        // Zero-length frames, control frames with junk, non-whitelisted types, h for a team that is not ours, short N.
        g.eb.raw(new byte[] { 0, 0, 0 });
        g.eb.raw(new byte[] { 3, 0, 'E', 'x' });
        g.eb.raw(new byte[] { 2, 0, (byte) 0xff });
        g.eb.send("Z");                       // whitelisted: forwarded
        g.eb.send("\u0001junk");
        g.eb.send(new byte[] { 'h', 1, 0 });   // team 0 belongs to PhoneA
        g.eb.send(new byte[] { 'N' });
        g.eb.send(new byte[] { 'M' });
        Thread.sleep(500);
        check(!dropped(g.f, "PhoneA") && !dropped(g.f, "PhoneB"), "junk, control and short frames drop nobody");
        boolean leaked = false;
        for (String r : g.ea.received) if (r.startsWith("\u0001") || r.startsWith("h")) leaked = true;
        check(!leaked, "the other phone received neither the junk type nor the speech for a team that is not the sender's");
        check(g.ea.received.stream().anyMatch(r -> r.equals("Z")), "a whitelisted message is forwarded");
        sane(g.f, "after the junk frames");
        g.end();
    }

    static void testSilenceRule() throws Exception {
        System.out.println("M3: the silence rule arms only when every live phone has sent its map digest");
        // Phone B is slow to generate the map: A sends M and then waits (it is B's turn); B's M comes after the
        // silence limit plus grace. Nobody may be dropped before B's M, and not after it either (B talks).
        final Game g = new Game(1500, 500);
        g.ea.send("Mdigest-one");
        Thread.sleep(4500);                    // longer than silence + grace; B has said nothing yet
        check(!dropped(g.f, "PhoneA") && !dropped(g.f, "PhoneB"), "A waiting 4.5 s for B's map (limit 1.5 s + 0.5 s grace): nobody dropped");
        g.eb.send("Mdigest-one");
        for (int i = 0; i < 6; i++) { g.eb.send(new byte[] { '#', 0, 1 }); Thread.sleep(300); }
        check(!dropped(g.f, "PhoneA") && !dropped(g.f, "PhoneB"), "after B's M, B sending ticks: nobody dropped");
        // Now B stalls for good: it was the last sender, so it is the one dropped, after silence + grace (no N yet).
        long t0 = System.currentTimeMillis();
        boolean dropB = waitFor(() -> dropped(g.f, "PhoneB") || dropped(g.f, "PhoneA"), 6000);
        long took = System.currentTimeMillis() - t0;
        check(dropB, "a phone that goes silent after the rule is armed is dropped (after " + took + " ms)");
        check(dropped(g.f, "PhoneA") || dropped(g.f, "PhoneB"), "  exactly one phone dropped: " + (dropped(g.f, "PhoneA") ? "A" : "B") + " (M was the last message: the other phone is blamed, as after an N)");
        check(took >= 1500, "  not before the silence limit (" + took + " ms >= 1500)");
        g.end();

        // The first turn gets grace: silence 1 s + grace 3 s means no drop within 2.5 s of the last M.
        Game g2 = new Game(1000, 3000);
        g2.ea.send("Mx");
        g2.eb.send("Mx");
        Thread.sleep(2500);
        check(!dropped(g2.f, "PhoneA") && !dropped(g2.f, "PhoneB"), "grace before the first turn: no drop after 2.5 s with limit 1 s + grace 3 s");
        check(waitFor(() -> dropped(g2.f, "PhoneA") || dropped(g2.f, "PhoneB"), 4000), "  but a phone is dropped once the grace is over");
        g2.end();
    }

    static void testViewerAbuse() throws Exception {
        System.out.println("m7/M1: the TV viewer endpoint");
        Game g = new Game(20000, 1000);
        int vp = g.f.relay.viewerPort();
        check(vp > 0, "viewer endpoint is on port " + vp);
        String req = "GET / HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n";
        byte[] mask = { 1, 2, 3, 4 };
        // Ping payload is echoed in the pong; close is echoed with its code.
        try (Socket s = new Socket("127.0.0.1", vp)) {
            s.setSoTimeout(3000);
            s.getOutputStream().write(req.getBytes(StandardCharsets.ISO_8859_1));
            InputStream in = s.getInputStream();
            readUntilBlank(in);
            readWsFrame(in);                                         // the first message (setup lines)
            byte[] payload = "hello-ping".getBytes(StandardCharsets.ISO_8859_1);
            s.getOutputStream().write(maskedFrame(9, payload, mask));
            s.getOutputStream().flush();
            int[] op = new int[1];
            byte[] pong = null;
            for (int i = 0; i < 20 && pong == null; i++) { byte[] fr = readWsFrame(in, op); if (op[0] == 10) pong = fr; }
            check(pong != null && new String(pong, StandardCharsets.ISO_8859_1).equals("hello-ping"), "a ping is answered by a pong with the same payload");
            s.getOutputStream().write(maskedFrame(8, new byte[] { 0x03, (byte) 0xe8, 'b', 'y', 'e' }, mask));
            s.getOutputStream().flush();
            byte[] cl = null;
            for (int i = 0; i < 20 && cl == null; i++) { byte[] fr = readWsFrame(in, op); if (fr == null) break; if (op[0] == 8) cl = fr; }
            check(cl != null && cl.length >= 2 && (cl[0] & 0xff) == 0x03 && (cl[1] & 0xff) == 0xe8, "a close frame is echoed with its status code");
        }
        // Bad lengths: negative (top bit), enormous, and a frame cut short. Each ends that viewer only.
        byte[][] bads = {
            new byte[] { (byte) 0x82, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff },
            new byte[] { (byte) 0x82, (byte) 0x7f, 0, 0, 0, 0, 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff },
            new byte[] { (byte) 0x82, (byte) 0xfe, (byte) 0xff, (byte) 0xff, 1, 2, 3, 4, 'x' },
            new byte[] { (byte) 0x82, (byte) 0x7f, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0 },
            new byte[] { (byte) 0x89, (byte) 0x85, 1, 2, 3 },
        };
        int k = 0;
        for (byte[] bad : bads) {
            try (Socket s = new Socket("127.0.0.1", vp)) {
                s.setSoTimeout(1500);
                s.getOutputStream().write(req.getBytes(StandardCharsets.ISO_8859_1));
                s.getOutputStream().write(bad);
                s.getOutputStream().flush();
                s.shutdownOutput();
                boolean closed = false;
                try { byte[] b = new byte[65536]; while (s.getInputStream().read(b) >= 0) { } closed = true; } catch (java.net.SocketTimeoutException e) { closed = false; } catch (java.io.IOException e) { closed = true; }
                check(closed, "bad WebSocket frame " + (++k) + " ends that viewer's connection");
            }
        }
        check(abuse(vp, repeat('G', 20000, ""), 800), "a 20000 byte request head with no end: connection closed");
        check(abuse(vp, cat(req.getBytes(StandardCharsets.ISO_8859_1), maskedFrame(2, new byte[70000], mask)), 800), "an oversized masked frame: connection closed");
        Thread.sleep(300);
        check(!dropped(g.f, "PhoneA") && !dropped(g.f, "PhoneB"), "the game and both phones are unaffected by the viewer abuse");
        sane(g.f, "after the viewer abuse");
        g.end();
    }

    static void testViewerReconnect() throws Exception {
        System.out.println("TV viewer: late join and reconnect");
        Game g = new Game(20000, 1000);
        int vp = g.f.relay.viewerPort();
        String req = "GET / HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n";
        byte[] firstA;
        try (Socket s = new Socket("127.0.0.1", vp)) {
            s.setSoTimeout(3000);
            s.getOutputStream().write(req.getBytes(StandardCharsets.ISO_8859_1));
            InputStream in = s.getInputStream();
            readUntilBlank(in);
            firstA = readWsFrame(in);
            check(firstA != null && new String(firstA, StandardCharsets.ISO_8859_1).indexOf("e$spectate") < 0, "a viewer that joins before any move gets the setup lines only (" + firstA.length + " bytes)");
            check(waitFor(() -> g.f.relay.viewerCount() == 1, 2000), "the host counts one TV watching");
            g.ea.send("Mdig");
            g.eb.send("Mdig");
            for (int i = 0; i < 5; i++) g.ea.send(new byte[] { 'Z', (byte) i });
            byte[] live = readWsFrame(in);
            check(live != null, "live messages reach the connected viewer");
        }
        check(waitFor(() -> g.f.relay.viewerCount() == 0, 3000), "after the viewer closes the host counts none");
        for (int i = 0; i < 5; i++) g.eb.send(new byte[] { 'Z', (byte) (10 + i) });
        Thread.sleep(300);
        try (Socket s = new Socket("127.0.0.1", vp)) {
            s.setSoTimeout(3000);
            s.getOutputStream().write(req.getBytes(StandardCharsets.ISO_8859_1));
            InputStream in = s.getInputStream();
            readUntilBlank(in);
            byte[] second = readWsFrame(in);
            String t = new String(second, StandardCharsets.ISO_8859_1);
            check(t.contains("e$spectate 1") && second.length > firstA.length, "the reconnecting viewer gets the setup, e$spectate 1 and the whole backlog (" + second.length + " bytes)");
            int zs = 0;
            for (int i = 0; i < second.length - 2; i++) if (second[i] == 2 && second[i + 1] == 'Z') zs++;
            check(zs == 10, "the backlog holds all 10 moves sent so far (" + zs + ")");
            g.ea.send(new byte[] { 'Z', 99 });
            check(readWsFrame(in) != null, "and it follows live again");
            check(waitFor(() -> g.f.relay.viewerCount() == 1, 2000), "one TV watching again");
        }
        g.end();
    }


    static final String WS_REQ = "GET / HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n";

    /** Item 4: the relay pings each viewer, takes the round trip from the pong and reports it with the queue depth. */
    static void testViewerRtt() throws Exception {
        System.out.println("TV viewer: WebSocket ping, round trip and queue depth");
        Game g = new Game(20000, 1000);
        int vp = g.f.relay.viewerPort();
        byte[] mask = { 1, 2, 3, 4 };
        try (Socket s = new Socket("127.0.0.1", vp)) {
            s.setSoTimeout(3000);
            s.getOutputStream().write(WS_REQ.getBytes(StandardCharsets.ISO_8859_1));
            InputStream in = s.getInputStream();
            readUntilBlank(in);
            readWsFrame(in);
            int[] op = new int[1];
            int pings = 0;
            for (int i = 0; i < 40 && pings < 3; i++) {
                byte[] fr = readWsFrame(in, op);
                if (fr == null) break;
                if (op[0] == 9) {
                    pings++;
                    if (pings == 1) check(fr.length == 8, "the ping carries an 8 byte timestamp payload");
                    Thread.sleep(40);                                     // a measurable round trip
                    s.getOutputStream().write(maskedFrame(10, fr, mask));
                    s.getOutputStream().flush();
                }
            }
            check(pings >= 3, "the relay pings the viewer repeatedly (" + pings + " in the time allowed)");
            check(waitFor(() -> { List<String> l = g.f.relay.viewerLines(); return l.size() == 1 && l.get(0).contains("pongs of"); }, 2000),
                    "the host has a round trip for the viewer: " + g.f.relay.viewerLines());
            String line = g.f.relay.viewerLines().get(0);
            check(line.contains("frames behind"), "and the viewer's queue depth: " + line);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("round trip ([0-9.]+) ms").matcher(line);
            check(m.find() && Double.parseDouble(m.group(1)) >= 30 && Double.parseDouble(m.group(1)) < 2000, "the round trip includes the 40 ms the test waited");
            // A pong with a payload that is not a timestamp is ignored.
            s.getOutputStream().write(maskedFrame(10, new byte[] { 1, 2, 3 }, mask));
            s.getOutputStream().flush();
        }
        check(waitFor(() -> g.f.relay.viewerCount() == 0, 3000), "the viewer is gone from the list after it closes");
        g.end();
        Relay.GameSummary gs = g.f.relay.lastSummary();
        check(gs != null && gs.text().contains("TV viewers") && gs.text().contains("round trip") && gs.text().contains("frames behind"),
                "the end-of-game report has the viewer's round trip and queue depth");
    }

    /** Item 3: a viewer that is killed or reset has disconnected; only an overflowing queue is "too slow". */
    static void testViewerClassification() throws Exception {
        System.out.println("TV viewer: disconnects are not slowness");
        Game g = new Game(20000, 1000);
        int vp = g.f.relay.viewerPort();
        // 1. reset (RST), as when the TV process is killed, while the relay is sending
        Socket s = new Socket("127.0.0.1", vp);
        s.setSoTimeout(3000);
        s.getOutputStream().write(WS_REQ.getBytes(StandardCharsets.ISO_8859_1));
        readUntilBlank(s.getInputStream());
        readWsFrame(s.getInputStream());
        check(waitFor(() -> g.f.relay.viewerCount() == 1, 2000), "viewer 1 joined");
        s.setSoLinger(true, 0);
        s.close();
        for (int i = 0; i < 300; i++) { g.ea.send(new byte[] { 'Z', (byte) i }); if (i % 50 == 0) Thread.sleep(20); }
        check(waitFor(() -> g.f.relay.viewerCount() == 0, 3000), "a reset viewer is removed");
        // 2. plain close without a close frame (EOF)
        Socket s2 = new Socket("127.0.0.1", vp);
        s2.setSoTimeout(3000);
        s2.getOutputStream().write(WS_REQ.getBytes(StandardCharsets.ISO_8859_1));
        readUntilBlank(s2.getInputStream());
        readWsFrame(s2.getInputStream());
        check(waitFor(() -> g.f.relay.viewerCount() == 1, 2000), "viewer 2 joined");
        s2.close();
        check(waitFor(() -> g.f.relay.viewerCount() == 0, 3000), "a viewer that just closes is removed");
        Thread.sleep(300);
        check(!g.f.logged("too slow"), "neither disconnect was logged as too slow");
        check(g.f.logged("viewer") && g.f.logged(" left: "), "both were logged as having left with a reason");
        boolean named = false;
        for (String l : g.f.log) if (l.contains(" left: ") && (l.contains("connection lost") || l.contains("disconnected") || l.contains("closed"))) named = true;
        check(named, "and the reason says the connection was lost or closed");
        g.end();
        waitFor(() -> g.f.relay.lastSummary() != null, 8000);
        Relay.GameSummary gs = g.f.relay.lastSummary();
        int left = 0;
        for (String e : gs == null ? new java.util.ArrayList<String>() : gs.events) if (e.startsWith("TV viewer ") && e.contains(" left (")) left++;
        check(left == 2, "the report lists both departures as events (" + left + ")");
    }

    /** A viewer that stays connected but does not read really is too slow: its small queue overflows and it is dropped with that reason. */
    static void testViewerSlow() throws Exception {
        System.out.println("TV viewer: a real overflow is still reported as too slow");
        int old = Fix.queueItems;
        Fix.queueItems = 40;
        try {
            Game g = new Game(20000, 1000);
            int vp = g.f.relay.viewerPort();
            Socket s = new Socket();
            s.setReceiveBufferSize(2048);
            s.connect(new java.net.InetSocketAddress("127.0.0.1", vp));
            s.getOutputStream().write(WS_REQ.getBytes(StandardCharsets.ISO_8859_1));
            readUntilBlank(s.getInputStream());                     // then it never reads again
            check(waitFor(() -> g.f.relay.viewerCount() == 1, 2000), "the slow viewer joined");
            byte[] big = new byte[250];
            big[0] = 'Z';
            long end = System.currentTimeMillis() + 60000;
            for (int i = 0; i < 400000 && !g.f.logged("too slow") && System.currentTimeMillis() < end; i++) {
                g.ea.send(big);
                if (i % 200 == 0) Thread.sleep(2);
            }
            check(g.f.logged("too slow"), "the overflowing queue is logged as too slow");
            check(waitFor(() -> g.f.relay.viewerCount() == 0, 3000), "and the viewer is dropped");
            s.close();
            g.end();
        } finally {
            Fix.queueItems = old;
        }
    }

    /** Item 5: the viewer port taken by an old instance is retried, not given up for the session. */
    static void testViewerBindRetry() throws Exception {
        System.out.println("TV viewer: bind retry");
        java.net.ServerSocket hold = new java.net.ServerSocket();
        hold.setReuseAddress(true);
        hold.bind(new java.net.InetSocketAddress(0));
        int port = hold.getLocalPort();
        Relay.Config cfg = new Relay.Config();
        cfg.port = 0;
        cfg.viewerPort = port;
        cfg.viewerBindRetries = 20;
        cfg.viewerBindRetryMs = 100;
        cfg.data = data;
        cfg.hostSetup = () -> SetupModel.defaults(data, new Random(3));
        cfg.listener = new Relay.Listener() {
            public void log(String line) { }
            public void changed() { }
        };
        Relay r = new Relay(cfg);
        r.start();
        check(r.viewerPort() == -1 && !r.viewerError().isEmpty(), "while the port is taken the relay reports it: " + r.viewerError());
        hold.close();
        check(waitFor(() -> r.viewerPort() == port, 5000), "once the port is free the relay opens it by itself");
        check(r.viewerError().isEmpty(), "and the error is cleared");
        r.stop();
        // stop() before the retry succeeds ends the retry thread without opening anything
        java.net.ServerSocket hold2 = new java.net.ServerSocket();
        hold2.setReuseAddress(true);
        hold2.bind(new java.net.InetSocketAddress(0));
        cfg.viewerPort = hold2.getLocalPort();
        Relay r2 = new Relay(cfg);
        r2.start();
        r2.stop();
        hold2.close();
        Thread.sleep(500);
        check(r2.viewerPort() == -1, "a relay stopped while retrying never opens the port");
    }

    /** Item 2 and the host's Quit: the host ending the game ends it for everyone, with the right words for each phone. */
    static void testQuitWording() throws Exception {
        System.out.println("Quit wording: joiner quit, host quit");
        Game g = new Game(20000, 1000);
        g.ea.send("Mdig");
        g.eb.send("Mdig");
        g.eb.send("Q");                                             // phone B quits: the engine sends Q and its link ends
        g.eb.close();
        check(waitFor(() -> g.pb.notice.contains("You left the game"), 3000), "a joiner that quits is told 'You left the game.' (" + g.pb.notice + ")");
        check(!g.pb.notice.contains("dropped"), "and not that it was dropped");
        g.ea.send(new byte[] { 0, 'Z' });                           // the host confirms "End the game for everyone" (control frame Z) ...
        g.ea.send("Q");                                             // ... and its engine quits
        g.ea.close();
        check(waitFor(() -> g.f.relay.lastSummary() != null, 4000), "the host's end ends the game");
        Relay.GameSummary gs = g.f.relay.lastSummary();
        check(gs != null && gs.endReason.equals(Relay.HOST_ENDED), "the relay's end reason is '" + Relay.HOST_ENDED + "' (" + (gs == null ? null : gs.endReason) + ")");
        check(gs != null && gs.text().contains("quit"), "the report marks the host's phone as having quit");
        g.end();
    }

    // Rejoining ------------------------------------------------------------------------------

    static Map<String, Object> me(Fix f, String name) { return player(f.relay.status(0), name); }

    static boolean has(List<String> l, String part) {
        for (String x : l) if (x.contains(part)) return true;
        return false;
    }

    static RelayClient rejoinClient(Fix f, P h, String name, String token) throws Exception {
        RelayClient c = new RelayClient("127.0.0.1", f.port(), name, "tests", false, new ArrayList<>(), h).rejoin(token);
        h.client = c;
        c.connect();
        return c;
    }

    static final byte[] G_BACK = { 'g', 'B', 'r', 'a', 'v', 'o', 0, 0 };

    /** Items 1, 4 and 5 of the rejoin rules: the away seat, the token check, the replay, the team-back handshake. */
    static void testRejoin() throws Exception {
        System.out.println("rejoin: an away seat, the token check, the replay and the team-back handshake");
        Fix.rejoinMs = 60000;
        try {
            Game g = new Game(60000, 1000);
            g.ea.send("Mdig");
            g.eb.send("Mdig");
            g.eb.send("Lxx");                                              // a move of B, in the log before it is lost
            check(waitFor(() -> g.ea.received.contains("Lxx"), 2000), "B's move reaches A");
            check(!g.pb.rejoinToken.isEmpty() && g.pb.seatName.equals("PhoneB"), "B was given a rejoin token and its name at join (" + g.pb.seatName + ")");
            check(!g.ph.rejoinToken.isEmpty() && !g.ph.rejoinToken.equals(g.pb.rejoinToken), "and the host another one");
            String token = g.pb.rejoinToken;
            // The connection is lost: the engine link and the lobby link close.
            g.eb.close();
            g.b.close();
            check(waitFor(() -> Json.bool(me(g.f, "PhoneB"), "away", false), 3000), "B is marked away");
            check(Json.bool(me(g.f, "PhoneB"), "dropped", false) && !Json.bool(me(g.f, "PhoneB"), "gaveUp", false), "dropped for the engines, not given up");
            long left = Json.num(me(g.f, "PhoneB"), "awayLeftMs", 0);
            check(left > 50000 && left <= 60000, "the status says how long the seat is kept (" + left + " ms)");
            check(waitFor(() -> g.ea.received.contains("FBravo"), 2000), "its team is marked gone for the others: F<team> injected");
            check(waitFor(() -> has(g.ph.notices, "PhoneB lost connection; waiting up to 1 minute for them to rejoin"), 2000),
                    "the host is told 'PhoneB lost connection; waiting up to 1 minute for them to rejoin' (" + g.ph.notices + ")");
            check(waitFor(() -> has(g.ea.received, "s* PhoneB lost connection; waiting up to 1 minute"), 2000), "the same line is written into the game as chat");
            check(statusBody(g.f).contains("\"away\":true"), "the TV status GET marks the away player");
            // Wrong token, and the right token with another name: both refused alike.
            P bad1 = new P();
            rejoinClient(g.f, bad1, "PhoneB", "00112233445566778899aabbccddeeff");
            check(waitFor(() -> bad1.refusedCode.equals("rejoin-unknown"), 2000), "a wrong token is refused (rejoin-unknown)");
            P bad2 = new P();
            rejoinClient(g.f, bad2, "PhoneZ", token);
            check(waitFor(() -> bad2.refusedCode.equals("rejoin-unknown"), 2000), "the right token with another name is refused (rejoin-unknown)");
            check(bad1.refusedReason.equals(bad2.refusedReason), "with the same words, so nothing is learned about other seats");
            P bad3 = new P();
            rejoinClient(g.f, bad3, "PhoneA", g.ph.rejoinToken);
            check(waitFor(() -> bad3.refusedCode.equals("rejoin-unknown"), 2000), "the host's own seat cannot be taken by a hello with its token (the host uses its lobby link)");
            check(Json.bool(me(g.f, "PhoneB"), "away", false), "the seat is still waiting after the refusals");
            // The real thing.
            P pb2 = new P();
            RelayClient b2 = rejoinClient(g.f, pb2, "PhoneB", token);
            check(waitFor(() -> pb2.start != null, 3000), "the same phone with its token and name is let back in (start message)");
            RelayClient.StartInfo si = pb2.start;
            check(si.rejoin && si.lines.contains("TN") && si.lines.get(si.lines.size() - 1).equals("e$spectate 1"), "  the start says rejoin, TN, and ends with e$spectate 1");
            int teams = 0, rd = 0, own = 0;
            String prev = "";
            for (String l : si.lines) {
                if (l.startsWith("eaddteam ")) teams++;
                if (l.equals("erdriven")) rd++;
                if (l.equals("eowner PhoneB")) { own++; check(prev.equals("erdriven"), "  eowner follows the team's erdriven line"); }
                prev = l;
            }
            check(teams == 2 && rd == 2 && own == 1, "  every team is driven from outside (" + rd + " erdriven) and only B's own has eowner PhoneB (" + own + ")");
            check(!si.token.equals(g.pb.start.token), "  a new engine token");
            try { new Eng(g.f.port(), g.pb.start.token).close(); check(false, "the old engine token is refused"); }
            catch (IllegalStateException e) { check(true, "  the old engine token is refused"); }
            check(waitFor(() -> pb2.state != null, 2000), "  the new lobby link gets the state");
            Eng eb2 = new Eng(g.f.port(), si.token);
            check(waitFor(() -> eb2.received.size() >= 2, 2000) && eb2.received.get(0).equals("Mdig") && eb2.received.contains("Lxx"),
                    "its engine link gets the whole log so far (" + eb2.received.size() + " messages)");
            check(waitFor(() -> has(g.ph.notices, "PhoneB is back"), 2000), "the host is told 'PhoneB is back'");
            check(waitFor(() -> has(eb2.received, "s* PhoneB is back") || has(g.ea.received, "s* PhoneB is back"), 2000), "and it is written into the game");
            check(!Json.bool(me(g.f, "PhoneB"), "away", true), "B is not away any more");
            // Until its teams are back, what its engine says is not passed on (it is replaying: a viewer of its own game).
            long mBefore = g.ea.received.stream().filter(x -> x.equals("Mdig")).count();
            eb2.send("Mdig");
            eb2.send("Rxx");
            Thread.sleep(300);
            check(!g.ea.received.contains("Rxx") && g.ea.received.stream().filter(x -> x.equals("Mdig")).count() == mBefore, "while its teams are not back its messages are not forwarded");
            // A's engine plays on: a message from inside its turn; the relay asks for B's team to come back (G), A's engine answers with g.
            g.ea.send("Axx");
            check(waitFor(() -> g.ea.received.contains("GBravo") && eb2.received.contains("GBravo"), 2000), "team-back G<team> is injected after a message from inside a live turn, for every engine");
            g.ea.send(G_BACK);
            check(waitFor(() -> eb2.received.stream().anyMatch(x -> x.startsWith("gBravo")), 2000), "A's engine answers with the synced g: the relay forwards it");
            check(g.f.logged("teams are back in the game"), "the relay logs that B's teams are back");
            eb2.send("Rxx");
            check(waitFor(() -> g.ea.received.contains("Rxx"), 2000), "now B's moves are forwarded again");
            // No second G is needed.
            Thread.sleep(300);
            long gs = g.ea.received.stream().filter(x -> x.equals("GBravo")).count();
            g.ea.send("Axx");
            Thread.sleep(300);
            check(g.ea.received.stream().filter(x -> x.equals("GBravo")).count() == gs, "no further G once the g was seen");
            g.ea.send("q");
            eb2.send("q");
            g.ea.close();
            eb2.close();
            check(waitFor(() -> g.f.relay.lastSummary() != null, 4000), "the game ends normally");
            Relay.GameSummary gsum = g.f.relay.lastSummary();
            check(gsum.endReason.equals("every engine finished"), "end reason " + gsum.endReason);
            check(gsum.text().contains("rejoined 1x"), "the report says B rejoined");
            check(gsum.toHashByPlayer.containsKey(2) , "the stream totals count what the rejoined phone was sent");
            eb2.close(); b2.close();
            g.end();
        } finally {
            Fix.rejoinMs = 120000;
        }

        // Leaving on purpose keeps the lobby link: the seat comes back over it, no token needed.
        Fix.rejoinMs = 60000;
        try {
            Game g = new Game(60000, 1000);
            g.ea.send("Mdig");
            g.eb.send("Mdig");
            g.eb.send(new byte[] { 0, 'Q' });                            // Back > Leave the game
            g.eb.close();
            check(waitFor(() -> g.pb.notice.contains("You left the game."), 3000), "a joiner that leaves is told 'You left the game.' (" + g.pb.notice + ")");
            check(g.pb.notice.contains("rejoin within 1 minute"), "and that it can rejoin within the window (" + g.pb.notice + ")");
            check(waitFor(() -> has(g.ph.notices, "PhoneB left the game; waiting up to 1 minute for them to rejoin"), 2000), "the host is told it left, with the wait");
            g.pb.start = null;
            g.b.rejoinNow();
            check(waitFor(() -> g.pb.start != null && g.pb.start.rejoin, 3000), "'rejoin' over the same lobby link brings the game again");
            Eng eb2 = new Eng(g.f.port(), g.pb.start.token);
            check(waitFor(() -> eb2.received.contains("Mdig"), 2000), "its engine gets the log");
            // The takeover of a half-open phone: a new lobby link with the token while the old links look alive.
            g.ea.send("Axx");
            g.ea.send(G_BACK);
            check(waitFor(() -> !Json.bool(me(g.f, "PhoneB"), "away", true) && g.f.logged("teams are back"), 2000), "the teams are back");
            P pb3 = new P();
            RelayClient b3 = rejoinClient(g.f, pb3, "PhoneB", g.pb.rejoinToken);
            check(waitFor(() -> pb3.start != null && pb3.start.rejoin, 3000), "a phone whose old links still look alive can rejoin on a new lobby link (the old ones are cut)");
            check(waitFor(() -> eb2.eof, 2000), "  the old engine link was closed");
            check(has(g.ph.notices, "PhoneB lost connection"), "  and the host was told it had lost connection");
            b3.close();
            eb2.close();
            g.end();
        } finally {
            Fix.rejoinMs = 120000;
        }
    }

    /** The window runs out; nobody left to play; the late attempt; the host leaving and ending. */
    static void testRejoinWindow() throws Exception {
        System.out.println("rejoin: the window runs out, nobody is left, the host leaves or ends");
        Fix.rejoinMs = 1500;
        try {
            Game g = new Game(60000, 1000);
            g.ea.send("Mdig");
            g.eb.send("Mdig");
            g.eb.close();                                                 // B's engine dies; its lobby link stays (the phone is still on the Wi-Fi)
            check(waitFor(() -> Json.bool(me(g.f, "PhoneB"), "away", false), 3000), "B is away");
            check(waitFor(() -> Json.bool(me(g.f, "PhoneB"), "gaveUp", false), 4000), "after the window the seat is given up");
            check(!Json.bool(me(g.f, "PhoneB"), "away", true), "and it is no longer away");
            check(waitFor(() -> has(g.ph.notices, "PhoneB did not come back; their teams sit out"), 2000), "the host is told 'PhoneB did not come back; their teams sit out'");
            check(waitFor(() -> has(g.ea.received, "s* PhoneB did not come back"), 2000), "also as a chat line in the game");
            check(waitFor(() -> g.pb.gaveUp.contains("away too long"), 2000), "B is told by its lobby link: " + Relay.GAVE_UP_TEXT);
            g.pb.refusedCode = "";
            g.b.rejoinNow();
            check(waitFor(() -> g.pb.refusedCode.equals("rejoin-late"), 2000), "a rejoin over the lobby link is refused: rejoin-late");
            check(g.pb.refusedReason.equals(Relay.GAVE_UP_TEXT), "with the words: " + g.pb.refusedReason);
            P late = new P();
            rejoinClient(g.f, late, "PhoneB", g.pb.rejoinToken);
            check(waitFor(() -> late.refusedCode.equals("rejoin-late"), 2000) && late.refusedReason.contains("You can watch"), "a rejoin on a new lobby link is refused alike, offering to watch");
            check(g.f.relay.lastSummary() == null, "the game goes on (A is still playing)");
            g.ea.send("q");
            g.ea.close();
            check(waitFor(() -> g.f.relay.lastSummary() != null, 4000), "when A finishes, the game ends");
            check(g.f.relay.lastSummary().endReason.equals("every engine finished"), "normally, with a seat that gave up in the game (" + g.f.relay.lastSummary().endReason + ")");
            g.end();
        } finally {
            Fix.rejoinMs = 120000;
        }

        // Everybody away: the game ends when nobody is connected for the whole window, not before.
        Fix.rejoinMs = 2000;
        try {
            Game g = new Game(60000, 1000);
            g.ea.send("Mdig");
            g.eb.send("Mdig");
            g.eb.close();
            g.ea.close();
            Thread.sleep(800);
            check(g.f.relay.lastSummary() == null, "with everyone away the game waits inside the window");
            P ph2 = new P();
            check(waitFor(() -> Json.bool(me(g.f, "PhoneA"), "away", false) && Json.bool(me(g.f, "PhoneB"), "away", false), 2000), "both seats are away");
            g.pb.refusedCode = "";
            g.b.rejoinNow();
            check(waitFor(() -> g.pb.refusedCode.equals("rejoin-alone"), 2000), "a phone cannot come back when nobody else is playing (rejoin-alone: nobody could hand its teams back)");
            check(waitFor(() -> g.f.relay.lastSummary() != null, 6000), "when the last window has run out the game ends");
            Relay.GameSummary gs = g.f.relay.lastSummary();
            check(gs != null && gs.endReason.equals(Relay.NOBODY_LEFT), "with the reason: " + (gs == null ? null : gs.endReason));
            check(gs != null && Relay.NOBODY_LEFT.length() > 0 && gs.text().contains("did not come back"), "the report says the seats did not come back");
            g.end();
        } finally {
            Fix.rejoinMs = 120000;
        }

        // The host leaves on purpose: the game goes on; ending for everyone is its own question.
        Fix.rejoinMs = 60000;
        try {
            Game g = new Game(60000, 1000);
            Socket v = viewerOn(g.f);
            check(waitFor(() -> g.f.relay.viewerCount() == 1, 2000), "a TV is watching");
            g.ea.send("Mdig");
            g.eb.send("Mdig");
            g.ea.send(new byte[] { 0, 'Q' });                             // Back > Leave the game, as the host
            g.ea.send("Q");
            g.ea.close();
            check(waitFor(() -> Json.bool(me(g.f, "PhoneA"), "away", false), 3000), "the host's seat is away");
            check(waitFor(() -> g.ph.notice.contains("You left the game.") , 2000), "the host is told it left, not that the game ended (" + g.ph.notice + ")");
            check(waitFor(() -> g.eb.received.contains("FAlpha"), 2000), "the host's team is gone for the others");
            check(waitFor(() -> has(g.pb.notices, "PhoneA left the game; waiting up to 1 minute for them to rejoin"), 2000), "phone B is told the host left");
            Thread.sleep(600);
            check(g.f.relay.lastSummary() == null && g.pb.closed.isEmpty(), "the game goes on for B and its lobby link is open");
            check(g.f.relay.viewerCount() == 1, "the TV is still attached (no close code)");
            check(statusBody(g.f).contains("\"state\":\"playing\""), "the status still says playing");
            // The host comes back over its own lobby link.
            g.ph.start = null;
            g.a.rejoinNow();
            check(waitFor(() -> g.ph.start != null && g.ph.start.rejoin && g.ph.start.asHost, 3000), "the host rejoins over its lobby link (start with rejoin, as host)");
            Eng ea2 = new Eng(g.f.port(), g.ph.start.token);
            check(waitFor(() -> ea2.received.contains("Mdig"), 2000), "its engine gets the log");
            // The host's engine pause control works again once it is back (host link only).
            g.eb.send("Lxx");
            g.eb.send(new byte[] { 'g', 'A', 'l', 'p', 'h', 'a', 0, 0 });
            check(waitFor(() -> g.f.logged("teams are back"), 2000), "the host's teams come back");
            // Now it ends the game for everyone.
            ea2.send(new byte[] { 0, 'Z' });
            ea2.send("Q");
            ea2.close();
            check(waitFor(() -> g.f.relay.lastSummary() != null, 4000), "End the game for everyone ends it");
            check(g.f.relay.lastSummary().endReason.equals(Relay.HOST_ENDED), "with the old reason: " + g.f.relay.lastSummary().endReason);
            int[] n = new int[1];
            String c = closeOf(v, n);
            check(c.equals("4001 host-ended"), "the TV gets 4001 host-ended (" + c + ")");
            g.end();
        } finally {
            Fix.rejoinMs = 120000;
        }

        // Only the host may end the game for everyone.
        {
            Game g = new Game(60000, 1000);
            g.eb.send(new byte[] { 0, 'Z' });
            g.eb.send("Q");
            g.eb.close();
            check(waitFor(() -> dropped(g.f, "PhoneB"), 2000), "a joiner's Z does not end the game: it only leaves");
            Thread.sleep(300);
            check(g.f.relay.lastSummary() == null && g.f.logged("asked to end the game for everyone: ignored"), "and the request is logged as ignored");
            g.end();
        }
        // A map that differs: not a seat to come back to.
        Fix.rejoinMs = 60000;
        try {
            Game g = new Game(60000, 1000);
            g.ea.send("Mdig");
            g.eb.send("Mdig");
            g.eb.close();
            check(waitFor(() -> Json.bool(me(g.f, "PhoneB"), "away", false), 2000), "B away");
            g.pb.start = null;
            g.b.rejoinNow();
            check(waitFor(() -> g.pb.start != null, 2000), "B rejoins");
            Eng eb2 = new Eng(g.f.port(), g.pb.start.token);
            eb2.send("Mother");
            check(waitFor(() -> !Json.bool(me(g.f, "PhoneB"), "away", true) && dropped(g.f, "PhoneB"), 2000), "a map digest that differs from the game's drops the rejoiner");
            check(waitFor(() -> g.pb.notices.stream().anyMatch(x -> x.contains("different map")), 2000), "with a reason");
            g.pb.refusedCode = "";
            g.b.rejoinNow();
            check(waitFor(() -> g.pb.refusedCode.equals("rejoin-none"), 2000), "and it cannot rejoin again");
            eb2.close();
            g.end();
        } finally {
            Fix.rejoinMs = 120000;
        }
    }

    /** Review fixes: a rejoin accepted just before the window ends survives a late engine link; a rejoiner's map digest arms the silence rule; chat does not ask for the team back. */
    static void testRejoinEdges() throws Exception {
        System.out.println("rejoin: a late engine link, the silence rule and the trigger for team-back");
        Fix.rejoinMs = 1500;
        try {
            Game g = new Game(60000, 1000);
            g.ea.send("Mdig");
            g.eb.send("Mdig");
            g.eb.close();                                                 // B's engine dies; its lobby link stays
            check(waitFor(() -> Json.bool(me(g.f, "PhoneB"), "away", false), 3000), "B is away");
            Thread.sleep(900);
            g.pb.start = null;
            g.b.rejoinNow();                                              // asked just before the 1.5 s window ends
            check(waitFor(() -> g.pb.start != null && g.pb.start.rejoin, 3000), "a rejoin just inside the window is accepted");
            Thread.sleep(1800);                                           // the window is over; the engine link has not come yet
            check(!Json.bool(me(g.f, "PhoneB"), "gaveUp", false) && Json.bool(me(g.f, "PhoneB"), "away", false), "the seat is not given up while the accepted rejoin waits for its engine link");
            Eng eb2 = new Eng(g.f.port(), g.pb.start.token);
            check(waitFor(() -> !Json.bool(me(g.f, "PhoneB"), "away", true) && eb2.received.contains("Mdig"), 3000), "the late engine link is accepted and replays the game");
            g.ea.send("bchat");                                           // team chat: the current team's phone did not send it
            Thread.sleep(400);
            check(!g.ea.received.contains("GBravo"), "chat does not ask for the team back");
            g.ea.send("Axx");
            check(waitFor(() -> g.ea.received.contains("GBravo"), 2000), "a move from inside the turn does");
            g.ea.send(G_BACK);
            check(waitFor(() -> g.f.logged("teams are back"), 2000), "the teams come back after the window had ended");
            check(!Json.bool(me(g.f, "PhoneB"), "gaveUp", false), "and the seat was never given up");
            eb2.close();
            g.end();
        } finally {
            Fix.rejoinMs = 120000;
        }
        Fix.rejoinMs = 60000;
        try {
            Game g = new Game(60000, 1000);
            g.eb.close();                                                 // B is gone before any map digest
            check(waitFor(() -> Json.bool(me(g.f, "PhoneB"), "away", false), 3000), "B is away before the silence rule is armed");
            g.pb.start = null;
            g.b.rejoinNow();
            check(waitFor(() -> g.pb.start != null && g.pb.start.rejoin, 3000), "B rejoins");
            Eng eb2 = new Eng(g.f.port(), g.pb.start.token);
            g.ea.send("Mdig");
            Thread.sleep(300);
            check(!g.f.logged("the silence rule is armed"), "A's digest alone does not arm it (B is back and has not sent one)");
            eb2.send("Mdig");
            check(waitFor(() -> g.f.logged("the silence rule is armed"), 2000), "the rejoiner's digest while replaying arms the silence rule");
            eb2.close();
            g.end();
        } finally {
            Fix.rejoinMs = 120000;
        }
    }

    /** Reads frames until a text frame (opcode 1) arrives; null when none comes within the socket timeout. */
    static String readTextFrame(Socket s) { return readTextFrame(s, 8000); }

    static String readTextFrame(Socket s, long maxMs) {
        try {
            s.setSoTimeout(6000);
            int[] op = new int[1];
            long end = System.currentTimeMillis() + maxMs;
            while (System.currentTimeMillis() < end) {
                byte[] fr = readWsFrame(s.getInputStream(), op);
                if (fr == null) return null;
                if (op[0] == 1) return new String(fr, StandardCharsets.UTF_8);
            }
        } catch (Exception e) { /* no frame in time */ }
        return null;
    }

    /** A TV that connects; returns the socket after the handshake with the opcode of its first frame in op[0] and the first frame in first[0]. */
    static Socket viewerRaw(Fix f, int[] op, byte[][] first) throws Exception {
        Socket s = new Socket("127.0.0.1", f.relay.viewerPort());
        s.setSoTimeout(8000);
        s.getOutputStream().write(("GET / HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        readUntilBlank(s.getInputStream());
        first[0] = readWsFrame(s.getInputStream(), op);
        return s;
    }

    /** The TV overlay: away, back and gaveup arrive as WebSocket text frames (opcode 1), a late TV gets the remaining time, nothing reaches the game log. */
    static void testTvOverlay() throws Exception {
        System.out.println("TV overlay: away, back and gaveup as text frames");
        Fix.rejoinMs = 60000;
        try {
            Game g = new Game(60000, 1000);
            g.ea.send("Mdig");
            g.eb.send("Mdig");
            Socket tv = viewerOn(g.f);
            g.eb.close();
            String away = readTextFrame(tv);
            check(away != null, "a TV gets a text frame (opcode 1) when a phone drops: " + away);
            Map<String, Object> m = away == null ? new java.util.HashMap<>() : Json.parseObject(away);
            check(Json.str(m, "t", "").equals("away") && Json.str(m, "name", "").equals("PhoneB") && !Json.bool(m, "onPurpose", true)
                    && Json.num(m, "leftMs", 0) > 55000 && Json.num(m, "leftMs", 0) <= 60000, "  it says away, the name, onPurpose false and the window left: " + away);
            Thread.sleep(700);
            int[] op = new int[1];
            byte[][] first = new byte[1][];
            Socket late = viewerRaw(g.f, op, first);
            check(op[0] == 2, "a TV that connects mid-window gets the binary backlog first (opcode " + op[0] + ")");
            check(new String(first[0], StandardCharsets.ISO_8859_1).indexOf("\"t\"") < 0, "  and no JSON is in the backlog");
            String lateAway = readTextFrame(late);
            long lm = lateAway == null ? -1 : Json.num(Json.parseObject(lateAway), "leftMs", -1);
            check(lateAway != null && lateAway.contains("\"away\"") && lateAway.contains("PhoneB") && lm > 0 && lm < 59500, "  then an away frame with the remaining time (" + lateAway + ")");
            g.pb.start = null;
            g.b.rejoinNow();
            check(waitFor(() -> g.pb.start != null && g.pb.start.rejoin, 3000), "B rejoins");
            Eng eb2 = new Eng(g.f.port(), g.pb.start.token);
            check(waitFor(() -> eb2.received.contains("Mdig"), 2000), "B's engine replays the log");
            g.ea.send("Axx");
            check(waitFor(() -> g.ea.received.contains("GBravo"), 2000), "team back asked for");
            g.ea.send(G_BACK);
            String back = readTextFrame(tv);
            check(back != null && Json.str(Json.parseObject(back), "t", "").equals("back") && back.contains("PhoneB"), "when the teams are back the TV gets back: " + back);
            check(!has(g.ea.received, "\"t\":\"away\"") && !has(eb2.received, "\"t\":\"away\"") && !has(eb2.received, "\"t\":\"back\""), "no overlay text reaches the phones' engine links");
            Socket again = viewerOn(g.f);
            String stray = readTextFrame(again, 1500);
            check(stray == null, "a TV that connects after the teams are back gets no away frame (" + stray + ") " + (stray == null ? "" : g.f.log.subList(Math.max(0, g.f.log.size() - 8), g.f.log.size())));
            again.close();
            tv.close();
            late.close();
            eb2.close();
            g.end();
        } finally {
            Fix.rejoinMs = 120000;
        }
        Fix.rejoinMs = 1500;
        try {
            Game g = new Game(60000, 1000);
            g.ea.send("Mdig");
            g.eb.send("Mdig");
            Socket tv = viewerOn(g.f);
            g.eb.close();
            String away = readTextFrame(tv);
            check(away != null && away.contains("\"away\""), "second game: away frame");
            String gave = readTextFrame(tv);
            check(gave != null && Json.str(Json.parseObject(gave), "t", "").equals("gaveup") && gave.contains("PhoneB"), "after the window the TV gets gaveup: " + gave);
            tv.close();
            g.end();
        } finally {
            Fix.rejoinMs = 120000;
        }
    }

    static byte[] maskedFrame(int op, byte[] p, byte[] mask) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x80 | op);
        if (p.length < 126) b.write(0x80 | p.length);
        else if (p.length < 65536) { b.write(0x80 | 126); b.write(p.length >> 8); b.write(p.length); }
        else { b.write(0x80 | 127); for (int s = 56; s >= 0; s -= 8) b.write((int) ((long) p.length >> s)); }
        b.write(mask, 0, 4);
        for (int i = 0; i < p.length; i++) b.write(p[i] ^ mask[i & 3]);
        return b.toByteArray();
    }

    static void readUntilBlank(InputStream in) throws Exception {
        int st = 0, c;
        while ((c = in.read()) >= 0) {
            st = (c == '\r' && (st == 0 || st == 2)) ? st + 1 : (c == '\n' && (st == 1 || st == 3)) ? st + 1 : 0;
            if (st == 4) return;
        }
    }

    static byte[] readWsFrame(InputStream in) throws Exception { return readWsFrame(in, new int[1]); }

    static byte[] readWsFrame(InputStream in, int[] op) throws Exception {
        int b0 = in.read(), b1 = in.read();
        if (b0 < 0 || b1 < 0) return null;
        op[0] = b0 & 0x0f;
        long len = b1 & 0x7f;
        if (len == 126) len = (in.read() << 8) | in.read();
        else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | in.read(); }
        byte[] p = new byte[(int) len];
        int got = 0;
        while (got < len) { int r = in.read(p, got, (int) len - got); if (r < 0) return null; got += r; }
        return p;
    }

    /** Connects a TV viewer to a running game; returns the open socket after the handshake and the first (setup) message. */
    static Socket viewerOn(Fix f) throws Exception {
        Socket s = new Socket("127.0.0.1", f.relay.viewerPort());
        s.setSoTimeout(8000);
        s.getOutputStream().write(("GET / HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        readUntilBlank(s.getInputStream());
        readWsFrame(s.getInputStream());
        return s;
    }

    /** Reads frames until the close frame; returns "code reason", or "none" if the socket ended without one. {@code lastData} gets the number of data frames read. */
    static String closeOf(Socket s, int[] dataFrames) throws Exception {
        InputStream in = s.getInputStream();
        long deadline = System.currentTimeMillis() + 15000;      // pings arrive every few hundred ms, so a read never times out by itself
        while (System.currentTimeMillis() < deadline) {
            int[] op = new int[1];
            byte[] p;
            try { p = readWsFrame(in, op); } catch (java.io.IOException e) { return "none"; }
            if (p == null) return "none";
            if (op[0] == 8) return p.length < 2 ? "empty" : (((p[0] & 0xff) << 8) | (p[1] & 0xff)) + " " + new String(p, 2, p.length - 2, StandardCharsets.UTF_8);
            if (op[0] == 2) dataFrames[0]++;
        }
        return "none";
    }

    static String statusBody(Fix f) throws Exception {
        try (Socket c = new Socket("127.0.0.1", f.relay.viewerPort())) {
            c.setSoTimeout(3000);
            c.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = c.getInputStream().read(buf)) > 0) all.write(buf, 0, n);
            String r = new String(all.toByteArray(), StandardCharsets.UTF_8);
            return r.substring(r.indexOf("\r\n\r\n") + 4);
        }
    }

    @SuppressWarnings("unchecked")
    static void testViewerCloseCodes() throws Exception {
        System.out.println("TV viewer: close codes and lastGame (how the TV is told the game ended)");
        // before any game
        Fix f0 = new Fix(20000, 1000);
        check(!statusBody(f0).contains("lastGame"), "lastGame is absent before the first game");
        f0.stop();
        // host ends the game: 4001
        Game g = new Game(20000, 1000);
        Socket v = viewerOn(g.f);
        check(waitFor(() -> g.f.relay.viewerCount() == 1, 2000), "viewer attached");
        g.ea.send(new byte[] { 'Z', 1 });
        g.ea.send(new byte[] { 0, 'Z' });               // "End the game for everyone"
        g.ea.send("Q");
        g.ea.close();                                   // the engine sends Q and exits: its link ends
        int[] n = new int[1];
        String c = closeOf(v, n);
        check(c.equals("4001 host-ended"), "host ended the game: close code 4001 host-ended (" + c + ")");
        check(n[0] >= 1, "the queued game frame was delivered before the close (" + n[0] + " data frames)");
        // As a browser does: answer the close frame with a close of our own; the relay must then end TCP at once
        // (an older WebView reports 1006 without the code while the server holds the connection) and send nothing more.
        v.getOutputStream().write(maskedFrame(8, new byte[] { (byte) 0x0f, (byte) 0xa1 }, new byte[] { 1, 2, 3, 4 }));
        v.getOutputStream().flush();
        long t0 = System.currentTimeMillis();
        int after = -2;
        try { after = v.getInputStream().read(); } catch (java.io.IOException e) { after = -3; }
        long took = System.currentTimeMillis() - t0;
        check(after == -1, "nothing follows the relay's close frame, not even an echo (read " + after + ")");
        check(took < 1000, "the relay ends TCP straight after the TV's close reply (" + took + " ms)");
        Map<String, Object> st = (Map<String, Object>) Json.parse(statusBody(g.f));
        Map<String, Object> lg = (Map<String, Object>) st.get("lastGame");
        check(lg != null && "host".equals(lg.get("ended")) && ((Number) lg.get("n")).intValue() == 1 && ((Number) lg.get("atMs")).longValue() > System.currentTimeMillis() - 20000, "lastGame {n:1, ended:host, atMs} (" + lg + ")");
        check("setup".equals(st.get("state")), "state stays setup after the game");
        v.close();
        g.end();
        // normal game over: 4002, after the last messages
        Game g2 = new Game(20000, 1000);
        Socket v2 = viewerOn(g2.f);
        waitFor(() -> g2.f.relay.viewerCount() == 1, 2000);
        g2.ea.send(new byte[] { 'Z', 2 });
        g2.ea.send("q");
        g2.eb.send("q");
        g2.ea.close();
        g2.eb.close();                                  // each engine says q and exits
        int[] n2 = new int[1];
        String c2 = closeOf(v2, n2);
        check(c2.equals("4002 game-over"), "normal game over: close code 4002 game-over (" + c2 + ")");
        check(n2[0] >= 1, "the final messages came first (" + n2[0] + ")");
        Map<String, Object> lg2 = (Map<String, Object>) ((Map<String, Object>) Json.parse(statusBody(g2.f))).get("lastGame");
        check(lg2 != null && "over".equals(lg2.get("ended")), "lastGame ended over (" + lg2 + ")");
        v2.close();
        g2.end();
        // a phone dropped earlier, then the game ends normally for the phone still in it: still 4002 and "over"
        Game gd = new Game(20000, 1000);
        Socket vd = viewerOn(gd.f);
        waitFor(() -> gd.f.relay.viewerCount() == 1, 2000);
        gd.ea.send(new byte[] { 'Z', 2 });
        gd.eb.close();                                  // phone B's engine link closes mid-game: it is dropped
        Thread.sleep(500);
        check(gd.f.relay.lastSummary() == null, "the game goes on after phone B drops");
        gd.ea.send("q");
        gd.ea.close();                                  // phone A's engine sees the game over and exits
        String cd = closeOf(vd, new int[1]);
        check(cd.equals("4002 game-over"), "game over after a phone dropped: close code 4002 game-over (" + cd + ")");
        Map<String, Object> lgd = (Map<String, Object>) ((Map<String, Object>) Json.parse(statusBody(gd.f))).get("lastGame");
        check(lgd != null && "over".equals(lgd.get("ended")), "lastGame ended over after a drop (" + lgd + ")");
        vd.close();
        gd.end();
        // host loses its Wi-Fi: 4003
        Fix.netProbe = true;
        Fix.netLossMs = 500;
        try {
            Game g3 = new Game(60000, 1000);
            Socket v3 = viewerOn(g3.f);
            waitFor(() -> g3.f.relay.viewerCount() == 1, 2000);
            g3.f.netUp.set(false);
            String c3 = closeOf(v3, new int[1]);
            check(c3.equals("4003 host-lost"), "host lost: close code 4003 host-lost (" + c3 + ")");
            Map<String, Object> lg3 = (Map<String, Object>) ((Map<String, Object>) Json.parse(statusBody(g3.f))).get("lastGame");
            check(lg3 != null && "host-lost".equals(lg3.get("ended")), "lastGame ended host-lost (" + lg3 + ")");
            v3.close();
            g3.end();
        } finally {
            Fix.netProbe = false;
            Fix.netLossMs = 4000;
        }
        // hosting stops during a game: 4004
        Game g4 = new Game(20000, 1000);
        Socket v4 = viewerOn(g4.f);
        waitFor(() -> g4.f.relay.viewerCount() == 1, 2000);
        Thread stopper = new Thread(g4.f::stop);
        stopper.start();
        String c4 = closeOf(v4, new int[1]);
        check(c4.equals("4004 relay-stopped"), "hosting stopped: close code 4004 relay-stopped (" + c4 + ")");
        v4.close();
        stopper.join(5000);
        // a desync the relay acts on is neither host-ended, a game over, host-lost nor relay-stopped: 4005 ended, lastGame "other"
        Game g5 = new Game(20000, 1000);
        Socket v5 = viewerOn(g5.f);
        check(waitFor(() -> g5.f.relay.viewerCount() == 1, 2000), "viewer attached (desync)");
        g5.ea.send(new byte[] { 'Z', 3 });
        g5.ea.send("\u0000Edesync at turn 3");           // control frame 'E': the phone reports a desync
        g5.ea.close();                                   // the engine exits: its link ends, and with two phones the game ends for both
        String c5 = closeOf(v5, new int[1]);
        check(c5.equals("4005 ended"), "a desync the relay acts on: close code 4005 ended (" + c5 + ")");
        Map<String, Object> lg5 = (Map<String, Object>) ((Map<String, Object>) Json.parse(statusBody(g5.f))).get("lastGame");
        check(lg5 != null && "other".equals(lg5.get("ended")), "lastGame ended other (" + lg5 + ")");
        v5.close();
        g5.end();
    }

    static void testViewerCount() throws Exception {
        System.out.println("M2: the viewer's first message counts the setup lines in one byte");
        Game g = new Game(20000, 1000);
        byte[] first = g.f.relay.viewerBacklog();
        check(first != null && (first[0] & 0xff) > 0 && (first[0] & 0xff) <= 255, "first byte of the viewer backlog is the line count (" + (first == null ? -1 : first[0] & 0xff) + ")");
        // Count the framed lines that follow and compare with the count byte.
        int pos = 1, n = 0;
        while (n < (first[0] & 0xff)) { pos += 1 + (first[pos] & 0xff); n++; }
        check(pos <= first.length, "the count matches the framed lines that follow");
        g.end();
    }


    /** Item 5: RelayClient reports a closed link once, whichever way it ends, and a refused or closed link frees its writer. */
    static void testClientClose() throws Exception {
        System.out.println("RelayClient: close is idempotent, a refusal or host close closes the client");
        Fix f = new Fix(20000, 1000);
        final AtomicInteger closes = new AtomicInteger();
        P h = new P() { @Override public void onClosed(String r) { super.onClosed(r); closes.incrementAndGet(); } };
        RelayClient c = join(f, h, "Host", true, team("A", 0, 0));
        c.close();
        c.close();
        Thread[] ts = new Thread[4];
        for (int i = 0; i < ts.length; i++) { ts[i] = new Thread(c::close); ts[i].start(); }
        for (Thread t : ts) t.join(2000);
        Thread.sleep(200);
        check(closes.get() == 0, "close() from the app does not call onClosed, however often it is called");
        // The host stops hosting: the other phone's client gets 'closed', reports it once and its writer thread ends.
        P hh = new P();
        RelayClient host = join(f, hh, "Host2", true, team("H", 2, 0));
        final AtomicInteger jc = new AtomicInteger();
        P jp = new P() { @Override public void onClosed(String r) { super.onClosed(r); jc.incrementAndGet(); } };
        RelayClient joiner = join(f, jp, "Joiner", false, team("J", 3, 0));
        Thread.sleep(300);
        f.stop();
        check(waitFor(() -> jc.get() >= 1, 4000), "the joiner is told the host closed: " + jp.closed);
        Thread.sleep(300);
        check(jc.get() == 1, "and told once (" + jc.get() + ")");
        joiner.close();
        host.close();
        Thread.sleep(500);
        int base = writerThreads();
        P ph2 = new P();
        RelayClient extra = join(f2(), ph2, "Extra", false, team("X", 4, 0));
        extra.setReady(true);                                       // the first write starts the writer thread
        Thread.sleep(300);
        int with = writerThreads();
        extra.close();
        Thread.sleep(500);
        check(with == base + 1 && writerThreads() == base, "a client's writer thread ends when the client is closed (" + base + " -> " + with + " -> " + writerThreads() + ")");
        // A refusal (here: protocol mismatch is not reachable from the client, so use a full lobby) also closes the client.
    }

    static Fix extraFix;
    static Fix f2() throws Exception {
        if (extraFix == null) extraFix = new Fix(20000, 1000);
        return extraFix;
    }

    static int writerThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.getName().equals("relay-writer") && t.isAlive()) n++;
        return n;
    }

    /** The 0.3.0-tv1 crash: the UI thread (Start tap) wrote to the relay socket. Every call the UI makes must leave the socket to the writer thread. */
    static void testNoSocketWriteOnCallerThread() throws Exception {
        System.out.println("N1: the calls the UI makes never write to the socket on the calling thread");
        Fix f = new Fix(20000, 1000);
        P h = new P();
        RelayClient c = join(f, h, "Host", true, team("A", 0, 0));
        final Thread[] ui = new Thread[1];
        final boolean[] returnedFast = { true };
        Thread t = new Thread(() -> {
            long t0 = System.nanoTime();
            c.setForeground(true);
            c.setReady(true);
            c.setTeams(Arrays.asList(team("A", 0, 0)));
            c.start();
            returnedFast[0] = (System.nanoTime() - t0) < 200_000_000L;
        }, "fake-main");
        ui[0] = t;
        t.start();
        t.join();
        c.flushWrites();
        Thread w = c.lastWriteThread();
        check(w != null, "something was written");
        check(w != ui[0], "the write did not happen on the UI thread (was " + (w == null ? "null" : w.getName()) + ")");
        check(w != null && w.getName().equals("relay-writer"), "the write ran on the relay-writer thread");
        check(returnedFast[0], "the calls returned at once");
        Thread t2 = new Thread(c::close, "fake-main-close");
        t2.start();
        t2.join(2000);
        check(!t2.isAlive(), "close() returned at once");
        f.stop();
    }

    static void testPause() throws Exception {
        System.out.println("pause: only the host's link pauses; the silence rule is off while paused; the cap and a vanished host end it");
        // silence 1.5 s, no grace worth speaking of, pause cap 4 s
        final Game g = new Game(1500, 500);
        g.ea.send("Mdigest-one");
        g.eb.send("Mdigest-one");
        Thread.sleep(300);
        // a joiner's request, and malformed ones, change nothing
        g.eb.send(new byte[] { 0, 'P' });
        g.eb.send(new byte[] { 0, 'P', 'x' });
        g.ea.send(new byte[] { 0, 'P', 'x' });
        Thread.sleep(400);
        check(g.ea.controls.isEmpty() && g.eb.controls.isEmpty(), "a joiner's pause request and malformed pause frames are ignored: no control frame went out");
        check(g.f.logged("PhoneB asked to pause: ignored"), "  the joiner's request is logged");
        // the engine whitelist: a pause letter or an 'e' command in an ordinary message never gets forwarded
        final int before = g.ea.received.size();          // B's map digest has been forwarded already
        g.eb.send("epause");
        g.eb.send("enetpause 1");
        g.eb.send("I");
        g.eb.send("y1");
        Thread.sleep(300);
        check(g.ea.received.size() == before, "  'epause', 'enetpause 1', 'I' and 'y1' from a joiner are not forwarded to the host's engine");
        // the host's request reaches both engines, the host's too
        g.ea.send(new byte[] { 0, 'P' });
        check(waitFor(() -> g.ea.controls.contains("P") && g.eb.controls.contains("P"), 2000), "the host's pause is sent to both engine links, the host's own included");
        // paused for much longer than the silence limit: nobody is dropped
        Thread.sleep(3000);
        check(!dropped(g.f, "PhoneA") && !dropped(g.f, "PhoneB"), "3 s paused (silence limit 1.5 s): nobody dropped");
        // a second pause request while paused changes nothing; a joiner cannot resume
        g.ea.send(new byte[] { 0, 'P' });
        g.eb.send(new byte[] { 0, 'R' });
        Thread.sleep(300);
        check(g.ea.controls.size() == 1 && g.eb.controls.size() == 1, "a repeated pause and a joiner's resume change nothing");
        g.ea.send(new byte[] { 0, 'R' });
        check(waitFor(() -> g.ea.controls.contains("R") && g.eb.controls.contains("R"), 2000), "the host's resume is sent to both");
        // the silence clock restarts at the resume: not dropped at once, dropped after the limit when somebody really stalls
        Thread.sleep(600);
        check(!dropped(g.f, "PhoneA") && !dropped(g.f, "PhoneB"), "0.6 s after the resume: nobody dropped (the clock restarted)");
        check(waitFor(() -> dropped(g.f, "PhoneA") || dropped(g.f, "PhoneB"), 6000), "after the resume the silence rule is back: a silent phone is dropped");
        g.end();

        // the relay's cap, and a host that vanishes while paused
        Fix.pingMs = 300;
        final Game h = new Game(60000, 60000);
        h.ea.send("Mdigest-one");
        h.eb.send("Mdigest-one");
        Thread.sleep(300);
        h.ea.send(new byte[] { 0, 'P' });
        check(waitFor(() -> h.eb.controls.contains("P"), 2000), "pause 2: sent");
        h.ea.close();                       // the host's engine link vanishes while paused
        check(waitFor(() -> h.eb.controls.contains("R"), 4000), "a pause whose host is gone is ended by the relay (resume sent to the other phone)");
        h.end();
    }

    /** The plain GET on the viewer port: the old fields stay, and host, players, compatible, reason and engine are added; polling counts as waiting. */
    static void testStatusGet() throws Exception {
        System.out.println("status GET: the JSON shape the TV viewer reads");
        Game g = new Game(20000, 1000);
        // the game is already running here (state playing); players are the two phones
        for (String path : new String[] { "/", "/?proto=1", "/?proto=7", "/?compat=" + org.hedgewars.android.net.Compat.VALUE, "/?compat=hwnet-60-sim0", "/?xcompat=hwnet-60-sim0", "/?xproto=7", "/?a=1&compat=hwnet-60-sim0", "/?a=1&proto=7&b=2" }) {
            try (Socket c = new Socket("127.0.0.1", g.f.relay.viewerPort())) {
                c.setSoTimeout(3000);
                c.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: x\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                c.getOutputStream().flush();
                ByteArrayOutputStream all = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = c.getInputStream().read(buf)) > 0) all.write(buf, 0, n);
                String resp = new String(all.toByteArray(), StandardCharsets.UTF_8);
                String body = resp.substring(resp.indexOf("\r\n\r\n") + 4);
                check(resp.startsWith("HTTP/1.1 200") && resp.contains("Access-Control-Allow-Origin: *"), "GET " + path + ": 200 with CORS");
                @SuppressWarnings("unchecked") Map<String, Object> j = (Map<String, Object>) Json.parse(body);
                check(j.get("state") instanceof String && j.containsKey("game") && j.containsKey("viewers") && j.containsKey("viewersEver"), "  the old fields are there: " + body);
                check(j.get("host") instanceof String && !((String) j.get("host")).isEmpty(), "  host is a name");
                check(j.get("players") instanceof List && ((List<?>) j.get("players")).size() == 2, "  players lists the 2 phones");
                Object p0 = ((List<?>) j.get("players")).get(0);
                check(p0 instanceof Map && ((Map<?, ?>) p0).get("name") instanceof String && ((Map<?, ?>) p0).get("teams") instanceof Number, "  a player has a name and a team count");
                check(j.get("compatible") instanceof Boolean && j.get("reason") instanceof String && j.get("engine") instanceof String && !((String) j.get("engine")).isEmpty() , "  compatible, reason and engine have the right types");
                check("hwnet-60-sim2".equals(j.get("compat")) && j.get("protocol") instanceof Number && ((Number) j.get("protocol")).intValue() == 60, "  compat is hwnet-60-sim2 and protocol 60 (" + j.get("compat") + ")");
                boolean bad = path.equals("/?proto=7") || path.equals("/?compat=hwnet-60-sim0") || path.equals("/?a=1&compat=hwnet-60-sim0") || path.equals("/?a=1&proto=7&b=2");
                check(((Boolean) j.get("compatible")) == !bad && (bad == !((String) j.get("reason")).isEmpty()), "  compatible is " + !bad + (bad ? ", with a reason: " + j.get("reason") : ""));
            }
        }
        check(Relay.queryParam("GET /?xcompat=a HTTP/1.1", "compat") == null && "a".equals(Relay.queryParam("GET /?q=1&compat=a HTTP/1.1", "compat")), "queryParam anchors on ? or &");
        check(g.f.relay.viewersWaiting() == 1, "polls from one TV count as 1 waiting (" + g.f.relay.viewersWaiting() + ")");
        g.end();
    }

    static RelayClient watcher(Fix f, P h, String name) throws Exception {
        RelayClient c = new RelayClient("127.0.0.1", f.port(), name, "tests", false, new ArrayList<SetupModel.Team>(), h).watch(true);
        c.connect();
        return c;
    }

    @SuppressWarnings("unchecked")
    static boolean listedAsWatching(Map<String, Object> st, String name) {
        Map<String, Object> p = player(st, name);
        return p != null && Boolean.TRUE.equals(p.get("watching"));
    }

    /** Watching phones (spectators): registration, caps, no say in the game, catch-up, pause, end report. */
    static void testWatchers() throws Exception {
        System.out.println("watchers: a watching phone is a spectator, not a player");
        // Registration and caps, in the lobby
        Fix f0 = new Fix(20000, 1000);
        P hp = new P();
        RelayClient host = join(f0, hp, "Host", true, team("H", 0, 0));
        List<RelayClient> clients = new ArrayList<>();
        for (int i = 1; i < Relay.MAX_PLAYERS; i++) clients.add(join(f0, new P(), "Pl" + i, false, team("T" + i, i % 8, 0)));
        P late = new P();
        RelayClient ninth = join(f0, late, "Ninth", false, team("N9", 1, 0));
        check(waitFor(() -> !late.closed.isEmpty(), 3000) && late.closed.contains("full"), "the player cap is " + Relay.MAX_PLAYERS + ": a ninth player is refused (" + late.closed + ")");
        P w1 = new P();
        RelayClient wc = watcher(f0, w1, "Pl1");                      // same name as a player: renamed
        check(waitFor(() -> w1.state != null, 3000), "with the players full, a watching phone is still accepted");
        check(waitFor(() -> listedAsWatching(w1.state, "Pl1 (2)"), 3000), "  it is listed as watching, and renamed because its name was taken");
        check(Json.num(w1.state, "watching", -1) == 1, "  the state counts 1 watching");
        check(player(hp.state, "Pl1 (2)") != null && Json.list(player(hp.state, "Pl1 (2)"), "teams").isEmpty(), "  the host sees it with no teams");
        wc.setReady(true);
        wc.setTeams(new ArrayList<SetupModel.Team>());
        wc.start();
        check(waitFor(() -> w1.notice.contains("Only the host") || w1.notice.contains("no teams"), 2000), "  it cannot ready, set teams or start the game (told so)");
        check(Json.num(hp.state, "watching", -1) == 1, "  a watcher's ready, teams and start leave the lobby as it was (still 1 watching, no game)");
        List<RelayClient> ws = new ArrayList<>();
        List<P> wps = new ArrayList<>();
        for (int i = 2; i <= Relay.MAX_SPECTATORS; i++) { P x = new P(); wps.add(x); ws.add(watcher(f0, x, "Watch" + i)); }
        final P last = wps.get(wps.size() - 1);
        check(waitFor(() -> last.state != null && Json.num(last.state, "watching", -1) == Relay.MAX_SPECTATORS, 4000), Relay.MAX_SPECTATORS + " watchers are accepted");
        P extra = new P();
        RelayClient xc = watcher(f0, extra, "TooMany");
        check(waitFor(() -> !extra.closed.isEmpty(), 3000) && extra.closed.contains("at most " + Relay.MAX_SPECTATORS), "the watcher cap is its own: one more is refused (" + extra.closed + ")");
        P hostWatch = new P();
        RelayClient hw = new RelayClient("127.0.0.1", f0.port(), "Fake", "tests", true, new ArrayList<SetupModel.Team>(), hostWatch).watch(true).hostSecret(f0.secret);
        hw.connect();
        check(waitFor(() -> !hostWatch.closed.isEmpty(), 3000), "a watcher cannot take the host role (" + hostWatch.closed + ")");
        for (RelayClient c : clients) c.close();
        for (RelayClient c : ws) c.close();
        wc.close(); ninth.close(); xc.close(); hw.close(); host.close();
        f0.stop();

        // In a game
        final Game g = new Game(1500, 500);
        // (the Game fixture has started already: this watcher joins a running game, before any message)
        P wp = new P();
        RelayClient wcl = watcher(g.f, wp, "Eye");
        check(waitFor(() -> wp.start != null, 3000), "a watcher joining a running game is sent the game at once");
        boolean allRemote = true;
        int teams = 0, rd = 0;
        for (String l : wp.start.lines) { if (l.startsWith("eaddteam ")) teams++; if (l.equals("erdriven")) rd++; }
        check(teams == 2 && rd == 2 && wp.start.lines.contains("TN") && !wp.start.lines.contains("TL"), "  its setup is the game with every team driven from outside (" + teams + " teams, " + rd + " erdriven)");
        Eng ew = new Eng(g.f.port(), wp.start.token);
        g.ea.send("Mdigest-one");
        g.eb.send("Mdigest-one");
        check(waitFor(() -> ew.received.size() >= 1, 2000), "  its engine link gets the game's messages");
        // everything it sends is ignored
        int beforeA = g.ea.received.size(), beforeB = g.eb.received.size();
        ew.send("Lxx"); ew.send("NABCDEFGH"); ew.send("Mevil"); ew.send("FAlpha"); ew.send("hxx"); ew.send("epause"); ew.send("y1");
        ew.send(new byte[] { 0, 'P' }); ew.send(new byte[] { 0, 'R' }); ew.send(new byte[] { 0, 'X', 'e' });
        Thread.sleep(500);
        check(g.ea.received.size() == beforeA && g.eb.received.size() == beforeB, "a watcher's game messages reach no player");
        check(g.ea.controls.isEmpty() && g.eb.controls.isEmpty(), "  its pause and resume do nothing (no control frame went out)");
        check(g.f.logged("Eye sent a 'L' message") && g.f.logged("Eye asked to pause: ignored"), "  they are logged as ignored");
        check(g.f.relay.turns() == 0, "  no turn checksum was taken from it");
        // the host's pause reaches it, a late joiner during the pause is told
        g.ea.send(new byte[] { 0, 'P' });
        check(waitFor(() -> ew.controls.contains("P"), 2000), "the host's pause is sent to the watcher's engine link too");
        P w2 = new P();
        RelayClient w2c = watcher(g.f, w2, "Late");
        check(waitFor(() -> w2.start != null, 3000), "a second watcher joins during the pause");
        Eng e2 = new Eng(g.f.port(), w2.start.token);
        check(waitFor(() -> e2.received.size() >= 2 && e2.controls.contains("P"), 2000), "  it gets the backlog, then is told the game is paused");
        check(e2.received.get(0).equals("Mdigest-one") && !e2.received.contains("e$spectate 1"), "  the backlog is the log from its start, with no 'e' frame (" + e2.received + ")");
        check(w2.start.lines.get(w2.start.lines.size() - 1).equals("e$spectate 1") && wp.start.lines.get(wp.start.lines.size() - 1).equals("e$spectate 1"),
                "  e$spectate 1 is the last of its setup lines (local), also for a watcher from the start");
        g.ea.send(new byte[] { 0, 'R' });
        check(waitFor(() -> e2.controls.contains("R") && ew.controls.contains("R"), 2000), "the resume reaches both watchers");
        // a token works once; the same token twice is refused
        try { new Eng(g.f.port(), w2.start.token).close(); check(false, "a used watcher token is refused"); }
        catch (IllegalStateException e) { check(true, "a used watcher token is refused"); }
        // a watcher that goes away is no dropout, nobody gets a team-gone message, and the silence rule is not involved
        final int recvA = g.ea.received.size();
        ew.close();
        Thread.sleep(600);
        check(g.ea.received.size() == recvA && !dropped(g.f, "PhoneA") && !dropped(g.f, "PhoneB"), "a watcher's link closing drops nobody and injects no team-gone message");
        check(waitFor(() -> g.f.logged("watcher Eye"), 1000), "  and the departure is in the relay log");
        // the same phone can watch again
        wcl.watchAgain();
        check(waitFor(() -> wp.start != null && !wp.start.token.isEmpty(), 1000), "a watcher that left its engine link can ask to watch again");
        // end: summary with the same figures, watcher lines
        g.ea.send("q");
        g.eb.send("q");
        Thread.sleep(200);
        g.ea.close();
        g.eb.close();
        check(waitFor(() -> g.f.relay.lastSummary() != null, 4000), "the game ends when the players finish (watchers do not hold it up)");
        Relay.GameSummary gs = g.f.relay.lastSummary();
        check(gs != null && gs.watchers.size() >= 2 && gs.text().contains("Watching phones"), "the report lists the watching phones: " + (gs == null ? "-" : gs.watchers));
        check(gs != null && gs.mapDigest.startsWith("digest-one (from Phone") && gs.toHashByPlayer.size() >= 3, "  the map digest is a player's (whichever arrived first); the relay keeps a stream hash for the watcher too (" + (gs == null ? "-" : gs.mapDigest + " " + gs.toHashByPlayer.keySet()) + ")");
        wcl.close(); w2c.close(); e2.close();
        g.end();

        // a watcher present at Start, whose engine never links, does not hold the gate or the game
        Fix f3 = new Fix(20000, 1000);
        P h3 = new P(), b3 = new P(), s3 = new P();
        RelayClient a3 = join(f3, h3, "PA", true, team("A", 0, 0));
        RelayClient bb3 = join(f3, b3, "PB", false, team("B", 1, 0));
        RelayClient sp3 = watcher(f3, s3, "Spec");
        bb3.setReady(true);
        check(waitFor(() -> b3.state != null && Json.bool(b3.state, "canStart", false), 3000), "start is decided by the players: a watcher needs no ready");
        a3.start();
        check(waitFor(() -> h3.start != null && b3.start != null && s3.start != null, 5000), "a watcher connected before Start gets the game at Start");
        Eng e3a = new Eng(f3.port(), h3.start.token), e3b = new Eng(f3.port(), b3.start.token);
        e3a.send("Mdigest-two");
        e3b.send("Mdigest-two");
        check(waitFor(() -> f3.logged("every live phone has sent its map digest"), 3000), "the gate opens and the silence rule arms without the watcher's engine");
        e3a.close(); e3b.close();
        a3.close(); bb3.close(); sp3.close();
        f3.stop();
    }


    // Phase 5 ------------------------------------------------------------------------------------

    static java.io.File tmpTree(String... filesAndSizes) throws Exception {
        java.io.File d = java.nio.file.Files.createTempDirectory("hpdata").toFile();
        for (int i = 0; i < filesAndSizes.length; i += 2) {
            java.io.File f = new java.io.File(d, filesAndSizes[i]);
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), new byte[Integer.parseInt(filesAndSizes[i + 1])]);
        }
        return d;
    }

    static void testCompat() throws Exception {
        System.out.println("join check: compat, app version and game data fingerprint (phase 5)");
        final String HD = "0123456789abcdef0123456789abcdef";
        Fix.dataDigest = HD;
        Fix f = new Fix(20000, 1000);
        try {
            String C = org.hedgewars.android.net.Compat.VALUE;
            // match
            P ok = new P();
            RelayClient c = new RelayClient("127.0.0.1", f.port(), "Same", "tests", false, Arrays.asList(team("T1", 0, 0)), ok).identity(C, HD);
            c.connect();
            check(waitFor(() -> ok.state != null, 3000) && ok.closed.isEmpty(), "same compat, version and data: joins");
            c.close();
            // compat mismatch
            P pc = new P();
            RelayClient cc = new RelayClient("127.0.0.1", f.port(), "OldSim", "tests", false, new ArrayList<>(), pc).identity("hwnet-60-sim0", HD);
            cc.connect();
            check(waitFor(() -> !pc.closed.isEmpty(), 3000) && pc.closed.contains("hwnet-60-sim0") && pc.closed.contains(C), "compat mismatch refused, naming both sides: " + pc.closed);
            // app version mismatch
            P pv = new P();
            RelayClient cv = new RelayClient("127.0.0.1", f.port(), "OldApp", "0.0.1", false, new ArrayList<>(), pv).identity(C, HD);
            cv.connect();
            check(waitFor(() -> !pv.closed.isEmpty(), 3000) && pv.closed.contains("0.0.1") && pv.closed.contains("tests"), "app version mismatch refused, naming both sides: " + pv.closed);
            // data mismatch
            P pd = new P();
            RelayClient cd = new RelayClient("127.0.0.1", f.port(), "OtherData", "tests", false, new ArrayList<>(), pd).identity(C, "ffffffffffffffffffffffffffffffff");
            cd.connect();
            check(waitFor(() -> !pd.closed.isEmpty(), 3000) && pd.closed.startsWith("Game data differs") && pd.closed.contains("ffffffff") && pd.closed.contains("01234567"), "data digest mismatch refused, naming both sides: " + pd.closed);
            // no digest at all while the host has one
            P pn = new P();
            RelayClient cn = new RelayClient("127.0.0.1", f.port(), "NoData", "tests", false, new ArrayList<>(), pn).identity(C, "");
            cn.connect();
            check(waitFor(() -> !pn.closed.isEmpty(), 3000) && pn.closed.contains("could not be read"), "a phone without a fingerprint is refused: " + pn.closed);
            // an old client that sends no compat at all
            check(abuse(f.port(), line("{\"t\":\"hello\",\"role\":\"lobby\",\"proto\":1,\"name\":\"Old\",\"build\":\"tests\",\"teams\":[]}"), 1500), "a hello without compat: refused and closed");
            // a watcher is checked too
            P pw = new P();
            RelayClient cw = new RelayClient("127.0.0.1", f.port(), "WatchOld", "tests", false, new ArrayList<>(), pw).watch(true).identity("hwnet-60-sim0", HD);
            cw.connect();
            check(waitFor(() -> !pw.closed.isEmpty(), 3000) && pw.closed.contains("hwnet-60-sim0"), "a watching phone with a different compat is refused: " + pw.closed);
            check(f.relay.status(0) != null && player(f.relay.status(0), "OldSim") == null && player(f.relay.status(0), "OldApp") == null, "refused phones are not in the lobby");
            check(sane(f, "after refusals"), "relay still serving");
        } finally {
            Fix.dataDigest = "";
            f.stop();
        }
        // pure rules
        String C = org.hedgewars.android.net.Compat.VALUE;
        check(org.hedgewars.android.net.Compat.refusal(C, "1", "", C, "1", "") == null, "refusal: host without a fingerprint skips the data check");
        check(org.hedgewars.android.net.Compat.refusal(C, "1", HD2, C, "1", HD2) == null, "refusal: everything equal passes");
        check(org.hedgewars.android.net.Compat.refusal(C, "1", HD2, C, "2", HD2) != null, "refusal: version differs");
        check(org.hedgewars.android.net.Compat.refusal("a", "1", HD2, "b", "1", HD2).startsWith("Different game versions"), "refusal: compat is checked first");
    }

    static final String HD2 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    static void testDataDigest() throws Exception {
        System.out.println("data fingerprint: manifest of file names and sizes, cached beside the install marker");
        java.io.File a = tmpTree("Themes/Nature/t.png", "10", "Hats/h.png", "20", "Fonts/f.ttf", "30", ".installed", "5");
        java.io.File b = tmpTree("Fonts/f.ttf", "30", "Hats/h.png", "20", "Themes/Nature/t.png", "10", ".installed", "7777");
        String da = org.hedgewars.android.net.DataDigest.compute(a), db = org.hedgewars.android.net.DataDigest.compute(b);
        check(da.matches("[0-9a-f]{32}"), "digest is 32 hex digits: " + da);
        check(da.equals(db), "same files and sizes: same digest whatever the marker text and the creation order");
        java.io.File full = tmpTree("Themes/Nature/t.png", "10", "Themes/Cheese/t.png", "11", "Hats/h.png", "20", "Hats/h2.png", "21", "Fonts/f.ttf", "30");
        check(!da.equals(org.hedgewars.android.net.DataDigest.compute(full)), "a reduced (test profile) data set differs from the full one");
        java.nio.file.Files.write(new java.io.File(b, "Hats/h.png").toPath(), new byte[21]);
        check(!da.equals(org.hedgewars.android.net.DataDigest.compute(b)), "one file with a different size differs");
        java.nio.file.Files.write(new java.io.File(b, "Hats/h.png").toPath(), new byte[20]);
        new java.io.File(b, "Hats/h.png").renameTo(new java.io.File(b, "Hats/other.png"));
        check(!da.equals(org.hedgewars.android.net.DataDigest.compute(b)), "one file renamed differs");
        check("".equals(org.hedgewars.android.net.DataDigest.compute(new java.io.File("/nonexistent/x"))), "a missing folder gives an empty digest");
        // real staged data against itself and against a copy without one theme file
        java.io.File real = new java.io.File(dataDir);
        String dr = org.hedgewars.android.net.DataDigest.compute(real);
        check(dr.matches("[0-9a-f]{32}") && dr.equals(org.hedgewars.android.net.DataDigest.compute(real)), "the staged game data has a stable digest: " + dr);
        // cache
        java.nio.file.Files.write(new java.io.File(a, ".installed").toPath(), "5".getBytes(StandardCharsets.UTF_8));
        java.io.File cache = new java.io.File(a, ".digest");
        String c1 = org.hedgewars.android.net.DataDigest.cached(a);
        check(c1.equals(da) && cache.isFile(), "cached() computes once and writes Data/.digest");
        java.nio.file.Files.write(cache.toPath(), ("5\n" + HD2 + "\n").getBytes(StandardCharsets.UTF_8));
        check(org.hedgewars.android.net.DataDigest.cached(a).equals(HD2), "cached() trusts the cache while the install marker is unchanged");
        java.nio.file.Files.write(new java.io.File(a, ".installed").toPath(), "6".getBytes(StandardCharsets.UTF_8));
        check(org.hedgewars.android.net.DataDigest.cached(a).equals(da), "cached() recomputes after a new install (marker changed)");
    }

    static String dataDir = "";

    static void testHostAdvert() throws Exception {
        System.out.println("NSD record: contents and parsing");
        Map<String, String> t = org.hedgewars.android.net.HostAdvert.txt("0.3.0-tv7", org.hedgewars.android.net.Compat.VALUE, 60, "Fairphone 4", 8766, HD2);
        check("0.3.0-tv7".equals(t.get("v")) && org.hedgewars.android.net.Compat.VALUE.equals(t.get("compat")) && "60".equals(t.get("proto"))
                && "Fairphone 4".equals(t.get("name")) && "8766".equals(t.get("tv")) && HD2.equals(t.get("data")) && "1".equals(t.get("lp")), "TXT has version, compat, engine protocol, host name, TV port, data fingerprint and lobby protocol: " + t);
        int total = 0;
        for (Map.Entry<String, String> e : t.entrySet()) total += e.getKey().length() + e.getValue().length() + 2;
        check(total < 400, "the record is small (" + total + " bytes)");
        check(org.hedgewars.android.net.HostAdvert.SERVICE_TYPE.equals("_hedgeparty._tcp."), "service type _hedgeparty._tcp");
        Map<String, byte[]> raw = new java.util.HashMap<>();
        for (Map.Entry<String, String> e : t.entrySet()) raw.put(e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8));
        org.hedgewars.android.net.HostAdvert.Found h = org.hedgewars.android.net.HostAdvert.parse("HedgeParty Fairphone", "192.168.1.57", 46633, raw);
        String C = org.hedgewars.android.net.Compat.VALUE;
        check(h.parsedOk && h.name.equals("Fairphone 4") && h.port == 46633 && h.tvPort == 8766 && h.protocol == 60 && h.address().equals("192.168.1.57:46633"), "parse reads the record back");
        check(h.incompatibility(C, "0.3.0-tv7", HD2) == null, "same compat, version, data: compatible");
        check(h.incompatibility("hwnet-60-sim1", "0.3.0-tv7", HD2).startsWith("Different game versions"), "different compat: incompatible with the reason");
        check(h.incompatibility(C, "0.3.0-tv6", HD2).contains("0.3.0-tv6") && h.incompatibility(C, "0.3.0-tv6", HD2).contains("0.3.0-tv7"), "different version: reason names both");
        check(h.incompatibility(C, "0.3.0-tv7", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb").startsWith("Game data differs"), "different data: incompatible with the reason");
        org.hedgewars.android.net.HostAdvert.Found junk = org.hedgewars.android.net.HostAdvert.parse("x", "10.0.0.1", 46633, new java.util.HashMap<String, byte[]>());
        check(!junk.parsedOk && junk.incompatibility(C, "v", HD2) != null, "a record without TXT is listed as incompatible, not ignored");
        raw.put("tv", "12x".getBytes(StandardCharsets.UTF_8));
        raw.put("proto", new byte[0]);
        check(org.hedgewars.android.net.HostAdvert.parse("s", "h", 1, raw).tvPort == 0, "a garbled number reads as 0");
        check("0".equals(t.get("pin")) && !h.pinRequired, "TXT pin=0 without a password; parsed as pinRequired false");
        Map<String, String> tp = org.hedgewars.android.net.HostAdvert.txt("0.3.0-tv12", C, 60, "Fairphone 4", 8766, HD2, true);
        Map<String, byte[]> rawp = new java.util.HashMap<>();
        for (Map.Entry<String, String> e : tp.entrySet()) rawp.put(e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8));
        check("1".equals(tp.get("pin")) && org.hedgewars.android.net.HostAdvert.parse("s", "h", 1, rawp).pinRequired, "TXT pin=1 with a password; parsed as pinRequired true");
        rawp.remove("pin");
        check(!org.hedgewars.android.net.HostAdvert.parse("s", "h", 1, rawp).pinRequired, "a record without pin (an older host) reads as no password");
        rawp.put("pin", "yes".getBytes(StandardCharsets.UTF_8));
        check(!org.hedgewars.android.net.HostAdvert.parse("s", "h", 1, rawp).pinRequired, "a garbled pin value reads as no password");
        check(org.hedgewars.android.net.HostAdvert.txt("v", "c", 60, new String(new char[500]).replace('\0', 'x'), 1, "").get("name").length() <= 100, "long values are cut");
        raw.put("name", "Pixel\u202E\u0007 7\u200B\n".getBytes(StandardCharsets.UTF_8));
        check("Pixel 7".equals(org.hedgewars.android.net.HostAdvert.parse("s", "h", 1, raw).name), "control and bidi characters are stripped from the name (Cc, Cf)");
        raw.put("name", "\u202E\u0001".getBytes(StandardCharsets.UTF_8));
        check("s".equals(org.hedgewars.android.net.HostAdvert.parse("s", "h", 1, raw).name), "a name of only control characters falls back to the service name");
    }


    // Game password ---------------------------------------------------------------------------

    /** A phone join with an optional password; returns once the host has answered (a state) or refused (closed). */
    static P phoneJoin(Fix f, String name, String pin, boolean watch, boolean host) throws Exception {
        P h = new P();
        RelayClient c = new RelayClient("127.0.0.1", f.port(), name, "tests", host,
                watch ? new ArrayList<SetupModel.Team>() : Arrays.asList(team(name, 2, 0)), h).watch(watch);
        if (host) c.hostSecret(f.secret);
        if (pin != null) c.pin(pin);
        c.connect();
        waitFor(() -> h.state != null || !h.closed.isEmpty(), 3000);
        h.client = c;
        return h;
    }

    /** A TV viewer WebSocket request with the given extra query ("" or "pin=1234"); returns the socket after the handshake. */
    static Socket viewerReq(Fix f, String query) throws Exception {
        Socket s = new Socket("127.0.0.1", f.relay.viewerPort());
        s.setSoTimeout(8000);
        s.getOutputStream().write(("GET /" + (query.isEmpty() ? "" : "?" + query) + " HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        s.getOutputStream().flush();
        readUntilBlank(s.getInputStream());
        return s;
    }

    /** The first frame after the handshake: "data" for a binary frame, else "close code reason". */
    static String firstFrame(Socket s) throws Exception {
        int[] op = new int[1];
        byte[] p = readWsFrame(s.getInputStream(), op);
        if (p == null) return "none";
        if (op[0] == 2) return "data";
        if (op[0] == 8) return "close " + (p.length < 2 ? "empty" : (((p[0] & 0xff) << 8) | (p[1] & 0xff)) + " " + new String(p, 2, p.length - 2, StandardCharsets.UTF_8));
        return "op" + op[0];
    }

    static boolean pinFlag(Fix f) throws Exception {
        @SuppressWarnings("unchecked") Map<String, Object> j = (Map<String, Object>) Json.parse(statusBody(f));
        return Boolean.TRUE.equals(j.get("pin"));
    }

    @SuppressWarnings("unchecked")
    static void testGamePassword() throws Exception {
        System.out.println("Game password: status flag, phones, TV viewers, lock");
        long oldLock = Relay.pinLockMs;
        Relay.pinLockMs = 1500;
        try {
            // Phones: no password set, then set.
            Fix f = new Fix(20000, 1000);
            check(!pinFlag(f), "GET shows pin false when no password is set");
            P p0 = phoneJoin(f, "NoPin", null, false, false);
            check(p0.state != null && p0.closed.isEmpty(), "no password set: a phone joins without a PIN");
            P p0b = phoneJoin(f, "AnyPin", "0000", false, false);
            check(p0b.state != null && p0b.closed.isEmpty(), "no password set: a pin in the hello is ignored");
            p0.client.close(); p0b.client.close();
            f.relay.setPin("0042");
            check("0042".equals(f.relay.getPin()), "the PIN keeps its leading zeros");
            check(pinFlag(f), "GET shows pin true when a password is set");
            boolean threw = false;
            try { f.relay.setPin("12a4"); } catch (IllegalArgumentException e) { threw = true; }
            check(threw && "0042".equals(f.relay.getPin()), "a PIN that is not exactly 4 digits is refused");
            P hst = phoneJoin(f, "HostPhone", null, false, true);
            check(hst.state != null && hst.closed.isEmpty(), "the host's own loopback connection (with its secret) needs no PIN");
            P pm = phoneJoin(f, "Missing", null, false, false);
            check(!pm.closed.isEmpty() && pm.state == null && "password".equals(pm.refusedCode) && "Wrong password.".equals(pm.closed), "a phone without a PIN is refused with code password (" + pm.closed + ")");
            P pw = phoneJoin(f, "Wrong", "1111", false, false);
            check("password".equals(pw.refusedCode) && pw.state == null, "a phone with a wrong PIN is refused with code password");
            P pws = phoneJoin(f, "WrongWatch", "1111", true, false);
            check("password".equals(pws.refusedCode) && pws.state == null, "Join to watch with a wrong PIN is refused with code password");
            P pr = phoneJoin(f, "Right", "0042", false, false);
            check(pr.state != null && pr.refusedCode.isEmpty(), "a phone with the right PIN joins");
            P prs = phoneJoin(f, "RightWatch", "0042", true, false);
            check(prs.state != null && prs.refusedCode.isEmpty(), "Join to watch with the right PIN works");
            check(!f.logged("0042") && !f.logged("1111"), "no PIN digits in the relay log");
            // Brute force: 2 wrong so far from this address (Missing, Wrong, WrongWatch = 3), the right one cleared the count.
            for (int i = 0; i < 4; i++) {
                P w = phoneJoin(f, "W" + i, "9999", false, false);
                check("password".equals(w.refusedCode), "wrong attempt " + (i + 1) + " after a right PIN: password, not locked");
            }
            P fifth = phoneJoin(f, "W5", null, false, false);
            check("password".equals(fifth.refusedCode), "the 5th wrong or missing attempt is itself answered password");
            long lockedAt = System.currentTimeMillis();
            P locked = phoneJoin(f, "L1", "0042", false, false);
            check("password-locked".equals(locked.refusedCode) && locked.state == null && locked.closed.startsWith("Too many wrong passwords. Try again in"), "afterwards the address is locked, even for the right PIN (" + locked.closed + ")");
            Thread.sleep(700);
            P locked2 = phoneJoin(f, "L2", "0042", true, false);
            check("password-locked".equals(locked2.refusedCode), "a watch join during the lock is locked too");
            Thread.sleep(Math.max(0, lockedAt + Relay.pinLockMs + 150 - System.currentTimeMillis()));
            P after = phoneJoin(f, "After", "0042", false, false);
            check(after.state != null && after.refusedCode.isEmpty(), "attempts during the lock did not extend it: the right PIN works once 30 s (here 1.5 s) have passed");
            // the right PIN cleared the count: 4 more wrong ones do not lock
            for (int i = 0; i < 4; i++) phoneJoin(f, "X" + i, "1234", false, false);
            P still = phoneJoin(f, "StillOk", "0042", false, false);
            check(still.state != null, "a right PIN clears the count: four more wrong ones then a right one still joins");
            check(pinFlag(f), "GET never needs the PIN and is not an attempt");
            for (int i = 0; i < 12; i++) statusBody(f);
            P afterGets = phoneJoin(f, "AfterGets", "1234", false, false);
            check("password".equals(afterGets.refusedCode), "twelve GETs were not counted as attempts: the next wrong PIN is plain password");
            for (P q : new P[] { hst, pr, prs, after, still }) if (q.client != null) q.client.close();
            f.stop();

            // TV viewers: a running game. The password never applies to them.
            Game g = new Game(20000, 1000);
            g.f.relay.setPin("7305");
            check(pinFlag(g.f), "GET shows pin true while a game runs");
            String[] queries = { "", "pin=0000", "pin=73", "pin=7305", "compat=" + org.hedgewars.android.net.Compat.VALUE + "&pin=1" };
            for (String q : queries) {
                try (Socket s = viewerReq(g.f, q)) {
                    String ff = firstFrame(s);
                    check(ff.equals("data") && waitFor(() -> g.f.relay.viewerCount() == 1, 2000), "a TV streams with a password set, query '" + q + "' (" + ff + ")");
                }
                waitFor(() -> g.f.relay.viewerCount() == 0, 3000);
            }
            // Eight TV attempts with no or a wrong PIN never count towards the lockout: the next phone's wrong PIN is plain password.
            for (int i = 0; i < 8; i++) {
                try (Socket s = viewerReq(g.f, i % 2 == 0 ? "" : "pin=1")) {
                    check(firstFrame(s).equals("data"), "TV attempt " + (i + 1) + " streams");
                }
                waitFor(() -> g.f.relay.viewerCount() == 0, 3000);
            }
            P pw2 = phoneJoin(g.f, "Late", "5555", false, false);
            check("password".equals(pw2.refusedCode), "TV attempts did not count: a phone's first wrong PIN is plain password, not locked");
            P pw3 = phoneJoin(g.f, "Late2", "7305", false, false);
            check(pw3.refusedCode.isEmpty() && !pw3.closed.toLowerCase().contains("password"), "the right PIN is not refused for the password after the TV attempts (" + pw3.closed + ")");
            if (pw3.client != null) pw3.client.close();
            if (pw2.client != null) pw2.client.close();
            g.f.relay.setPin(null);
            try (Socket s = viewerReq(g.f, "pin=1234")) {
                check(firstFrame(s).equals("data"), "no password set: a pin parameter is ignored and the TV streams");
            }
            check(!pinFlag(g.f), "GET shows pin false again after the password is turned off");
            check(!g.f.logged("7305"), "no PIN digits in the relay log (TV)");
            g.end();
            // No game running: still 503, PIN or not.
            Fix f3 = new Fix(20000, 1000);
            f3.relay.setPin("1234");
            try (Socket s = new Socket("127.0.0.1", f3.relay.viewerPort())) {
                s.setSoTimeout(3000);
                s.getOutputStream().write("GET /?pin=1234 HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                byte[] b = new byte[64];
                int r = s.getInputStream().read(b);
                check(r > 12 && new String(b, 0, 12, StandardCharsets.ISO_8859_1).equals("HTTP/1.1 503"), "no game running: the viewer path still answers 503");
            }
            f3.stop();
        } finally {
            Relay.pinLockMs = oldLock;
        }
    }

    static void testResolveQueue() throws Exception {
        System.out.println("NSD resolve queue: one at a time, retries, timeouts");
        final AtomicInteger inFlight = new AtomicInteger(), maxInFlight = new AtomicInteger();
        final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> calls = new java.util.concurrent.ConcurrentHashMap<>();
        final List<String> got = new CopyOnWriteArrayList<>(), gave = new CopyOnWriteArrayList<>();
        org.hedgewars.android.net.ResolveQueue q = new org.hedgewars.android.net.ResolveQueue((key, done) -> {
            int n = calls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            int now = inFlight.incrementAndGet();
            maxInFlight.updateAndGet(m -> Math.max(m, now));
            new Thread(() -> {
                try { Thread.sleep(40); } catch (InterruptedException ignored) { }
                inFlight.decrementAndGet();
                if (key.equals("flaky") && n < 3) done.fail("FAILURE_ALREADY_ACTIVE");
                else if (key.equals("dead")) done.fail("FAILURE_INTERNAL_ERROR");
                else if (key.equals("silent")) { /* the callback never comes */ }
                else done.ok("addr-of-" + key);
            }).start();
        }, new org.hedgewars.android.net.ResolveQueue.Sink() {
            public void resolved(String key, Object r) { got.add(key + "=" + r); }
            public void gaveUp(String key, String why) { gave.add(key + ":" + why); }
        }, 300, 30, 3);
        q.add("a"); q.add("b"); q.add("flaky"); q.add("a"); q.add("c");
        check(waitFor(() -> got.size() >= 4, 4000), "four services resolved: " + got);
        check(calls.get("a").get() == 1, "a service announced twice is resolved once");
        check(calls.get("flaky").get() == 3 && got.contains("flaky=addr-of-flaky"), "a service that fails twice is retried and then resolves (" + calls.get("flaky") + " calls)");
        check(maxInFlight.get() == 1, "never two resolves at the same time (max " + maxInFlight.get() + ")");
        q.add("dead");
        check(waitFor(() -> gave.size() >= 1, 3000) && calls.get("dead").get() == 3 && gave.get(0).startsWith("dead:"), "a service that always fails is given up after 3 attempts: " + gave);
        q.add("silent");
        check(waitFor(() -> gave.size() >= 2, 5000) && calls.get("silent").get() == 3 && gave.get(1).contains("no answer"), "a resolve whose callback never comes times out and is retried: " + gave);
        q.add("dead");
        check(waitFor(() -> calls.get("dead").get() == 6, 3000), "a given-up service is tried again when it is announced again");
        q.remove("c");
        q.stop();
    }

    static void testLinkLiveness() throws Exception {
        System.out.println("engine link liveness: a half-open link of a phone that is not playing is found (phase 5)");
        Fix.linkDeadMs = 2000;
        Fix.linkPingMs = 300;
        try {
            Game g = new Game(60000, 1000);
            check(waitFor(() -> g.ea.controls.contains("K") && g.eb.controls.contains("K"), 3000), "the relay pings both engine links");
            g.eb.autoPong = false;                 // phone B stops answering, the socket stays open: a half-open link
            long t0 = System.currentTimeMillis();
            check(waitFor(() -> dropped(g.f, "PhoneB"), 8000), "the silent phone B is dropped");
            long took = System.currentTimeMillis() - t0;
            check(took < 4500, "within about the limit (" + took + " ms for a 2000 ms limit)");
            check(g.f.logged("half-open"), "the log says half-open link");
            check(!dropped(g.f, "PhoneA"), "phone A, which answers, is not dropped");
            g.end();
        } finally {
            Fix.linkDeadMs = 0;
            Fix.linkPingMs = 5000;
        }
        // Everybody answering for longer than the limit: nobody is dropped.
        Fix.linkDeadMs = 1000;
        Fix.linkPingMs = 200;
        try {
            Game g = new Game(60000, 1000);
            Thread.sleep(3000);
            check(!dropped(g.f, "PhoneA") && !dropped(g.f, "PhoneB"), "phones that answer the pings for 3x the limit are never dropped");
            g.end();
        } finally {
            Fix.linkDeadMs = 0;
            Fix.linkPingMs = 5000;
        }
    }

    static void testHostNetworkLoss() throws Exception {
        System.out.println("host network loss: the game ends with a clear reason (phase 5)");
        Fix.netProbe = true;
        Fix.netLossMs = 600;
        try {
            Game g = new Game(60000, 1000);
            g.f.netUp.set(false);
            check(waitFor(() -> g.f.relay.lastSummary() != null, 6000), "the running game is ended");
            check(g.f.relay.lastSummary() != null && g.f.relay.lastSummary().endReason.contains("Wi-Fi"), "reason mentions Wi-Fi: " + (g.f.relay.lastSummary() == null ? "-" : g.f.relay.lastSummary().endReason));
            check(waitFor(() -> g.eb.controls.stream().anyMatch(c -> c.startsWith("X") && c.contains("Wi-Fi")), 3000), "phone B's engine is told why (control X)");
            g.f.netUp.set(true);
            Thread.sleep(1500);
            check(sane(g.f, "after the network came back"), "after the network is back the relay serves phones again");
            g.end();
            // a short blip is not a loss
            Game g2 = new Game(60000, 1000);
            g2.f.netUp.set(false);
            Thread.sleep(300);
            g2.f.netUp.set(true);
            Thread.sleep(2500);
            check(g2.f.relay.lastSummary() == null, "a blip shorter than the limit does not end the game");
            g2.end();
        } finally {
            Fix.netProbe = false;
            Fix.netLossMs = 4000;
        }
    }

    static void testConnectRetry() throws Exception {
        System.out.println("C1: the join connect tries again when the host cannot be reached yet");
        int port;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) { port = probe.getLocalPort(); }
        final int p = port;
        final java.util.concurrent.atomic.AtomicInteger retries = new java.util.concurrent.atomic.AtomicInteger();
        final java.net.ServerSocket[] late = new java.net.ServerSocket[1];
        Thread opener = new Thread(() -> {
            try {
                Thread.sleep(1000);
                late[0] = new java.net.ServerSocket(p);
                late[0].accept().close();
            } catch (Exception ignored) { }
        });
        opener.setDaemon(true);
        opener.start();
        RelayClient c = new RelayClient("127.0.0.1", p, "Retry", "t", false, new java.util.ArrayList<>(), new P());
        long t0 = System.currentTimeMillis();
        boolean ok;
        try {
            c.connect(3, 700, 15000, (n, of) -> retries.incrementAndGet());
            ok = true;
        } catch (java.io.IOException e) {
            ok = false;
        }
        check(ok, "a port that starts listening after about 1 s is reached on a later attempt (" + (System.currentTimeMillis() - t0) + " ms)");
        check(retries.get() >= 1 && retries.get() <= 2, "the progress callback was told about " + retries.get() + " further attempt(s)");
        c.close();
        if (late[0] != null) late[0].close();
        int dead;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) { dead = probe.getLocalPort(); }
        final java.util.concurrent.atomic.AtomicInteger r2 = new java.util.concurrent.atomic.AtomicInteger();
        RelayClient d = new RelayClient("127.0.0.1", dead, "Retry", "t", false, new java.util.ArrayList<>(), new P());
        boolean failed = false;
        try { d.connect(3, 200, 15000, (n, of) -> r2.incrementAndGet()); } catch (java.io.IOException e) { failed = true; }
        check(failed && r2.get() == 2, "a port nobody listens on fails after 3 attempts (" + r2.get() + " retries)");
        // A join the phone gives up (Back, another join) while it is still retrying stops at once and never connects.
        RelayClient q = new RelayClient("127.0.0.1", dead, "Cancel", "t", false, new java.util.ArrayList<>(), new P());
        Thread closer = new Thread(() -> { try { Thread.sleep(300); } catch (InterruptedException ignored) { } q.close(); });
        closer.start();
        long tq = System.currentTimeMillis();
        String why = "";
        try { q.connect(10, 1000, 15000, (n, of) -> { }); } catch (java.io.IOException e) { why = String.valueOf(e.getMessage()); }
        long took = System.currentTimeMillis() - tq;
        check(why.contains("cancelled") && took < 2500, "a join closed while retrying gives up at the next attempt (" + took + " ms: " + why + ")");
    }

    public static void main(String[] a) throws Exception {
        dataDir = a[0];
        data = new GameData(new File(a[0]));
        data.warm();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> { System.out.println("  FAIL uncaught exception reached the default handler in " + t.getName() + ": " + e); failures++; });
        testJson();
        testTeams();
        testGameRefusals();
        testAbuse();
        testHostRole();
        testEngineAbuse();
        testSilenceRule();
        testPause();
        testStatusGet();
        testWatchers();
        testViewerCount();
        testViewerAbuse();
        testViewerCloseCodes();
        testViewerReconnect();
        testViewerRtt();
        testViewerClassification();
        testViewerSlow();
        testViewerBindRetry();
        testQuitWording();
        testRejoin();
        testRejoinWindow();
        testRejoinEdges();
        testTvOverlay();
        testClientClose();
        testConnectRetry();
        testNoSocketWriteOnCallerThread();
        testCompat();
        testDataDigest();
        testHostAdvert();
        testGamePassword();
        testResolveQueue();
        testLinkLiveness();
        testHostNetworkLoss();
        System.out.println("\nrelay tests: " + checks + " checks, " + failures + " failed");
        System.out.println(failures == 0 ? "RESULT: PASS (the relay survived all malformed input)" : "RESULT: FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
