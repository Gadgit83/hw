package org.hedgewars.android;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.view.ViewGroup;

import org.libsdl.app.SDLActivity;

/**
 * Hosts the native engine. SDLActivity loads libSDL2.so and libmain.so and runs SDL_main
 * (native/launcher/hp_main.c) on its own thread. The engine reads its game from
 * files/game.ipc, which MainActivity wrote before starting this activity.
 *
 * This activity runs in its own process (":game", see the manifest). The engine keeps global
 * state and may end the process with exit() when it hits a fatal error, so keeping it apart
 * means a failed game cannot take the setup screen down, and the setup screen can show why.
 */
public class GameActivity extends SDLActivity {

    @Override
    protected String[] getLibraries() {
        // SDL2_image, SDL2_mixer, SDL2_ttf, SDL2_net and the Rust library are NEEDED by libmain.so
        // and are found by the dynamic linker in the same directory.
        return new String[] { "SDL2", "main" };
    }

    @Override
    public void loadLibraries() {
        try {
            super.loadLibraries();
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            Diag.writeError(this, "Could not load the native game libraries: " + e.getMessage());
            throw e;
        }
    }

    /**
     * SDL asks for an orientation whenever the engine creates its window and, with no hint and a
     * resizable window, picks FULL_USER (every orientation the phone allows). That overrides the
     * manifest's sensorLandscape, so the game could open in portrait. Stay in landscape.
     */
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private Options options = new Options();
    private long lastRumble;

    /**
     * Called from the native launcher (hp_main.c, through JNI) just before the engine starts.
     * Returns the 15 display values of DisplayCalc.measure (scales, cut-out insets, corner radii, margin, cut-out rows),
     * with the sizes chosen in the options menu applied.
     */
    @SuppressWarnings("unused")
    public int[] engineDisplayInfo() {
        return DisplayCalc.measure(this, options);
    }

    /**
     * Called from the native launcher (hp_main.c, through JNI) just before the engine starts: the engine
     * command line options that come from the options menu (sound, quality, name tags, frame rate counter).
     */
    @SuppressWarnings("unused")
    public String[] engineOptions() {
        java.util.List<String> a = options.toEngineArgs();
        return a.toArray(new String[0]);
    }

