package party.hedge.host;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Minimal setup screen: number of teams, their names, then start a hotseat game. */
public class MainActivity extends Activity {
    private static final int BG = Color.rgb(34, 51, 38);

    private static final int MIN_TEAMS = 2;
    private static final int MAX_TEAMS = 4;

    private final EditText[] nameFields = new EditText[MAX_TEAMS];
    private final LinearLayout[] rows = new LinearLayout[MAX_TEAMS];
    private TextView status;
    private Button start;
    private LinearLayout problemBox;
    private TextView problemText;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean sharing;
    private int teamCount = 2;
    private SharedPreferences prefs;

    // Static, so a recreated MainActivity in the same process sees an install already running
    // and does not start a second one. The install thread reports to whichever activity is shown.
    private static volatile boolean installing;
    private static volatile boolean dataReady;
    private static volatile String installError;
    private static volatile String installProgress = "";
    private static volatile MainActivity shown;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        shown = this;
        prefs = getSharedPreferences("setup", MODE_PRIVATE);
        teamCount = Math.max(MIN_TEAMS, Math.min(MAX_TEAMS, prefs.getInt("teams", 2)));
        setupEdgeToEdge();
        setContentView(buildUi());
        refreshRows();
        prepareData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshProblem();
        // The game process may still be shutting down when this screen comes back: look again shortly.
        ui.postDelayed(this::refreshProblem, 900);
    }

    /**
     * Draws behind the system bars and the camera cut-out, with the window background filling
     * everything. buildUi then pads the content by the insets, so nothing sits under a bar
     * or the cut-out and no black bands appear.
     */
    private void setupEdgeToEdge() {
        android.view.Window w = getWindow();
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            w.getAttributes().layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    /** Insets to keep clear of: system bars, the keyboard and the display cut-out. */
    private static int[] safeInsets(WindowInsets in) {
        int l, t, r, b;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            l = i.left; t = i.top; r = i.right; b = i.bottom;
        } else {
            l = in.getSystemWindowInsetLeft(); t = in.getSystemWindowInsetTop();
            r = in.getSystemWindowInsetRight(); b = in.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 28 && in.getDisplayCutout() != null) {
                android.view.DisplayCutout dc = in.getDisplayCutout();
                l = Math.max(l, dc.getSafeInsetLeft()); t = Math.max(t, dc.getSafeInsetTop());
                r = Math.max(r, dc.getSafeInsetRight()); b = Math.max(b, dc.getSafeInsetBottom());
            }
        }
        return new int[] { l, t, r, b };
    }

    @Override
    protected void onDestroy() {
        if (shown == this) shown = null;
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View buildUi() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(32), dp(16), dp(32), dp(16));

        TextView title = new TextView(this);
        title.setText("Hedge Party");
        title.setTextSize(32);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Hotseat game, one phone, take turns");
        sub.setTextColor(Color.rgb(200, 220, 200));
        root.addView(sub);

        LinearLayout count = new LinearLayout(this);
        count.setOrientation(LinearLayout.HORIZONTAL);
        count.setGravity(Gravity.CENTER_VERTICAL);
        count.setPadding(0, dp(12), 0, dp(4));
        TextView lbl = new TextView(this);
        lbl.setText("Teams: ");
        lbl.setTextColor(Color.WHITE);
        count.addView(lbl);
        for (int n = MIN_TEAMS; n <= MAX_TEAMS; n++) {
            final int nn = n;
            Button bt = new Button(this);
            bt.setText(String.valueOf(n));
            bt.setOnClickListener(v -> { teamCount = nn; refreshRows(); });
            count.addView(bt);
        }
        root.addView(count);

        for (int i = 0; i < MAX_TEAMS; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            View swatch = new View(this);
            swatch.setBackgroundColor((int) GameConfig.COLORS[i]);
            row.addView(swatch, new LinearLayout.LayoutParams(dp(24), dp(24)));
            EditText et = new EditText(this);
            et.setInputType(InputType.TYPE_CLASS_TEXT);
            et.setSingleLine(true);
            et.setTextColor(Color.WHITE);
            et.setText(prefs.getString("team" + i, GameConfig.DEFAULT_TEAM_NAMES[i]));
            nameFields[i] = et;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(12);
            row.addView(et, lp);
            rows[i] = row;
            root.addView(row);
        }

        start = new Button(this);
        start.setText("Start game");
        start.setEnabled(false);
        start.setOnClickListener(v -> { if (dataReady) startGame(); else prepareData(); });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(12);
        root.addView(start, sp);

        problemBox = new LinearLayout(this);
        problemBox.setOrientation(LinearLayout.VERTICAL);
        problemBox.setGravity(Gravity.CENTER_HORIZONTAL);
        problemBox.setVisibility(View.GONE);
        problemBox.setPadding(dp(12), dp(8), dp(12), dp(8));
        problemBox.setBackgroundColor(Color.rgb(74, 46, 40));
        problemText = new TextView(this);
        problemText.setTextColor(Color.rgb(255, 214, 190));
        problemText.setGravity(Gravity.CENTER_HORIZONTAL);
        problemBox.addView(problemText);
        Button shareLast = new Button(this);
        shareLast.setText("Share last log");
        shareLast.setOnClickListener(v -> shareLog());
        problemBox.addView(shareLast);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.topMargin = dp(12);
        root.addView(problemBox, pp);

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        status = new TextView(this);
        status.setTextColor(Color.rgb(200, 220, 200));
        foot.addView(status);
        Button shareLog = new Button(this);
        shareLog.setText("Share log");
        shareLog.setTextSize(12);
        shareLog.setAllCaps(false);
        shareLog.setOnClickListener(v -> shareLog());
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.leftMargin = dp(16);
        foot.addView(shareLog, fp);
        root.addView(foot);

        // Fills the window even when the content is short; the insets become padding.
        final ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(BG);
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // The insets (bars, cut-out and the keyboard) are padding on the ScrollView itself, not on
        // its content: the scrolling viewport then ends above the keyboard, so a focused team
        // name field can always be scrolled into view. With decorFitsSystemWindows(false) (API 30+)
        // the system no longer resizes the window for the keyboard.
        sv.setClipToPadding(true);
        sv.setOnApplyWindowInsetsListener((v, in) -> {
            int[] i = safeInsets(in);
            v.setPadding(i[0], i[1], i[2], i[3]);
            // Once the new padding is laid out, bring the focused field back on screen.
            v.post(() -> {
                View f = v.findFocus();
                if (f != null) f.requestRectangleOnScreen(
                        new android.graphics.Rect(0, 0, f.getWidth(), f.getHeight()), false);
            });
            return in;
        });
        return sv;
    }

    /** Shows why the last game failed, if it did, and offers the log. */
    private void refreshProblem() {
        if (problemBox == null || Diag.gameProcessAlive(this)) return;
        String err = Diag.lastError(this);
        boolean unclean = Diag.previousGameUnclean(this);
        if (err == null && !unclean) {
            problemBox.setVisibility(View.GONE);
            return;
        }
        problemText.setText(err != null
                ? "The last game stopped with a problem:\n" + err
                : "The last game did not end cleanly.");
        problemBox.setVisibility(View.VISIBLE);
    }

    /** Builds the report off the UI thread, then offers it to mail and messaging apps. */
    private void shareLog() {
        if (sharing) return;
        sharing = true;
        Toast.makeText(this, "Collecting the log...", Toast.LENGTH_SHORT).show();
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                Diag.buildReport(app);
            } catch (Throwable t) {
                // Share what there is: Diag writes whatever it collected before the failure.
                android.util.Log.w("HedgeParty", "log report failed", t);
            } finally {
                // However the report ended, the button must work again.
                ui.post(() -> sharing = false);
            }
            ui.post(() -> {
                if (isDestroyed() || isFinishing()) return;
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                Uri uri = LogProvider.reportUri();
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_SUBJECT, "Hedge Party log " + Diag.versionName(app));
                send.putExtra(Intent.EXTRA_TEXT, "Hedge Party log from " + Diag.deviceLine());
                send.setClipData(android.content.ClipData.newRawUri("log", uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(Intent.createChooser(send, "Share the Hedge Party log"));
                } catch (Exception e) {
                    status.setText("Could not open the share sheet: " + e.getMessage());
                }
            });
        }, "log-report").start();
    }

    private void refreshRows() {
        for (int i = 0; i < MAX_TEAMS; i++) rows[i].setVisibility(i < teamCount ? View.VISIBLE : View.GONE);
    }

    /** Extract the game data on first run, off the UI thread. Also used by the Retry button. */
    private void prepareData() {
        if (installing) {
            render();
            return;
        }
        if (DataInstaller.isInstalled(this)) {
            dataReady = true;
            render();
            return;
        }
        installing = true;
        installError = null;
        installProgress = "Unpacking game data, first start only...";
        render();
        Context app = getApplicationContext();
        new Thread(() -> {
            String error = null;
            try {
                DataInstaller.install(app, (done, total) -> {
                    installProgress = "Unpacking game data " + (100 * done / Math.max(total, 1)) + "%";
                    refresh();
                });
                dataReady = true;
            } catch (Exception e) {
                error = "Could not unpack the game data: " + (e.getMessage() != null ? e.getMessage() : e.toString());
            }
            installError = error;
            installing = false;
            refresh();
        }, "data-install").start();
    }

    /** Posts a redraw to the activity on screen, if any. */
    private static void refresh() {
        MainActivity a = shown;
        if (a != null) a.runOnUiThread(() -> {
            if (!a.isDestroyed() && !a.isFinishing()) a.render();
        });
    }

    /** Shows the install state held in the statics. */
    private void render() {
        if (installing) {
            status.setText(installProgress);
            start.setText("Start game");
            start.setEnabled(false);
        } else if (installError != null) {
            status.setText(installError + ". Free up storage if needed, then tap Retry.");
            start.setText("Retry");
            start.setEnabled(true);
        } else if (dataReady) {
            status.setText("Ready");
            start.setText("Start game");
            start.setEnabled(true);
        }
    }

    private void startGame() {
        String[] names = new String[teamCount];
        SharedPreferences.Editor ed = prefs.edit().putInt("teams", teamCount);
        for (int i = 0; i < teamCount; i++) {
            names[i] = GameConfig.clean(nameFields[i].getText().toString(), GameConfig.DEFAULT_TEAM_NAMES[i]);
            ed.putString("team" + i, names[i]);
        }
        ed.apply();

        List<String> themes = new ArrayList<>();
        String[] listed = new File(getFilesDir(), "Data/Themes").list();
        if (listed != null) {
            for (String t : listed) {
                if (new File(getFilesDir(), "Data/Themes/" + t + "/theme.cfg").isFile()) themes.add(t);
            }
        }
        if (themes.isEmpty()) themes.add("Nature");
        try {
            GameConfig.write(new File(getFilesDir(), "game.ipc"),
                    GameConfig.build(names, themes, System.nanoTime() & 0xffffffffL));
        } catch (Exception e) {
            status.setText("Could not write the game setup: " + e);
            return;
        }
        Diag.clearGameState(this);
        startActivity(new Intent(this, GameActivity.class));
    }
}
