package org.hedgewars.android.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;

/**
 * The little WebSocket server side the TV viewer needs (plan section 4: HTTP upgrade, SHA-1, base64, frame encode,
 * a reader that ignores what the client sends). Same behaviour as the e2e-host bridge (tv/e2e-host/bridge/bridge.js):
 * unmasked binary frames to the viewer, close and ping handled, everything else ignored.
 */
final class Ws {
    private Ws() { }

    static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** The request head (up to the blank line) as text; null at end of stream. */
    static String readHead(InputStream in, long deadlineMs) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, state = 0;
        while ((c = in.read()) >= 0) {
            if (System.currentTimeMillis() > deadlineMs) throw new IOException("request head too slow");
            b.write(c);
            if (b.size() > 16384) throw new IOException("request head too long");
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1 : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return b.toString("ISO-8859-1");
        }
        return null;
    }

    static String header(String head, String name) {
        String lower = head.toLowerCase(Locale.ROOT), key = "\r\n" + name.toLowerCase(Locale.ROOT) + ":";
        int i = lower.indexOf(key);
        if (i < 0) return null;
        int s = i + key.length(), e = head.indexOf("\r\n", s);
        return head.substring(s, e < 0 ? head.length() : e).trim();
    }

    static byte[] handshakeResponse(String key) throws IOException {
        try {
            byte[] sha = MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1));
            String r = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                    + Base64.getEncoder().encodeToString(sha) + "\r\n\r\n";
            return r.getBytes(StandardCharsets.ISO_8859_1);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** One unfragmented server frame (opcode 2 binary, 8 close, 10 pong). */
    static byte[] frame(int op, byte[] p) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(p.length + 10);
        b.write(0x80 | op);
        if (p.length < 126) b.write(p.length);
        else if (p.length < 65536) { b.write(126); b.write(p.length >> 8); b.write(p.length); }
        else {
            b.write(127);
            for (int s = 56; s >= 0; s -= 8) b.write((int) ((long) p.length >> s));
        }
        b.write(p, 0, p.length);
        return b.toByteArray();
    }

    private static final int MAX_CLIENT_FRAME = 1 << 16;

    /** One byte, or an IOException at the end of the stream (a frame cut in half is an error, not a zero). */
    private static int need(InputStream in) throws IOException {
        int c = in.read();
        if (c < 0) throw new IOException("stream ended inside a frame");
        return c;
    }

    /**
     * Reads client frames until the client closes or the stream ends. A ping is answered with a pong that carries the
     * same payload, a close frame is echoed (with its status code) before returning; text and binary frames are
     * ignored (viewers are read-only). A negative or oversized length ends the connection with an IOException.
     * {@code reply} receives the frames to send back.
     */
    static void readClient(InputStream in, java.util.function.Consumer<byte[]> reply) throws IOException {
        for (;;) {
            int b0 = in.read();
            if (b0 < 0) return;
            int b1 = need(in);
            long len = b1 & 0x7f;
            if (len == 126) len = (need(in) << 8) | need(in);
            else if (len == 127) { len = 0; for (int k = 0; k < 8; k++) len = (len << 8) | need(in); }
            if (len < 0 || len > MAX_CLIENT_FRAME) throw new IOException("client frame length " + len + " refused");
            byte[] mask = new byte[4];
            if ((b1 & 0x80) != 0) for (int k = 0; k < 4; k++) mask[k] = (byte) need(in);
            byte[] p = new byte[(int) len];
            int got = 0;
            while (got < len) {
                int r = in.read(p, got, (int) len - got);
                if (r < 0) throw new IOException("stream ended inside a frame");
                got += r;
            }
            if ((b1 & 0x80) != 0) for (int k = 0; k < p.length; k++) p[k] ^= mask[k & 3];
            int op = b0 & 0x0f;
            if (op == 8) {
                reply.accept(frame(8, p.length >= 2 ? new byte[] { p[0], p[1] } : new byte[0]));
                return;
            }
            if (op == 9) reply.accept(frame(10, p.length > 125 ? java.util.Arrays.copyOf(p, 125) : p));
        }
    }
}
