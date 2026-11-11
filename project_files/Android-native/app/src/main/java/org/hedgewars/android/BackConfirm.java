package org.hedgewars.android;

import android.app.Activity;
import android.app.AlertDialog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The question Back asks in a network game ("Leave the game?", for the host "End the game for everyone?"). It is kept
 * apart from {@link GameActivity} (which needs the native engine) so the texts and the dialog can be tested on their own.
 * A local game does not use it: Back there still goes to the engine as Escape.
 */
final class BackConfirm {
    private BackConfirm() { }

    /** How this phone takes part: no network game, a joining phone, or the hosting phone. */
    static final int LOCAL = 0, PLAYER = 1, HOST = 2, WATCH = 3;

    private static final String RUNNING = " Unless the host has paused, the game keeps running while you decide.";

    /** How long a phone that left (or lost its connection) can come back; the relay's default ({@code Relay.Config.rejoinMs}). */
    static final String REJOIN_TIME = "2 minutes";

    static String title() {
        return "Leave the game?";
    }

    static String message(boolean host) {
        return host
                ? "The game goes on for the others without your teams, which sit out. You can rejoin from the Network screen within "
                        + REJOIN_TIME + ". Or end the game for every phone and every TV watching." + RUNNING
                : "Your teams sit out and the other players carry on. You can rejoin from the Network screen within " + REJOIN_TIME + "." + RUNNING;
    }

    static String leave() {
        return "Leave the game";
    }

    /** The host's third answer: today's behaviour, the game stops on every phone and the TV is told (close code 4001). */
    static String endAll() {
        return "End the game for everyone";
    }

    static String no() {
        return "Keep playing";
    }

    /** The question for a phone that only watches: it leaves nothing behind but its view. */
    static final String WATCH_TITLE = "Stop watching?", WATCH_MESSAGE = "The game carries on for the players." + RUNNING,
            WATCH_YES = "Stop watching", WATCH_NO = "Keep watching";

    /** A note in the files folder, written by the game process when a watching phone confirms "Stop watching". */
    static final String LEAVE_NOTE = "leave-after-game";

    static void markLeave(File filesDir) {
        try {
            new File(filesDir, LEAVE_NOTE).createNewFile();
        } catch (IOException e) {
            // without the note the Network screen's own Stop watching button still works
        }
    }

    /** True once, if the note was there (it is removed). */
    static boolean takeLeave(File filesDir) {
        return new File(filesDir, LEAVE_NOTE).delete();
    }

    /** The role written by the Network screen into net.cfg when it started the game; LOCAL when there is no such file. */
    static int role(File filesDir) {
        File f = new File(filesDir, "net.cfg");
        if (!f.exists()) return LOCAL;
        int role = PLAYER;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String l; (l = r.readLine()) != null; ) {
                if (l.trim().equals("role=host")) role = HOST;
                if (l.trim().equals("role=watch")) role = WATCH;
            }
        } catch (IOException e) {
            // an unreadable file is still a network game
        }
        return role;
    }

    /**
     * The question for any role. A watching phone gets "Stop watching?"; a joining phone "Leave the game" or "Keep playing"; the host
     * also "End the game for everyone". {@code onLeave} runs for Leave (and for Stop watching), {@code onEndAll} for the host's end.
     */
    static AlertDialog show(Activity a, int role, Runnable onLeave, Runnable onEndAll) {
        if (role != WATCH) return show(a, role == HOST, onLeave, onEndAll);
        AlertDialog d = Ui.dialog(a)
                .setTitle(WATCH_TITLE)
                .setMessage(WATCH_MESSAGE)
                .setPositiveButton(WATCH_YES, (dlg, w) -> onLeave.run())
                .setNegativeButton(WATCH_NO, null)
                .create();
        d.show();
        return d;
    }

    /** Shows the question for a playing phone: Leave the game, (host only) End the game for everyone, Keep playing. */
    static AlertDialog show(Activity a, boolean host, Runnable onLeave, Runnable onEndAll) {
        AlertDialog.Builder b = Ui.dialog(a)
                .setTitle(title())
                .setMessage(message(host))
                .setPositiveButton(leave(), (dlg, w) -> onLeave.run())
                .setNegativeButton(no(), null);
        if (host) b.setNeutralButton(endAll(), (dlg, w) -> onEndAll.run());
        AlertDialog d = b.create();
        d.show();
        return d;
    }
}
