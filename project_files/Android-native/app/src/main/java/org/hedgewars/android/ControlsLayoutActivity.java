package org.hedgewars.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.Map;

/**
 * The controls layout editor on its own, opened from the Options screen (issue #26): no game is running, so the default places
 * come from TouchDefaults, computed for this screen the way the engine computes them in the game. The editor is laid over a
 * picture of a game (drawable-nodpi/controls_layout_bg.png, a game frame with the controls not drawn), scaled to fill the
 * screen. The window is full screen and landscape like the game's (GameTheme, sensorLandscape in the manifest), so a fraction
 * of the screen means the same here as in the game.
 *
 * Save returns the sanitised spec as RESULT_TOUCH_LAYOUT; Cancel and Back return RESULT_CANCELED. Nothing is written to disk
 * here: the Options screen keeps the result and saves its model when it closes.
 */
public class ControlsLayoutActivity extends Activity {

    /** Intent extra: the options as the Options screen holds them (Options.toText), including changes not yet saved. */
    static final String EXTRA_OPTIONS = "options";
    /** Result extra: the saved layout spec ("" for the engine's default layout). */
    static final String RESULT_TOUCH_LAYOUT = "touchLayout";

    private Options options;
    private ControlsLayoutView editor;
    private boolean defaultsSet;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String text = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_OPTIONS);
        options = text != null ? Options.fromText(text) : Store.loadOptions(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        // drawable-nodpi/controls_layout_bg.png, 2340x1080, 256 colours: a frame of an AI game with no game running on the phone.
        // Captured with HP_TEST_SCENARIO=ai HP_TEST_SIZE=2340x1080 HP_TEST_SHOTS=14,20 scripts/host-test.sh at the phone's
        // arguments (frame at 20 s). The two controls still drawn in that frame, the pause button and the weapons chest, were
        // painted out by hand afterwards (scenery copied over them).
        ImageView picture = new ImageView(this);
        picture.setImageResource(R.drawable.controls_layout_bg);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        editor = new ControlsLayoutView(this, options.twoJump, new ControlsLayoutView.Listener() {
            @Override public void onSave(String spec) {
                Intent r = new Intent().putExtra(RESULT_TOUCH_LAYOUT, TouchLayout.sanitize(spec));
                setResult(RESULT_OK, r);
                finish();
            }
            @Override public void onCancel() { cancel(); }
        });
        root.addView(editor, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        layOutLikeTheGame();
        // The cut-out and the insets are known only once the window is laid out (DisplayCalc reads them), so the defaults wait for that.
        root.post(this::applyDefaults);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            Ui.hideBars(this);
            layOutLikeTheGame();
        }
    }

    /**
     * The window is laid out under the bars and the camera cut-out, as SDLActivity lays out the game's window (the LAYOUT_* flags
     * with the short-edges cut-out mode). Without them a cut-out on a short edge insets the content: on the FP4 the editor left an
     * 80 px strip on the left and its left-hand boxes sat about 70 px right of the game's buttons (RESULT 34 item 8b), because the
     * places are fractions of the whole screen.
     */
    @SuppressWarnings("deprecation")
    private void layOutLikeTheGame() {
        android.view.Window w = getWindow();
        View decor = w.getDecorView();
        decor.setSystemUiVisibility(decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (android.os.Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
    }

    /** The places the engine would report at its default layout for this screen, then the saved layout on top of them. */
    private void applyDefaults() {
        if (defaultsSet) return;
        defaultsSet = true;
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int w = Math.max(dm.widthPixels, dm.heightPixels), h = Math.min(dm.widthPixels, dm.heightPixels);
        Map<String, TouchLayout.Place> defaults = TouchDefaults.compute(TouchDefaults.fromDisplay(w, h,
                DisplayCalc.measure(this, options), Options.JUMP_INSET_PCT, options.twoJump, options.swapFireJump));
        editor.setDefaults(defaults, options.touchLayout);
    }

    private void cancel() {
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    public void onBackPressed() {
        cancel();
    }
}
