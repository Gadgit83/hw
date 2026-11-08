package org.hedgewars.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.File;

/** Small pictures for the pickers, cut from the game's own artwork. Decoding is cheap but cached. */
public final class Icons {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(400);

    private Icons() { }

    private static Bitmap cached(String key) {
        return CACHE.get(key);
    }

    private static Bitmap put(String key, Bitmap b) {
        if (b != null) CACHE.put(key, b);
        return b;
    }

    /** The first 32 by 32 frame of a sprite sheet, enlarged by a whole factor so the pixels stay sharp. */
    private static Bitmap firstFrame(File f, int frame, int scale) {
        Bitmap src = BitmapFactory.decodeFile(f.getPath());
        if (src == null) return null;
        int w = Math.min(frame, src.getWidth()), h = Math.min(frame, src.getHeight());
        Bitmap cut = Bitmap.createBitmap(src, 0, 0, w, h);
        if (cut != src) src.recycle();
        return Bitmap.createScaledBitmap(cut, w * scale, h * scale, false);
    }

    public static Bitmap hat(GameData d, String name) {
        String k = "hat:" + name;
        Bitmap b = cached(k);
        return b != null ? b : put(k, firstFrame(new File(d.root, "Graphics/Hats/" + name + ".png"), 32, 3));
    }

    public static Bitmap grave(GameData d, String name) {
        String k = "grave:" + name;
        Bitmap b = cached(k);
        return b != null ? b : put(k, firstFrame(new File(d.root, "Graphics/Graves/" + name + ".png"), 32, 3));
    }

    public static Bitmap fort(GameData d, String name) {
        String k = "fort:" + name;
        Bitmap b = cached(k);
        return b != null ? b : put(k, firstFrame(new File(d.root, "Forts/" + name + "-icon.png"), 32, 3));
    }

    public static Bitmap theme(GameData d, String name) {
        String k = "theme:" + name;
        Bitmap b = cached(k);
        if (b != null) return b;
        File f2 = new File(d.root, "Themes/" + name + "/icon@2x.png");
        File f = f2.isFile() ? f2 : new File(d.root, "Themes/" + name + "/icon.png");
        Bitmap src = BitmapFactory.decodeFile(f.getPath());
        return put(k, src);
    }

    /** A small preview of an image map, decoded at a reduced size. Null for maps that only have a mask. */
    public static Bitmap map(GameData d, String name) {
        String k = "map:" + name;
        Bitmap b = cached(k);
        if (b != null) return b;
        File f = new File(d.root, "Maps/" + name + "/map.png");
        if (!f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= 220) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        return put(k, BitmapFactory.decodeFile(f.getPath(), o));
    }
}
