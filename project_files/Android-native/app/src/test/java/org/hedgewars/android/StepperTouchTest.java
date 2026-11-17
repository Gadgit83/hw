package org.hedgewars.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The +/- buttons of a number setting (the Rules tab). A tap changes the value once; a swipe that starts on a button must
 * scroll the page and leave the value alone (on the G6, a scroll that began on Water rise changed it).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = { 34, 35 })
public class StepperTouchTest {
    /** A stepper in a page that scrolls, with the setting at the top and a tall spacer below it. */
    private static final class Page {
        final ScrollView scroll;
        final Button plus;
        final int[] changes = { 0 };
        int value = 20;

        Page() {
            Context c = RuntimeEnvironment.getApplication();
            Ui ui = new Ui(c);
            LinearLayout column = new LinearLayout(c);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout row = ui.stepper("Water rise", 0, 100, 5, value, n -> Integer.toString(n), n -> {
                value = n;
                changes[0]++;
            });
            column.addView(row);
            View spacer = new View(c);
            column.addView(spacer, new LinearLayout.LayoutParams(1, 4000));
            scroll = new ScrollView(c);
            scroll.addView(column);
            scroll.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
            scroll.layout(0, 0, 1080, 1000);
            plus = findPlus(row);
        }

        private static Button findPlus(ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                View v = g.getChildAt(i);
                if (v instanceof Button && "+".equals(((Button) v).getText().toString())) return (Button) v;
            }
            throw new AssertionError("no + button");
        }

        /** The centre of the plus button in the scroll view's own coordinates. */
        float[] plusCentre() {
            int x = plus.getWidth() / 2, y = plus.getHeight() / 2;
            View v = plus;
            while (v != scroll) {
                x += v.getLeft();
                y += v.getTop();
                v = (View) v.getParent();
            }
            return new float[] { x, y };
        }

        void touch(View target, int action, float x, float y) {
            MotionEvent e = MotionEvent.obtain(0, System.currentTimeMillis(), action, x, y, 0);
            target.dispatchTouchEvent(e);
            e.recycle();
        }
    }

    /** A tap (down and up in place) steps the value once, as before. */
    @Test
    public void tapStepsOnce() {
        Page p = new Page();
        float[] c = p.plusCentre();
        p.touch(p.scroll, MotionEvent.ACTION_DOWN, c[0], c[1]);
        assertEquals("nothing changes on touch-down", 0, p.changes[0]);
        p.touch(p.scroll, MotionEvent.ACTION_UP, c[0], c[1]);
        assertEquals("a tap steps once", 1, p.changes[0]);
        assertEquals(25, p.value);
    }

    /** A vertical swipe that starts on the plus button scrolls the page and does not change the value. */
    @Test
    public void swipeFromButtonScrollsAndKeepsValue() {
        Page p = new Page();
        float[] c = p.plusCentre();
        float x = c[0], y = c[1];
        p.touch(p.scroll, MotionEvent.ACTION_DOWN, x, y);
        for (int step = 1; step <= 10; step++) p.touch(p.scroll, MotionEvent.ACTION_MOVE, x, y - step * 30);
        p.touch(p.scroll, MotionEvent.ACTION_UP, x, y - 300);
        assertTrue("the page scrolled: " + p.scroll.getScrollY(), p.scroll.getScrollY() > 0);
        assertEquals("the value is unchanged by the swipe", 0, p.changes[0]);
        assertEquals(20, p.value);
    }

    /** A press that moves past the slop without lifting is a drag, not a tap: the value stays put. */
    @Test
    public void movedPressNeverSteps() {
        Page p = new Page();
        float[] c = p.plusCentre();
        p.touch(p.scroll, MotionEvent.ACTION_DOWN, c[0], c[1]);
        p.touch(p.scroll, MotionEvent.ACTION_MOVE, c[0], c[1] - 200);
        p.touch(p.scroll, MotionEvent.ACTION_UP, c[0], c[1] - 200);
        assertEquals("a moved press never steps", 0, p.changes[0]);
    }
}
