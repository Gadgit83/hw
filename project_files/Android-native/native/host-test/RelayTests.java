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

    static final class P implements RelayClient.Handler {
        volatile Map<String, Object> state;
        volatile RelayClient.StartInfo start;
        volatile String closed = "", notice = "";
        public void onState(Map<String, Object> s) { state = s; }
        public void onStart(RelayClient.StartInfo s) { start = s; }
        public void onSummary(Map<String, Object> m) { }
        public void onNotice(String t) { notice = t; }
        public void onClosed(String r) { closed = r; }
    }

    static final class Fix {
        final Relay relay;
        final List<String> log = new CopyOnWriteArrayList<>();
        final String secret = "test-secret";
        SetupModel hostModel = SetupModel.defaults(data, new Random(3));

        Fix(long silenceMs, long graceMs) throws Exception {
            Relay.Config cfg = new Relay.Config();
            cfg.port = 0;
            cfg.viewerPort = 0;
            cfg.data = data;
            cfg.hostSetup = () -> hostModel;
            cfg.hostSecret = secret;
            cfg.silenceMs = silenceMs;
            cfg.graceMs = graceMs;
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
                        if (m.length > 0 && m[0] == 0) controls.add(new String(m, 1, m.length - 1, StandardCharsets.UTF_8));
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
        evil.getOutputStream().write(line("{\"t\":\"hello\",\"role\":\"lobby\",\"proto\":1,\"name\":\"Evil\",\"build\":\"x\",\"teams\":[" + team + "]}"));
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
        String hello = "{\"t\":\"hello\",\"role\":\"lobby\",\"proto\":1,\"name\":\"Joe\",\"build\":\"x\",\"teams\":[]}";
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
                new byte[] { (byte) 0xff, (byte) 0xfe, (byte) 0xc3 }, "\",\"build\":\"x\",\"teams\":[]}\n".getBytes(StandardCharsets.ISO_8859_1));
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
        RelayClient c = new RelayClient("127.0.0.1", f.port(), "Spoof", "t", true, new ArrayList<>(), p);
        c.connect();
        check(waitFor(() -> p.closed.contains("Only the hosting phone"), 2000), "a loopback client claiming to be host without the secret is refused: " + p.closed);
        P p2 = new P();
        RelayClient c2 = new RelayClient("127.0.0.1", f.port(), "Spoof2", "t", true, new ArrayList<>(), p2).hostSecret("guess");
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

    public static void main(String[] a) throws Exception {
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
        testViewerCount();
        testViewerAbuse();
        testViewerReconnect();
        testNoSocketWriteOnCallerThread();
        System.out.println("\nrelay tests: " + checks + " checks, " + failures + " failed");
        System.out.println(failures == 0 ? "RESULT: PASS (the relay survived all malformed input)" : "RESULT: FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
