package org.hedgewars.android;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * On-device diagnostics, so logs can be sent from a phone without a computer.
 *
 * Each process (the setup screen "main" and the game process "game") writes its own logcat to
 * files/logs/&lt;tag&gt;.log from a child logcat process, and records uncaught Java exceptions in
 * files/logs/crash-&lt;tag&gt;.txt. The native launcher writes files/logs/last_error.txt when the
 * engine fails and GameActivity creates files/logs/game_running, which the launcher removes
 * after a clean end. {@link #buildReport} gathers all of it into one text file to share.
 */
final class Diag {
    static final String TAG = "HWNative";
    static final String LAST_ERROR = "last_error.txt";
    static final String GAME_MARKER = "game_running";
    private static final String REPORT = "report.txt";
    private static final long TAIL_BYTES = 256 * 1024;

    private static volatile boolean started;

    private Diag() { }

    static File logsDir(Context c) {
        File d = new File(c.getFilesDir(), "logs");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** "main" for the setup screen, "game" for the engine process. */
    static String processTag() {
        String name = "";
        try (InputStream in = new FileInputStream("/proc/self/cmdline")) {
            byte[] b = new byte[256];
            int n = in.read(b);
            if (n > 0) {
                int end = 0;
                while (end < n && b[end] != 0) end++;
                name = new String(b, 0, end, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : "main";
    }

    static synchronized void init(Context c) {
        if (started) return;
        started = true;
        final Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        final String tag = processTag();
        File dir = logsDir(app);

        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String text = timestamp() + " uncaught exception in thread " + t.getName()
                        + " (process " + tag + ")\n" + sw;
                Log.e(TAG, text);
                writeFile(new File(logsDir(app), "crash-" + tag + ".txt"), text, false);
                writeFile(new File(logsDir(app), LAST_ERROR),
                        "The app crashed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ").\n", false);
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(t, e);
        });

        if ("main".equals(tag)) dropStaleProblem(app, dir);
        startLogcat(dir, tag);
        Log.i(TAG, "process " + tag + " started, version " + versionName(app) + ", " + deviceLine());
    }

    /** Child logcat limited to this process, so native engine output survives a crash. */
    private static void startLogcat(File dir, String tag) {
        try {
            File cur = new File(dir, tag + ".log");
            File prev = new File(dir, tag + ".prev.log");
            if (cur.isFile()) {
                prev.delete();
                cur.renameTo(prev);
            }
            File rotated = new File(dir, tag + ".log.1");
            rotated.delete();
            new ProcessBuilder("logcat", "-v", "threadtime", "-f", cur.getAbsolutePath(),
                    "-r", "2048", "-n", "2", "--pid=" + android.os.Process.myPid())
                    .redirectErrorStream(true).start();
        } catch (Throwable t) {
            Log.w(TAG, "could not start the logcat writer: " + t);
        }
    }

    static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(new Date());
    }

    static String versionName(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Exception e) {
            return "?";
        }
    }

    static String deviceLine() {
        return Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + "), " + Build.SUPPORTED_ABIS[0];
    }

    static void writeFile(File f, String text, boolean append) throws IOException {
        try (OutputStream out = new FileOutputStream(f, append)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Called by GameActivity before anything can fail. */
    static void markGameStarted(Context c) {
        try {
            writeFile(new File(logsDir(c), GAME_MARKER), timestamp() + "\n", false);
        } catch (IOException e) {
            Log.w(TAG, "cannot write the game marker: " + e);
        }
    }

    static void writeError(Context c, String message) {
        try {
            File f = new File(logsDir(c), LAST_ERROR);
            if (!f.isFile()) writeFile(f, message + "\n", false);
        } catch (IOException e) {
            Log.w(TAG, "cannot write the error file: " + e);
        }
    }

    static String lastError(Context c) {
        File f = new File(logsDir(c), LAST_ERROR);
        if (!f.isFile()) return null;
        String s = readTail(f, 2000).trim();
        return s.isEmpty() ? null : s;
    }

    /** True when a game was started and neither finished cleanly nor reported why. */
    static boolean previousGameUnclean(Context c) {
        return new File(logsDir(c), GAME_MARKER).isFile();
    }

    /**
     * An error recorded by an older version must not show after an update (0.2.2 still showed the launch crash of
     * 0.2.1): the version that wrote the logs is kept beside them and the stored problem is dropped when it differs.
     * Runs in the setup screen process only, before anything of this version could have been recorded.
     */
    static void dropStaleProblem(Context app, File dir) {
        try {
            File v = new File(dir, "app_version.txt");
            String cur = versionName(app);
            String old = v.isFile() ? readTail(v, 200).trim() : "";
            if (!cur.equals(old)) {
                new File(dir, LAST_ERROR).delete();
                new File(dir, GAME_MARKER).delete();
                writeFile(v, cur + "\n", false);
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "cannot check the version of the stored problem: " + e);
        }
    }

    /** When the stored problem was written (0: none). The setup screen shows it once per problem. */
    static long problemStamp(Context c) {
        File e = new File(logsDir(c), LAST_ERROR), m = new File(logsDir(c), GAME_MARKER);
        return Math.max(e.isFile() ? e.lastModified() : 0, m.isFile() ? m.lastModified() : 0);
    }

    /** The first line of the stored problem, shortened: the details stay in the shared log. */
    static String problemSummary(String err) {
        if (err == null) return "The last game did not end cleanly.";
        String first = err.split("\n", 2)[0].trim();
        if (first.length() > 160) first = first.substring(0, 160) + "...";
        return first;
    }

    static void clearGameState(Context c) {
        new File(logsDir(c), LAST_ERROR).delete();
        new File(logsDir(c), GAME_MARKER).delete();
    }

    static boolean gameProcessAlive(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> l = am.getRunningAppProcesses();
            if (l != null) for (ActivityManager.RunningAppProcessInfo p : l) {
                if (p.processName != null && p.processName.endsWith(":game")) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Last {@code max} bytes of a text file, decoded leniently; empty if unreadable. */
    static String readTail(File f, long max) {
        if (f == null || !f.isFile()) return "";
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            long from = Math.max(0, len - max);
            r.seek(from);
            byte[] b = new byte[(int) (len - from)];
            r.readFully(b);
            String s = new String(b, StandardCharsets.UTF_8);
            return from > 0 ? "[... earlier part cut ...]\n" + s : s;
        } catch (Exception e) {
            return "[could not read " + f.getName() + ": " + e + "]";
        }
    }

    private static String run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try (InputStream in = p.getInputStream()) {
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 1024 * 1024) out.write(buf, 0, n);
            }
            p.destroy();
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return "[" + String.join(" ", cmd) + " failed: " + t + "]";
        }
    }

    private static void section(StringBuilder sb, String title, String body) {
        sb.append("\n===== ").append(title).append(" =====\n");
        sb.append(body == null || body.isEmpty() ? "(none)\n" : body);
        if (body != null && !body.isEmpty() && !body.endsWith("\n")) sb.append('\n');
    }

    private static String exitReasons(Context c) {
        if (Build.VERSION.SDK_INT < 30) return "not available before Android 11";
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 8);
            StringBuilder sb = new StringBuilder();
            String[] names = { "?", "EXIT_SELF", "SIGNALED", "LOW_MEMORY", "CRASH", "CRASH_NATIVE", "ANR",
                    "INITIALIZATION_FAILURE", "PERMISSION_CHANGE", "EXCESSIVE_RESOURCE_USAGE",
                    "USER_REQUESTED", "USER_STOPPED", "DEPENDENCY_DIED", "OTHER" };
            for (ApplicationExitInfo i : l) {
                int r = i.getReason();
                sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(new Date(i.getTimestamp())))
                        .append("  ").append(i.getProcessName())
                        .append("  pid ").append(i.getPid())
                        .append("  ").append(r >= 0 && r < names.length ? names[r] : String.valueOf(r))
                        .append("  status ").append(i.getStatus())
                        .append("  ").append(i.getDescription()).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "[failed: " + t + "]";
        }
    }

    /**
     * Native crash dump (tombstone) of the latest CRASH_NATIVE exit of the game process, from
     * ApplicationExitInfo.getTraceInputStream() (Android 11+). Android 12+ gives a protobuf
     * (tombstone.proto), Android 11 text. The raw bytes are kept in logs/tombstone-game.bin and the
     * report carries a text rendering: the text itself, or a generic protobuf dump (field numbers
     * as in system/core/debuggerd/proto/tombstone.proto, which names the signal, fault address,
     * registers and backtrace frames with pc, function and build id).
     */
    private static String nativeTombstone(Context c) {
        if (Build.VERSION.SDK_INT < 30) return "not available before Android 11";
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 16);
            for (ApplicationExitInfo i : l) {
                if (i.getReason() != ApplicationExitInfo.REASON_CRASH_NATIVE) continue;
                String pn = i.getProcessName();
                if (pn == null || !pn.endsWith(":game")) continue;
                String head = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(new Date(i.getTimestamp()))
                        + "  " + pn + "  pid " + i.getPid() + "\n";
                InputStream in = i.getTraceInputStream();
                if (in == null) return head + "(the system kept no trace for this exit)";
                byte[] data;
                try (InputStream t = in) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = t.read(buf)) > 0 && bo.size() < 2 * 1024 * 1024) bo.write(buf, 0, n);
                    data = bo.toByteArray();
                }
                try (OutputStream o = new FileOutputStream(new File(logsDir(c), "tombstone-game.bin"))) {
                    o.write(data);
                } catch (IOException ignored) {
                }
                if (data.length == 0) return head + "(empty trace)";
                if (looksLikeText(data)) return head + new String(data, StandardCharsets.UTF_8);
                StringBuilder sb = new StringBuilder(head).append("(protobuf, ").append(data.length).append(" bytes)\n");
                dumpProto(data, 0, data.length, 0, sb);
                return sb.toString();
            }
            return "(no native crash of the game process in the system's exit history)";
        } catch (Throwable t) {
            return "[failed: " + t + "]";
        }
    }

    private static boolean looksLikeText(byte[] d) {
        int n = Math.min(d.length, 512);
        for (int i = 0; i < n; i++) {
            int b = d[i] & 0xff;
            if (b == 0 || (b < 9) || (b > 13 && b < 32)) return false;
        }
        return true;
    }

    private static boolean printable(byte[] d, int from, int to) {
        if (to <= from) return false;
        for (int i = from; i < to; i++) {
            int b = d[i] & 0xff;
            if (b < 32 && b != 9 && b != 10) return false;
            if (b == 127) return false;
        }
        return true;
    }

    /** Generic protobuf walker: "field: value", nested messages indented. Stops quietly on bad data. */
    private static int dumpProto(byte[] d, int pos, int end, int depth, StringBuilder sb) {
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < depth; i++) pad.append("  ");
        while (pos < end && sb.length() < 400 * 1024) {
            long[] k = varint(d, pos, end);
            if (k == null) return -1;
            pos = (int) k[1];
            int field = (int) (k[0] >>> 3), wire = (int) (k[0] & 7);
            if (field == 0) return -1;
            if (wire == 0) {
                long[] v = varint(d, pos, end);
                if (v == null) return -1;
                pos = (int) v[1];
                sb.append(pad).append(field).append(": ").append(v[0] < 0 ? Long.toUnsignedString(v[0]) : String.valueOf(v[0]))
                        .append(v[0] > 4096 || v[0] < 0 ? " (0x" + Long.toHexString(v[0]) + ")" : "").append('\n');
            } else if (wire == 1) {
                if (pos + 8 > end) return -1;
                long v = 0;
                for (int i = 7; i >= 0; i--) v = (v << 8) | (d[pos + i] & 0xff);
                pos += 8;
                sb.append(pad).append(field).append(": 0x").append(Long.toHexString(v)).append('\n');
            } else if (wire == 5) {
                if (pos + 4 > end) return -1;
                long v = 0;
                for (int i = 3; i >= 0; i--) v = (v << 8) | (d[pos + i] & 0xff);
                pos += 4;
                sb.append(pad).append(field).append(": 0x").append(Long.toHexString(v)).append('\n');
            } else if (wire == 2) {
                long[] len = varint(d, pos, end);
                if (len == null || len[0] < 0 || len[1] + len[0] > end) return -1;
                int s = (int) len[1], e = (int) (s + len[0]);
                pos = e;
                if (printable(d, s, e)) {
                    sb.append(pad).append(field).append(": \"").append(new String(d, s, e - s, StandardCharsets.UTF_8)).append("\"\n");
                } else {
                    int mark = sb.length();
                    sb.append(pad).append(field).append(" {\n");
                    if (depth < 8 && e > s && dumpProto(d, s, e, depth + 1, sb) >= 0) {
                        sb.append(pad).append("}\n");
                    } else {
                        sb.setLength(mark);
                        sb.append(pad).append(field).append(": [").append(e - s).append(" bytes]\n");
                    }
                }
            } else {
                return -1;
            }
        }
        return pos;
    }

    /** {value, next position} or null. */
    private static long[] varint(byte[] d, int pos, int end) {
        long v = 0;
        for (int shift = 0; shift < 64 && pos < end; shift += 7) {
            int b = d[pos++] & 0xff;
            v |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return new long[] { v, pos };
        }
        return null;
    }

    /** Newest engine log written by the game itself (files/user/Logs/game*.log). */
    private static File newestEngineLog(Context c) {
        File[] l = new File(c.getFilesDir(), "user/Logs").listFiles();
        File best = null;
        if (l != null) for (File f : l) {
            if (f.isFile() && f.getName().endsWith(".log") && (best == null || f.lastModified() > best.lastModified())) best = f;
        }
        return best;
    }

    /** Gathers everything useful into files/logs/report.txt. Slow (runs logcat): call off the UI thread. */
    static File buildReport(Context c) {
        File dir = logsDir(c);
        StringBuilder sb = new StringBuilder();
        sb.append(BuildConfig.APP_NAME).append(" log report, ").append(timestamp()).append('\n');
        sb.append("App ").append(versionName(c)).append('\n');
        sb.append("Engine ").append(BuildConfig.ENGINE_COMMIT).append(" (").append(BuildConfig.ENGINE_BRANCH)
                .append("), app source ").append(BuildConfig.APP_COMMIT).append('\n');
        sb.append(deviceLine()).append('\n');
        sb.append("Options: ").append(Store.loadOptions(c).toText().replace("\n", " ")).append('\n');
        File ipc = new File(c.getFilesDir(), "game.ipc");
        section(sb, "Game setup sent to the engine (files/game.ipc)", readTail(ipc, 16 * 1024));
        sb.append("Game marker present: ").append(previousGameUnclean(c)).append('\n');

        section(sb, "Last error", readTail(new File(dir, LAST_ERROR), 4000));
        section(sb, "Recent process exits", exitReasons(c));
        section(sb, "Native crash of the game process (tombstone)", nativeTombstone(c));
        for (String t : new String[] { "game", "main" }) {
            section(sb, "Java crash in " + t, readTail(new File(dir, "crash-" + t + ".txt"), 20000));
        }
        section(sb, "Engine log (user/Logs, newest)", readTail(newestEngineLog(c), 128 * 1024));
        section(sb, "Game process log, this run", readTail(new File(dir, "game.log"), TAIL_BYTES));
        section(sb, "Game process log, previous run", readTail(new File(dir, "game.prev.log"), TAIL_BYTES));
        section(sb, "Setup screen log, this run", readTail(new File(dir, "main.log"), 64 * 1024));
        section(sb, "System log for this app (logcat, includes earlier runs and crash buffer)",
                run("logcat", "-d", "-v", "threadtime", "-b", "main", "-b", "crash", "-t", "3000"));
        File out = new File(dir, REPORT);
        try {
            writeFile(out, sb.toString(), false);
        } catch (IOException e) {
            Log.w(TAG, "cannot write the report: " + e);
        }
        return out;
    }
}
