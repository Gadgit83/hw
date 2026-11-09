package org.hedgewars.android.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The engine's IPC framing (one length byte, then that many bytes) and the relay's message rules.
 * Everything here is the plan's section 2.6 / 4 / 2.8: the whitelist is the Rust server's VALID_MESSAGES,
 * {@code M#+LlRrUuDdZzAaSjJ,NpPwtgfhbc12345} and 0x80..0x8A.
 */
public final class Frames {
    private Frames() { }

    public static final String WHITELIST = "M#+LlRrUuDdZzAaSjJ,NpPwtgfhbc12345";

    /** True for the message types the relay forwards. */
    public static boolean whitelisted(int type) {
        type &= 0xff;
        if (type >= 0x80 && type <= 0x8A) return true;
        return type != 0 && type < 0x80 && WHITELIST.indexOf((char) type) >= 0;
    }

    /**
     * The relay's per-message check: whitelisted type and, for hedgehog speech ({@code h}), a team index
     * that belongs to the sending phone (the Rust server's inroom.rs check; f and g are exempt).
     */
    public static boolean valid(byte[] msg, boolean[] ownedByTeamIndex) {
        if (msg.length == 0 || !whitelisted(msg[0])) return false;
        if (msg[0] == 'h') {
            if (msg.length < 3) return false;
            int style = msg[1] & 0xff, team = msg[2] & 0xff;
            return style >= 1 && style <= 8 && team < ownedByTeamIndex.length && ownedByTeamIndex[team];
        }
        return true;
    }

    /** Reads one frame; returns null at a clean end of stream before the length byte. */
    public static byte[] read(InputStream in) throws IOException {
        int len = in.read();
        if (len < 0) return null;
        byte[] b = new byte[len];
        int got = 0;
        while (got < len) {
            int r = in.read(b, got, len - got);
            if (r < 0) throw new IOException("stream ended inside a frame");
            got += r;
        }
        return b;
    }

    public static byte[] frame(byte[] msg) {
        if (msg.length > 255) throw new IllegalArgumentException("message too long: " + msg.length);
        byte[] f = new byte[msg.length + 1];
        f[0] = (byte) msg.length;
        System.arraycopy(msg, 0, f, 1, msg.length);
        return f;
    }

    public static byte[] frame(String line) {
        return frame(line.getBytes(StandardCharsets.UTF_8));
    }

    public static void write(OutputStream out, byte[] msg) throws IOException {
        out.write(frame(msg));
    }

    /** Reads a text line (up to the newline, which is consumed) byte by byte, so no binary data is swallowed. */
    public static String readLine(InputStream in, int max) throws IOException {
        return readLine(in, max, 0);
    }

    /** As {@link #readLine(InputStream, int)}, but gives up with an IOException once the clock passes {@code deadlineMs} (0: no limit). */
    public static String readLine(InputStream in, int max, long deadlineMs) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        for (;;) {
            if (deadlineMs != 0 && System.currentTimeMillis() > deadlineMs) throw new IOException("line too slow");
            int c = in.read();
            if (c < 0) return b.size() == 0 ? null : b.toString("UTF-8");
            if (c == '\n') return b.toString("UTF-8");
            if (b.size() >= max) throw new IOException("line too long");
            b.write(c);
        }
    }

    /** Hex of the first n bytes, for the logs. */
    public static String hex(byte[] b, int off, int n) {
        StringBuilder s = new StringBuilder();
        for (int k = 0; k < n && off + k < b.length; k++) s.append(String.format("%02x", b[off + k] & 0xff));
        return s.toString();
    }

    /** A message type for logs: the character, or slotNN for 0x80..0x8A. */
    public static String typeName(int t) {
        t &= 0xff;
        if (t >= 0x80 && t <= 0x8A) return "slot" + (t - 0x80);
        return t >= 0x20 && t < 0x7f ? String.valueOf((char) t) : String.format("0x%02x", t);
    }
}
