package org.hedgewars.android.net;

/**
 * FNV-1a, 64 bit. Used for the running hashes of message streams, because the C launcher can compute
 * the same value without a library. Feed the bytes of each framed message (length byte first).
 */
public final class Fnv {
    private long h = 0xcbf29ce484222325L;

    public void add(byte[] b, int off, int len) {
        for (int k = 0; k < len; k++) {
            h ^= (b[off + k] & 0xff);
            h *= 0x100000001b3L;
        }
    }

    public void add(byte[] b) {
        add(b, 0, b.length);
    }

    public String hex() {
        return String.format("%016x", h);
    }
}
