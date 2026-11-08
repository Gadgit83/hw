package org.hedgewars.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The setup screen: teams, map and theme, rules and weapons, then Start. The last setup is kept in the
 * preferences and comes back on the next launch. Options and the log are one tap away.
 */
public class MainActivity extends SetupActivity {
    private static final int TAB_TEAMS = 0, TAB_MAP = 1, TAB_RULES = 2;

    private static final String[] MAP_TYPE_NAMES = {
        "Random island", "Random maze", "Random perlin", "Forts", "Wave function collapse", "Image map",
    };
    private static final int[] MAP_TYPE_VALUES = {
        SetupModel.MAP_GENERATED, SetupModel.MAP_MAZE, SetupModel.MAP_PERLIN, SetupModel.MAP_FORTS,
        SetupModel.MAP_WFC, SetupModel.MAP_IMAGE,
    };

    private Ui ui;
    private GameData data;
    private SetupModel model;
    private int tab = TAB_TEAMS;
    private boolean contentReady;

    private LinearLayout root, topBar, bottomBar, holder, problemBox;
    private ScrollView scroll;
    private Ui.Tabs tabs;
    private TextView status, summary, problemText, schemeInfo;
    private Button start;
    private boolean moreRules;
    private boolean warming;
    private final Random rnd = new Random();

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
        ui = new Ui(this);
        Ui.edgeToEdge(this);
        tab = getSharedPreferences(Store.PREFS_NAME, MODE_PRIVATE).getInt("tab", TAB_TEAMS);
        if (tab < 0 || tab > TAB_RULES) tab = TAB_TEAMS;
        setContentView(buildFrame());
        prepareData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (dataReady && !DataInstaller.isInstalled(this) && !installing) {
            // The options menu asked for the game data to be unpacked again.
            dataReady = false;
            contentReady = false;
            prepareData();
        }
        if (dataReady) {
            int y = scroll.getScrollY();
            loadContent();
            scroll.post(() -> scroll.scrollTo(0, y));
        }
        refreshProblem();
        // The game process may still be shutting down when this screen comes back: look again shortly.
        ui.handler.postDelayed(this::refreshProblem, 900);
    }

    @Override
    protected void onPause() {
        saveModel();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (shown == this) shown = null;
        super.onDestroy();
    }

    private void saveModel() {
        if (model != null && contentReady) Store.saveSetup(this, model);
        getSharedPreferences(Store.PREFS_NAME, MODE_PRIVATE).edit().putInt("tab", tab).apply();
    }

    // Frame ---------------------------------------------------------------------------------------

    private View buildFrame() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // One row: title, tabs, Options.
        topBar = ui.row();
        topBar.setPadding(ui.dp(14), ui.dp(2), ui.dp(8), 0);
        TextView title = ui.text(BuildConfig.APP_NAME, 20, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        topBar.addView(title, ui.weight(1f));
        tabs = ui.tabs(new String[] { "Teams", "Map", "Rules" }, tab, i -> {
            tab = i;
            showTab();
        });
        topBar.addView(tabs.view, Ui.lp(ui.dp(270), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout optionsBox = new LinearLayout(this);
        optionsBox.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        optionsBox.addView(ui.iconButton(Ui.GLYPH_SLIDERS, "Options",
                v -> startActivity(new Intent(this, OptionsActivity.class))), Ui.lp(ui.dp(Ui.TOUCH_DP), ui.dp(Ui.TOUCH_DP)));
        topBar.addView(optionsBox, ui.weight(1f));
        root.addView(topBar);

        problemBox = new LinearLayout(this);
        problemBox.setOrientation(LinearLayout.HORIZONTAL);
        problemBox.setGravity(Gravity.CENTER_VERTICAL);
        problemBox.setVisibility(View.GONE);
        problemBox.setPadding(ui.dp(12), ui.dp(2), ui.dp(4), ui.dp(2));
        problemBox.setBackground(ui.round(Color.rgb(84, 52, 44), 10));
        problemText = ui.text("", 12, Color.rgb(255, 214, 190));
        problemBox.addView(problemText, ui.weight(1f));
        problemBox.addView(ui.button("Share last log", false, v -> shareLog()));
        LinearLayout.LayoutParams pp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.setMargins(ui.dp(12), ui.dp(2), ui.dp(12), ui.dp(2));
        root.addView(problemBox, pp);

        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(8));
        scroll.addView(holder, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Always visible: what will be played, the data state, and Start.
        bottomBar = ui.row();
        bottomBar.setBackgroundColor(Color.rgb(28, 42, 32));
        bottomBar.setPadding(ui.dp(14), ui.dp(2), ui.dp(12), ui.dp(2));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        summary = ui.text("", 13, Color.WHITE);
        summary.setMaxLines(1);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status = ui.text("", 11, Ui.MUTED);
        status.setMaxLines(2);
        texts.addView(summary);
        texts.addView(status);
        bottomBar.addView(texts, ui.weight(1f));
        start = ui.button("Start game", true, v -> {
            if (dataReady) startGame(); else prepareData();
        });
        start.setEnabled(false);
        start.setTextSize(15);
        bottomBar.addView(start, Ui.lp(ui.dp(170), ui.dp(Ui.TOUCH_DP)));
        root.addView(bottomBar);

        // The cut-out, bars and keyboard become padding around everything, so nothing is covered and the
        // list above the Start bar shrinks when the keyboard opens (the title and tabs make room for it).
        ui.trackInsets(root, (l, t, r, b, kb) -> {
            root.setPadding(l, t, r, b);
            topBar.setVisibility(kb ? View.GONE : View.VISIBLE);
            if (kb) {
                // bring the field being typed in back on screen once the new padding is laid out
                root.post(() -> {
                    View f = root.findFocus();
                    if (f != null) f.requestRectangleOnScreen(
                            new android.graphics.Rect(0, 0, f.getWidth(), f.getHeight()), false);
                });
            }
        });
        return root;
    }

    private int contentWidthDp() {
        return getResources().getConfiguration().screenWidthDp;
    }

    // Data ----------------------------------------------------------------------------------------

    /** Extract the game data on first run, off the UI thread. Also used by the Retry button. */
    private void prepareData() {
        if (installing) {
            render();
            return;
        }
        if (DataInstaller.isInstalled(this)) {
            dataReady = true;
            render();
            loadContent();
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
            if (!a.isDestroyed() && !a.isFinishing()) {
                a.render();
                if (dataReady) a.loadContent();
            }
        });
    }

    /** Shows the install state held in the statics. */
    private void render() {
        if (installing) {
            status.setText(installProgress);
            start.setText("Start game");
            start.setEnabled(false);
            if (!contentReady) showTab();
        } else if (installError != null) {
            status.setText(installError + ". Free up storage if needed, then tap Retry.");
            start.setText("Retry");
            start.setEnabled(true);
            if (!contentReady) showTab();
        } else if (dataReady) {
            status.setText("Ready");
            start.setText("Start game");
            start.setEnabled(true);
        }
    }

    /** (Re)reads the saved setup and the lists from the unpacked data, then shows the current tab. */
    private void loadContent() {
        final GameData d = Store.data(this);
        if (d.isWarm()) {
            applyContent(d);
            return;
        }
        // First look at the data folders: scan them off the UI thread (the lists are cached for later visits).
        if (warming) return;
        warming = true;
        new Thread(() -> {
            d.warm();
            runOnUiThread(() -> {
                warming = false;
                if (!isDestroyed() && !isFinishing() && dataReady) applyContent(d);
            });
        }, "data-scan").start();
    }

    private void applyContent(GameData d) {
        data = d;
        model = Store.loadSetup(this, data);
        contentReady = true;
        showTab();
    }

    // Tabs ----------------------------------------------------------------------------------------

    private void showTab() {
        tabs.select(tab);
        holder.removeAllViews();
        if (!contentReady) {
            LinearLayout c = ui.card();
            c.setGravity(Gravity.CENTER);
            TextView t = ui.text(installError != null ? installError : (installing ? installProgress : "Getting ready..."), 16, Color.WHITE);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, ui.dp(30), 0, ui.dp(30));
            c.addView(t);
            holder.addView(c);
            summary.setText("");
            return;
        }
        switch (tab) {
            case TAB_MAP: buildMap(); break;
            case TAB_RULES: buildRules(); break;
            default: buildTeams(); break;
        }
        updateSummary();
    }

    private void updateSummary() {
        int hogs = 0;
        for (SetupModel.Team t : model.teams) hogs += t.hogs;
        int ai = 0;
        for (SetupModel.Team t : model.teams) if (t.level > 0) ai++;
        StringBuilder s = new StringBuilder();
        s.append(model.teams.size()).append(" teams, ").append(hogs).append(" hedgehogs");
        if (ai > 0) s.append(" (").append(ai).append(ai == 1 ? " computer)" : " computers)");
        s.append("  ·  ").append(mapName());
        s.append("  ·  ").append(model.style.isEmpty() ? schemeLabel() : GameData.styleTitle(model.style));
        summary.setText(s.toString());
    }

    private String mapName() {
        if (model.mapType == SetupModel.MAP_IMAGE) return model.imageMap.isEmpty() ? "Image map" : model.imageMap;
        return MAP_TYPE_NAMES[typeIndex()];
    }

    private int typeIndex() {
        for (int i = 0; i < MAP_TYPE_VALUES.length; i++) if (MAP_TYPE_VALUES[i] == model.mapType) return i;
        return 0;
    }

    private String schemeLabel() {
        return model.rulesEdited() ? model.schemeName + " (edited)" : model.schemeName;
    }

    // Teams tab -----------------------------------------------------------------------------------

    private void buildTeams() {
        int hogs = 0;
        for (SetupModel.Team tm : model.teams) hogs += tm.hogs;
        boolean sameSide = false;
        for (int i = 0; i < model.teams.size(); i++)
            for (int j = i + 1; j < model.teams.size(); j++)
                if (model.teams.get(i).color == model.teams.get(j).color) sameSide = true;

        LinearLayout head = ui.row();
        head.setMinimumHeight(ui.dp(Ui.TOUCH_DP));
        TextView h = ui.heading("Teams");
        head.addView(h, ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 0, 10, 0));
        TextView info = ui.text(model.teams.size() + " of " + SetupModel.MAX_TEAMS + " teams, " + hogs + " hedgehogs"
                + (sameSide ? ". Teams of one colour play as one side." : ""), 12, Ui.MUTED);
        info.setSingleLine(true);
        info.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(info, ui.weight(1f));
        Button add = ui.button("Add team", false, v -> {
            if (model.teams.size() >= SetupModel.MAX_TEAMS) return;
            model.teams.add(SetupModel.randomTeam(data, rnd, model.freeColor(), model.teams));
            redraw();
        });
        add.setEnabled(model.teams.size() < SetupModel.MAX_TEAMS);
        Button dice = ui.button("Randomise", false, v -> {
            for (int i = 0; i < model.teams.size(); i++) {
                SetupModel.Team old = model.teams.get(i);
                SetupModel.Team nt = SetupModel.randomTeam(data, rnd, old.color, model.teams);
                nt.level = old.level;
                nt.hogs = old.hogs;
                model.teams.set(i, nt);
            }
            redraw();
        });
        dice.setContentDescription("Randomise teams");
        head.addView(dice, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(Ui.TOUCH_DP)));
        head.addView(add, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(Ui.TOUCH_DP)));
        holder.addView(head);

        // Three columns on a phone held sideways, so up to six teams are in view at once.
        int w = contentWidthDp();
        int cols = w >= 800 ? 3 : w >= 520 ? 2 : 1;
        LinearLayout grid = null;
        for (int i = 0; i < model.teams.size(); i++) {
            if (i % cols == 0) {
                grid = new LinearLayout(this);
                grid.setOrientation(LinearLayout.HORIZONTAL);
                holder.addView(grid, ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, i == 0 ? 0 : 6, 0, 0));
            }
            grid.addView(teamCard(i), ui.margins(ui.weight(1f), i % cols == 0 ? 0 : 6, 0, 0, 0));
        }
        if (grid != null) {
            for (int pad = model.teams.size() % cols; pad != 0 && pad < cols; pad++) {
                grid.addView(new View(this), ui.margins(ui.weight(1f), 6, 0, 0, 0));
            }
        }
    }

    private void redraw() {
        int y = scroll.getScrollY();
        showTab();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private LinearLayout teamCard(final int index) {
        final SetupModel.Team tm = model.teams.get(index);
        LinearLayout card = ui.card();
        card.setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2));

        LinearLayout line1 = ui.row();
        Button colour = ui.swatchButton(SetupModel.COLORS[tm.color], "Team colour: " + SetupModel.COLOR_NAMES[tm.color],
                v -> ui.pickGrid("Team colour", Arrays.asList(SetupModel.COLOR_NAMES),
                        key -> colourIcon(SetupModel.COLORS[Arrays.asList(SetupModel.COLOR_NAMES).indexOf(key)]),
                        SetupModel.COLOR_NAMES[tm.color], 84, false, key -> {
                            tm.color = Arrays.asList(SetupModel.COLOR_NAMES).indexOf(key);
                            redraw();
                        }));
        line1.addView(colour, Ui.lp(ui.dp(Ui.TOUCH_DP), ui.dp(Ui.TOUCH_DP)));
        EditText name = ui.field(tm.name, "Team name");
        name.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(32) });
        name.addTextChangedListener(new SimpleWatcher(s -> tm.name = s));
        line1.addView(name, ui.margins(Ui.lp(0, ui.dp(40), 1f), 0, 0, 2, 0));
        Ui.Glyph remove = ui.iconButton(Ui.GLYPH_CROSS, "Remove team", v -> {
            if (model.teams.size() <= SetupModel.MIN_TEAMS) return;
            model.teams.remove(index);
            redraw();
        });
        remove.setEnabled(model.teams.size() > SetupModel.MIN_TEAMS);
        line1.addView(remove, Ui.lp(ui.dp(Ui.TOUCH_DP), ui.dp(Ui.TOUCH_DP)));
        card.addView(line1);

        LinearLayout line2 = ui.row();
        List<String> levels = levelNames();
        Button type = ui.choiceButton(shortLevelNames().get(tm.level));
        type.setPadding(ui.dp(10), 0, ui.dp(8), 0);
        type.setOnClickListener(v ->
                ui.pickFromList("Team " + (index + 1) + " is played by", levels, tm.level, which -> {
                    tm.level = which;
                    redraw();
                }));
        line2.addView(type, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(Ui.TOUCH_DP)));
        LinearLayout hogs = ui.stepper("", SetupModel.MIN_HOGS, SetupModel.MAX_HOGS, tm.hogs, 54, 34, n -> n + " hogs",
                (cur, dir) -> cur + dir, n -> {
                    tm.hogs = n;
                    updateSummary();
                });
        hogs.setGravity(Gravity.CENTER);
        line2.addView(hogs, ui.weight(1f));
        Button edit = ui.button("Edit", false, v -> {
            saveModel();
            startActivity(new Intent(this, TeamEditActivity.class).putExtra("team", index));
        });
        edit.setContentDescription("Customise team " + (index + 1));
        edit.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        line2.addView(edit, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(Ui.TOUCH_DP)));
        card.addView(line2);
        return card;
    }

    /** The full names, for the list. */
    static List<String> levelNames() {
        List<String> l = new ArrayList<>();
        l.add("Human");
        for (int i = 1; i <= 5; i++) {
            l.add("Computer, level " + i + (i == 1 ? " (easiest)" : i == 5 ? " (hardest)" : ""));
        }
        return l;
    }

    /** The short names, for a button: "Human", "CPU 1" to "CPU 5". */
    static List<String> shortLevelNames() {
        List<String> l = new ArrayList<>();
        l.add("Human");
        for (int i = 1; i <= 5; i++) l.add("CPU " + i);
        return l;
    }

    private static Bitmap colourIcon(int rgb) {
        Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xff000000 | rgb);
        c.drawRoundRect(8, 8, 88, 88, 18, 18, p);
        return b;
    }

    /** Text watcher that reports the new text. */
    static final class SimpleWatcher implements TextWatcher {
        private final java.util.function.Consumer<String> on;

        SimpleWatcher(java.util.function.Consumer<String> on) {
            this.on = on;
        }

        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void afterTextChanged(Editable e) { on.accept(e.toString()); }
    }

    // Map tab -------------------------------------------------------------------------------------

    private void buildMap() {
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(contentWidthDp() >= 760 ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        boolean wide = contentWidthDp() >= 760;

        LinearLayout map = ui.card();
        map.addView(ui.heading("Map"));
        List<String> types = Arrays.asList(MAP_TYPE_NAMES);
        List<String> usable = new ArrayList<>(types);
        map.addView(ui.choice("Map type", usable, typeIndex(), i -> {
            model.mapType = MAP_TYPE_VALUES[i];
            if (model.mapType == SetupModel.MAP_IMAGE && model.imageMap.isEmpty() && !data.imageMaps().isEmpty()) {
                model.imageMap = data.imageMaps().get(0);
            }
            showTab();
        }));
        switch (model.mapType) {
            case SetupModel.MAP_GENERATED:
            case SetupModel.MAP_WFC:
                map.addView(ui.choice("Size", Arrays.asList(SetupModel.TEMPLATE_FILTERS), model.templateFilter,
                        i -> model.templateFilter = i));
                map.addView(ui.slider("Complexity", 1, 25, model.featureSize, n -> Integer.toString(n), n -> model.featureSize = n));
                map.addView(ui.hint(model.mapType == SetupModel.MAP_WFC
                        ? "Maps built from tiles. “Cavern” makes closed caves, “Wacky” odd shapes."
                        : "Islands are drawn from a template. “Cavern” makes closed caves, “Wacky” odd shapes."));
                break;
            case SetupModel.MAP_MAZE:
                map.addView(ui.choice("Style", Arrays.asList(SetupModel.MAZE_STYLES), model.mazeSize, i -> model.mazeSize = i));
                map.addView(ui.slider("Complexity", 1, 25, model.featureSize, n -> Integer.toString(n), n -> model.featureSize = n));
                break;
            case SetupModel.MAP_PERLIN:
                map.addView(ui.slider("Complexity", 1, 25, model.featureSize, n -> Integer.toString(n), n -> model.featureSize = n));
                map.addView(ui.hint("Rolling hills and caves from smooth noise."));
                break;
            case SetupModel.MAP_FORTS:
                map.addView(ui.slider("Distance between forts", 1, 25, model.featureSize, n -> Integer.toString(n), n -> model.featureSize = n));
                map.addView(ui.hint("Two sides, each in its own fort. Teams are divided automatically."));
                break;
            default:
                buildImageMap(map);
                break;
        }

        LinearLayout look = ui.card();
        look.addView(ui.heading("Theme"));
        look.addView(themeRow());
        look.addView(ui.heading("Seed"));
        EditText seed = ui.field(model.seed, "Random each game");
        seed.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        seed.addTextChangedListener(new SimpleWatcher(s -> model.seed = s));
        look.addView(seed, ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(40)), 0, 2, 0, 4));
        look.addView(ui.hint("Leave empty for a new random map every game. The same seed with the same map settings always gives the same map."));

        cols.addView(map, wide ? ui.weight(1f) : Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cols.addView(look, wide ? ui.margins(ui.weight(1f), 8, 0, 0, 0)
                : ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 8, 0, 0));
        holder.addView(cols);
    }

    private void buildImageMap(LinearLayout map) {
        List<String> maps = data.imageMaps();
        if (maps.isEmpty()) {
            map.addView(ui.hint("No image maps were found in the game data."));
            return;
        }
        LinearLayout r = ui.row();
        r.setMinimumHeight(ui.dp(Ui.TOUCH_DP));
        r.addView(ui.text("Map", 14, Color.WHITE), ui.weight(1f));
        Button b = ui.choiceButton(model.imageMap);
        b.setOnClickListener(v ->
                ui.pickGrid("Image map", maps, key -> Icons.map(data, key), model.imageMap, 150, true, key -> {
                    model.imageMap = key;
                    showTab();
                }));
        r.addView(b, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(Ui.TOUCH_DP)));
        map.addView(r);
        // The preview is decoded on a worker (the picture can be large); the cache makes a second showing instant.
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setBackground(ui.round(Color.rgb(20, 30, 24), 8));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final String mapName = model.imageMap;
        ui.loadPicture(iv, () -> Icons.map(data, mapName));
        map.addView(iv, ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(96)), 0, 2, 0, 2));
        int hogs = 0;
        for (SetupModel.Team t : model.teams) hogs += t.hogs;
        int limit = data.mapHogLimit(model.imageMap);
        map.addView(ui.hint("Made for up to " + limit + " hedgehogs; you have " + hogs + "."
                + (hogs > limit ? " Extra hedgehogs are placed wherever they fit." : "")));
    }

    private View themeRow() {
        LinearLayout r = ui.row();
        r.setMinimumHeight(ui.dp(Ui.TOUCH_DP));
        r.addView(ui.text("Theme", 14, Color.WHITE), ui.weight(1f));
        String cur = model.theme.isEmpty() ? "Random" : model.theme;
        if (!model.theme.isEmpty()) {
            ImageView icon = new ImageView(this);
            final String theme = model.theme;
            ui.loadPicture(icon, () -> Icons.theme(data, theme));
            r.addView(icon, Ui.lp(ui.dp(32), ui.dp(32)));
        }
        Button b = ui.choiceButton(cur);
        b.setOnClickListener(v -> {
            List<String> keys = new ArrayList<>();
            keys.add("Random");
            keys.addAll(data.themes());
            ui.pickGrid("Theme", keys, key -> key.equals("Random") ? null : Icons.theme(data, key),
                    cur, 120, false, key -> {
                        model.theme = key.equals("Random") ? "" : key;
                        showTab();
                    });
        });
        r.addView(b, ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(Ui.TOUCH_DP)), 4, 0, 0, 0));
        return r;
    }

    // Rules tab -----------------------------------------------------------------------------------

    private void buildRules() {
        boolean wide = contentWidthDp() >= 760;
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);

        // Left: what is played.
        LinearLayout left = ui.card();
        left.addView(ui.heading("Rules and weapons"));
        List<String> styles = new ArrayList<>();
        styles.add("Normal");
        for (String s : data.styles()) styles.add(GameData.styleTitle(s));
        int styleIdx = model.style.isEmpty() ? 0 : data.styles().indexOf(model.style) + 1;
        left.addView(ui.choice("Game style", styles, styleIdx, i -> {
            model.style = i == 0 ? "" : data.styles().get(i - 1);
            showTab();
        }));
        if (!model.style.isEmpty()) {
            String[] cfg = data.styleCfg(model.style);
            String note = (cfg[0].equals("locked") ? "Uses its own rules" : cfg[0].equals("*") ? "Any rules" : "Rules: " + cfg[0])
                    + ", " + (cfg[1].equals("locked") ? "its own weapons." : cfg[1].equals("*") ? "any weapons." : "weapons: " + cfg[1] + ".");
            left.addView(ui.hint("This style plays a script. " + note));
        }
        boolean schemeLocked = model.schemeLocked(data);
        boolean weaponsLocked = model.weaponsLocked(data);
        List<String> schemes = Rules.presetNames();
        Rules shown = model.effectiveRules(data);
        String shownName = schemeLocked ? (shown.matchingPreset() != null ? shown.matchingPreset() : "Style rules") : model.schemeName;
        int schemeIdx = Math.max(0, schemes.indexOf(shownName));
        LinearLayout schemeRow = ui.choice("Scheme", schemes, schemeIdx, i -> {
            model.chooseScheme(schemes.get(i));
            showTab();
        });
        enableAll(schemeRow, !schemeLocked);
        left.addView(schemeRow);
        schemeInfo = ui.hint(schemeLocked ? "The game style sets the scheme." : (model.rulesEdited() ? "Changed from " + model.schemeName + " below." : "Built-in scheme."));
        left.addView(schemeInfo);

        Switch link = ui.toggle("Pick the weapons that share the scheme's name", model.linkWeapons, c -> model.linkWeapons = c);
        link.setEnabled(!schemeLocked && !weaponsLocked);
        left.addView(link);
        List<String> sets = Rules.weaponSetNames();
        String shownSet = model.effectiveWeaponSet(data);
        LinearLayout weaponsRow = ui.choice("Weapon set", sets, Math.max(0, sets.indexOf(shownSet)), i -> {
            model.weaponSet = sets.get(i);
            showTab();
        });
        enableAll(weaponsRow, !weaponsLocked);
        left.addView(weaponsRow);
        if (weaponsLocked) left.addView(ui.hint("The game style sets the weapons."));

        // Right: the key numbers.
        LinearLayout right = ui.card();
        right.addView(ui.heading("Key values"));
        if (schemeLocked) {
            right.addView(ui.hint("These come from the game style, so they cannot be changed."));
        }
        boolean editable = !schemeLocked;
        // What will really be played: a game style may force a scheme, and then those numbers are shown.
        Rules r = shown;
        addTurnTime(right, r, editable);
        addValue(right, r, Rules.V_HEALTH, "Starting health", 1, 1000, 5, n -> Integer.toString(n), editable);
        addValue(right, r, Rules.V_SUDDEN, "Sudden death after", 0, 51, 1, n -> (n + 1) + (n == 0 ? " round" : " rounds"), editable);
        addValue(right, r, Rules.V_WATER_RISE, "Water rise", 0, 100, 5, n -> Integer.toString(n), editable);
        addValue(right, r, Rules.V_MINES, "Mines", 0, 200, 1, n -> Integer.toString(n), editable);
        addValue(right, r, Rules.V_MINES_TIME, "Mine timer", -1, 5, 1, n -> n < 0 ? "Random" : n + " s", editable);
        addValue(right, r, Rules.V_CASE_PROB, "Crate drops", 0, 9, 1, n -> n == 0 ? "Never" : "Every " + n, editable);
        addValue(right, r, Rules.V_HEALTH_PROB, "Health crate chance", 0, 100, 5, n -> n + "%", editable);
        addValue(right, r, Rules.V_EXPLOSIVES, "Explosive barrels", 0, 200, 1, n -> Integer.toString(n), editable);

        cols.addView(left, wide ? ui.weight(1f) : Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cols.addView(right, wide ? ui.margins(ui.weight(1f), 8, 0, 0, 0)
                : ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 8, 0, 0));
        holder.addView(cols);

        // More rules, hidden until asked for.
        Button more = ui.button(moreRules ? "Hide more rules" : "More rules", false, v -> {
            moreRules = !moreRules;
            redraw();
        });
        holder.addView(more, ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(Ui.TOUCH_DP)), 0, 4, 0, 0));
        if (moreRules) {
            LinearLayout extra = ui.card();
            extra.addView(ui.heading("More rules"));
            LinearLayout vals = new LinearLayout(this);
            vals.setOrientation(LinearLayout.VERTICAL);
            addValue(vals, r, Rules.V_DAMAGE, "Damage modifier", 10, 300, 10, n -> n + "%", editable);
            addValue(vals, r, Rules.V_HEALTH_AMOUNT, "Health in a crate", 0, 1000, 5, n -> Integer.toString(n), editable);
            addValue(vals, r, Rules.V_HEALTH_DEC, "Health lost in sudden death", 0, 1000, 1, n -> Integer.toString(n), editable);
            addValue(vals, r, Rules.V_ROPE, "Rope length", 25, 999, 5, n -> n + "%", editable);
            addValue(vals, r, Rules.V_GETAWAY, "Get away time", 0, 999, 5, n -> n + "%", editable);
            addValue(vals, r, Rules.V_DUD, "Dud mines", 0, 100, 5, n -> n + "%", editable);
            addValue(vals, r, Rules.V_AIR_MINES, "Air mines", 0, 200, 1, n -> Integer.toString(n), editable);
            addValue(vals, r, Rules.V_SENTRIES, "Sentries", 0, 200, 1, n -> Integer.toString(n), editable);
            LinearLayout edge = ui.choice("World edge", Arrays.asList(Rules.WORLD_EDGES), r.value[Rules.V_WORLD_EDGE], i -> {
                r.value[Rules.V_WORLD_EDGE] = i;
                updateSchemeInfo();
            });
            enableAll(edge, editable);
            vals.addView(edge);
            LinearLayout flags = new LinearLayout(this);
            flags.setOrientation(LinearLayout.VERTICAL);
            for (int k = 1; k <= 25; k++) {
                final int kk = k;
                Switch sw = ui.toggle(Rules.FLAG_LABELS[k], r.flag[k], c -> {
                    r.flag[kk] = c;
                    updateSchemeInfo();
                });
                sw.setEnabled(editable);
                flags.addView(sw);
            }
            LinearLayout both = new LinearLayout(this);
            both.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            both.addView(vals, wide ? ui.weight(1f) : Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            both.addView(flags, wide ? ui.margins(ui.weight(1f), 16, 0, 0, 0) : Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            extra.addView(both);
            holder.addView(extra, ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 10, 0, 0));
        }
    }

    /** Turn time: by 5 seconds up to 5 minutes, then "Unlimited" (9999, as the Timeless and HedgeEditor schemes use). */
    private void addTurnTime(LinearLayout into, Rules r, boolean editable) {
        final int unlimited = 9999;
        LinearLayout row = ui.stepper("Turn time", 1, unlimited, r.value[Rules.V_TURN_TIME], 76, 44,
                n -> n >= unlimited ? "Unlimited" : n + " s",
                (cur, dir) -> dir > 0 ? (cur >= 300 ? unlimited : Math.min(300, cur + 5))
                        : (cur >= unlimited ? 300 : Math.max(1, cur - 5)),
                n -> {
                    r.value[Rules.V_TURN_TIME] = n;
                    updateSchemeInfo();
                });
        enableAll(row, editable);
        into.addView(row);
    }

    private void addValue(LinearLayout into, Rules r, int entry, String label, int min, int max, int step,
                          java.util.function.IntFunction<String> fmt, boolean editable) {
        LinearLayout row = ui.stepper(label, min, max, step, r.value[entry], fmt, n -> {
            r.value[entry] = n;
            updateSchemeInfo();
        });
        enableAll(row, editable);
        into.addView(row);
    }

    private void updateSchemeInfo() {
        if (schemeInfo != null && tab == TAB_RULES) {
            schemeInfo.setText(model.rulesEdited() ? "Changed from " + model.schemeName + " below." : "Built-in scheme.");
        }
        updateSummary();
    }

    private static void enableAll(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.5f);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) enableAll(g.getChildAt(i), on);
        }
    }

    // Problems and the log ------------------------------------------------------------------------

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

    private void shareLog() {
        LogShare.share(this, msg -> status.setText(msg));
    }

    // Start ---------------------------------------------------------------------------------------

    private void startGame() {
        if (!contentReady) return;
        Ui.hideKeyboard(root);
        Store.saveSetup(this, model);
        try {
            List<String> lines = GameConfig.build(model, data, new Random());
            GameConfig.write(new File(getFilesDir(), "game.ipc"), lines);
        } catch (Exception e) {
            status.setText("Could not write the game setup: " + e);
            return;
        }
        Diag.clearGameState(this);
        startActivity(new Intent(this, GameActivity.class));
    }
}
