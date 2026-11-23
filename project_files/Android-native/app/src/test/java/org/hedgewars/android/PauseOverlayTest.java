package org.hedgewars.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The pause overlay of a rejoined host (PauseOverlay): shown when the host asks to pause while its engine is still replaying, gone when
 * the pause reaches the engine or the game is resumed, and never in the way of a touch. A phone that did not rejoin shows nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {34})
public class PauseOverlayTest {
    private static final int ASKED_ON = PauseOverlay.ASKED_ON, ASKED_OFF = PauseOverlay.ASKED_OFF,
            APPLIED_ON = PauseOverlay.APPLIED_ON, APPLIED_OFF = PauseOverlay.APPLIED_OFF;

    @Rule public TemporaryFolder files = new TemporaryFolder();

    private static Activity activity() {
        return Robolectric.buildActivity(Activity.class).create().start().resume().get();
    }

    private static ViewGroup decor(Activity a) {
        return (ViewGroup) a.getWindow().getDecorView();
    }

    /** How many overlay views are on screen: TextViews carrying the overlay's text, anywhere in the window. */
    private static int overlaysOn(ViewGroup g) {
        int n = 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof TextView && PauseOverlay.TEXT.equals(((TextView) v).getText().toString())) n++;
            if (v instanceof ViewGroup) n += overlaysOn((ViewGroup) v);
        }
        return n;
    }

    /** The first overlay view on screen, or null. */
    private static TextView find(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof TextView && PauseOverlay.TEXT.equals(((TextView) v).getText().toString())) return (TextView) v;
            if (v instanceof ViewGroup) {
                TextView t = find((ViewGroup) v);
                if (t != null) return t;
            }
        }
        return null;
    }

    @Test
    public void asking_to_pause_while_catching_up_shows_the_overlay() {
        Activity a = activity();
        PauseOverlay o = new PauseOverlay(a, true);
        o.onStatus(ASKED_ON);
        assertTrue(o.isShowing());
        assertEquals(1, overlaysOn(decor(a)));
    }

    /** Device report on issue #1, item 6: the relay's pause reaches the launcher at once (APPLIED_ON) while the engine still replays,
     *  so the overlay must stay up until the host resumes. */
    @Test
    public void the_relay_pause_reaching_this_phone_keeps_the_overlay_up() {
        Activity a = activity();
        PauseOverlay o = new PauseOverlay(a, true);
        o.onStatus(ASKED_ON);
        o.onStatus(APPLIED_ON);
        assertTrue(o.isShowing());
        assertEquals(1, overlaysOn(decor(a)));
    }

    @Test
    public void the_overlay_stays_up_until_the_host_resumes() {
        Activity a = activity();
        PauseOverlay o = new PauseOverlay(a, true);
        o.onStatus(ASKED_ON);
        o.onStatus(APPLIED_ON);
        o.onStatus(ASKED_OFF);
        assertFalse(o.isShowing());
        assertEquals(0, overlaysOn(decor(a)));
    }

    @Test
    public void the_relay_resuming_the_game_takes_the_overlay_down() {
        Activity a = activity();
        PauseOverlay o = new PauseOverlay(a, true);
        o.onStatus(ASKED_ON);
        o.onStatus(APPLIED_ON);
        o.onStatus(APPLIED_OFF);
        assertFalse(o.isShowing());
        assertEquals(0, overlaysOn(decor(a)));
    }

    @Test
    public void resuming_takes_the_overlay_down() {
        Activity a = activity();
        PauseOverlay o = new PauseOverlay(a, true);
        o.onStatus(ASKED_ON);
        o.onStatus(ASKED_OFF);
        assertFalse(o.isShowing());
        assertEquals(0, overlaysOn(decor(a)));
    }

    @Test
    public void a_second_ask_does_not_stack_a_second_overlay() {
        Activity a = activity();
        PauseOverlay o = new PauseOverlay(a, true);
        o.onStatus(ASKED_ON);
        o.onStatus(ASKED_ON);
        assertEquals(1, overlaysOn(decor(a)));
        o.onStatus(ASKED_OFF);
        assertEquals(0, overlaysOn(decor(a)));
    }


    @Test
    public void a_phone_that_did_not_rejoin_shows_nothing() {
        Activity a = activity();
        PauseOverlay o = new PauseOverlay(a, false);
        o.onStatus(ASKED_ON);
        assertFalse(o.isShowing());
        assertEquals(0, overlaysOn(decor(a)));
    }

    /** Owner's decision after RESULT 31 item 1: in the middle the box half-hid the engine's own "Paused" heading on the G6, so it sits at
     *  the top of the screen, centred across it. */
    @Test
    public void the_overlay_sits_at_the_top_centre() {
        Activity a = activity();
        new PauseOverlay(a, true).onStatus(ASKED_ON);
        TextView t = find(decor(a));
        assertTrue(t != null);
        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) t.getLayoutParams();
        assertEquals(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL, lp.gravity);
        assertTrue("a margin from the top edge (" + lp.topMargin + " px)", lp.topMargin > 0);
    }

    @Test
    public void the_overlay_takes_no_touches() {
        Activity a = activity();
        new PauseOverlay(a, true).onStatus(ASKED_ON);
        TextView t = find(decor(a));
        assertTrue(t != null);
        assertFalse(t.isClickable());
        assertFalse(t.isFocusable());
        MotionEvent down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 10f, 0);
        assertFalse(t.dispatchTouchEvent(down));
        down.recycle();
    }

    @Test
    public void rejoined_reads_the_flag_the_game_files_carry() throws Exception {
        File dir = files.newFolder();
        assertFalse(PauseOverlay.rejoined(dir));                       // no net.cfg
        Files.write(new File(dir, "net.cfg").toPath(), "host=h\nport=1\nrole=host\n".getBytes(StandardCharsets.UTF_8));
        assertFalse(PauseOverlay.rejoined(dir));
        Files.write(new File(dir, "net.cfg").toPath(), "host=h\nport=1\nrole=host\nrejoin=1\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(PauseOverlay.rejoined(dir));
    }
}
