package org.hedgewars.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * The controls editor's geometry (issue #26): each outline sits where the engine reports its control, and a Save without a move
 * sends the default layout. The reports are the engine's own at the default layout (two jump buttons) on a 1280 x 720 screen at
 * UI scale 1, the scale whose picture was checked; scripts/layout-scale-test.sh checks the same places at scale 2.5.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = { 34 })
public class ControlsLayoutViewTest {
    private static final int W = 1280, H = 720;
    private static final String[] DEFAULT_REPORT = {
        "fire:0.122,0.930,1.000,0.050,0.088", "jump:0.964,0.866,1.000,0.050,0.088", "highjump:0.902,0.866,1.000,0.050,0.088",
        "left:0.029,0.930,1.000,0.039,0.069", "right:0.078,0.930,1.000,0.039,0.069", "aim:0.941,0.769,1.000,0.039,0.069",
        "weapons:0.925,0.937,1.000,0.050,0.088", "pause:0.976,0.034,1.000,0.046,0.069", "timer:0.035,0.829,1.000,0.050,0.088",
        "bounce:0.097,0.829,1.000,0.050,0.088" };

    /** The places the engine reported, by name, as the editor receives them (GameActivity.touchLayoutReport). */
    private static Map<String, TouchLayout.Place> reported() {
        Map<String, TouchLayout.Place> m = new LinkedHashMap<>();
        for (String entry : DEFAULT_REPORT) m.put(TouchLayout.reportName(entry), TouchLayout.reportPlace(entry));
        return m;
    }

    /** An editor over a W x H screen, opened with the reported places and the saved layout (a layout string, "" for none). */
    private static ControlsLayoutView editor(String saved) {
        ControlsLayoutView v = new ControlsLayoutView(RuntimeEnvironment.getApplication(), true, null);
        v.setDefaults(reported(), saved);
        v.layout(0, 0, W, H);
        return v;
    }

    private static void touch(ControlsLayoutView v, int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(1L, 1L, action, x, y, 0);
        v.dispatchTouchEvent(e);
        e.recycle();
    }

    @Test
    public void everyOutlineSitsOnItsReportedPlace() {
        ControlsLayoutView v = editor("");
        for (String entry : DEFAULT_REPORT) {
            String name = TouchLayout.reportName(entry);
            TouchLayout.Place p = TouchLayout.reportPlace(entry);
            RectF r = v.outlineOf(name);
            assertNotNull(name + " is in the editor", r);
            assertEquals(name + " centre x", p.x * W, r.centerX(), 0.5);
            assertEquals(name + " centre y", p.y * H, r.centerY(), 0.5);
            assertEquals(name + " width", p.w * W, r.width(), 0.5);
            assertEquals(name + " height", p.h * H, r.height(), 0.5);
        }
        assertNull("no control the engine did not report", v.outlineOf("nosuch"));
    }

    @Test
    public void saveWithoutAMoveSendsTheDefaultLayout() {
        assertEquals("", editor("").currentSpec());
    }

    @Test
    public void draggingOneControlMovesOnlyThatControl() {
        ControlsLayoutView v = editor("");
        RectF fire = v.outlineOf("fire");
        float x = fire.centerX(), y = fire.centerY();
        touch(v, MotionEvent.ACTION_DOWN, x, y);
        touch(v, MotionEvent.ACTION_MOVE, x + 64, y - 36);
        touch(v, MotionEvent.ACTION_UP, x + 64, y - 36);

        Map<String, double[]> places = TouchLayout.parse(v.currentSpec());
        assertTrue("the moved layout is saved", places.containsKey("fire"));
        assertEquals("fire moved right by 64 px", 0.122 + 64.0 / W, places.get("fire")[0], 0.002);
        assertEquals("fire moved up by 36 px", 0.930 - 36.0 / H, places.get("fire")[1], 0.002);
        assertEquals("fire keeps its size", 1.0, places.get("fire")[2], 0.002);

        ControlsLayoutView fresh = editor("");
        for (String name : TouchLayout.NAMES) {
            if (name.equals("fire") || fresh.outlineOf(name) == null) continue;
            RectF before = fresh.outlineOf(name), after = v.outlineOf(name);
            assertEquals(name + " stays put", before.centerX(), after.centerX(), 0.5);
            assertEquals(name + " stays put", before.centerY(), after.centerY(), 0.5);
        }
    }

    /**
     * A picture of the editor (not a check): with LAYOUT_PICTURE_DIR set, the editor is drawn over the game picture
     * (LAYOUT_PICTURE_BG, a 1280 x 720 PNG) once for each pair in LAYOUT_PICTURE_PAIRS, "name=reportFile;name=reportFile", where a
     * report file holds the engine's report lines (one per control). Each picture is LAYOUT_PICTURE_DIR/name.png. It is run by
     * hand; a normal run skips it.
     */
    @Test
    public void picture() throws Exception {
        String dir = System.getenv("LAYOUT_PICTURE_DIR");
        Assume.assumeTrue("set LAYOUT_PICTURE_DIR to draw the editor", dir != null && !dir.isEmpty());
        new File(dir).mkdirs();
        for (String pair : System.getenv("LAYOUT_PICTURE_PAIRS").split(";")) {
            String[] nv = pair.split("=", 2);
            Map<String, TouchLayout.Place> places = new LinkedHashMap<>();
            for (String line : Files.readAllLines(new File(nv[1]).toPath())) {
                String entry = line.trim();
                if (entry.isEmpty()) continue;
                places.put(TouchLayout.reportName(entry), TouchLayout.reportPlace(entry));
            }
            FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
            ImageView game = new ImageView(RuntimeEnvironment.getApplication());
            game.setImageBitmap(BitmapFactory.decodeFile(System.getenv("LAYOUT_PICTURE_BG")));
            root.addView(game, new FrameLayout.LayoutParams(W, H));
            ControlsLayoutView v = new ControlsLayoutView(RuntimeEnvironment.getApplication(), true, null);
            v.setDefaults(places, "");
            root.addView(v, new FrameLayout.LayoutParams(W, H));
            root.layout(0, 0, W, H);
            Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(bmp));
            try (FileOutputStream o = new FileOutputStream(new File(dir, nv[0] + ".png"))) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
            }
        }
    }
}
