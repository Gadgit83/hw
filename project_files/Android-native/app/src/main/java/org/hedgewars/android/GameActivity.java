package org.hedgewars.android;

import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
        if (Build.VERSION.SDK_INT >= 33) registerBackGesture();
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
    private boolean returnedToLobby;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    /** A second Back this soon after the first is the same press reaching us twice (gesture and key), so it is ignored. */
    private static final long BACK_GAP_MS = 400;
    private long lastBackAt = -1000000L;

    /**
     * Every way out of the game ends here (the player left, the engine quit, the 10 second fallback, a fatal error). A network
     * game goes back to its Network lobby, which shows the game still running, the Rejoin card while the seat is kept, Watch and
     * leaving the host; it used to fall through to the setup screen. This process cannot read NetSession (that is the main
     * process), so the net.cfg the lobby wrote for this game says whether it is a network game. A local game finishes as before.
     */
    @Override
    public void finish() {
        if (!returnedToLobby && BackConfirm.role(getFilesDir()) != BackConfirm.LOCAL) {
            returnedToLobby = true;
            startActivity(new android.content.Intent(this, NetLobbyActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        }
        super.finish();
    }

    /**
     * The question Back asks, also what the pause screen's Quit game asks ({@link #pauseMenuAction}): "Quit the game?" in a
     * local game, the network question in a network game ({@link BackConfirm}). A second press while it is up means "keep
     * playing". On "yes" the engine is quit the way a player does it (see {@link #quitEngine}).
     */
    private void askQuit() {
        if (quitting) return;
        if (backDialog != null && backDialog.isShowing()) {
            backDialog.dismiss();
            return;
        }
        backDialog = BackConfirm.show(this, BackConfirm.role(getFilesDir()), () -> quitEngine(false), () -> quitEngine(true));
    }

    /** The pause screen's buttons (--pause-menu): the native launcher calls this from its IPC thread with the engine's letter. */
    static final int PAUSE_QUIT = 'q', PAUSE_OPTIONS = 'o';

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
        // A scroll view, so a short landscape screen (the Moto G6) shows every row: the dialog would otherwise squeeze the rows
        // (the Mute switch shrank to "OFF") and cut off the Controls layout button and the hint.
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        optionsDialog = Ui.dialog(this)
                .setTitle("Options")
                .setView(scroll)
                .setPositiveButton("Done", null)
                .setOnDismissListener(d -> Store.saveOptions(this, options))
                .create();
        optionsDialog.show();
    }

    /** How long the engine has to report where its controls are before the editor gives up. */
    private static final long LAYOUT_REPORT_MS = 3000;

    /** The controls editor (issue #21), while it is open over the game, and the places the engine reported for it. */
    private ControlsLayoutView layoutEditor;
    private final java.util.Map<String, TouchLayout.Place> layoutReported = new java.util.LinkedHashMap<>();
    private boolean layoutDefaultsSet;
    private final Runnable layoutTimedOut = () -> {
        if (layoutEditor != null && !layoutDefaultsSet) {
            layoutEditor.setError("The game did not report its controls, so they cannot be placed now. Cancel and try again.");
        }
    };

    /**
     * Controls layout (issue #21): the editor is laid over the running game. Opening it resets the engine to its default places
     * (the engine reports them, IPC 'T', see {@link #touchLayoutReport}); Cancel puts the saved layout back, Save stores the new one
     * and sends it to the engine at once.
     */
    private void openControlsLayout() {
        if (layoutEditor != null) return;
        layoutReported.clear();
        layoutDefaultsSet = false;
        ControlsLayoutView view = new ControlsLayoutView(this, options.twoJump, new ControlsLayoutView.Listener() {
            @Override public void onSave(String spec) { saveControlsLayout(spec); }
            @Override public void onCancel() {
                closeControlsLayout();
                sendTouchLayout(options.touchLayout);
            }
        });
        addContentView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layoutEditor = view;
        sendTouchLayout("");
        uiHandler.postDelayed(layoutTimedOut, LAYOUT_REPORT_MS);
    }

    /** Save: the layout is kept in the options (it is passed as --touch-layout at every game) and applied to this game now. */
    private void saveControlsLayout(String spec) {
        options.touchLayout = TouchLayout.sanitize(spec);
        Store.saveOptions(this, options);
        closeControlsLayout();
        sendTouchLayout(options.touchLayout);
    }

    private void closeControlsLayout() {
        uiHandler.removeCallbacks(layoutTimedOut);
        if (layoutEditor == null) return;
        android.view.ViewParent parent = layoutEditor.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(layoutEditor);
        layoutEditor = null;
    }

    /**
     * Sends a layout to the engine (the touchlayout command); "" is the default layout. Nothing happens when no game runs:
     * the launcher then returns -1 (no engine connected), which is normal and not logged.
     */
    private void sendTouchLayout(String spec) {
        try {
            int rc = nativeTouchLayout(spec);
            if (rc != 0 && rc != -1) android.util.Log.w(Diag.TAG, "touch layout not sent to the engine, result " + rc);
        } catch (Throwable e) {
            android.util.Log.w(Diag.TAG, "nativeTouchLayout failed: " + e);
        }
    }

    /**
     * Called from the native launcher (hp_main.c, through JNI) for each control's place the engine reports (IPC 'T', one message per
     * control, e.g. "fire:0.920,0.850,1.000,0.120,0.210"). Runs on the IPC thread, so it hands over to the UI thread.
     */
    @SuppressWarnings("unused")
    public void touchLayoutReport(String entry) {
        uiHandler.post(() -> {
            if (layoutEditor == null || layoutDefaultsSet) return;
            String name = TouchLayout.reportName(entry);
            TouchLayout.Place place = TouchLayout.reportPlace(entry);
            if (name == null || place == null) return;
            layoutReported.put(name, place);
            if (layoutReported.size() >= TouchLayout.NAMES.length) {
                layoutDefaultsSet = true;
                uiHandler.removeCallbacks(layoutTimedOut);
                layoutEditor.setDefaults(layoutReported, options.touchLayout);
            }
        });
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
     * Back asks the question for this phone's role ({@link BackConfirm}): "Quit the game?" in a local game, the network
     * question in a network game. The engine has no Back handling of its own, so the app asks first; on "yes" the engine is
     * quit the way a player does it (see {@link #quitEngine}). The game keeps running while the question is up unless the host
     * has paused it. Back with the question already up means "keep playing".
     */
    private void onBack() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastBackAt < BACK_GAP_MS) return;
        lastBackAt = now;
        askQuit();
    }

    @Override
    public void onBackPressed() {
        onBack();
    }

    /**
     * Back on Android 13 and later with gesture navigation: the system calls this callback and does not send the Back key,
     * so {@link #dispatchKeyEvent} never sees the gesture. Three-button navigation still sends the key.
     */
    @android.annotation.TargetApi(33)
    private void registerBackGesture() {
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBack);
    }

    /**
     * SDLActivity consumes the Back key in its own dispatchKeyEvent and forwards it to the engine as a key (device test of
     * 0.3.0-tv4: no dialog in a network game), so the key is taken here, before SDL sees it, for every game. The question opens
     * when the key goes up (a held key does not repeat it). The soft keyboard handles its own Back before the activity sees it.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) onBack();
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

    /** The launcher (hp_main.c): the touch controls' layout, sent to the engine as touchlayout (or the default when ""). */
    private static native int nativeTouchLayout(String spec);

    /** The launcher (hp_ipc.c): the host chose "End the game for everyone": control frame Z to the relay, then the engine quits. */
    private static native int nativeEndForEveryone();

    /** The launcher (hp_ipc.c): the app went to the background (true, control frame H) or is in front again (false, V). */
    private static native void nativeHidden(boolean hidden);

    /**
     * A network game whose app is hidden (home screen, another app, the screen off): SDL pauses the engine then, so it sends nothing,
     * and the relay would take the phone for stalled. Control frame H tells the relay the phone is hidden (it leaves the silence rule
     * alone for a while, see Relay.java); V tells it the game is in front again. The launcher's threads keep answering the relay's
     * pings meanwhile. A local game has no relay, so nothing is sent.
     */
    private void reportHidden(boolean hidden) {
        if (BackConfirm.role(getFilesDir()) == BackConfirm.LOCAL) return;
        try {
            nativeHidden(hidden);
        } catch (Throwable e) {
            // The native libraries did not load (the error is shown elsewhere): nothing to tell.
            android.util.Log.w(Diag.TAG, "could not tell the relay the game is " + (hidden ? "hidden" : "in front") + ": " + e);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!quitting) reportHidden(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        reportHidden(false);
    }

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
