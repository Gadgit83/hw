package org.hedgewars.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * Small toolkit for the setup and options screens: plain framework views, drawn in the app's green and
 * gold, with 48 dp touch targets. (No AndroidX in this build, so no Material components.)
 */
public final class Ui {
    public static final int BG = Color.rgb(34, 51, 38);
    public static final int PANEL = Color.rgb(46, 70, 52);
    public static final int PANEL_HI = Color.rgb(64, 96, 72);
    public static final int FIELD = Color.rgb(28, 42, 32);
    public static final int ACCENT = Color.rgb(242, 182, 50);
    public static final int ACCENT_DARK = Color.rgb(196, 140, 24);
    public static final int ON_ACCENT = Color.rgb(40, 30, 8);
    public static final int MUTED = Color.rgb(190, 212, 192);
    public static final int DANGER = Color.rgb(229, 115, 115);

    public final Context ctx;
    private final float density;
    public final Handler handler = new Handler(Looper.getMainLooper());

    public Ui(Context c) {
        ctx = c;
        density = c.getResources().getDisplayMetrics().density;
    }

    public int dp(int v) {
        return Math.round(v * density);
    }

    // Window and insets -------------------------------------------------------------------------

    /**
     * Draws behind the system bars and the camera cut-out; the content is then padded by the insets so
     * nothing sits under a bar or the cut-out and no black bands appear.
     */
    public static void edgeToEdge(Activity a) {
        Window w = a.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(BG));
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
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    /** Insets to keep clear of: system bars, the keyboard and the display cut-out: left, top, right, bottom. */
    public static int[] safeInsets(WindowInsets in) {
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

    public boolean keyboardShown(WindowInsets in) {
        if (Build.VERSION.SDK_INT >= 30) return in.isVisible(WindowInsets.Type.ime());
        return in.getSystemWindowInsetBottom() > dp(160);
    }

    public interface InsetsListener {
        void onInsets(int left, int top, int right, int bottom, boolean keyboard);
    }

    /** Calls back with the safe insets (cut-out, bars, keyboard) whenever they change. */
    public void trackInsets(View root, InsetsListener l) {
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int[] i = safeInsets(in);
            l.onInsets(i[0], i[1], i[2], i[3], keyboardShown(in));
            return in;
        });
    }

    public static void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    // Shapes ------------------------------------------------------------------------------------

    public GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    public GradientDrawable roundStroke(int fill, int stroke, int strokeDp, int radiusDp) {
        GradientDrawable g = round(fill, radiusDp);
        g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    public StateListDrawable pressable(int color, int pressed, int radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] { -android.R.attr.state_enabled }, round(Color.argb(90, 120, 130, 120), radiusDp));
        s.addState(new int[] { android.R.attr.state_pressed }, round(pressed, radiusDp));
        s.addState(new int[] {}, round(color, radiusDp));
        return s;
    }

    // Text and buttons --------------------------------------------------------------------------

    public TextView text(String s, float sp, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    public TextView heading(String s) {
        TextView t = text(s, 15, ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(4), dp(10), 0, dp(4));
        return t;
    }

    public TextView hint(String s) {
        TextView t = text(s, 13, MUTED);
        t.setPadding(dp(4), dp(2), dp(4), dp(2));
        return t;
    }

    public Button button(String s, boolean primary, View.OnClickListener l) {
        Button b = new Button(ctx);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(primary ? ON_ACCENT : Color.WHITE);
        b.setBackground(primary ? pressable(ACCENT, ACCENT_DARK, 10) : pressable(PANEL_HI, Color.rgb(84, 120, 92), 10));
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        b.setMinWidth(dp(48));
        b.setMinimumWidth(dp(48));
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setStateListAnimator(null);
        if (primary) b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setOnClickListener(l);
        return b;
    }

    public LinearLayout card() {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(PANEL, 14));
        c.setPadding(dp(14), dp(8), dp(14), dp(12));
        return c;
    }

    public LinearLayout row() {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    public LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p, int l, int t, int r, int b) {
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    public EditText field(String value, String hint) {
        EditText e = new EditText(ctx);
        e.setText(value);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.rgb(120, 146, 124));
        e.setTextSize(16);
        e.setBackground(roundStroke(FIELD, Color.rgb(90, 124, 98), 1, 8));
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setMinHeight(dp(48));
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        return e;
    }

    public View swatch(int rgb, int sizeDp, boolean selected) {
        View v = new View(ctx);
        GradientDrawable g = round(0xff000000 | rgb, 10);
        g.setStroke(dp(selected ? 3 : 1), selected ? Color.WHITE : Color.argb(120, 255, 255, 255));
        v.setBackground(g);
        v.setLayoutParams(lp(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    // Tabs --------------------------------------------------------------------------------------

    public static final class Tabs {
        public final LinearLayout view;
        private final Button[] buttons;
        private final Ui ui;

        Tabs(Ui ui, String[] titles, IntConsumer on) {
            this.ui = ui;
            view = ui.row();
            view.setBackground(ui.round(Color.rgb(28, 42, 32), 12));
            view.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
            buttons = new Button[titles.length];
            for (int i = 0; i < titles.length; i++) {
                final int idx = i;
                Button b = ui.button(titles[i], false, v -> {
                    select(idx);
                    on.accept(idx);
                });
                b.setPadding(ui.dp(18), 0, ui.dp(18), 0);
                buttons[i] = b;
                view.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
        }

        public void select(int idx) {
            for (int i = 0; i < buttons.length; i++) {
                boolean on = i == idx;
                buttons[i].setBackground(on ? ui.pressable(ACCENT, ACCENT_DARK, 9)
                        : ui.pressable(Color.TRANSPARENT, PANEL_HI, 9));
                buttons[i].setTextColor(on ? ON_ACCENT : Color.WHITE);
                buttons[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
        }
    }

    public Tabs tabs(String[] titles, int selected, IntConsumer on) {
        Tabs t = new Tabs(this, titles, on);
        t.select(selected);
        return t;
    }

    // Value rows --------------------------------------------------------------------------------

    /** A number with minus and plus buttons (hold to repeat) and a tap on the value to type one in. */
    public LinearLayout stepper(String label, int min, int max, int step, int value, IntFunction<String> fmt, IntConsumer on) {
        final int[] v = { Math.max(min, Math.min(max, value)) };
        LinearLayout r = row();
        r.setMinimumHeight(dp(52));
        if (!label.isEmpty()) r.addView(text(label, 15, Color.WHITE), weight(1f));
        final TextView val = text(fmt.apply(v[0]), 16, Color.WHITE);
        val.setGravity(Gravity.CENTER);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setBackground(roundStroke(FIELD, Color.rgb(90, 124, 98), 1, 8));
        val.setMinWidth(dp(76));
        val.setMinHeight(dp(44));
        val.setPadding(dp(8), 0, dp(8), 0);
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> val.setText(fmt.apply(v[0]));
        final IntConsumer set = nv -> {
            int c = Math.max(min, Math.min(max, nv));
            if (c == v[0]) return;
            v[0] = c;
            refresh[0].run();
            on.accept(c);
        };
        Button minus = repeatButton("−", () -> set.accept(v[0] - step));
        Button plus = repeatButton("+", () -> set.accept(v[0] + step));
        val.setOnClickListener(x -> typeNumber(label, min, max, v[0], set));
        r.addView(minus, lp(dp(48), dp(48)));
        r.addView(val, margins(lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)), 6, 0, 6, 0));
        r.addView(plus, lp(dp(48), dp(48)));
        return r;
    }

    private Button repeatButton(String s, Runnable action) {
        Button b = button(s, false, null);
        b.setTextSize(20);
        b.setPadding(0, 0, 0, 0);
        final Runnable[] loop = new Runnable[1];
        final int[] count = { 0 };
        loop[0] = () -> {
            action.run();
            count[0]++;
            handler.postDelayed(loop[0], count[0] > 12 ? 40 : 110);
        };
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!v.isEnabled()) return false;
                    v.setPressed(true);
                    action.run();
                    count[0] = 0;
                    handler.postDelayed(loop[0], 420);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    handler.removeCallbacks(loop[0]);
                    return true;
                default:
                    return true;
            }
        });
        return b;
    }

    private void typeNumber(String label, int min, int max, int current, IntConsumer set) {
        final EditText e = new EditText(ctx);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | (min < 0 ? InputType.TYPE_NUMBER_FLAG_SIGNED : 0));
        e.setText(Integer.toString(current));
        e.setSelectAllOnFocus(true);
        AlertDialog d = new AlertDialog.Builder(ctx)
                .setTitle((label.isEmpty() ? "Value" : label) + " (" + min + " to " + max + ")")
                .setView(e)
                .setPositiveButton("OK", (dlg, w) -> {
                    try {
                        set.accept(Integer.parseInt(e.getText().toString().trim()));
                    } catch (NumberFormatException ignored) { }
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        d.show();
    }

    /** A label with the current choice on a button; tapping opens a list of the options. The button shows the new choice at once. */
    public LinearLayout choice(String label, List<String> options, int selected, IntConsumer on) {
        LinearLayout r = row();
        r.setMinimumHeight(dp(52));
        r.addView(text(label, 15, Color.WHITE), weight(1f));
        final int[] sel = { options.isEmpty() ? 0 : Math.max(0, Math.min(selected, options.size() - 1)) };
        final Button b = button(options.isEmpty() ? "" : options.get(sel[0]) + "  \u25be", false, null);
        b.setOnClickListener(v -> pickFromList(label, options, sel[0], which -> {
            sel[0] = which;
            b.setText(options.get(which) + "  \u25be");
            on.accept(which);
        }));
        r.addView(b, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        return r;
    }

    public void pickFromList(String title, List<String> options, int selected, IntConsumer on) {
        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setSingleChoiceItems(options.toArray(new String[0]), selected, (dlg, which) -> {
                    dlg.dismiss();
                    on.accept(which);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public Switch toggle(String label, boolean value, java.util.function.Consumer<Boolean> on) {
        Switch s = new Switch(ctx);
        s.setText(label);
        s.setTextSize(15);
        s.setTextColor(Color.WHITE);
        s.setChecked(value);
        s.setMinHeight(dp(52));
        s.setPadding(0, 0, dp(4), 0);
        s.setOnCheckedChangeListener((b, c) -> on.accept(c));
        return s;
    }

    /** A labelled slider with a value readout. */
    public LinearLayout slider(String label, int min, int max, int value, IntFunction<String> fmt, IntConsumer on) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = row();
        top.addView(text(label, 15, Color.WHITE), weight(1f));
        final TextView val = text(fmt.apply(value), 15, ACCENT);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(val);
        box.addView(top);
        SeekBar sb = new SeekBar(ctx);
        sb.setMax(max - min);
        sb.setProgress(value - min);
        sb.setPadding(dp(14), dp(6), dp(14), dp(6));
        sb.setMinimumHeight(dp(44));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                val.setText(fmt.apply(p + min));
                if (fromUser) on.accept(p + min);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        return box;
    }

    // Grid picker -------------------------------------------------------------------------------

    public interface IconLoader {
        Bitmap load(String key);
    }

    /** A dialog with a grid of pictures and names; returns the chosen key. */
    public void pickGrid(String title, List<String> keys, IconLoader icons, String selected, int cellDp, boolean wide,
                         java.util.function.Consumer<String> on) {
        final GridView grid = new GridView(ctx);
        grid.setColumnWidth(dp(cellDp));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(4));
        grid.setHorizontalSpacing(dp(4));
        grid.setPadding(dp(8), dp(8), dp(8), dp(8));
        final AlertDialog[] dlg = new AlertDialog[1];
        final java.util.concurrent.ExecutorService loader = java.util.concurrent.Executors.newSingleThreadExecutor();
        final java.util.concurrent.ConcurrentHashMap<String, Bitmap> loaded = new java.util.concurrent.ConcurrentHashMap<>();
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return keys.size(); }
            @Override public Object getItem(int i) { return keys.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                LinearLayout cell = new LinearLayout(ctx);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(4), dp(6), dp(4), dp(6));
                String k = keys.get(i);
                boolean sel = k.equals(selected);
                cell.setBackground(sel ? roundStroke(PANEL_HI, ACCENT, 2, 10) : round(Color.TRANSPARENT, 10));
                ImageView iv = new ImageView(ctx);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                if (icons != null) {
                    Bitmap ready = loaded.get(k);
                    if (ready != null) {
                        iv.setImageBitmap(ready);
                    } else {
                        // Decode off the UI thread (an image map preview can take a moment); the cell is filled in when ready.
                        iv.setTag(k);
                        loader.execute(() -> {
                            Bitmap b = icons.load(k);
                            if (b == null) return;
                            loaded.put(k, b);
                            handler.post(() -> {
                                if (k.equals(iv.getTag())) iv.setImageBitmap(b);
                            });
                        });
                    }
                }
                cell.addView(iv, lp(wide ? dp(cellDp - 16) : dp(52), dp(52)));
                TextView t = text(k.replace('_', ' '), 12, Color.WHITE);
                t.setGravity(Gravity.CENTER);
                t.setMaxLines(2);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                cell.addView(t);
                cell.setMinimumHeight(dp(48));
                return cell;
            }
        });
        grid.setOnItemClickListener((p, v, i, id) -> {
            dlg[0].dismiss();
            on.accept(keys.get(i));
        });
        dlg[0] = new AlertDialog.Builder(ctx).setTitle(title).setView(grid).setNegativeButton("Cancel", null).create();
        dlg[0].setOnDismissListener(d -> loader.shutdownNow());
        dlg[0].show();
        int sel = keys.indexOf(selected);
        if (sel > 0) grid.post(() -> grid.setSelection(sel));
        // Use most of the screen so many pictures are visible at once.
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        dlg[0].getWindow().setLayout(Math.min(dm.widthPixels - dp(48), dp(wide ? 760 : 640)),
                Math.min(dm.heightPixels - dp(24), dp(360)));
    }
}
