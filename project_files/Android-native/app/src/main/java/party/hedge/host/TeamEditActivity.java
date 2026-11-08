package party.hedge.host;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** One team in detail: name, player, colour, fort, grave, voice, and every hedgehog's hat and name. */
public class TeamEditActivity extends Activity {
    private Ui ui;
    private GameData data;
    private SetupModel model;
    private SetupModel.Team team;
    private int index;
    private final Random rnd = new Random();

    private LinearLayout root, topBar, holder, hogList;
    private ScrollView scroll;
    private TextView title;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Ui(this);
        Ui.edgeToEdge(this);
        data = Store.data(this);
        model = Store.loadSetup(this, data);
        index = Math.max(0, Math.min(getIntent().getIntExtra("team", 0), model.teams.size() - 1));
        team = model.teams.get(index);
        setContentView(buildFrame());
        fill();
    }

    @Override
    protected void onPause() {
        Store.saveSetup(this, model);
        super.onPause();
    }

    private View buildFrame() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        topBar = ui.row();
        topBar.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(4));
        Button back = ui.button("‹ Done", true, v -> finish());
        topBar.addView(back, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(48)));
        title = ui.text("", 22, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        topBar.addView(title, ui.margins(ui.weight(1f), 16, 0, 0, 0));
        root.addView(topBar);

        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        holder = new LinearLayout(this);
        holder.setPadding(ui.dp(16), ui.dp(2), ui.dp(16), ui.dp(16));
        scroll.addView(holder, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ui.trackInsets(root, (l, t, r, bt, kb) -> {
            root.setPadding(l, t, r, bt);
            if (kb) {
                root.post(() -> {
                    View f = root.findFocus();
                    if (f != null) f.requestRectangleOnScreen(new android.graphics.Rect(0, 0, f.getWidth(), f.getHeight()), false);
                });
            }
        });
        return root;
    }

    private boolean wide() {
        return getResources().getConfiguration().screenWidthDp >= 760;
    }

    private void fill() {
        title.setText("Team " + (index + 1) + ": " + team.name);
        holder.removeAllViews();
        holder.setOrientation(wide() ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);

        LinearLayout left = ui.card();
        left.addView(ui.heading("Team"));
        EditText name = ui.field(team.name, "Team name");
        name.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(32) });
        name.addTextChangedListener(new MainActivity.SimpleWatcher(s -> {
            team.name = s;
            title.setText("Team " + (index + 1) + ": " + s);
        }));
        left.addView(name, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48)));

        List<String> levels = new ArrayList<>();
        levels.add("Human");
        for (int i = 1; i <= 5; i++) levels.add("Computer, level " + i + (i == 1 ? " (easiest)" : i == 5 ? " (hardest)" : ""));
        left.addView(ui.choice("Played by", levels, team.level, i -> {
            team.level = i;
            fill();
        }));

        left.addView(ui.text("Colour", 15, Color.WHITE), ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 6, 0, 4));
        left.addView(colourRow());

        left.addView(ui.stepper("Hedgehogs", SetupModel.MIN_HOGS, SetupModel.MAX_HOGS, 1, team.hogs, n -> Integer.toString(n), n -> {
            team.hogs = n;
            fillHogs();
        }));

        left.addView(pickerRow("Fort", team.fort, Icons.fort(data, team.fort), data.forts(), key -> Icons.fort(data, key), 100, k -> team.fort = k));
        left.addView(pickerRow("Grave", team.grave, Icons.grave(data, team.grave), data.graves(), key -> Icons.grave(data, key), 100, k -> team.grave = k));
        left.addView(pickerRow("Voice", team.voice, null, data.voices(), null, 130, k -> team.voice = k));
        left.addView(ui.button("Randomise the whole team", false, v -> {
            SetupModel.Team nt = SetupModel.randomTeam(data, rnd, team.color, model.teams);
            nt.level = team.level;
            nt.hogs = team.hogs;
            model.teams.set(index, nt);
            team = nt;
            fill();
        }), ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48)), 0, 8, 0, 0));

        LinearLayout right = ui.card();
        right.addView(ui.heading("Hedgehogs"));
        LinearLayout actions = ui.row();
        actions.addView(ui.button("Randomise hats", false, v -> {
            for (int i = 0; i < SetupModel.MAX_HOGS; i++) team.hogHat[i] = data.randomHat(rnd);
            fillHogs();
        }), ui.weight(1f));
        actions.addView(ui.button("Randomise names", false, v -> {
            SetupModel.fillHogNames(data, rnd, team);
            fillHogs();
        }), ui.margins(ui.weight(1f), 8, 0, 0, 0));
        right.addView(actions);
        hogList = new LinearLayout(this);
        hogList.setOrientation(LinearLayout.VERTICAL);
        right.addView(hogList, ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 6, 0, 0));
        fillHogs();

        holder.addView(left, wide() ? ui.weight(1f) : Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.addView(right, wide() ? ui.margins(ui.weight(1f), 10, 0, 0, 0)
                : ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 10, 0, 0));
    }

    private View colourRow() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int c = 0; c < SetupModel.COLORS.length; c++) {
            if (c % 5 == 0) {
                row = ui.row();
                box.addView(row);
            }
            final int cc = c;
            View sw = ui.swatch(SetupModel.COLORS[c], 44, team.color == c);
            sw.setContentDescription(SetupModel.COLOR_NAMES[c]);
            sw.setOnClickListener(v -> {
                team.color = cc;
                fill();
            });
            row.addView(sw, ui.margins(Ui.lp(ui.dp(44), ui.dp(44)), 0, 2, 8, 2));
        }
        return box;
    }

    private LinearLayout pickerRow(String label, String current, Bitmap icon, List<String> keys, Ui.IconLoader loader,
                                   int cellDp, java.util.function.Consumer<String> on) {
        LinearLayout r = ui.row();
        r.setMinimumHeight(ui.dp(56));
        r.addView(ui.text(label, 15, Color.WHITE), ui.weight(1f));
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(icon);
            r.addView(iv, Ui.lp(ui.dp(40), ui.dp(40)));
        }
        Button b = ui.button(current.replace('_', ' ') + "  ▾", false, v ->
                ui.pickGrid(label, keys, loader, current, cellDp, false, key -> {
                    on.accept(key);
                    fill();
                }));
        r.addView(b, ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(48)), 8, 0, 0, 0));
        return r;
    }

    private void fillHogs() {
        hogList.removeAllViews();
        for (int h = 0; h < team.hogs; h++) {
            final int hh = h;
            LinearLayout row = ui.row();
            row.setPadding(0, ui.dp(3), 0, ui.dp(3));
            ImageButton hat = new ImageButton(this);
            hat.setBackground(ui.pressable(Ui.PANEL_HI, Color.rgb(84, 120, 92), 10));
            hat.setScaleType(ImageView.ScaleType.FIT_CENTER);
            hat.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
            Bitmap hb = Icons.hat(data, team.hogHat[h]);
            if (hb != null) hat.setImageBitmap(hb);
            hat.setContentDescription("Hat of hedgehog " + (h + 1) + ": " + team.hogHat[h]);
            hat.setOnClickListener(v -> ui.pickGrid("Hat for hedgehog " + (hh + 1), data.hats(), key -> Icons.hat(data, key),
                    team.hogHat[hh], 96, false, key -> {
                        team.hogHat[hh] = key;
                        fillHogs();
                    }));
            row.addView(hat, Ui.lp(ui.dp(56), ui.dp(56)));
            EditText name = ui.field(team.hogName[h], "Hedgehog " + (h + 1));
            name.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(32) });
            name.addTextChangedListener(new MainActivity.SimpleWatcher(s -> team.hogName[hh] = s));
            row.addView(name, ui.margins(ui.weight(1f), 8, 0, 8, 0));
            Button dice = ui.button("↻", false, v -> {
                List<String> taken = new ArrayList<>(Arrays.asList(team.hogName).subList(0, team.hogs));
                team.hogName[hh] = data.randomHogName(team.hogHat[hh], taken, rnd);
                fillHogs();
            });
            dice.setContentDescription("New name for hedgehog " + (h + 1));
            row.addView(dice, Ui.lp(ui.dp(48), ui.dp(48)));
            hogList.addView(row);
        }
    }
}
