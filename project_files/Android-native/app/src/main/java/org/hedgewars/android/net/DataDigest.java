package org.hedgewars.android.net;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A fingerprint of the installed game data, compared when a phone joins (docs/tv-viewer-plan.md section 2.8, compatibility checks).
 *
 * <p>What is hashed: the whole manifest, that is every file under the data folder as "relative path, tab, size in bytes", sorted,
 * except files whose name starts with a dot (the install marker {@code .installed} holds an install time that differs on every phone,
 * {@code .profile} and {@code .digest} are bookkeeping). Contents are not read: sizes make this cheap (a few thousand directory
 * entries) and, since both phones run the same build, a changed or missing file shows as a changed size or list.
 *
 * <p>Why the whole manifest rather than only "the content the host's setup uses": the host picks the theme, the map generator and
 * the scheme at random after the phones have joined, and each phone's teams bring their own forts, graves, flags, voices and hats,
 * so the set a game needs is not known at join time. A partial check would pass at join and fail at the map digest M, after the
 * map was generated. The test profile (3 themes, few hats) and the full data therefore differ, as they should.
 *
 * <p>The result is cached beside the install marker ({@code Data/.digest}, first line the marker's text, second the digest) so it is
 * computed once after an install. Plain Java: no Android classes.
 */
public final class DataDigest {
    private DataDigest() { }

    public static final String CACHE = ".digest";
    public static final String MARKER = ".installed";

    /** The 32-hex-digit fingerprint of the data under {@code root}; "" if the folder is missing or unreadable. */
    public static String compute(File root) {
        if (root == null || !root.isDirectory()) return "";
        List<String> lines = new ArrayList<>();
        try {
            walk(root, "", lines);
            Collections.sort(lines);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String l : lines) md.update((l + "\n").getBytes(StandardCharsets.UTF_8));
            byte[] d = md.digest();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 16; i++) b.append(String.format("%02x", d[i] & 0xff));
            return lines.isEmpty() ? "" : b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void walk(File dir, String prefix, List<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String n = k.getName();
            if (n.startsWith(".")) continue;
            if (k.isDirectory()) walk(k, prefix + n + "/", out);
            else out.add(prefix + n + "\t" + k.length());
        }
    }

    /** The cached digest if it belongs to the current install marker, else computed now and cached. Not for the UI thread. */
    public static String cached(File root) {
        if (root == null || !root.isDirectory()) return "";
        String marker = readFirstLine(new File(root, MARKER));
        File cache = new File(root, CACHE);
        if (!marker.isEmpty()) {
            try {
                List<String> l = Files.readAllLines(cache.toPath(), StandardCharsets.UTF_8);
                if (l.size() >= 2 && l.get(0).equals(marker) && l.get(1).matches("[0-9a-f]{32}")) return l.get(1);
            } catch (IOException ignored) {
            }
        }
        String d = compute(root);
        if (!marker.isEmpty() && !d.isEmpty()) {
            try {
                Files.write(cache.toPath(), (marker + "\n" + d + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }
        return d;
    }

    private static String readFirstLine(File f) {
        try {
            List<String> l = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            return l.isEmpty() ? "" : l.get(0).trim();
        } catch (IOException e) {
            return "";
        }
    }
}
