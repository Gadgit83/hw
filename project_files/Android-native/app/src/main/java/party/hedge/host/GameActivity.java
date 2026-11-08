package party.hedge.host;

import android.os.Bundle;
import android.view.KeyEvent;

import org.libsdl.app.SDLActivity;

/**
 * Hosts the native engine. SDLActivity loads libSDL2.so and libmain.so and runs SDL_main
 * (host/native/launcher/hp_main.c) on its own thread. The engine reads its game from
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // The native launcher removes this marker when the engine ends cleanly.
        Diag.markGameStarted(this);
        super.onCreate(savedInstanceState);
    }

    /** The engine keeps global state in its statics, so end the process to start every game clean. */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /**
     * The engine has no Android back button handling, so the system Back key is forwarded as
     * Escape, which opens its pause/quit dialog. Leaving the game finishes the activity.
     */
    @Override
    public void onBackPressed() {
        SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ESCAPE);
        SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ESCAPE);
    }
}