    /**
     * Called from the engine (through hp_main.c) when something big blows up: a short buzz, if switched on.
     * Throttled, because a cluster bomb can raise several explosions within a moment.
     */
    @SuppressWarnings("unused")
    public void rumble(int ms) {
        if (!options.vibration) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastRumble < 150) return;
        lastRumble = now;
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            int d = Math.max(10, Math.min(ms, 80));
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createOneShot(d, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(d);
            }
        } catch (RuntimeException ignored) {
            // no vibration is not worth a crash
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        // The native launcher removes this marker when the engine ends cleanly.
        Diag.markGameStarted(this);
        options = Store.loadOptions(this);
        // Only the host can pause a network game, and a phone that sleeps stalls everyone's engine on its turn.
        if (new java.io.File(getFilesDir(), "net.cfg").exists()) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        super.onCreate(savedInstanceState);
    }

    /** The engine keeps global state in its statics, so end the process to start every game clean. */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private android.app.AlertDialog backDialog;
    private boolean quitting;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * The engine has no Android back button handling. In a local game the system Back key is forwarded as Escape, which
     * opens the engine's own quit question. In a network game that question is easy to miss and the pause button does
     * nothing useful, so Back asks in the app ({@link BackConfirm}); on "yes" the engine is quit the way a player does
     * it, Escape then Y, so it sends its quit message and ends cleanly. The game keeps running while the question is up
     * unless the host has paused it.
     */
    @Override
    public void onBackPressed() {
        askQuit();
    }

    /**
     * The question Back asks, also what the pause screen's Quit game asks: a local game quits the engine's own way (Escape,
     * which opens its quit question), a network game shows {@link BackConfirm}. Kept in one place so both stay the same.
     */
    private void askQuit() {
        int role = BackConfirm.role(getFilesDir());
        if (role == BackConfirm.LOCAL) {
            SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ESCAPE);
            SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ESCAPE);
            return;
        }
        if (quitting) return;
        if (backDialog != null && backDialog.isShowing()) {
            backDialog.dismiss();                    // Back again means "keep playing"
            return;
        }
        backDialog = BackConfirm.show(this, role, () -> quitEngine(false), () -> quitEngine(true));
    }

    /** The pause screen's buttons (--pause-menu): the native launcher calls this from its IPC thread with the engine's letter. */
    static final int PAUSE_QUIT = 'q', PAUSE_OPTIONS = 'o';

    /** Activity request code for the options screen opened from the game (its result carries the saved options). */
    private static final int REQUEST_OPTIONS = 1;

    /**
     * Called from the native launcher (hp_main.c, through JNI) when the player presses Quit game or Options on the pause screen.
     * Runs on the UI thread. Quit game opens the same question as Back ({@link #askQuit}), so the network rules stay in one place.
     */
    @SuppressWarnings("unused")
    public void pauseMenuAction(int action) {
        uiHandler.post(() -> {
            if (isFinishing()) return;
            if (action == PAUSE_QUIT) askQuit();
            else if (action == PAUSE_OPTIONS) showPauseOptions();
        });
    }

    private android.app.AlertDialog optionsDialog;

    /**
     * Options during a game: the sound settings, which take effect at once (the engine gets them as livevolume), and the way to the
     * display and controls options. The dialog saves what it changed when it closes. The rest of the options wait for the next game.
     */
    private void showPauseOptions() {
        if (optionsDialog != null && optionsDialog.isShowing()) return;
        Ui ui = new Ui(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(Ui.GUTTER), ui.dp(8), ui.dp(Ui.GUTTER), ui.dp(8));
        body.addView(ui.slider("Master volume", 0, 100, options.master, n -> n + "%", n -> { options.master = n; applyLiveVolume(); }));
        body.addView(ui.slider("Sound effects", 0, 100, options.effects, n -> n + "%", n -> { options.effects = n; applyLiveVolume(); }));
        body.addView(ui.slider("Music", 0, 100, options.music, n -> n + "%", n -> { options.music = n; applyLiveVolume(); }));
        body.addView(ui.toggle("Mute all sound and music", options.mute, c -> { options.mute = c; applyLiveVolume(); }));
        body.addView(ui.hint("Sound changes at once. A game that started muted has no sound until the next game."));
        body.addView(ui.button("Controls layout", false, v -> {
            if (optionsDialog != null) optionsDialog.dismiss();
            openControlsLayout();
        }), ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(Ui.TOUCH_DP)), 0, 8, 0, 0));
        optionsDialog = Ui.dialog(this)
                .setTitle("Options")
                .setView(body)
                .setPositiveButton("Done", null)
                .setOnDismissListener(d -> Store.saveOptions(this, options))
                .create();
        optionsDialog.show();
    }

    /**
     * Hook for the controls editor (issue #21), which will replace this: until then the app's Options open at the display and
     * controls tab. Its saved options come back in the result ({@link #onActivityResult}).
     */
    private void openControlsLayout() {
        startActivityForResult(new Intent(this, OptionsActivity.class)
                .putExtra(OptionsActivity.EXTRA_TAB, 1)
                .putExtra(OptionsActivity.EXTRA_OPTIONS, options.toText()), REQUEST_OPTIONS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OPTIONS && resultCode == RESULT_OK && data != null) {
            String text = data.getStringExtra(OptionsActivity.RESULT_OPTIONS);
            if (text != null) {
                options = Options.fromText(text);
                applyLiveVolume();
            }
        }
    }

    /**
     * The sound of the game that is running follows the options at once: the level as the engine's master volume (0 to 128),
     * the effects and music shares, and mute. The launcher passes it to the engine (the command livevolume). Nothing happens
     * when no game runs or sound is off for it.
     */
    private void applyLiveVolume() {
        try {
            nativeSetVolume(Math.round(Math.max(0, Math.min(100, options.master)) * 128f / 100f),
                    Math.max(0, Math.min(100, options.effects)), Math.max(0, Math.min(100, options.music)), options.mute ? 1 : 0);
        } catch (Throwable e) {
            android.util.Log.w(Diag.TAG, "nativeSetVolume failed: " + e);
        }
    }

    /**
     * SDLActivity consumes the Back key in its own dispatchKeyEvent and forwards it to the engine as a key, so
     * {@link #onBackPressed} is never reached (device test of 0.3.0-tv4: no dialog in a network game). In a network game Back is
     * taken here, before SDL sees it: the dialog opens when the key goes up (a held key does not repeat it). A local game
     * still forwards Back to the engine as before. The soft keyboard handles its own Back before the activity sees it.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && BackConfirm.role(getFilesDir()) != BackConfirm.LOCAL) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) onBackPressed();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static void tap(int keyCode) {
        SDLActivity.onNativeKeyDown(keyCode);
        SDLActivity.onNativeKeyUp(keyCode);
    }

    /** The launcher (hp_ipc.c): tells the relay the player is leaving and makes the engine quit whatever its UI state. */
    private static native int nativeUserQuit();

    /** The launcher (hp_main.c): the sound levels changed during the game (see applyLiveVolume). */
    private static native int nativeSetVolume(int level, int effects, int music, int mute);

    /** The launcher (hp_ipc.c): the host chose "End the game for everyone": control frame Z to the relay, then the engine quits. */
    private static native int nativeEndForEveryone();

    /**
     * The player confirmed. First the launcher is told (it sends the relay an explicit "user quit" and the engine its
     * own forcequit command), which works even with the chat open. As a fallback, the keys a player would press follow
     * if the engine is still up: Escape then Y, repeated so that an open chat is closed first.
     */
    private void quitEngine(boolean endForEveryone) {
        if (quitting) return;
        quitting = true;
        if (BackConfirm.role(getFilesDir()) == BackConfirm.WATCH) {
            // This process is not the one that holds the relay connection: leave a note for the Network screen, which then
            // leaves the relay too and shows the chooser, so "Stop watching" is one step and not two.
            BackConfirm.markLeave(getFilesDir());
        }
        try {
            if (endForEveryone && BackConfirm.role(getFilesDir()) == BackConfirm.HOST) nativeEndForEveryone();
            else nativeUserQuit();
        } catch (Throwable e) {
            android.util.Log.w(Diag.TAG, "nativeUserQuit failed: " + e);
        }
        // Pairs of Escape then Y: the first pair may only close an open chat, the next opens the question and confirms it.
        for (int i = 0; i < 3; i++) {
            final long at = 2000 + i * 900L;
            uiHandler.postDelayed(() -> { if (!isFinishing()) tap(KeyEvent.KEYCODE_ESCAPE); }, at);
            uiHandler.postDelayed(() -> { if (!isFinishing()) tap(KeyEvent.KEYCODE_Y); }, at + 300);
        }
        // The engine did not answer: leave anyway (the host then sees this phone dropped).
        uiHandler.postDelayed(() -> { if (!isFinishing()) finish(); }, 10000);
    }
}
