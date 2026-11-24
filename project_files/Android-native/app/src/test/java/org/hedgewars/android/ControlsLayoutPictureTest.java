package org.hedgewars.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Switch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;

import static org.robolectric.Shadows.shadowOf;

import org.robolectric.RobolectricTestRunner;

/**
 * The controls layout editor opened from the Options screen, over a picture of a game with no game running (issue #26).
 * The Options screen has a "Controls layout" button on its Display tab, at the narrow and the upright sizes. Tapping it starts
 * ControlsLayoutActivity with the options as the screen holds them (an unsaved change too). The editor shows the default places
 * from TouchDefaults over the picture; Save returns the spec, Reset to default then Save returns "", Cancel and Back return
 * nothing. The Options screen takes the result, and its Swap option is greyed out while a layout is saved.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = { 34 }, qualifiers = TestSupport.QUALIFIERS)
public class ControlsLayoutPictureTest {
    private static final String TWO_JUMP = "Two jump buttons: Jump (forward) and High jump";
    private static final String SWAP = "Swap Fire and Jump";
    private static final String NOTE = "Not available while a custom controls layout is saved. Reset the layout to use it.";
    private static final String CONTROLS = "Controls layout";
    /** A layout the user saved earlier: the fire control moved to the centre. */
    private static final String SAVED = TouchLayout.sanitize("fire:0.500,0.500,1.000");

    @Before
    public void freshProcess() throws Exception {
        TestSupport.resetStatics();
    }

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    /** The Options screen on the Display tab, as the main menu opens it. */
    private static ActivityController<OptionsActivity> optionsController() throws Exception {
        Intent in = new Intent(app(), OptionsActivity.class).putExtra(OptionsActivity.EXTRA_TAB, 1);
        return Robolectric.buildActivity(OptionsActivity.class, in).setup();
    }

    /** The editor, as Options starts it: with the given options, laid out at the full screen with the camera cut-out. */
    private static ActivityController<ControlsLayoutActivity> editorController(Options o) throws Exception {
        Intent in = new Intent(app(), ControlsLayoutActivity.class).putExtra(ControlsLayoutActivity.EXTRA_OPTIONS, o.toText());
        ActivityController<ControlsLayoutActivity> c = Robolectric.buildActivity(ControlsLayoutActivity.class, in).setup();
        TestSupport.layout(c.get());
        TestSupport.settle(200);
        TestSupport.layout(c.get());
        return c;
    }

    /**
     * RESULT 34 item 8b (FP4): with a camera cut-out on the left, the editor left an 80 px black strip there, so the left-hand boxes
     * sat about 70 px right of the game's buttons (the right-hand ones matched: the view was inset by the cut-out while the places are
     * fractions of the whole screen). The game's window is laid out under the bars and the cut-out: SDLActivity sets
     * SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN, LAYOUT_HIDE_NAVIGATION and LAYOUT_STABLE with the short-edges cut-out mode. The editor's window
     * must be laid out the same way. Robolectric does not inset a window for a cut-out, so this pins the window settings themselves.
     */
    @Test
    public void theEditorWindowIsLaidOutUnderTheCutOutAndBarsLikeTheGame() throws Exception {
        ActivityController<ControlsLayoutActivity> c = editorController(new Options());
        View decor = c.get().getWindow().getDecorView();
        int want = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        assertEquals("laid out under the status bar, the navigation bar and the cut-out", want, decor.getSystemUiVisibility() & want);
        assertEquals(android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                c.get().getWindow().getAttributes().layoutInDisplayCutoutMode);
    }

    private static void touch(View v, int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(1L, 1L, action, x, y, 0);
        v.dispatchTouchEvent(e);
        e.recycle();
    }

    private static ControlsLayoutView findEditor(View v) {
        if (v instanceof ControlsLayoutView) return (ControlsLayoutView) v;
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                ControlsLayoutView r = findEditor(((ViewGroup) v).getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private static ImageView firstImage(View v) {
        if (v instanceof ImageView) return (ImageView) v;
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                ImageView r = firstImage(((ViewGroup) v).getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    /** The first view of kind k whose text is the label, or null. */
    private static <T extends android.widget.TextView> T find(View v, Class<T> k, String label) {
        if (k.isInstance(v) && label.contentEquals(((android.widget.TextView) v).getText())) return k.cast(v);
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                T r = find(((ViewGroup) v).getChildAt(i), k, label);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static void collect(View v, List<View> out) {
        out.add(v);
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) collect(((ViewGroup) v).getChildAt(i), out);
        }
    }

    /** Every visible button and switch lies inside the window's width: nothing is pushed off to the side. */
    private static void buttonsAndSwitchesInside(View decor, int width) {
        List<View> all = new ArrayList<>();
        collect(decor, all);
        int[] loc = new int[2];
        for (View v : all) {
            if (!(v instanceof Button || v instanceof Switch) || !TestSupport.shown(v) || v.getWidth() == 0) continue;
            v.getLocationInWindow(loc);
            assertTrue("'" + ((android.widget.TextView) v).getText() + "' starts inside the window: x " + loc[0],
                    loc[0] >= 0);
            assertTrue("'" + ((android.widget.TextView) v).getText() + "' ends inside the window: x " + (loc[0] + v.getWidth()) + " of " + width,
                    loc[0] + v.getWidth() <= width);
        }
    }

    /** The distance from the top of v to the top of its ancestor, through the parents in between. */
    private static int offsetIn(View v, View ancestor) {
        int y = 0;
        for (View x = v; x != null && x != ancestor; x = x.getParent() instanceof View ? (View) x.getParent() : null) y += x.getTop();
        return y;
    }

    /** The button can be reached by scrolling its scroll view: it is shown in the visible part once the view is scrolled to it. */
    private static void reachableByScrolling(Button b) {
        ScrollView sv = null;
        for (View p = (View) b.getParent(); p != null && sv == null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p instanceof ScrollView) sv = (ScrollView) p;
        }
        assertNotNull("the Options rows are in a scroll view", sv);
        int top = offsetIn(b, sv);
        sv.scrollTo(0, Math.max(0, top + b.getHeight() - sv.getHeight()));
        int visibleTop = top - sv.getScrollY();
        assertTrue("the Controls layout button is in the visible part after scrolling: y " + visibleTop + " of " + sv.getHeight(),
                visibleTop >= 0 && visibleTop + b.getHeight() <= sv.getHeight());
    }

    /** The places the editor should show: TouchDefaults for the screen, as the activity computes them. */
    private static Map<String, TouchLayout.Place> expected(Activity a, Options o) {
        DisplayMetrics dm = new DisplayMetrics();
        a.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int w = Math.max(dm.widthPixels, dm.heightPixels), h = Math.min(dm.widthPixels, dm.heightPixels);
        return TouchDefaults.compute(TouchDefaults.fromDisplay(w, h, DisplayCalc.measure(a, o), Options.JUMP_INSET_PCT,
                o.twoJump, o.swapFireJump));
    }

    /** Drags the fire control by a fixed offset, as a finger would. */
    private static void dragFire(ControlsLayoutView editor) {
        RectF fire = editor.outlineOf("fire");
        float x = fire.centerX(), y = fire.centerY();
        touch(editor, MotionEvent.ACTION_DOWN, x, y);
        touch(editor, MotionEvent.ACTION_MOVE, x - 200, y - 100);
        touch(editor, MotionEvent.ACTION_UP, x - 200, y - 100);
    }

    private static void saveOptions(String layout, boolean twoJump) {
        Options o = new Options();
        o.touchLayout = layout;
        o.twoJump = twoJump;
        Store.saveOptions(app(), o);
    }

    // The Options button ----------------------------------------------------------------------

    @Test
    @Config(sdk = { 34 }, qualifiers = "w640dp-h360dp-land-xxhdpi")
    public void optionsNarrow_controlsLayoutButtonIsShownAndReachable() throws Exception {
        ActivityController<OptionsActivity> c = optionsController();
        OptionsActivity a = c.get();
        View decor = a.getWindow().getDecorView();
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, w, h);
        TestSupport.settle(100);
        Button controls = find(decor, Button.class, CONTROLS);
        assertNotNull("the Display tab has a Controls layout button", controls);
        assertTrue("the Controls layout button is shown", TestSupport.shown(controls));
        reachableByScrolling(controls);
        buttonsAndSwitchesInside(decor, w);
        assertNotNull("the Swap option is still there beside it", find(decor, Switch.class, SWAP));
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": narrow Options has Controls layout ok");
    }

    @Test
    @Config(sdk = { 34 }, qualifiers = TestSupport.PORTRAIT_QUALIFIERS)
    public void optionsUpright_controlsLayoutButtonIsShownAndNothingIsPushedOff() throws Exception {
        ActivityController<OptionsActivity> c = optionsController();
        OptionsActivity a = c.get();
        TestSupport.layoutUpright(a);
        TestSupport.settle(100);
        TestSupport.layoutUpright(a);
        View decor = a.getWindow().getDecorView();
        Button controls = find(decor, Button.class, CONTROLS);
        assertNotNull("the upright Display tab has a Controls layout button", controls);
        assertTrue("the Controls layout button is shown", TestSupport.shown(controls));
        buttonsAndSwitchesInside(decor, TestSupport.PW);
        assertNotNull("the two-jump switch is still there", find(decor, Switch.class, TWO_JUMP));
        assertNotNull("the Swap switch is still there", find(decor, Switch.class, SWAP));
        System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": upright Options has Controls layout ok");
    }

    @Test
    public void controlsLayoutButton_startsTheEditorWithTheUnsavedTwoJumpChange() throws Exception {
        saveOptions("", true);
        ActivityController<OptionsActivity> c = optionsController();
        OptionsActivity a = c.get();
        Switch two = find(a.getWindow().getDecorView(), Switch.class, TWO_JUMP);
        assertNotNull(two);
        two.setChecked(false);                       // not saved: the screen holds it
        find(a.getWindow().getDecorView(), Button.class, CONTROLS).performClick();

        ShadowActivity.IntentForResult started = shadowOf(a).getNextStartedActivityForResult();
        assertNotNull("the button starts a screen for a result", started);
        assertEquals(OptionsActivity.REQUEST_CONTROLS, started.requestCode);
        assertEquals(ControlsLayoutActivity.class.getName(), started.intent.getComponent().getClassName());
        Options sent = Options.fromText(started.intent.getStringExtra(ControlsLayoutActivity.EXTRA_OPTIONS));
        assertFalse("the editor gets the unsaved two-jump change", sent.twoJump);
        assertTrue("the change is not written yet", Store.loadOptions(app()).twoJump);
        c.pause().stop().destroy();
    }

    // The editor ----------------------------------------------------------------------------

    @Test
    public void editorShowsOneBoxPerTouchDefault_overThePicture() throws Exception {
        Options o = new Options();                   // two jump buttons, the default
        ActivityController<ControlsLayoutActivity> c = editorController(o);
        ControlsLayoutActivity a = c.get();
        View decor = a.getWindow().getDecorView();

        ImageView picture = firstImage(decor);
        assertNotNull("the picture is on the screen", picture);
        assertNotNull("the picture is decoded", picture.getDrawable());
        assertEquals("the picture fills the screen, cropped to fit", ImageView.ScaleType.CENTER_CROP, picture.getScaleType());

        ControlsLayoutView editor = findEditor(decor);
        assertNotNull("the editor is over the picture", editor);
        assertEquals("the editor covers the screen (width)", TestSupport.W, editor.getWidth());
        assertEquals("the editor covers the screen (height)", TestSupport.H, editor.getHeight());

        Map<String, TouchLayout.Place> want = expected(a, o);
        int boxes = 0;
        for (String name : TouchLayout.NAMES) {
            RectF r = editor.outlineOf(name);
            assertNotNull(name + " has a box", r);
            TouchLayout.Place p = want.get(name);
            assertNotNull(name + " has a default place", p);
            assertEquals(name + " centre x", p.x * editor.getWidth(), r.centerX(), 1.0);
            assertEquals(name + " centre y", p.y * editor.getHeight(), r.centerY(), 1.0);
            boxes++;
        }
        assertEquals("one box per control", TouchLayout.NAMES.length, boxes);
        c.pause().stop().destroy();
    }

    @Test
    public void editorDropsHighJump_withOneJumpButton() throws Exception {
        Options o = new Options();
        o.twoJump = false;
        ActivityController<ControlsLayoutActivity> c = editorController(o);
        ControlsLayoutView editor = findEditor(c.get().getWindow().getDecorView());
        assertNotNull(editor);
        assertNull("no High jump box with one jump button", editor.outlineOf("highjump"));
        assertNotNull("the Jump box is there", editor.outlineOf("jump"));
        c.pause().stop().destroy();
    }

    // Save, Reset, Cancel and Back ------------------------------------------------------------

    @Test
    public void dragThenSave_returnsTheSpec_optionsHasIt_swapIsGreyed_andItIsSavedWhenOptionsCloses() throws Exception {
        saveOptions("", true);
        ActivityController<OptionsActivity> oc = optionsController();
        OptionsActivity options = oc.get();
        ShadowActivity.IntentForResult started = openEditorFrom(options);

        ActivityController<ControlsLayoutActivity> ec = editorFor(started);
        ControlsLayoutActivity editorActivity = ec.get();
        ControlsLayoutView editor = findEditor(editorActivity.getWindow().getDecorView());
        dragFire(editor);
        find(editorActivity.getWindow().getDecorView(), Button.class, "Save").performClick();

        assertTrue("Save closes the editor", editorActivity.isFinishing());
        assertEquals("Save reports a result", Activity.RESULT_OK, shadowOf(editorActivity).getResultCode());
        String spec = shadowOf(editorActivity).getResultIntent().getStringExtra(ControlsLayoutActivity.RESULT_TOUCH_LAYOUT);
        assertNotNull("the spec is returned", spec);
        assertTrue("the spec has the moved fire", TouchLayout.parse(spec).containsKey("fire"));
        assertEquals("the spec is sanitised", TouchLayout.sanitize(spec), spec);

        shadowOf(options).receiveResult(started.intent, Activity.RESULT_OK, shadowOf(editorActivity).getResultIntent());
        Switch swap = find(options.getWindow().getDecorView(), Switch.class, SWAP);
        assertNotNull(swap);
        assertFalse("Swap is greyed out once a layout is saved", swap.isEnabled());
        assertEquals("the note says why", View.VISIBLE, find(options.getWindow().getDecorView(), android.widget.TextView.class, NOTE).getVisibility());

        oc.pause().stop().destroy();                 // Options saves its model when it closes
        assertEquals("the layout is kept", spec, Store.loadOptions(app()).touchLayout);
        ec.pause().stop().destroy();
    }

    @Test
    public void resetToDefaultThenSave_returnsEmpty_andSwapIsAvailableAgain() throws Exception {
        saveOptions(SAVED, true);
        ActivityController<OptionsActivity> oc = optionsController();
        OptionsActivity options = oc.get();
        Switch swap = find(options.getWindow().getDecorView(), Switch.class, SWAP);
        assertFalse("Swap is greyed out while the layout is saved", swap.isEnabled());

        ShadowActivity.IntentForResult started = openEditorFrom(options);
        ActivityController<ControlsLayoutActivity> ec = editorFor(started);
        ControlsLayoutActivity editorActivity = ec.get();
        ControlsLayoutView editor = findEditor(editorActivity.getWindow().getDecorView());
        assertEquals("the saved fire is where the editor starts", 0.5, editor.outlineOf("fire").centerX() / editor.getWidth(), 0.01);
        find(editorActivity.getWindow().getDecorView(), Button.class, "Reset to default").performClick();
        find(editorActivity.getWindow().getDecorView(), Button.class, "Save").performClick();

        assertEquals(Activity.RESULT_OK, shadowOf(editorActivity).getResultCode());
        assertEquals("Reset to default and Save returns an empty layout", "",
                shadowOf(editorActivity).getResultIntent().getStringExtra(ControlsLayoutActivity.RESULT_TOUCH_LAYOUT));

        shadowOf(options).receiveResult(started.intent, Activity.RESULT_OK, shadowOf(editorActivity).getResultIntent());
        assertTrue("Swap is available again", find(options.getWindow().getDecorView(), Switch.class, SWAP).isEnabled());
        android.widget.TextView note = find(options.getWindow().getDecorView(), android.widget.TextView.class, NOTE);
        assertTrue("the note is hidden", note == null || note.getVisibility() != View.VISIBLE);

        oc.pause().stop().destroy();
        assertEquals("the saved layout is cleared", "", Store.loadOptions(app()).touchLayout);
        ec.pause().stop().destroy();
    }

    @Test
    public void cancelReturnsNothing_andChangesNothing() throws Exception {
        saveOptions("", true);
        ActivityController<OptionsActivity> oc = optionsController();
        OptionsActivity options = oc.get();
        ShadowActivity.IntentForResult started = openEditorFrom(options);
        ActivityController<ControlsLayoutActivity> ec = editorFor(started);
        ControlsLayoutActivity editorActivity = ec.get();
        dragFire(findEditor(editorActivity.getWindow().getDecorView()));
        find(editorActivity.getWindow().getDecorView(), Button.class, "Cancel").performClick();

        assertTrue("Cancel closes the editor", editorActivity.isFinishing());
        assertEquals("Cancel gives RESULT_CANCELED", Activity.RESULT_CANCELED, shadowOf(editorActivity).getResultCode());
        assertNull("Cancel returns no layout", shadowOf(editorActivity).getResultIntent());

        shadowOf(options).receiveResult(started.intent, Activity.RESULT_CANCELED, null);
        assertTrue("Swap is still available", find(options.getWindow().getDecorView(), Switch.class, SWAP).isEnabled());
        oc.pause().stop().destroy();
        assertEquals("nothing is saved", "", Store.loadOptions(app()).touchLayout);
        ec.pause().stop().destroy();
    }

    @Test
    public void backReturnsNothing_andChangesNothing() throws Exception {
        saveOptions(SAVED, true);
        ActivityController<OptionsActivity> oc = optionsController();
        OptionsActivity options = oc.get();
        ShadowActivity.IntentForResult started = openEditorFrom(options);
        ActivityController<ControlsLayoutActivity> ec = editorFor(started);
        ControlsLayoutActivity editorActivity = ec.get();
        dragFire(findEditor(editorActivity.getWindow().getDecorView()));
        editorActivity.onBackPressed();

        assertTrue("Back closes the editor", editorActivity.isFinishing());
        assertEquals("Back gives RESULT_CANCELED", Activity.RESULT_CANCELED, shadowOf(editorActivity).getResultCode());
        assertNull("Back returns no layout", shadowOf(editorActivity).getResultIntent());

        shadowOf(options).receiveResult(started.intent, Activity.RESULT_CANCELED, null);
        oc.pause().stop().destroy();
        assertEquals("the saved layout is unchanged", SAVED, Store.loadOptions(app()).touchLayout);
        ec.pause().stop().destroy();
    }

    /** Taps Controls layout on the Options screen and returns the screen-for-result request it started. */
    private static ShadowActivity.IntentForResult openEditorFrom(OptionsActivity options) {
        find(options.getWindow().getDecorView(), Button.class, CONTROLS).performClick();
        ShadowActivity.IntentForResult started = shadowOf(options).getNextStartedActivityForResult();
        assertNotNull("Controls layout starts the editor", started);
        return started;
    }

    /** The editor as the started request describes it, laid out and with its defaults applied. */
    private static ActivityController<ControlsLayoutActivity> editorFor(ShadowActivity.IntentForResult started) throws Exception {
        ActivityController<ControlsLayoutActivity> c = Robolectric.buildActivity(ControlsLayoutActivity.class, started.intent).setup();
        TestSupport.layout(c.get());
        TestSupport.settle(200);
        TestSupport.layout(c.get());
        return c;
    }
}
