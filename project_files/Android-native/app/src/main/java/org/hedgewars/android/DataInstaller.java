package org.hedgewars.android;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.StatFs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies the game data from the APK assets to internal storage on first run (or after an
 * update). PhysFS in the engine needs real files; reading straight from the APK would need a
 * custom PhysFS archiver. The cost is that the data exists twice on the device.
 */
public final class DataInstaller {
    public interface Progress { void onProgress(int done, int total); }

    private static final String ROOT = "Data";
    private static final String MARKER = ".installed";
    /** Room needed for the unpacked data plus headroom. A constant, as the asset total is not known cheaply. */
    private static final long REQUIRED_BYTES = 150L * 1024 * 1024;

    private DataInstaller() { }

    private static String stamp(Context c) throws Exception {
        long t = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
        return Long.toString(t);
    }

    public static boolean isInstalled(Context c) {
        File marker = new File(c.getFilesDir(), ROOT + "/" + MARKER);
        try {
            if (!marker.isFile()) return false;
            byte[] b = new byte[64];
            try (InputStream in = new java.io.FileInputStream(marker)) {
                int n = in.read(b);
                return new String(b, 0, Math.max(n, 0)).trim().equals(stamp(c));
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static void install(Context c, Progress p) throws Exception {
        long free = new StatFs(c.getFilesDir().getPath()).getAvailableBytes();
        if (free < REQUIRED_BYTES) {
            throw new IOException("not enough free storage: about 150 MB is needed, "
                    + (free / (1024 * 1024)) + " MB is free");
        }
        AssetManager am = c.getAssets();
        File dest = new File(c.getFilesDir(), ROOT);
        deleteTree(dest);
        int total = count(am, ROOT);
        int[] done = { 0 };
        copyTree(am, ROOT, dest, done, total, p);
        try (FileOutputStream m = new FileOutputStream(new File(dest, MARKER))) {
            m.write(stamp(c).getBytes("UTF-8"));
        }
        // Fingerprint of the data for the join check, cached beside the marker so it is computed once per install.
        org.hedgewars.android.net.DataDigest.cached(dest);
        new File(c.getFilesDir(), "user").mkdirs();
    }

    private static int count(AssetManager am, String path) throws IOException {
        String[] kids = am.list(path);
        if (kids == null || kids.length == 0) return 1;
        int n = 0;
        for (String k : kids) n += count(am, path + "/" + k);
        return n;
    }

    private static void copyTree(AssetManager am, String path, File dest, int[] done, int total, Progress p)
            throws IOException {
        String[] kids = am.list(path);
        if (kids == null || kids.length == 0) {
            dest.getParentFile().mkdirs();
            try (InputStream in = am.open(path); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            done[0]++;
            if (p != null && (done[0] % 50 == 0 || done[0] == total)) p.onProgress(done[0], total);
            return;
        }
        dest.mkdirs();
        for (String k : kids) copyTree(am, path + "/" + k, new File(dest, k), done, total, p);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
