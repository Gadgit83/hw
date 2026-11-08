package party.hedge.host;

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
    static final String TAG = "HedgeParty";
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
        sb.append("Hedge Party log report, ").append(timestamp()).append('\n');
        sb.append("App ").append(versionName(c)).append('\n');
        sb.append(deviceLine()).append('\n');
        sb.append("Game marker present: ").append(previousGameUnclean(c)).append('\n');

        section(sb, "Last error", readTail(new File(dir, LAST_ERROR), 4000));
        section(sb, "Recent process exits", exitReasons(c));
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
