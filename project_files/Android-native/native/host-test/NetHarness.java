import org.hedgewars.android.GameData;
import org.hedgewars.android.Rules;
import org.hedgewars.android.SetupModel;
import org.hedgewars.android.net.Relay;
import org.hedgewars.android.net.RelayClient;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Host-side proof of the multi-phone relay: the relay core on the JVM and two (or more) engines, each started
 * the way the app starts one (game.ipc plus net.cfg, launcher in network mode), acting as separate phones.
 *
 * Usage: NetHarness DATA_DIR WORK_DIR HOSTTEST_BINARY SCENARIO
 * Scenarios (explicit names, the number of phones is part of the name):
 *   basic      two phones play to the end                      basic3     three phones play to the end
 *   dropout    two phones, phone B's engine is killed          dropout3   three phones, phone C's engine is killed
 *   stall      two phones, phone B's engine is frozen (SIGSTOP) and the silence rule has to catch it
 *   slowstart  two phones, phone B is 30 s late with its map digest (its launcher holds its first message back);
 *              nobody may be dropped and the games must agree
 *   solo       one phone with three teams, no joiner           viewer1    solo with the TV viewer from the start
 *   viewer2    two phones, the TV viewer joins late
 *   viewer3    one phone, a short game (low health) with the TV viewer from the start, played to a NORMAL game over (no quit);
 *              the viewer must see the game over, no desync, the relay must have measured a round trip for it
 *   viewerflap one phone, a short game; the TV viewer connects, is killed, connects again, is killed and connects a third time
 *              (each time a new viewer-sim and spectator engine, so each joins with the backlog); the last one must see the game over;
 *              the relay must log two departures as lost connections, never as "too slow"
 *   hostquit   two phones; at turn NET_TURNS_BEFORE_KILL the host phone's engine gets Escape then Y, exactly what the app's
 *              Back confirmation sends: the host's engine must exit cleanly (0) and the relay must end the game for everyone
 *              with "The host ended the game" (the other phone is told by the launcher)
 *   hostquitchat / joinerquitchat  the same with the chat open and half a message typed, quitting through the launcher's explicit
 *              user quit (control frame Q to the relay plus the engine's forcequit), as the app now does
 *   joinerquit the same on phone B: B's engine exits 0, the relay says B quit (not dropped by force), A is unaffected
 *   negcontrol two phones, the relay deliberately alters one byte of the first turn checksum N sent to phone B: the
 *              desync detection MUST fire (this scenario passes only if it does)
 * Prints a verdict line "RESULT: PASS" or "RESULT: FAIL"; the exit code is 0 only for PASS (for negcontrol: only if the
 * detection fired). Any desync report, unexpected protocol line, engine queue error, non-zero engine exit that is not the
 * victim's, mismatch of the per-turn checksums or of the relay/launcher stream hashes is a FAIL in the positive scenarios.
 * Environment: DISPLAY must point at a running X server (Xvfb); NET_SEED, NET_TURNS_BEFORE_KILL, NET_TIMEOUT_S,
 * NET_SILENCE_MS, NET_GRACE_MS, NET_DELAY_MS (slowstart, default 30000), NET_INPUT=1, NET_SCRIPT.
 */
public class NetHarness {
    static final class Phone implements RelayClient.Handler {
        final String name;
        final Path dir;
        final boolean host;
        final List<SetupModel.Team> teams;
        RelayClient client;
        Process proc;
        volatile Map<String, Object> summary;
        volatile String closed;
        final CountDownLatch gotSummary = new CountDownLatch(1);
        final CountDownLatch gotStart = new CountDownLatch(1);
        final String script;
        final String hosttest, data;
        int id;
        final Map<String, String> extraEnv = new HashMap<>();

        Phone(String name, Path dir, boolean host, List<SetupModel.Team> teams, String hosttest, String data, String script) {
            this.name = name; this.dir = dir; this.host = host; this.teams = teams;
            this.hosttest = hosttest; this.data = data; this.script = script;
        }

        public void onState(Map<String, Object> s) { }

        public void onStart(RelayClient.StartInfo si) {
            try {
                id = si.you;
                Files.createDirectories(dir.resolve("user"));
                RelayClient.writeGameFiles(dir.resolve("game.ipc").toFile(), dir.resolve("net.cfg").toFile(), si,
                        dir.resolve("netgame.txt").toString());
                ProcessBuilder pb = new ProcessBuilder(hosttest, data, dir.resolve("user").toString(),
                        dir.resolve("game.ipc").toString(), "960", "540");
                pb.environment().put("HP_NET_CFG", dir.resolve("net.cfg").toString());
                pb.environment().put("SDL_AUDIODRIVER", "dummy");
                pb.environment().putAll(extraEnv);
                if (script != null) pb.environment().put("HP_TEST_SCRIPT", script);
                pb.redirectErrorStream(true);
                pb.redirectOutput(dir.resolve("engine.log").toFile());
                proc = pb.start();
                System.out.println("[harness] " + name + ": engine started (pid " + proc.pid() + ") with " + si.lines.size()
                        + " setup lines, team lines owned: " + owned(si.lines));
                gotStart.countDown();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        static String owned(List<String> lines) {
            StringBuilder b = new StringBuilder();
            String last = "";
            for (String l : lines) {
                if (l.startsWith("eaddteam ")) last = l.substring(l.lastIndexOf(' ') + 1) + "";
                if (l.startsWith("eaddteam ")) { String[] p = l.split(" ", 4); b.append(p[3]); }
                if (l.equals("erdriven")) b.append("(remote) ");
                else if (l.startsWith("eaddteam ")) b.append("(own) ");
            }
            return b.toString().trim();
        }

        public void onSummary(Map<String, Object> m) { summary = m; gotSummary.countDown(); }
        volatile String lastNotice = "";
        public void onNotice(String t) { lastNotice = t; System.out.println("[harness] " + name + " notice: " + t); }
        public void onClosed(String r) { closed = r; System.out.println("[harness] " + name + " lobby closed: " + r); }
    }

    static SetupModel.Team team(GameData d, Random r, String name, int color, int level, List<SetupModel.Team> others) {
        SetupModel.Team t = SetupModel.randomTeam(d, r, color, others);
        t.name = name;
        t.level = level;
        t.hogs = 2;
        return t;
    }

    public static void main(String[] a) throws Exception {
        String data = a[0], work = a[1], hosttest = a[2], scenario = a.length > 3 ? a[3] : "basic";
        long seed = Long.parseLong(System.getenv().getOrDefault("NET_SEED", "4242"));
        int killAfterTurns = Integer.parseInt(System.getenv().getOrDefault("NET_TURNS_BEFORE_KILL", "3"));
        long timeout = Long.parseLong(System.getenv().getOrDefault("NET_TIMEOUT_S", "420"));
        Path root = new File(work).toPath();
        deleteTree(root);
        Files.createDirectories(root);
        GameData d = new GameData(new File(data));
        Random rnd = new Random(seed);

        SetupModel hostModel = SetupModel.defaults(d, rnd);
        hostModel.theme = "Nature";
        hostModel.seed = "net-harness-" + seed;
        hostModel.rules.value[Rules.V_TURN_TIME] = 10;
        hostModel.rules.value[Rules.V_HEALTH] = 40;
        hostModel.rules.value[Rules.V_SUDDEN] = 1;
        if (scenario.equals("viewer3") || scenario.equals("viewerflap")) {
            // a short game that still reaches a normal game over: little health, short turns
            hostModel.rules.value[Rules.V_HEALTH] = Integer.parseInt(System.getenv().getOrDefault("NET_HEALTH", "15"));
            hostModel.rules.value[Rules.V_TURN_TIME] = 8;
        }

        List<SetupModel.Team> aTeams = new ArrayList<>(), bTeams = new ArrayList<>(), cTeams = new ArrayList<>();
        aTeams.add(team(d, rnd, "Alpha One", 0, 0, aTeams));
        aTeams.add(team(d, rnd, "Alpha Bot", 1, 5, aTeams));
        bTeams.add(team(d, rnd, "Bravo One", 0, 0, bTeams));        // same colour as Alpha One on purpose
        bTeams.add(team(d, rnd, "Bravo Bot", 2, 3, bTeams));
        if (scenario.equals("solo") || scenario.startsWith("viewer1") || scenario.equals("viewer3") || scenario.equals("viewerflap")) { aTeams.add(team(d, rnd, "Alpha Three", 3, 4, aTeams)); bTeams.clear(); }
        java.util.Set<String> known = new java.util.HashSet<>(java.util.Arrays.asList("basic", "basic3", "dropout", "dropout3", "stall",
                "slowstart", "solo", "viewer1", "viewer2", "viewer3", "viewerflap", "hostquit", "joinerquit", "hostquitchat", "joinerquitchat", "negcontrol",
                "hostpause", "hostpausequit", "joinerpause", "pausecap"));
        if (!known.contains(scenario)) {
            System.out.println("[harness] unknown scenario '" + scenario + "'; known: " + new java.util.TreeSet<>(known));
            System.exit(2);
        }
        if (scenario.equals("basic3") || scenario.equals("dropout3")) {
            cTeams.add(team(d, rnd, "Charlie One", 0, 0, cTeams));
            cTeams.add(team(d, rnd, "Charlie Bot", 4, 4, cTeams));
        }

        Relay.Config cfg = new Relay.Config();
        cfg.port = 0;
        cfg.viewerPort = 0;                 // a free port: runs can go side by side and never meet another test on 8766
        cfg.data = d;
        cfg.hostSetup = () -> hostModel;
        cfg.build = "harness";
        cfg.hostName = "Phone A";
        cfg.silenceMs = Long.parseLong(System.getenv().getOrDefault("NET_SILENCE_MS", "20000"));
        cfg.graceMs = Long.parseLong(System.getenv().getOrDefault("NET_GRACE_MS", "40000"));
        cfg.hostSecret = "harness-secret";
        if (scenario.equals("pausecap")) cfg.pauseMaxMs = Long.parseLong(System.getenv().getOrDefault("NET_PAUSE_MAX_MS", "15000"));
        final java.util.concurrent.atomic.AtomicBoolean tampered = new java.util.concurrent.atomic.AtomicBoolean();
        final int[] tamperId = { -1 };
        if (scenario.equals("negcontrol")) {
            // Fault injection: the first N (turn checksum) that goes to phone B has one checksum byte altered.
            cfg.fault = (to, f) -> {
                if (to == tamperId[0] && f.length >= 6 && f[1] == 'N' && !tampered.getAndSet(true)) {
                    byte[] c = f.clone();
                    c[2] ^= 0x5a;
                    System.out.println("[harness] === negcontrol: the relay alters one byte of the first N sent to phone B ===");
                    return c;
                }
                return f;
            };
        }
        final List<String> relayLog = new java.util.concurrent.CopyOnWriteArrayList<>();
        cfg.listener = new Relay.Listener() {
            public void log(String line) { relayLog.add(line); System.out.println("[relay] " + line); }
            public void changed() { }
        };
        Relay relay = new Relay(cfg);
        relay.start();

        // NET_INPUT=1: the human teams get scripted touch input (walk, jump, fire), see inputScript.
        String script = System.getenv("NET_SCRIPT");
        if (script == null && System.getenv("NET_INPUT") != null) script = inputScript(400);
        // hostquit / joinerquit: the quitter's engine waits for a file the harness creates, then gets Escape and Y (the app's Back confirmation).
        boolean quitScenario = scenario.startsWith("hostquit") || scenario.startsWith("joinerquit") || scenario.equals("hostpausequit");
        boolean chatQuit = scenario.endsWith("chat");
        Path quitFlag = root.resolve("quit.now");
        // The chat variants open the chat and leave half a message in it, then confirm the way GameActivity now does (hp_ipc_user_quit).
        String quitScript = chatQuit ? "0:waitfile:" + quitFlag + ";0:key:enter;400:text:half typed;800:userquit"
                : "0:waitfile:" + quitFlag + ";0:key:esc;600:key:y";
        // Host pause scenarios. The host's script taps the pause button (top right of the touch HUD) when the harness creates a flag
        // file, so the whole path is exercised: touch, engine, launcher, relay, every launcher, every engine. Phone B has scripted
        // touch input of its own, which must be ignored while the game is paused, and a launcher that applies pause and resume 700 ms
        // late, so the engines pause and resume at different wall-clock moments.
        boolean pauseScenario = scenario.equals("hostpause") || scenario.equals("hostpausequit") || scenario.equals("pausecap");
        String tapPause = "0:down:5:" + PAUSE_X + ":" + PAUSE_Y + ";80:up:5:" + PAUSE_X + ":" + PAUSE_Y + ";";
        String hostScript = script;
        if (pauseScenario) {
            hostScript = "0:waitfile:" + root.resolve("pause1.now") + ";" + tapPause;
            if (scenario.equals("hostpause")) hostScript += "0:waitfile:" + root.resolve("resume1.now") + ";" + tapPause
                    + "0:waitfile:" + root.resolve("pause2.now") + ";" + tapPause + "0:waitfile:" + root.resolve("resume2.now") + ";" + tapPause;
            if (scenario.equals("hostpausequit")) hostScript += "0:waitfile:" + quitFlag + ";" + (System.getenv("NET_PAUSEQUIT_KEYS") != null ? "0:key:esc;600:key:y" : "0:userquit");
        }
        String joinerScript = script;
        if (pauseScenario) joinerScript = inputScript(400);
        if (scenario.equals("joinerpause")) joinerScript = "0:waitfile:" + root.resolve("jp.now") + ";0:ctl:P;1500:ctl:P;1500:ctl:R";
        Phone pa = new Phone("Phone A", root.resolve("phoneA"), true, aTeams, hosttest, data, scenario.startsWith("hostquit") ? quitScript : hostScript);
        Phone pb = new Phone("Phone B", root.resolve("phoneB"), false, bTeams, hosttest, data, scenario.startsWith("joinerquit") ? quitScript : joinerScript);
        if (pauseScenario) pb.extraEnv.put("HP_NET_PAUSE_JITTER_MS", "700");
        Phone pc = new Phone("Phone C", root.resolve("phoneC"), false, cTeams, hosttest, data, script);
        pa.client = new RelayClient("127.0.0.1", relay.port(), "Phone A", "harness", true, aTeams, pa).hostSecret(cfg.hostSecret);
        if (scenario.equals("slowstart")) pb.extraEnv.put("HP_NET_TX_DELAY_MS", System.getenv().getOrDefault("NET_DELAY_MS", "30000"));
        pa.client.connect();
        pa.client.setReady(true);
        List<Phone> list = new ArrayList<>();
        list.add(pa);
        if (!bTeams.isEmpty()) {
            pb.client = new RelayClient("127.0.0.1", relay.port(), "Phone B", "harness", false, bTeams, pb);
            pb.client.connect();
            pb.client.setReady(true);
            list.add(pb);
        }
        if (!cTeams.isEmpty()) {
            pc.client = new RelayClient("127.0.0.1", relay.port(), "Phone C", "harness", false, cTeams, pc);
            pc.client.connect();
            pc.client.setReady(true);
            list.add(pc);
        }
        Phone[] phones = list.toArray(new Phone[0]);
        Phone victim = cTeams.isEmpty() ? pb : pc;
        Thread.sleep(800);
        pa.client.start();
        for (Phone p : phones) if (!p.gotStart.await(20, TimeUnit.SECONDS)) throw new IllegalStateException(p.name + " got no start");
        tamperId[0] = pb.id;

        long t0 = System.currentTimeMillis();
        boolean killed = false;
        final PauseState ps = new PauseState();
        // TV viewer (scenarios viewer*): the existing e2e-host spectator test. viewer-sim.js plays the TV page
        // (WebSocket client, TL->TN and erdriven, pushes into) pc_host spec, a second desktop engine as the TV's engine.
        // viewerflap runs three such sessions one after the other, killing the first two.
        boolean wantViewer = scenario.startsWith("viewer") || scenario.equals("hostpause");
        boolean flap = scenario.equals("viewerflap");
        int viewerAfter = Integer.parseInt(System.getenv().getOrDefault("NET_VIEWER_AFTER_TURNS", scenario.equals("viewer2") ? "3" : flap ? "1" : "0"));
        final int[] killAt = flap ? new int[] { viewerAfter + 1, viewerAfter + 3 } : new int[0];
        final String specPort = System.getenv().getOrDefault("NET_SPEC_PORT", "18770");
        final List<ViewerRun> runs = new ArrayList<>();
        ViewerRun cur = null;
        long reconnectAt = 0;
        while (System.currentTimeMillis() - t0 < timeout * 1000) {
            if (pa.gotSummary.await(1, TimeUnit.SECONDS)) break;
            if (wantViewer && relay.viewerPort() > 0) {
                int n = runs.size();
                if (cur == null) {
                    int need = n == 0 ? viewerAfter : (flap && n < 3 ? killAt[n - 1] + 1 : Integer.MAX_VALUE);
                    if (relay.turns() >= need && System.currentTimeMillis() >= reconnectAt) {
                        cur = new ViewerRun(root.resolve("viewer" + (flap ? "-" + (n + 1) : "")), n + 1, data, specPort, relay.viewerPort());
                        cur.start();
                        runs.add(cur);
                        System.out.println("[harness] === TV viewer session " + (n + 1) + " (viewer-sim.js + spectator engine) connected after " + relay.turns() + " turn checksums ===");
                    }
                } else if (flap && n <= killAt.length && relay.turns() >= killAt[n - 1]) {
                    System.out.println("[harness] === TV viewer session " + n + " killed (SIGKILL of viewer-sim and its spectator engine) after " + relay.turns() + " turn checksums ===");
                    cur.kill();
                    cur = null;
                    reconnectAt = System.currentTimeMillis() + 1500;
                }
            }
            if (pauseScenario || scenario.equals("joinerpause")) pauseDriver(scenario, relay, root, ps);
            if (quitScenario && !scenario.equals("hostpausequit") && !Files.exists(quitFlag) && relay.turns() >= killAfterTurns) {
                System.out.println("[harness] === " + scenario + ": " + (scenario.startsWith("hostquit") ? "the host" : "phone B") + " confirms 'leave the game' (" + (chatQuit ? "chat open with typed text, then the launcher quit" : "Escape then Y") + ") after " + relay.turns() + " turn checksums ===");
                Files.write(quitFlag, new byte[] { 1 });
            }
            if (!killed && (scenario.startsWith("dropout") || scenario.equals("stall")) && relay.turns() >= killAfterTurns) {
                killed = true;
                System.out.println("[harness] === " + scenario + ": " + (scenario.equals("stall") ? "freezing (SIGSTOP)" : "killing (SIGKILL)")
                        + " " + victim.name + "'s engine after " + relay.turns() + " turn checksums ===");
                if (scenario.equals("stall")) new ProcessBuilder("kill", "-STOP", Long.toString(victim.proc.pid())).start().waitFor();
                else victim.proc.destroyForcibly();
            }
        }
        boolean over = pa.gotSummary.getCount() == 0;
        final List<String> fails = new ArrayList<>();
        final Map<String, Integer> exits = new HashMap<>();
        int desyncTotal = 0;
        if (victim.proc != null && scenario.equals("stall")) { new ProcessBuilder("kill", "-KILL", Long.toString(victim.proc.pid())).start().waitFor(); }
        Thread.sleep(2000);
        for (Phone p : phones) {
            if (p.proc == null) continue;
            if (p.proc.isAlive()) p.proc.waitFor(15, TimeUnit.SECONDS);
            System.out.println("[harness] " + p.name + " engine process: " + (p.proc.isAlive() ? "still running 17 s after the game ended (killed by the harness)" : "exit code " + p.proc.exitValue()
                    + (p.proc.exitValue() > 128 ? " (signal " + (p.proc.exitValue() - 128) + ")" : "")));
            if (p.proc.isAlive()) { exits.put(p.name, -1); p.proc.destroyForcibly(); } else exits.put(p.name, p.proc.exitValue());
        }
        if (wantViewer && runs.isEmpty()) fails.add("the TV viewer never connected (the game was too short or the viewer port was closed)");
        if (flap && runs.size() < 3) fails.add("only " + runs.size() + " of the 3 TV viewer sessions started (the game ended too early)");
        for (ViewerRun vr : runs) {
            if (!vr.killed) vr.finish();
            vr.report(fails, vr.idx > 1);
        }
        System.out.println("\n[harness] game over seen by the host's lobby link: " + over);
        Relay.GameSummary gs = relay.lastSummary();
        if (gs == null) { System.out.println("[harness] FAIL: no summary"); System.out.println("RESULT: FAIL (no relay summary)"); System.exit(1); }
        System.out.println("\n================ RELAY REPORT ================\n" + gs.text());

        // Per-phone launcher diagnostics.
        Map<String, List<String>> seqs = new HashMap<>();
        Map<String, Integer> desyncs = new HashMap<>();
        Map<String, Boolean> streamOk = new HashMap<>();
        for (Phone p : phones) {
            List<String> lines = Files.exists(p.dir.resolve("netgame.txt")) ? Files.readAllLines(p.dir.resolve("netgame.txt")) : new ArrayList<>();
            List<String> n = new ArrayList<>();
            for (String l : lines) {
                int k = l.indexOf(" N ");
                if (k >= 0 && l.contains(" turn ") && l.contains(" checksum ")) {
                    String[] w = l.trim().split("\\s+");
                    n.add(w[4] + " " + w[6] + " " + w[8] + " (" + w[2] + ")");
                }
            }
            seqs.put(p.name, n);
            System.out.println("================ " + p.name + " launcher diagnostics (netgame.txt) ================");
            for (String l : lines) if (!l.contains(" N ") || l.contains("final")) System.out.println(l);
            String log = Files.exists(p.dir.resolve("engine.log")) ? new String(Files.readAllBytes(p.dir.resolve("engine.log"))) : "";
            System.out.println("engine log: 'Desync detected' lines: " + count(log, "Desync detected") + ", 'Unexpected protocol' lines: " + count(log, "Unexpected protocol")
                    + ", 'oops, queue error': " + count(log, "queue error") + ", engine returned: " + grep(log, "engine returned"));
            desyncs.put(p.name, count(log, "Desync detected"));
            if (count(log, "Unexpected protocol") > 0) fails.add(p.name + ": 'Unexpected protocol' in the engine log");
            if (count(log, "queue error") > 0) fails.add(p.name + ": engine queue error");
            // The stream the relay sent this phone must be exactly the stream its launcher received.
            String sum = "";
            for (String l : lines) if (l.contains("SUMMARY")) sum = l;
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("rx (\\d+) messages hash ([0-9a-f]+)").matcher(sum);
            if (mm.find()) {
                String relayHash = gs.toHashByPlayer.get(p.id);
                long relayMsgs = gs.toMsgsByPlayer.getOrDefault(p.id, -1L);
                boolean same = mm.group(2).equals(relayHash) && Long.parseLong(mm.group(1)) == relayMsgs;
                System.out.println("stream check " + p.name + ": relay sent " + relayMsgs + " messages hash " + relayHash + ", launcher received " + mm.group(1) + " hash " + mm.group(2) + (same ? "  (equal)" : "  (DIFFERENT)"));
                streamOk.put(p.name, same);
            } else {
                System.out.println("stream check " + p.name + ": no launcher SUMMARY line");
                streamOk.put(p.name, false);
            }
        }
        System.out.println("\n================ PER-TURN DIGESTS, side by side (turn checksum tick direction) ================");
        StringBuilder hdr = new StringBuilder(String.format("%-4s", "turn"));
        for (Phone p : phones) hdr.append(String.format(" %-32s", p.name));
        System.out.println(hdr + " relay");
        int rows = gs.digests.size();
        for (Phone p : phones) rows = Math.max(rows, seqs.get(p.name).size());
        int mismatches = 0, missing = 0;
        for (int i = 0; i < rows; i++) {
            StringBuilder line = new StringBuilder(String.format("%-4d", i + 1));
            String first = null;
            boolean bad = false;
            for (Phone p : phones) {
                List<String> sq = seqs.get(p.name);
                String x = i < sq.size() ? sq.get(i) : "-";
                line.append(String.format(" %-32s", x));
                if (!x.equals("-")) {
                    String cs = x.split(" ")[1];
                    if (first == null) first = cs; else if (!first.equals(cs)) bad = true;
                }
            }
            if (bad) mismatches++;
            line.append(' ').append(i < gs.digests.size() ? gs.digests.get(i) : "-").append(bad ? "   <-- MISMATCH" : "");
            System.out.println(line);
        }
        System.out.println("\nturn checksum mismatches between the phones: " + mismatches);
        byte[] vb = relay.viewerBacklog();
        System.out.println("viewer backlog framing available after the game: " + (vb == null ? "no (game cleared)" : vb.length + " bytes"));

        // Verdict ---------------------------------------------------------------------------------
        boolean droppedScenario = scenario.startsWith("dropout") || scenario.equals("stall") || scenario.startsWith("joinerquit");
        boolean hostQuit = scenario.startsWith("hostquit") || scenario.equals("hostpausequit");
        int desyncLines = 0;
        for (int v : desyncs.values()) desyncLines += v;
        boolean relayDesync = false;
        for (String e : gs.events) if (e.toLowerCase().contains("desync")) relayDesync = true;
        if (scenario.equals("negcontrol")) {
            boolean fired = desyncLines > 0 || relayDesync || gs.endReason.toLowerCase().contains("desync");
            System.out.println("\nnegative control: relay altered one byte of one N for Phone B; 'Desync detected' lines " + desyncs + ", relay end reason: " + gs.endReason);
            System.out.println("game verdict: " + (fired ? "FAIL (desync detected, as the negative control requires)" : "PASS (no desync noticed)"));
            System.out.println(fired ? "RESULT: PASS (negative control: the desync detection fired)" : "RESULT: FAIL (negative control: the desync detection did NOT fire)");
            System.exit(fired ? 0 : 1);
        }
        if (!over) fails.add("the game did not end within " + timeout + " s");
        if (desyncLines > 0 || relayDesync) fails.add("desync reported (engine log lines " + desyncs + ")");
        if (mismatches > 0) fails.add(mismatches + " turn checksum mismatches between the phones");
        if (gs.digests.isEmpty()) fails.add("no turn checksum was exchanged at all");
        for (Phone p : phones) {
            Integer ex = exits.get(p.name);
            boolean isVictim = droppedScenario && p == victim;
            if (ex == null) { fails.add(p.name + ": its engine never started"); continue; }
            boolean cutByHost = hostQuit && p != pa;                           // the other phone's engine is cut off by the host's quit
            if (quitScenario && p == (hostQuit ? pa : pb) && ex != 0) fails.add(p.name + ": quit by Escape then Y but the engine exit code is " + ex + " (a clean quit is 0)");
            if (!isVictim && !cutByHost && ex != 0) fails.add(p.name + ": engine exit code " + ex + " (expected 0)");
            if (!isVictim && !cutByHost && !Boolean.TRUE.equals(streamOk.get(p.name))) fails.add(p.name + ": the relay's stream and the launcher's received stream differ");
        }
        if (quitScenario) {
            Phone q = hostQuit ? pa : pb;
            String ql = Files.exists(q.dir.resolve("engine.log")) ? new String(Files.readAllBytes(q.dir.resolve("engine.log"))) : "";
            String nl = Files.exists(q.dir.resolve("netgame.txt")) ? new String(Files.readAllBytes(q.dir.resolve("netgame.txt"))) : "";
            System.out.println("quit check: " + q.name + " engine log: " + grep(ql, "engine returned") + "; launcher: " + grep(nl, "CONTROL") + " / SUMMARY " + (nl.contains("SUMMARY") ? "written" : "missing"));
            if (!ql.contains("engine returned 0")) fails.add(q.name + ": the engine log does not show a clean exit ('engine returned 0')");
            if (chatQuit) {
                boolean ctl = false;
                for (String l : relayLog) if (l.contains(q.name + " confirmed leaving the game")) ctl = true;
                System.out.println("quit check: relay saw the explicit user-quit control frame: " + ctl + "; chat typed: " + ql.contains("half typed"));
                if (!ctl) fails.add("the relay did not log the explicit user-quit control frame from " + q.name);
            }
        }
        if (hostQuit) {
            if (!gs.endReason.equals(Relay.HOST_ENDED)) fails.add("end reason '" + gs.endReason + "', expected '" + Relay.HOST_ENDED + "'");
            boolean ev = false;
            for (String e : gs.events) if (e.contains("(the host) ended the game")) ev = true;
            if (!ev) fails.add("no 'the host ended the game' event");
            for (String e : gs.events) if (e.contains("dropped")) fails.add("the host's quit was reported as a dropout: " + e);
            String bl = Files.exists(pb.dir.resolve("netgame.txt")) ? new String(Files.readAllBytes(pb.dir.resolve("netgame.txt"))) : "";
            String bres = Files.exists(pb.dir.resolve("engine.log")) ? new String(Files.readAllBytes(pb.dir.resolve("engine.log"))) : "";
            System.out.println("quit check: Phone B launcher: " + grep(bl, "LINK ") + "; engine: " + grep(bres, "engine returned"));
            if (!bl.contains("The host ended the game")) fails.add("Phone B was not told 'The host ended the game'");
            if (pa.lastNotice.contains("dropped")) fails.add("the host phone was told it was dropped: " + pa.lastNotice);
        } else if (scenario.startsWith("joinerquit")) {
            System.out.println("quit check: Phone B was told: '" + pb.lastNotice + "'");
            if (!pb.lastNotice.contains("You left the game")) fails.add("Phone B was not told 'You left the game.' (" + pb.lastNotice + ")");
            for (String e : gs.events) if (e.contains("Phone B dropped")) System.out.println("quit check: relay event: " + e);
        }
        if (wantViewer) {
            boolean rtt = false;
            for (String v : gs.viewers) if (v.contains("pongs of")) rtt = true;
            if (!rtt) fails.add("the relay measured no round trip for any TV viewer (the viewer must answer the WebSocket ping): " + gs.viewers);
            for (String l : relayLog) if (l.contains("too slow") && l.contains("viewer")) fails.add("a viewer was reported too slow: " + l);
            int joined = 0, lost = 0;
            for (String e : gs.events) {
                if (e.startsWith("TV viewer joined")) joined++;
                if (e.startsWith("TV viewer ") && e.contains(" left (") && (e.contains("connection lost") || e.contains("disconnected") || e.contains("closed the connection"))) lost++;
            }
            System.out.println("TV viewer events in the relay report: " + joined + " joined, " + lost + " left because the TV went away");
            if (flap && joined != 3) fails.add("expected 3 TV viewer joins, the relay saw " + joined);
            if (flap && lost != 2) fails.add("expected 2 departures as lost connections, the relay saw " + lost);
            if (!scenario.equals("viewer2") && !scenario.equals("viewer1") && !gs.endReason.contains("every engine finished")) fails.add("the game did not reach a normal game over: " + gs.endReason);
        }
        if (droppedScenario) {
            boolean dropEvent = false, injected = false;
            for (String e : gs.events) { if (e.contains(victim.name + " dropped")) dropEvent = true; if (e.startsWith("injected F")) injected = true; }
            if (!dropEvent) fails.add(victim.name + " was not reported dropped");
            if (!injected) fails.add("no team-gone F was injected");
            for (Phone p : phones) if (p != victim) for (String e : gs.events) if (e.contains(p.name + " dropped")) fails.add(p.name + " was dropped wrongly: " + e);
        } else if (!hostQuit) {
            for (String e : gs.events) if (e.contains("dropped")) fails.add("unexpected dropout: " + e);
            if (!gs.endReason.contains("every engine finished")) fails.add("the game did not end by every engine finishing: " + gs.endReason);
        }
        if (pauseScenario || scenario.equals("joinerpause")) pauseChecks(scenario, gs, relayLog, phones, ps, fails);
        System.out.println("\nchecks: desync lines " + desyncLines + ", checksum mismatches " + mismatches + ", turns " + gs.digests.size() + ", engine exits " + exits);
        System.out.println(fails.isEmpty() ? "RESULT: PASS (0 Desync detected, " + gs.digests.size() + " turn checksums identical on all " + phones.length + " phones)"
                : "RESULT: FAIL " + fails);
        System.exit(fails.isEmpty() ? 0 : 1);
    }


    /** Where the host's pause button is on the touch HUD (fractions of the screen): top right, left of the screen edge by its own width. */
    static final String PAUSE_X = System.getenv().getOrDefault("NET_PAUSE_X", "0.945"), PAUSE_Y = System.getenv().getOrDefault("NET_PAUSE_Y", "0.075");

    static final class PauseState {
        int stage;
        long at;                         // when the current stage began
        int turnsAtPause, turnsFrozen, turnsAtResume;
        long firstPauseMs;
        boolean frozenChecked;
        final List<String> notes = new ArrayList<>();
    }

    static void flag(Path root, String name) throws Exception {
        Files.write(root.resolve(name), new byte[] { 1 });
    }

    /** Called once a second from the main loop: drives the pause, the waits and the resumes by the relay's turn count. */
    static void pauseDriver(String sc, Relay relay, Path root, PauseState ps) throws Exception {
        long now = System.currentTimeMillis();
        int turns = relay.turns();
        long pauseLen = Long.parseLong(System.getenv().getOrDefault("NET_PAUSE_S", "30")) * 1000;
        switch (sc) {
            case "joinerpause":
                if (ps.stage == 0 && turns >= 2) { ps.stage = 1; ps.at = now; ps.turnsAtPause = turns; flag(root, "jp.now");
                    System.out.println("[harness] === joinerpause: Phone B's launcher sends pause, pause, resume control frames after " + turns + " turns ==="); }
                else if (ps.stage == 1 && now - ps.at > 20000) { ps.stage = 2; ps.turnsAtResume = turns;
                    System.out.println("[harness] === joinerpause: 20 s later " + turns + " turns (was " + ps.turnsAtPause + "), the game must have gone on ==="); }
                break;
            case "hostpause":
                if (ps.stage == 0 && turns >= 2) { ps.stage = 1; ps.at = now; }                                   // let the turn get going first
                else if (ps.stage == 1 && now - ps.at > 4000) { ps.stage = 2; ps.at = now; ps.turnsAtPause = turns; flag(root, "pause1.now");
                    System.out.println("[harness] === hostpause: the host taps pause mid-turn, " + turns + " turns so far; held for " + pauseLen / 1000 + " s ==="); }
                else if (ps.stage == 2 && now - ps.at > 5000 && !ps.frozenChecked) { ps.frozenChecked = true; ps.turnsFrozen = turns; }
                else if (ps.stage == 2 && now - ps.at > pauseLen) { ps.stage = 3; ps.at = now; ps.turnsAtResume = turns; ps.firstPauseMs = pauseLen; flag(root, "resume1.now");
                    System.out.println("[harness] === hostpause: the host resumes after " + pauseLen / 1000 + " s; turns " + ps.turnsAtPause + " at pause, " + ps.turnsFrozen + " 5 s into it, " + turns + " at resume ==="); }
                else if (ps.stage == 3 && turns >= ps.turnsAtResume + 2) { ps.stage = 4; ps.at = now; ps.notes.add("turns after the resume: " + turns + " (" + ps.turnsAtResume + " at the resume)"); }
                else if (ps.stage == 4 && now - ps.at > 3000) { ps.stage = 5; ps.at = now; flag(root, "pause2.now");
                    System.out.println("[harness] === hostpause: second pause (short) after " + turns + " turns ==="); }
                else if (ps.stage == 5 && now - ps.at > 3000) { ps.stage = 6; ps.at = now; flag(root, "resume2.now");
                    System.out.println("[harness] === hostpause: second resume ==="); }
                break;
            case "pausecap":
                if (ps.stage == 0 && turns >= 2) { ps.stage = 1; ps.at = now; }
                else if (ps.stage == 1 && now - ps.at > 4000) { ps.stage = 2; ps.at = now; ps.turnsAtPause = turns; flag(root, "pause1.now");
                    System.out.println("[harness] === pausecap: the host pauses and never resumes; the relay's cap resumes after the configured time ==="); }
                break;
            case "hostpausequit":
                if (ps.stage == 0 && turns >= 2) { ps.stage = 1; ps.at = now; }
                else if (ps.stage == 1 && now - ps.at > 4000) { ps.stage = 2; ps.at = now; ps.turnsAtPause = turns; flag(root, "pause1.now");
                    System.out.println("[harness] === hostpausequit: the host pauses ==="); }
                else if (ps.stage == 2 && now - ps.at > 6000) { ps.stage = 3; ps.turnsFrozen = turns; flag(root, "quit.now");
                    System.out.println("[harness] === hostpausequit: the host, still paused, confirms 'leave the game' (" + (System.getenv("NET_PAUSEQUIT_KEYS") != null ? "Escape then Y" : "the launcher quit, as the app's Back dialog does") + ") ==="); }
                break;
            default:
        }
    }

    static void pauseChecks(String sc, Relay.GameSummary gs, List<String> relayLog, Phone[] phones, PauseState ps, List<String> fails) throws Exception {
        int paused = 0, resumed = 0, ignored = 0, cap = 0;
        long firstLen = -1;
        for (String e : gs.events) {
            if (e.endsWith("(the host) paused the game")) paused++;
            if (e.contains("(the host) resumed the game after")) {
                resumed++;
                if (firstLen < 0) firstLen = Long.parseLong(e.replaceAll(".*after (\\d+) s$", "$1"));
            }
            if (e.contains("asked to") && e.contains("ignored")) ignored++;
            if (e.startsWith("Pause limit")) cap++;
        }
        int[] diagOn = new int[phones.length], diagOff = new int[phones.length];
        for (int i = 0; i < phones.length; i++) {
            Path g = phones[i].dir.resolve("netgame.txt");
            String d = Files.exists(g) ? new String(Files.readAllBytes(g)) : "";
            diagOn[i] = count(d, "PAUSE on"); diagOff[i] = count(d, "PAUSE off");
        }
        System.out.println("pause check: relay events: host paused " + paused + "x, resumed " + resumed + "x (first pause " + firstLen + " s), ignored requests " + ignored
                + ", cap resumes " + cap + "; launcher PAUSE on/off per phone: " + java.util.Arrays.toString(diagOn) + " / " + java.util.Arrays.toString(diagOff));
        for (String n : ps.notes) System.out.println("pause check: " + n);
        switch (sc) {
            case "hostpause":
                if (paused != 2 || resumed != 2) fails.add("expected 2 pauses and 2 resumes by the host, saw " + paused + " / " + resumed);
                long want = ps.firstPauseMs / 1000 - 2;
                if (firstLen < want) fails.add("the first pause lasted " + firstLen + " s in the relay's books, expected at least " + want + " s (longer than the silence limit)");
                if (ps.turnsFrozen != ps.turnsAtPause && ps.turnsFrozen != ps.turnsAtPause + 1) fails.add("the game moved during the pause: turns " + ps.turnsAtPause + " -> " + ps.turnsFrozen);
                if (ps.turnsAtResume != ps.turnsFrozen) fails.add("the turn count changed during the pause: " + ps.turnsFrozen + " -> " + ps.turnsAtResume);
                if (ps.notes.isEmpty()) fails.add("the game did not go on after the first resume");
                for (int i = 0; i < phones.length; i++) if (diagOn[i] != 2 || diagOff[i] != 2) fails.add(phones[i].name + ": its launcher saw " + diagOn[i] + " pauses / " + diagOff[i] + " resumes, expected 2 / 2");
                break;
            case "pausecap":
                if (paused != 1 || cap != 1) fails.add("expected 1 pause and 1 cap resume, saw " + paused + " / " + cap);
                for (int i = 0; i < phones.length; i++) if (diagOn[i] != 1 || diagOff[i] != 1) fails.add(phones[i].name + ": its launcher saw " + diagOn[i] + " pauses / " + diagOff[i] + " resumes, expected 1 / 1");
                break;
            case "hostpausequit":
                if (paused != 1) fails.add("expected 1 pause, saw " + paused);
                if (ps.turnsFrozen != ps.turnsAtPause && ps.turnsFrozen != ps.turnsAtPause + 1) fails.add("the game moved during the pause: turns " + ps.turnsAtPause + " -> " + ps.turnsFrozen);
                for (int i = 0; i < phones.length; i++) if (diagOn[i] != 1) fails.add(phones[i].name + ": its launcher saw " + diagOn[i] + " pauses, expected 1");
                break;
            case "joinerpause":
                if (paused != 0 || resumed != 0) fails.add("a joiner's request changed the pause state: " + paused + " / " + resumed);
                if (ignored < 3) fails.add("expected the relay to log 3 ignored requests from the joiner, saw " + ignored);
                for (int i = 0; i < phones.length; i++) if (diagOn[i] != 0 || diagOff[i] != 0) fails.add(phones[i].name + ": its launcher saw a pause (" + diagOn[i] + "/" + diagOff[i] + ")");
                if (ps.turnsAtResume < ps.turnsAtPause + 1) fails.add("the game did not go on while the joiner asked for a pause (turns " + ps.turnsAtPause + " -> " + ps.turnsAtResume + ")");
                break;
            default:
        }
    }

    /** One TV viewer session: viewer-sim.js (the TV page's stand-in) feeding a spectator engine, both from hedgewars-party's e2e-host. */
    static final class ViewerRun {
        final Path dir;
        final int idx;
        final String data, specPort;
        final int wsPort;
        Process spec, sim;
        boolean killed;

        ViewerRun(Path dir, int idx, String data, String specPort, int wsPort) {
            this.dir = dir; this.idx = idx; this.data = data; this.specPort = specPort; this.wsPort = wsPort;
        }

        void start() throws Exception {
            Files.createDirectories(dir.resolve("user"));
            ProcessBuilder sp = new ProcessBuilder(System.getenv("NET_PCHOST"), "spec", data, dir.resolve("user").toString(), "-", specPort, dir.toString());
            sp.environment().put("SDL_AUDIODRIVER", "dummy");
            sp.redirectErrorStream(true).redirectOutput(dir.resolve("spec.log").toFile());
            spec = sp.start();
            Thread.sleep(1500);
            ProcessBuilder np = new ProcessBuilder("node", System.getenv("NET_VIEWER_SIM"), "--url", "ws://127.0.0.1:" + wsPort,
                    "--spec", "127.0.0.1:" + specPort, "--out", dir.resolve("result.json").toString(), "--max", "600");
            np.redirectErrorStream(true).redirectOutput(dir.resolve("viewer-sim.log").toFile());
            sim = np.start();
        }

        /** The TV is switched off or loses the network: both processes die at once (SIGKILL), no close frame. */
        void kill() throws Exception {
            killed = true;
            sim.destroyForcibly();
            spec.destroyForcibly();
            sim.waitFor(10, TimeUnit.SECONDS);
            spec.waitFor(10, TimeUnit.SECONDS);
        }

        void finish() throws Exception {
            if (!sim.waitFor(40, TimeUnit.SECONDS)) sim.destroyForcibly();
            if (spec.isAlive()) spec.waitFor(20, TimeUnit.SECONDS);
            if (spec.isAlive()) spec.destroyForcibly();
        }

        void report(List<String> fails, boolean joinedLate) throws Exception {
            System.out.println("\n================ TV VIEWER session " + idx + (killed ? " (killed by the harness)" : "") + " ================");
            if (killed) return;
            String res = Files.exists(dir.resolve("result.json")) ? new String(Files.readAllBytes(dir.resolve("result.json"))) : "(no result.json)";
            for (String key : new String[] { "setupLines", "setupTransformedLines", "sawSpectateLine", "backlogMessages", "liveFrames", "liveMessages", "nonWhitelisted", "badFraming", "finishedBecause", "closed", "firstMessageBytes" })
                System.out.println("viewer-sim " + key + ": " + jsonField(res, key));
            System.out.println("viewer-sim message counts by type: " + jsonField(res, "counts"));
            String sl = new String(Files.readAllBytes(dir.resolve("spec.log")));
            String el = "";
            try (java.util.stream.Stream<Path> w = Files.walk(dir.resolve("user"))) {
                for (Path f : (Iterable<Path>) w.filter(x -> x.toString().endsWith(".log"))::iterator) el += new String(Files.readAllBytes(f));
            }
            System.out.println("spectator engine: 'Desync detected' " + count(el + sl, "Desync detected") + ", 'does not match' " + count(el + sl, "does not match")
                    + ", 'Unexpected protocol' " + count(el + sl, "Unexpected protocol") + ", 'queue error' " + count(el + sl, "queue error")
                    + ", turn-end checksums compared (got cmd N) " + count(el, "got cmd \"N\"") + ", " + grep(sl, "engine returned") + ", game over seen: " + sl.contains("game over ('q') seen"));
            String sres = el + sl, who = "TV viewer session " + idx + ": ";
            if (count(sres, "Desync detected") + count(sres, "does not match") + count(sres, "Unexpected protocol") + count(sres, "queue error") > 0) fails.add(who + "the spectator engine reported a desync, a mismatch, an unexpected protocol or a queue error");
            if (!sl.contains("game over ('q') seen")) fails.add(who + "the spectator engine did not see the game over");
            if (!jsonField(res, "nonWhitelisted").matches("0|\\[\\]|\\{\\}|-")) fails.add(who + "the viewer saw non-whitelisted messages: " + jsonField(res, "nonWhitelisted"));
            if (!jsonField(res, "badFraming").matches("0|false|\\[\\]|-")) fails.add(who + "the viewer saw bad framing: " + jsonField(res, "badFraming"));
            if (joinedLate && (!jsonField(res, "sawSpectateLine").equals("true") || jsonField(res, "backlogMessages").matches("0|-"))) fails.add(who + "a viewer that joined late did not get e$spectate 1 and a backlog");
            System.out.println("spectator engine map digest check: " + grep(el, "CheckLandDigest") + " | " + grep(el, "landcheck"));
        }
    }

    /** Taps on the touch HUD (fractions of the screen, as the host launcher injects them): walk right, jump, aim and fire, walk left. */
    static String inputScript(int seconds) {
        StringBuilder b = new StringBuilder();
        for (int t = 2000; t < seconds * 1000; t += 6000) {
            b.append(t).append(":down:0:0.077:0.93;").append(t + 900).append(":up:0:0.077:0.93;");
            b.append(t + 1200).append(":down:1:0.965:0.865;").append(t + 1300).append(":up:1:0.965:0.865;");
            b.append(t + 2600).append(":down:2:0.123:0.93;").append(t + 3800).append(":up:2:0.123:0.93;");
            b.append(t + 4600).append(":down:3:0.03:0.93;").append(t + 5200).append(":up:3:0.03:0.93;");
        }
        return b.toString();
    }

    static String jsonField(String json, String key) {
        int i = json.indexOf("\"" + key + "\":");
        if (i < 0) return "-";
        int st = i + key.length() + 3, depth = 0, e = st;
        for (; e < json.length(); e++) {
            char c = json.charAt(e);
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') { if (depth == 0) break; depth--; }
            else if (c == ',' && depth == 0) break;
        }
        return json.substring(st, e).replaceAll("\\s+", " ").trim();
    }

    static int count(String s, String n) { int c = 0, i = 0; while ((i = s.indexOf(n, i)) >= 0) { c++; i += n.length(); } return c; }
    static String grep(String s, String n) { int i = s.indexOf(n); if (i < 0) return "-"; int e = s.indexOf('\n', i); return s.substring(i, e < 0 ? s.length() : e); }

    static void deleteTree(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (java.util.stream.Stream<Path> w = Files.walk(p)) {
            w.sorted(java.util.Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        }
    }
}
