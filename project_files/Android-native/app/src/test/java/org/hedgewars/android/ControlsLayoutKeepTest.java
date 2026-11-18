package org.hedgewars.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;

/**
 * The controls layout (issue #21) must survive Quit and a new game (the saved layout is what the next game is started with), and
 * while a custom layout is saved the Swap Fire and Jump option is greyed out with a note; Reset to default and Save makes it
 * available again. The game runs in its own process, so the test writes the main menu's copy of the options back where the
 * real main process would (see Store, the layout file).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = { 34 }, qualifiers = TestSupport.QUALIFIERS)
public class ControlsLayoutKeepTest {
    /** The places the engine reports at the default layout, as the editor receives them (GameActivity.touchLayoutReport). */
    private static final String[] ENGINE_PLACES = {
        "fire:0.900,0.800,1.000,0.100,0.200", "jump:0.700,0.800,1.000,0.120,0.230", "highjump:0.560,0.800,1.000,0.120,0.230",
        "left:0.100,0.800,1.000,0.120,0.230", "right:0.200,0.800,1.000,0.120,0.230", "aim:0.300,0.500,1.000,0.100,0.200",
        "weapons:0.950,0.150,1.000,0.080,0.120", "pause:0.500,0.040,1.000,0.060,0.100", "timer:0.150,0.600,1.000,0.080,0.120",
        "bounce:0.250,0.600,1.000,0.080,0.120" };
    private static final String SWAP = "Swap Fire and Jump";
    private static final String NOTE = "Not available while a custom controls layout is saved. Reset the layout to use it.";

    @Before
    public void freshProcess() throws Exception {
        TestSupport.resetStatics();
    }

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    private static void writeRole(String role) throws Exception {
        java.nio.file.Files.write(new java.io.File(app().getFilesDir(), "net.cfg").toPath(),
                ("role=" + role + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static GameActivity gameActivity() {
        org.robolectric.android.controller.ActivityController<GameActivity> c = Robolectric.buildActivity(GameActivity.class);
        try {
            return c.create().get();
        } catch (Throwable e) {
            return c.get();
        }
    }

    private static void touch(View v, int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(1L, 1L, action, x, y, 0);
        v.dispatchTouchEvent(e);
        e.recycle();
    }

    private static ControlsLayoutView findEditor(View v) {
        if (v instanceof ControlsLayoutView) return (ControlsLayoutView) v;
        if (v instanceof android.view.ViewGroup) {
            for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) {
                ControlsLayoutView r = findEditor(((android.view.ViewGroup) v).getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    /** The first view of kind k whose text is the label, or null. */
    private static <T extends TextView> T find(View v, Class<T> k, String label) {
        if (k.isInstance(v) && label.contentEquals(((TextView) v).getText())) return k.cast(v);
        if (v instanceof android.view.ViewGroup) {
            for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) {
                T r = find(((android.view.ViewGroup) v).getChildAt(i), k, label);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** Opens the controls editor from the game's pause Options, with the engine's places reported. */
    private static ControlsLayoutView openEditor(GameActivity g) throws Exception {
        g.pauseMenuAction(GameActivity.PAUSE_OPTIONS);
        TestSupport.settle(100);
        android.app.AlertDialog options = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        find(options.getWindow().getDecorView(), Button.class, "Controls layout").performClick();
        TestSupport.settle(100);
        ControlsLayoutView editor = findEditor(g.getWindow().getDecorView());
        assertNotNull("the editor opens over the game", editor);
        for (String entry : ENGINE_PLACES) g.touchLayoutReport(entry);
        TestSupport.settle(100);
        editor.layout(0, 0, 1280, 720);
        return editor;
    }

    /** Saves a layout: a drag of the fire control, or (reset) Reset to default after the drag, then Save. */
    private static void saveLayout(GameActivity g, boolean reset) throws Exception {
        ControlsLayoutView editor = openEditor(g);
        float cx = 0.9f * 1280, cy = 0.8f * 720;
        touch(editor, MotionEvent.ACTION_DOWN, cx, cy);
        touch(editor, MotionEvent.ACTION_MOVE, cx - 200, cy - 100);
        touch(editor, MotionEvent.ACTION_UP, cx - 200, cy - 100);
        if (reset) find(editor, Button.class, "Reset to default").performClick();
        find(editor, Button.class, "Save").performClick();
        TestSupport.settle(100);
        assertEquals("the editor closes on Save", null, findEditor(g.getWindow().getDecorView()));
    }

    /** The options screen as the main menu opens it, on the Display tab (the Swap option is there). */
    private static OptionsActivity optionsScreen() {
        Intent in = new Intent(app(), OptionsActivity.class).putExtra(OptionsActivity.EXTRA_TAB, 1);
        return Robolectric.buildActivity(OptionsActivity.class, in).setup().get();
    }

    /** The game's master volume slider in the pause Options dialog, set to the value (the dialog saves it when it closes). */
    private static void setGameMasterVolume(GameActivity g, int value) throws Exception {
        g.pauseMenuAction(GameActivity.PAUSE_OPTIONS);
        TestSupport.settle(100);
        android.app.AlertDialog options = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        android.widget.SeekBar bar = findSeekBarAfter(options.getWindow().getDecorView(), "Master volume");
        assertNotNull("the game's Options has the master volume", bar);
        // a user's change: the bar's listener, called as a touch would (a programmatic setProgress is not a change)
        bar.setProgress(value - 0);
        try {
            java.lang.reflect.Field f = android.widget.SeekBar.class.getDeclaredField("mOnSeekBarChangeListener");
            f.setAccessible(true);
            android.widget.SeekBar.OnSeekBarChangeListener l = (android.widget.SeekBar.OnSeekBarChangeListener) f.get(bar);
            l.onProgressChanged(bar, value, true);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the seek bar's listener is not reachable", e);
        }
        options.dismiss();
        TestSupport.settle(100);
    }

    /** The seek bar of the row that follows the label (sliders are a label row, then the bar). */
    private static android.widget.SeekBar findSeekBarAfter(View v, String label) {
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            boolean found = false;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (found && c instanceof android.widget.SeekBar) return (android.widget.SeekBar) c;
                if (containsText(c, label)) found = true;
                android.widget.SeekBar r = findSeekBarAfter(c, label);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static boolean containsText(View v, String label) {
        if (v instanceof TextView && label.contentEquals(((TextView) v).getText())) return true;
        if (v instanceof android.view.ViewGroup) {
            for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) {
                if (containsText(((android.view.ViewGroup) v).getChildAt(i), label)) return true;
            }
        }
        return false;
    }

    @Test
    public void gameLayoutAndVolumeSurviveTheMainProcessSavingItsModel() throws Exception {
        java.io.File net = new java.io.File(app().getFilesDir(), "net.cfg");
        try {
            writeRole("host");
            TestSupport.installData(app());
            Store.saveOptions(app(), new Options());
            GameActivity g = gameActivity();
            saveLayout(g, false);
            setGameMasterVolume(g, 37);
            String saved = Store.loadOptions(app()).touchLayout;
            assertFalse("the layout is saved", saved.isEmpty());
            assertEquals("the volume change made in the game is saved", 37, Store.loadOptions(app()).master);
            assertTrue("the options are kept in their own file", new java.io.File(app().getFilesDir(), "options.txt").exists());

            // The main process: its screens write their own state when they leave (the setup and the tab), and an options
            // screen that was not changed writes nothing
            ActivityController<MainActivity> main = Robolectric.buildActivity(MainActivity.class);
            main.create().start().postCreate(null).resume().pause().stop();
            OptionsActivity opts = Robolectric.buildActivity(OptionsActivity.class).create().start().resume().pause().stop().get();
            assertNotNull(opts);

            Options after = Store.loadOptions(app());
            assertEquals("the layout survives the main process", saved, after.touchLayout);
            assertEquals("the volume change survives the main process", 37, after.master);

            String[] args = gameActivity().engineOptions();
            java.util.List<String> list = Arrays.asList(args);
            assertTrue("the new game is launched with --touch-layout", list.contains("--touch-layout"));
            assertEquals("the new game gets the saved layout", saved, args[list.indexOf("--touch-layout") + 1]);
            System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": game layout and volume kept through the main process ok");
        } finally {
            net.delete();
        }
    }

    @Test
    public void swapFireAndJumpIsGreyedOutWhileALayoutIsSaved_availableAfterReset() throws Exception {
        java.io.File net = new java.io.File(app().getFilesDir(), "net.cfg");
        try {
            writeRole("host");
            Store.saveOptions(app(), new Options());
            GameActivity g = gameActivity();
            saveLayout(g, false);

            OptionsActivity options = optionsScreen();
            Switch swap = find(options.getWindow().getDecorView(), Switch.class, SWAP);
            assertNotNull("the Swap option is on the Display tab", swap);
            assertFalse("Swap is greyed out while a custom layout is saved", swap.isEnabled());
            TextView note = find(options.getWindow().getDecorView(), TextView.class, NOTE);
            assertNotNull("the note says why", note);
            assertEquals("the note is shown", View.VISIBLE, note.getVisibility());

            // Reset to default in the editor, and Save: the layout is cleared
            saveLayout(gameActivity(), true);
            assertEquals("Reset to default and Save clears the layout", "", Store.loadOptions(app()).touchLayout);

            OptionsActivity again = optionsScreen();
            Switch swapAgain = find(again.getWindow().getDecorView(), Switch.class, SWAP);
            assertNotNull(swapAgain);
            assertTrue("Swap is available again after the reset", swapAgain.isEnabled());
            TextView noteAgain = find(again.getWindow().getDecorView(), TextView.class, NOTE);
            assertTrue("the note is hidden after the reset",
                    noteAgain == null || noteAgain.getVisibility() != View.VISIBLE);
            System.out.println("SDK " + android.os.Build.VERSION.SDK_INT + ": Swap greyed out with a layout, available after reset ok");
        } finally {
            net.delete();
        }
    }
}
