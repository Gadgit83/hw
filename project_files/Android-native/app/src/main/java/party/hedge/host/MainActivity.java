package party.hedge.host;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Minimal setup screen: number of teams, their names, then start a hotseat game. */
public class MainActivity extends Activity {
    private static final int MIN_TEAMS = 2;
    private static final int MAX_TEAMS = 4;

    private final EditText[] nameFields = new EditText[MAX_TEAMS];
    private final LinearLayout[] rows = new LinearLayout[MAX_TEAMS];
    private TextView status;
    private Button start;
    private int teamCount = 2;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("setup", MODE_PRIVATE);
        teamCount = Math.max(MIN_TEAMS, Math.min(MAX_TEAMS, prefs.getInt("teams", 2)));
        setContentView(buildUi());
        refreshRows();
        prepareData();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(34, 51, 38));
        root.setPadding(dp(32), dp(16), dp(32), dp(16));
        root.setGravity(Gravity.CENTER_HORIZONTAL);

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
        start.setOnClickListener(v -> startGame());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(12);
        root.addView(start, sp);

        status = new TextView(this);
        status.setTextColor(Color.rgb(200, 220, 200));
        root.addView(status);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(root);
        return sv;
    }

    private void refreshRows() {
        for (int i = 0; i < MAX_TEAMS; i++) rows[i].setVisibility(i < teamCount ? View.VISIBLE : View.GONE);
    }

    /** Extract the game data on first run, off the UI thread. */
    private void prepareData() {
        if (DataInstaller.isInstalled(this)) {
            status.setText("Ready");
            start.setEnabled(true);
            return;
        }
        status.setText("Unpacking game data, first start only...");
        new Thread(() -> {
            try {
                DataInstaller.install(this, (done, total) ->
                        runOnUiThread(() -> status.setText("Unpacking game data " + (100 * done / Math.max(total, 1)) + "%")));
                runOnUiThread(() -> { status.setText("Ready"); start.setEnabled(true); });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Could not unpack the game data: " + e));
            }
        }, "data-install").start();
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
        startActivity(new Intent(this, GameActivity.class));
    }
}
