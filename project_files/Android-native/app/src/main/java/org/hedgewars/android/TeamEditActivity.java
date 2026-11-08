package org.hedgewars.android;

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
public class TeamEditActivity extends SetupActivity {
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
        topBar.setPadding(ui.dp(10), ui.dp(2), ui.dp(10), 0);
        Button back = ui.button("‹ Done", true, v -> finish());
        topBar.addView(back, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(Ui.TOUCH_DP)));
        title = ui.text("", 18, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        topBar.addView(title, ui.margins(ui.weight(1f), 12, 0, 8, 0));
        topBar.addView(ui.button("Randomise team", false, v -> {
            SetupModel.Team nt = SetupModel.randomTeam(data, rnd, team.color, model.teams);
            nt.level = team.level;
            nt.hogs = team.hogs;
            model.teams.set(index, nt);
            team = nt;
            fill();
        }), Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(Ui.TOUCH_DP)));
        root.addView(topBar);

        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        holder = new LinearLayout(this);
        holder.setPadding(ui.dp(12), ui.dp(2), ui.dp(12), ui.dp(8));
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
        return getResources().getConfiguration().screenWidthDp >= 700;
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
        left.addView(name, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(40)));

        left.addView(ui.choice("Played by", MainActivity.shortLevelNames(), MainActivity.levelNames(), team.level, i -> {
            team.level = i;
            fill();
        }));
        left.addView(colourRow());
        left.addView(ui.stepper("Hedgehogs", SetupModel.MIN_HOGS, SetupModel.MAX_HOGS, 1, team.hogs, n -> Integer.toString(n), n -> {
            team.hogs = n;
            fillHogs();
        }));
        left.addView(pickerRow("Fort", team.fort, () -> Icons.fort(data, team.fort), data.forts(), key -> Icons.fort(data, key), 100, k -> team.fort = k));
        left.addView(pickerRow("Grave", team.grave, () -> Icons.grave(data, team.grave), data.graves(), key -> Icons.grave(data, key), 100, k -> team.grave = k));
        left.addView(pickerRow("Voice", team.voice, null, data.voices(), null, 130, k -> team.voice = k));

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
        }), ui.margins(ui.weight(1f), 6, 0, 0, 0));
        right.addView(actions);
        hogList = new LinearLayout(this);
        hogList.setOrientation(LinearLayout.VERTICAL);
        right.addView(hogList, ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 2, 0, 0));
        fillHogs();

        holder.addView(left, wide() ? ui.weight(1f) : Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.addView(right, wide() ? ui.margins(ui.weight(1f), 8, 0, 0, 0)
                : ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 8, 0, 0));
    }

    /** The team colour, as a row like the other pickers: a swatch and the name, opening the list of colours. */
    private View colourRow() {
        LinearLayout r = ui.row();
        r.setMinimumHeight(ui.dp(Ui.TOUCH_DP));
        r.addView(ui.text("Colour", 14, Color.WHITE), ui.weight(1f));
        List<String> names = Arrays.asList(SetupModel.COLOR_NAMES);
        Button b = ui.choiceButton(SetupModel.COLOR_NAMES[team.color]);
        b.setOnClickListener(v -> ui.pickGrid("Team colour", names, key -> colourIcon(SetupModel.COLORS[names.indexOf(key)]),
                SetupModel.COLOR_NAMES[team.color], 84, false, key -> {
                    team.color = names.indexOf(key);
                    fill();
                }));
        r.addView(ui.swatchButton(SetupModel.COLORS[team.color], "Team colour: " + SetupModel.COLOR_NAMES[team.color],
                v -> b.performClick()), Ui.lp(ui.dp(Ui.TOUCH_DP), ui.dp(Ui.TOUCH_DP)));
        r.addView(b, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(Ui.TOUCH_DP)));
        return r;
    }

    private static Bitmap colourIcon(int rgb) {
        Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xff000000 | rgb);
        c.drawRoundRect(8, 8, 88, 88, 18, 18, p);
        return b;
    }

    private LinearLayout pickerRow(String label, String current, java.util.concurrent.Callable<Bitmap> icon, List<String> keys,
                                   Ui.IconLoader loader, int cellDp, java.util.function.Consumer<String> on) {
        LinearLayout r = ui.row();
        r.setMinimumHeight(ui.dp(Ui.TOUCH_DP));
        r.addView(ui.text(label, 14, Color.WHITE), ui.weight(1f));
        if (icon != null) {
            ImageView iv = new ImageView(this);
            ui.loadPicture(iv, icon);
            r.addView(iv, Ui.lp(ui.dp(32), ui.dp(32)));
        }
        Button b = ui.choiceButton(current.replace('_', ' '));
        b.setOnClickListener(v -> ui.pickGrid(label, keys, loader, current, cellDp, false, key -> {
            on.accept(key);
            fill();
        }));
        r.addView(b, ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(Ui.TOUCH_DP)), 4, 0, 0, 0));
        return r;
    }

    private void fillHogs() {
        hogList.removeAllViews();
        for (int h = 0; h < team.hogs; h++) {
            final int hh = h;
            LinearLayout row = ui.row();
            ImageButton hat = new ImageButton(this);
            hat.setBackground(ui.inset(ui.pressable(Ui.PANEL_HI, Color.rgb(84, 120, 92), 9), 4, 4));
            hat.setScaleType(ImageView.ScaleType.FIT_CENTER);
            hat.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
            final String hatName = team.hogHat[h];
            ui.loadPicture(hat, () -> Icons.hat(data, hatName));
            hat.setContentDescription("Hat of hedgehog " + (h + 1) + ": " + team.hogHat[h]);
            hat.setOnClickListener(v -> ui.pickGrid("Hat for hedgehog " + (hh + 1), data.hats(), key -> Icons.hat(data, key),
                    team.hogHat[hh], 96, false, key -> {
                        team.hogHat[hh] = key;
                        fillHogs();
                    }));
            row.addView(hat, Ui.lp(ui.dp(Ui.TOUCH_DP), ui.dp(Ui.TOUCH_DP)));
            EditText name = ui.field(team.hogName[h], "Hedgehog " + (h + 1));
            name.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(32) });
            name.addTextChangedListener(new MainActivity.SimpleWatcher(s -> team.hogName[hh] = s));
            row.addView(name, ui.margins(Ui.lp(0, ui.dp(40), 1f), 2, 0, 2, 0));
            Button dice = ui.button("↻", false, v -> {
                List<String> taken = new ArrayList<>(Arrays.asList(team.hogName).subList(0, team.hogs));
                team.hogName[hh] = data.randomHogName(team.hogHat[hh], taken, rnd);
                fillHogs();
            });
            dice.setTextSize(18);
            dice.setPadding(0, 0, 0, 0);
            dice.setContentDescription("New name for hedgehog " + (h + 1));
            row.addView(dice, Ui.lp(ui.dp(Ui.TOUCH_DP), ui.dp(Ui.TOUCH_DP)));
            hogList.addView(row);
        }
    }
}
