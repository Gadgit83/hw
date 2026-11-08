package org.hedgewars.android;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * What a phone knows about the network game it just played, for the screen and for Share log: the launcher's
 * diagnostics file (files/logs/netgame.txt, written by the game process: every turn checksum N and map digest M
 * that crossed the link, in either direction, plus message counts and stream hashes) and the relay's own report
 * (files/logs/netsession.txt, written by the main process when the game ends).
 */
final class NetDiag {
    private NetDiag() { }

    static File launcherFile(Context c) {
        return new File(Diag.logsDir(c), "netgame.txt");
    }

    static File sessionFile(Context c) {
        return new File(Diag.logsDir(c), "netsession.txt");
    }

    static void saveSession(Context c, String text) {
        try {
            Diag.writeFile(sessionFile(c), text, false);
        } catch (IOException ignored) {
        }
    }

    static void clear(Context c) {
        launcherFile(c).delete();
    }

    /** Short digest of this phone's view of the last game, for the end-of-game screen. */
    static String summary(Context c) {
        String raw = Diag.readTail(launcherFile(c), 256 * 1024);
        if (raw == null || raw.isEmpty()) return "No network game has been played on this phone yet.";
        List<String> turns = new ArrayList<>();
        String map = "", summary = "", problem = "";
        int desync = 0;
        for (String l : raw.split("\n")) {
            String t = l.trim();
            if (t.contains(" N ") && t.contains("checksum")) {
                String[] w = t.split("\\s+");
                if (w.length >= 9) turns.add(w[4] + ":" + w[6] + (w[2].equals("tx") ? "" : "'"));
            } else if (t.contains(" M ") && t.contains("map digest") && map.isEmpty()) {
                map = t.substring(t.indexOf("map digest") + 11);
            } else if (t.contains(" SUMMARY ")) {
                summary = t.substring(t.indexOf("SUMMARY") + 8);
            } else if (t.contains(" LINK ") || t.contains(" CONTROL E ")) {
                problem += t.replaceFirst("^\\d+\\s+", "") + "\n";
            }
            if (t.toLowerCase().contains("desync")) desync++;
        }
        StringBuilder b = new StringBuilder();
        b.append("Map digest M: ").append(map.isEmpty() ? "(none)" : map).append('\n');
        b.append("Desync reports: ").append(desync == 0 ? "none" : Integer.toString(desync)).append('\n');
        b.append("Turn checksums N (turn:first 4 bytes, ' = received from another phone): ").append(turns.size()).append('\n');
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < turns.size(); i++) {
            row.append(turns.get(i));
            row.append(i % 4 == 3 ? '\n' : "  ");
        }
        b.append(row).append('\n');
        if (!summary.isEmpty()) b.append("Link totals: ").append(summary).append('\n');
        if (!problem.isEmpty()) b.append("Link problems:\n").append(problem);
        return b.toString();
    }

    /** Both files, whole, for Share log. */
    static String report(Context c) {
        StringBuilder b = new StringBuilder();
        b.append("-- launcher (this phone's engine link) --\n");
        String l = Diag.readTail(launcherFile(c), 512 * 1024);
        b.append(l == null || l.isEmpty() ? "(none)\n" : l);
        b.append("\n-- relay report as received by this phone --\n");
        String s = Diag.readTail(sessionFile(c), 128 * 1024);
        b.append(s == null || s.isEmpty() ? "(none)\n" : s);
        return b.toString();
    }
}
