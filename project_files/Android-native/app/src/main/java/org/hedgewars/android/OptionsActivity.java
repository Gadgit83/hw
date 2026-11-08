package org.hedgewars.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/** Options: sound, display and controls, and the advanced and about pages. Changes apply to the next game. */
public class OptionsActivity extends Activity {

    private Ui ui;
    private Options o;
    private int tab;
    private LinearLayout root, holder, topBar;
    private Ui.Tabs tabs;
    private TextView notice;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Ui(this);
        Ui.edgeToEdge(this);
        o = Store.loadOptions(this);
        tab = getSharedPreferences(Store.PREFS_NAME, MODE_PRIVATE).getInt("optionsTab", 0);
        if (tab < 0 || tab > 2) tab = 0;
        setContentView(buildFrame());
        show();
    }

    @Override
    protected void onPause() {
        Store.saveOptions(this, o);
        getSharedPreferences(Store.PREFS_NAME, MODE_PRIVATE).edit().putInt("optionsTab", tab).apply();
        super.onPause();
    }

    private View buildFrame() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        topBar = ui.row();
        topBar.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(4));
        topBar.addView(ui.button("‹ Done", true, v -> finish()), Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(48)));
        TextView title = ui.text("Options", 22, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        topBar.addView(title, ui.margins(ui.weight(1f), 16, 0, 0, 0));
        tabs = ui.tabs(new String[] { "Sound", "Display and controls", "Advanced" }, tab, i -> {
            tab = i;
            show();
        });
        topBar.addView(tabs.view, Ui.lp(ui.dp(500), ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(topBar);

        notice = ui.hint("Changes apply to the next game you start.");
        notice.setPadding(ui.dp(20), 0, ui.dp(16), ui.dp(2));
        root.addView(notice);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setPadding(ui.dp(16), ui.dp(2), ui.dp(16), ui.dp(16));
        scroll.addView(holder, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ui.trackInsets(root, (l, t, r, bt, kb) -> root.setPadding(l, t, r, bt));
        return root;
    }

    private boolean wide() {
        return getResources().getConfiguration().screenWidthDp >= 760;
    }

    private void show() {
        tabs.select(tab);
        holder.removeAllViews();
        switch (tab) {
            case 1: displayTab(); break;
            case 2: advancedTab(); break;
            default: soundTab(); break;
        }
    }

    private LinearLayout.LayoutParams full() {
        return Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void twoColumns(LinearLayout a, LinearLayout b) {
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(wide() ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        cols.addView(a, wide() ? ui.weight(1f) : full());
        cols.addView(b, wide() ? ui.margins(ui.weight(1f), 10, 0, 0, 0) : ui.margins(full(), 0, 10, 0, 0));
        holder.addView(cols);
    }

    private static void enableAll(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.45f);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) enableAll(g.getChildAt(i), on);
        }
    }

    // Sound ---------------------------------------------------------------------------------------

    private void soundTab() {
        LinearLayout levels = ui.card();
        levels.addView(ui.heading("Volume"));
        final LinearLayout sliders = new LinearLayout(this);
        sliders.setOrientation(LinearLayout.VERTICAL);
        sliders.addView(ui.slider("Master volume", 0, 100, o.master, n -> n + "%", n -> o.master = n));
        sliders.addView(ui.slider("Sound effects", 0, 100, o.effects, n -> n + "%", n -> o.effects = n));
        sliders.addView(ui.slider("Music", 0, 100, o.music, n -> n + "%", n -> o.music = n));
        levels.addView(sliders);
        levels.addView(ui.hint("Sound effects and music are set as a share of the master volume. "
                + "The phone's own volume buttons still apply on top of these."));
        enableAll(sliders, !o.mute);

        LinearLayout switches = ui.card();
        switches.addView(ui.heading("Switches"));
        switches.addView(ui.toggle("Mute all sound and music", o.mute, c -> {
            o.mute = c;
            enableAll(sliders, !c);
        }));
        switches.addView(ui.toggle("Hedgehog voices", o.voices, c -> o.voices = c));
        switches.addView(ui.hint("Voices are the taunts, grunts and victory shouts of the hedgehogs. "
                + "Muting also turns off the voices."));
        twoColumns(levels, switches);
    }

    // Display and controls ------------------------------------------------------------------------

    private void displayTab() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int[] auto = DisplayCalc.autoScales(dm);
        float mmPx = DisplayCalc.pxPerMm(dm);

        LinearLayout size = ui.card();
        size.addView(ui.heading("Size of the controls"));
        LinearLayout hudSlider = ui.slider("Buttons and text on screen", 100, 340, o.hudPct, n -> n + "%", n -> o.hudPct = n);
        enableAll(hudSlider, !o.hudAuto);
        size.addView(ui.toggle("Automatic (" + auto[0] + "% on this screen)", o.hudAuto, c -> {
            o.hudAuto = c;
            enableAll(hudSlider, !c);
        }));
        size.addView(hudSlider);
        LinearLayout menuSlider = ui.slider("Weapon menu", 100, 400, o.menuPct, n -> n + "%", n -> o.menuPct = n);
        enableAll(menuSlider, !o.menuAuto);
        size.addView(ui.toggle("Automatic (" + auto[1] + "% on this screen)", o.menuAuto, c -> {
            o.menuAuto = c;
            enableAll(menuSlider, !c);
        }));
        size.addView(menuSlider);
        int autoMm = Math.round((auto[0] / 100f) * 50f / mmPx);
        size.addView(ui.hint("Automatic aims for controls about 8 mm across. A control is about " + autoMm
                + " mm at the automatic size on this screen."));

        LinearLayout edge = ui.card();
        edge.addView(ui.heading("Screen edge"));
        LinearLayout marginSlider = ui.slider("Clear space at the edge", 0, 15, o.marginMm, n -> n + " mm", n -> o.marginMm = n);
        enableAll(marginSlider, !o.marginAuto);
        edge.addView(ui.toggle("Automatic (from the rounded corners)", o.marginAuto, c -> {
            o.marginAuto = c;
            enableAll(marginSlider, !c);
        }));
        edge.addView(marginSlider);
        edge.addView(ui.hint("Keeps buttons out of the rounded corners. The camera cut-out and the navigation bar are always avoided."));

        LinearLayout look = ui.card();
        look.addView(ui.heading("Effects and feedback"));
        List<String> q = Arrays.asList(
                "Best: all effects",
                "High: softer terrain",
                "Medium: no snow, plain splashes",
                "Low: flat water, plain explosions",
                "Lower: simple rope",
                "Lowest: no background");
        look.addView(ui.choice("Effects quality", q, 5 - o.quality, i -> o.quality = 5 - i));
        look.addView(ui.hint("Lower the quality if the game runs slowly."));
        look.addView(ui.toggle("Vibrate on big explosions", o.vibration, c -> o.vibration = c));
        look.addView(ui.toggle("Show frames per second", o.showFps, c -> o.showFps = c));
        look.addView(ui.heading("Tags above hedgehogs"));
        look.addView(ui.toggle("Team name", o.teamTags, c -> o.teamTags = c));
        look.addView(ui.toggle("Hedgehog name", o.hogTags, c -> o.hogTags = c));
        look.addView(ui.toggle("Health", o.healthTags, c -> o.healthTags = c));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(size);
        left.addView(edge, ui.margins(full(), 0, 10, 0, 0));
        twoColumns(left, look);
    }

    // Advanced ------------------------------------------------------------------------------------

    private void advancedTab() {
        LinearLayout tools = ui.card();
        tools.addView(ui.heading("Diagnostics"));
        tools.addView(ui.text("If a game stops or something looks wrong, share the log so it can be looked at.", 14, Color.WHITE));
        tools.addView(ui.button("Share log", false, v -> LogShare.share(this, msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show())),
                ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(48)), 0, 8, 0, 0));
        tools.addView(ui.heading("Reset"));
        tools.addView(ui.text("Unpack the game data again if something is missing or damaged. Your setup and options are kept.", 14, Color.WHITE));
        tools.addView(ui.button("Reset game data", false, v -> confirm("Unpack the game data again?",
                "The data is copied from the app again, which takes a few seconds. Your setup and options are kept.", this::resetData)),
                ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(48)), 0, 8, 0, 0));
        tools.addView(ui.text("Put every setting back to how it was when the app was installed.", 14, Color.WHITE),
                ui.margins(full(), 0, 12, 0, 0));
        tools.addView(ui.button("Reset setup and options", false, v -> confirm("Reset setup and options?",
                "Teams, map, rules and all options go back to their defaults.", this::resetSettings)),
                ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(48)), 0, 8, 0, 0));

        LinearLayout about = ui.card();
        about.addView(ui.heading("About"));
        about.addView(ui.text(BuildConfig.APP_NAME + " " + BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")", 16, Color.WHITE));
        about.addView(infoLine("Game engine", "Hedgewars, " + BuildConfig.ENGINE_COMMIT + " on " + BuildConfig.ENGINE_BRANCH));
        about.addView(infoLine("App source", BuildConfig.APP_COMMIT));
        about.addView(infoLine("Built for", BuildConfig.BUILD_ABIS));
        about.addView(infoLine("This device", Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                + " (" + Build.VERSION.SDK_INT + "), " + Arrays.toString(Build.SUPPORTED_ABIS)));
        about.addView(ui.heading("Licence and source"));
        about.addView(ui.text(BuildConfig.APP_NAME + " is free software. The game engine is Hedgewars, under the GNU General Public "
                + "Licence version 2; the art and sound are under the GNU Free Documentation Licence. "
                + "You are entitled to the corresponding source code, which is the repository this app was built from, "
                + "engine and app together:", 14, Color.WHITE));
        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.VERTICAL);
        links.addView(ui.button("Source code (" + BuildConfig.SOURCE_URL.replaceFirst("^https?://", "") + ")", false,
                v -> open(BuildConfig.SOURCE_URL)), full());
        LinearLayout lic = new LinearLayout(this);
        lic.setOrientation(LinearLayout.HORIZONTAL);
        lic.addView(ui.button("Licence text", false, v -> showFile("COPYING", "Licence")), ui.weight(1f));
        lic.addView(ui.button("Credits", false, v -> showFile("CREDITS", "Credits")), ui.margins(ui.weight(1f), 8, 0, 0, 0));
        links.addView(lic, ui.margins(full(), 0, 6, 0, 0));
        about.addView(links, ui.margins(full(), 0, 8, 0, 0));
        twoColumns(tools, about);
    }

    private View infoLine(String k, String v) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, ui.dp(4), 0, ui.dp(2));
        TextView a = ui.text(k, 12, Ui.MUTED);
        TextView b = ui.text(v, 14, Color.WHITE);
        b.setTextIsSelectable(true);
        r.addView(a);
        r.addView(b);
        return r;
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    private void showFile(String name, String title) {
        StringBuilder sb = new StringBuilder();
        for (String line : GameData.readLines(new File(getFilesDir(), "Data/" + name))) sb.append(line).append('\n');
        String text = sb.toString();
        if (text.isEmpty()) text = "The file is not in the game data. Reset the game data to unpack it again.";
        TextView t = ui.text(text, 12, Color.WHITE);
        t.setTypeface(Typeface.MONOSPACE);
        t.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
        t.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        AlertDialog d = new AlertDialog.Builder(this).setTitle(title).setView(sv).setPositiveButton("Close", null).create();
        d.show();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        d.getWindow().setLayout(Math.min(dm.widthPixels - ui.dp(48), ui.dp(700)), Math.min(dm.heightPixels - ui.dp(24), ui.dp(380)));
    }

    private void confirm(String title, String message, Runnable yes) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton("Yes", (d, w) -> yes.run()).setNegativeButton("Cancel", null).show();
    }

    private void resetData() {
        // The setup screen unpacks the data again when it finds the marker gone.
        new File(getFilesDir(), "Data/.installed").delete();
        Toast.makeText(this, "The game data will be unpacked again.", Toast.LENGTH_LONG).show();
        finish();
    }

    private void resetSettings() {
        getSharedPreferences(Store.PREFS_NAME, MODE_PRIVATE).edit().clear().commit();
        o = new Options();
        Toast.makeText(this, "Setup and options reset.", Toast.LENGTH_LONG).show();
        finish();
    }
}
