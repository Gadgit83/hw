package org.hedgewars.android;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * "Paused, catching up..." over the game, for a host that rejoined and asks to pause while its engine still replays the game.
 * The relay pauses the game for everyone at once, but this phone's own engine gets the pause only after its replay, so the host
 * would see nothing. The overlay is shown when the host asks (the launcher's ASKED_ON) and taken down when the host resumes
 * (ASKED_OFF) or the relay resumes the game for everyone (APPLIED_OFF). It takes no touches, so the resume tap reaches the game.
 * APPLIED_ON does not take it down: the launcher reports it when the relay's pause reaches the launcher, which is at once, while the
 * engine applies the pause only after its replay (device report on issue #1, item 6).
 * The states are the HP_PAUSE_* values of native/launcher/hp.h.
 */
final class PauseOverlay {
    static final int ASKED_ON = 1, ASKED_OFF = 2, APPLIED_ON = 3, APPLIED_OFF = 4;

    static final String TEXT = "Paused, catching up…";

    private final Activity activity;
    private final boolean catchingUp;
    private View view;
    /** {@code catchingUp}: this game was sent again to a rejoined phone ({@link #rejoined}), so its engine may still be replaying. */
    PauseOverlay(Activity activity, boolean catchingUp) {
        this.activity = activity;
        this.catchingUp = catchingUp;
    }

    /** True when net.cfg (written for this game, see RelayClient.writeGameFiles) says the phone rejoined it. */
    static boolean rejoined(File filesDir) {
        File f = new File(filesDir, "net.cfg");
        if (!f.exists()) return false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String l; (l = r.readLine()) != null; ) {
                if (l.trim().equals("rejoin=1")) return true;
            }
        } catch (IOException e) {
            // an unreadable file says nothing about a rejoin
        }
        return false;
    }

    /** One pause state from the launcher (called on the UI thread). */
    void onStatus(int state) {
        switch (state) {
            case ASKED_ON:
                if (catchingUp) show();
                break;
            case ASKED_OFF:
                hide();
                break;
            case APPLIED_ON:
                break;              // the relay's pause reached this phone's launcher; the overlay stays until the game is resumed
            case APPLIED_OFF:
                hide();
                break;
            default:
                break;
        }
    }

    boolean isShowing() {
        return view != null;
    }

    private void show() {
        if (view != null) return;
        Ui ui = new Ui(activity);
        TextView t = new TextView(activity);
        t.setText(TEXT);
        t.setTextColor(Color.WHITE);
        t.setTextSize(20);
        t.setGravity(Gravity.CENTER);
        t.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xC0000000);
        bg.setCornerRadius(ui.dp(12));
        t.setBackground(bg);
        // Taken out of touch handling: the taps go to the game beneath, the resume tap included.
        t.setClickable(false);
        t.setFocusable(false);
        t.setFocusableInTouchMode(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        activity.addContentView(t, lp);
        view = t;
    }

    private void hide() {
        if (view == null) return;
        android.view.ViewParent p = view.getParent();
        if (p instanceof ViewGroup) ((ViewGroup) p).removeView(view);
        view = null;
    }
}
