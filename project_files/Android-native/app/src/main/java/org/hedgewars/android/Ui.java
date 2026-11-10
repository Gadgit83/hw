package org.hedgewars.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
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
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * Small toolkit for the setup and options screens: plain framework views, drawn in the app's green and
 * gold. Every control has a touch area of at least 48 dp, but is drawn smaller than that (the background is
 * inset), so the screens stay compact. (No AndroidX in this build, so no Material components.)
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
    /** Side gutter of every screen, in dp: the title, the cards and the bottom bar all start this far inside the safe area. */
    public static final int GUTTER = 12;
    public static final int DANGER = Color.rgb(229, 115, 115);

    public final Context ctx;
    private final float density;
    public final Handler handler = new Handler(Looper.getMainLooper());

    public Ui(Context c) {
        ctx = c;
        density = c.getResources().getDisplayMetrics().density;
    }

    /** A dialog builder in the app's dialog style, whatever the activity's own theme is. */
    public static AlertDialog.Builder dialog(Context c) {
        return new AlertDialog.Builder(c, R.style.HpDialog);
    }

    /**
     * The scroll view in a frame that shows "More below" at its bottom edge while there is more to scroll to, so a list that is
     * cut off by the screen is not mistaken for a complete one. The scroll view must already hold its content.
     */
    public FrameLayout scrollWithHint(ScrollView sv) {
        FrameLayout f = new FrameLayout(ctx);
        f.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final TextView hint = text("More below \u25be", 12, ACCENT);
        hint.setTypeface(Typeface.DEFAULT_BOLD);
        hint.setPadding(dp(12), dp(3), dp(12), dp(3));
        hint.setBackground(round(Color.argb(225, 20, 30, 24), 12));
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hp.bottomMargin = dp(6);
        f.addView(hint, hp);
        final Runnable update = () -> {
            View c = sv.getChildAt(0);
            hint.setVisibility(c != null && sv.getScrollY() + sv.getHeight() < c.getHeight() - dp(6) ? View.VISIBLE : View.GONE);
        };
        sv.getViewTreeObserver().addOnScrollChangedListener(update::run);
        sv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> update.run());
        if (sv.getChildAt(0) != null) sv.getChildAt(0).addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> update.run());
        return f;
    }

    /** A small rounded label, for a state or a count. */
    public TextView chip(String s, int fill, int textColor) {
        TextView t = text(s, 13, textColor);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setPadding(dp(10), dp(3), dp(10), dp(3));
        t.setBackground(round(fill, 12));
        return t;
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
        hideBars(a);
    }

    /**
     * Full screen: the status and navigation bars stay hidden (a swipe from the edge shows them for a moment).
     * Safe to call at any point of the activity life cycle, including from onCreate before setContentView.
     * Window.getInsetsController() dereferences the decor view, which does not exist before setContentView
     * (NullPointerException on Android 11 and later, the 0.2.1 crash on launch), so the controller is taken from the
     * decor view instead. That creates the decor view on demand. Before the window is attached the decor view has no
     * controller yet; the legacy system UI flags then apply the same state, and onWindowFocusChanged calls this again.
     */
    public static void hideBars(Activity a) {
        Window w = a.getWindow();
        View decor = w.getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = null;
            try {
                c = decor.getWindowInsetsController();
            } catch (RuntimeException e) {
                // not attached yet: fall through to the flags
            }
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                return;
            }
        }
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
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
        s.addState(new int[] { -android.R.attr.state_enabled }, round(Color.rgb(58, 82, 64), radiusDp));
        s.addState(new int[] { android.R.attr.state_pressed }, round(pressed, radiusDp));
        s.addState(new int[] {}, round(color, radiusDp));
        return s;
    }

    /** A pressable shape drawn smaller than its view: the view keeps the full touch area. */
    public Drawable inset(Drawable d, int hDp, int vDp) {
        return new InsetDrawable(d, dp(hDp), dp(vDp), dp(hDp), dp(vDp));
    }

    // Text and buttons --------------------------------------------------------------------------

    /** The "TEST BUILD" marker of the test profile (setup screen and About). */
    public TextView testBadge() {
        TextView t = text("TEST BUILD", 11, Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setPadding(dp(8), dp(1), dp(8), dp(1));
        t.setBackground(round(Color.rgb(198, 40, 40), 8));
        return t;
    }

    public TextView text(String s, float sp, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    public TextView heading(String s) {
        TextView t = text(s, 12, ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        t.setSingleLine(true);
        t.setPadding(dp(2), dp(4), 0, dp(2));
        return t;
    }

    public TextView hint(String s) {
        TextView t = text(s, 12, MUTED);
        t.setPadding(dp(2), dp(1), dp(2), dp(1));
        return t;
    }

    /** Button height: 48 dp to touch, 36 dp to look at. */
    public static final int TOUCH_DP = 48, LOOK_DP = 36;

    public Button button(String s, boolean primary, View.OnClickListener l) {
        Button b = new Button(ctx);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setTextColor(new android.content.res.ColorStateList(
                new int[][] { { -android.R.attr.state_enabled }, {} },
                new int[] { Color.rgb(170, 188, 172), primary ? ON_ACCENT : Color.WHITE }));
        b.setBackground(inset(primary ? pressable(ACCENT, ACCENT_DARK, 9) : pressable(PANEL_HI, Color.rgb(84, 120, 92), 9),
                0, (TOUCH_DP - LOOK_DP) / 2));
        b.setMinHeight(dp(TOUCH_DP));
        b.setMinimumHeight(dp(TOUCH_DP));
        b.setMinWidth(dp(TOUCH_DP));
        b.setMinimumWidth(dp(TOUCH_DP));
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setStateListAnimator(null);
        if (primary) b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setOnClickListener(l);
        return b;
    }

    /** Which drawing a {@link Glyph} shows. */
    public static final int GLYPH_SLIDERS = 0, GLYPH_CROSS = 1;

    /** A small line drawing used as a button face (an icon font may not be on the phone). */
    public static final class Glyph extends View {
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        Glyph(Context c, int kind) {
            super(c);
            this.kind = kind;
            density = c.getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(2f * density);
        }

        @Override protected void onDraw(Canvas cv) {
            paint.setColor(isEnabled() ? Color.WHITE : Color.argb(110, 255, 255, 255));
            float cx = getWidth() / 2f, cy = getHeight() / 2f, u = 9f * density;
            if (kind == GLYPH_CROSS) {
                float k = 0.62f * u;
                cv.drawLine(cx - k, cy - k, cx + k, cy + k, paint);
                cv.drawLine(cx - k, cy + k, cx + k, cy - k, paint);
            } else {
                float[] ys = { -0.62f * u, 0f, 0.62f * u };
                float[] ks = { -0.35f, 0.4f, -0.1f };
                for (int i = 0; i < 3; i++) {
                    paint.setStyle(Paint.Style.STROKE);
                    cv.drawLine(cx - u, cy + ys[i], cx + u, cy + ys[i], paint);
                    paint.setStyle(Paint.Style.FILL);
                    cv.drawCircle(cx + ks[i] * u, cy + ys[i], 2.6f * density, paint);
                }
                paint.setStyle(Paint.Style.STROKE);
            }
        }
    }

    /** A square button with a drawing on it: 48 dp to touch, 36 dp to look at. */
    public Glyph iconButton(int glyph, String description, View.OnClickListener l) {
        Glyph g = new Glyph(ctx, glyph);
        g.setBackground(inset(pressable(PANEL_HI, Color.rgb(84, 120, 92), 9), (TOUCH_DP - LOOK_DP) / 2, (TOUCH_DP - LOOK_DP) / 2));
        g.setContentDescription(description);
        g.setClickable(true);
        g.setFocusable(true);
        g.setMinimumWidth(dp(TOUCH_DP));
        g.setMinimumHeight(dp(TOUCH_DP));
        g.setOnClickListener(l);
        return g;
    }

    /** A team-colour square: 48 dp to touch, 30 dp to look at. */
    public Button swatchButton(int rgb, String description, View.OnClickListener l) {
        Button b = new Button(ctx);
        b.setBackground(inset(roundStroke(0xff000000 | rgb, Color.WHITE, 2, 8), 9, 9));
        b.setContentDescription(description);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }

    public LinearLayout card() {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(PANEL, 12));
        c.setPadding(dp(12), dp(6), dp(12), dp(8));
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

    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
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
        e.setTextSize(15);
        e.setBackground(roundStroke(FIELD, Color.rgb(90, 124, 98), 1, 8));
        e.setPadding(dp(10), 0, dp(10), 0);
        e.setMinHeight(dp(40));
        e.setMinimumHeight(dp(40));
        e.setEllipsize(android.text.TextUtils.TruncateAt.END);
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
            view.setBackground(ui.round(Color.rgb(28, 42, 32), 11));
            view.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
            buttons = new Button[titles.length];
            for (int i = 0; i < titles.length; i++) {
                final int idx = i;
                Button b = ui.button(titles[i], false, v -> {
                    select(idx);
                    on.accept(idx);
                });
                // The segment is the touch area as well as the picture: 40 dp high plus the frame.
                b.setMinHeight(ui.dp(38));
                b.setMinimumHeight(ui.dp(38));
                b.setPadding(ui.dp(14), 0, ui.dp(14), 0);
                buttons[i] = b;
                view.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
        }

        public void select(int idx) {
            for (int i = 0; i < buttons.length; i++) {
                boolean on = i == idx;
                buttons[i].setBackground(on ? ui.pressable(ACCENT, ACCENT_DARK, 8)
                        : ui.pressable(Color.TRANSPARENT, PANEL_HI, 8));
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
        return stepper(label, min, max, value, 52, 44, fmt, (cur, dir) -> cur + dir * step, on);
    }

    /** Computes the next value from the current one, going up (+1) or down (-1); the result is limited to the range. */
    public interface Stepping {
        int next(int current, int direction);
    }

    /** A stepper whose step is not constant (turn time: by 5 up to 300, then straight to "unlimited"). */
    public LinearLayout stepper(String label, int min, int max, int value, int valueWidthDp, int buttonDp,
                                IntFunction<String> fmt, Stepping stepping, IntConsumer on) {
        final int[] v = { Math.max(min, Math.min(max, value)) };
        LinearLayout r = row();
        r.setMinimumHeight(dp(TOUCH_DP));
        if (!label.isEmpty()) {
            TextView l = text(label, 14, Color.WHITE);
            l.setSingleLine(true);
            l.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.addView(l, weight(1f));
        }
        final TextView val = text(fmt.apply(v[0]), 14, Color.WHITE);
        val.setGravity(Gravity.CENTER);
        val.setSingleLine(true);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setBackground(roundStroke(FIELD, Color.rgb(90, 124, 98), 1, 8));
        val.setMinWidth(dp(valueWidthDp));
        val.setMinHeight(dp(LOOK_DP));
        val.setPadding(dp(6), 0, dp(6), 0);
        final IntConsumer set = nv -> {
            int c = Math.max(min, Math.min(max, nv));
            if (c == v[0]) return;
            v[0] = c;
            val.setText(fmt.apply(c));
            on.accept(c);
        };
        Button minus = repeatButton("\u2212", () -> set.accept(stepping.next(v[0], -1)));
        Button plus = repeatButton("+", () -> set.accept(stepping.next(v[0], 1)));
        val.setOnClickListener(x -> typeNumber(label, min, max, v[0], set));
        r.addView(minus, lp(dp(buttonDp), dp(TOUCH_DP)));
        r.addView(val, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(LOOK_DP)));
        r.addView(plus, lp(dp(buttonDp), dp(TOUCH_DP)));
        return r;
    }

    private Button repeatButton(String s, Runnable action) {
        Button b = button(s, false, null);
        b.setTextSize(18);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(inset(pressable(PANEL_HI, Color.rgb(84, 120, 92), 9), 2, (TOUCH_DP - LOOK_DP) / 2));
        b.setPadding(0, 0, 0, 0); // an inset background brings its own padding
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
        AlertDialog d = dialog(ctx)
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
        return choice(label, options, options, selected, on);
    }

    /** As {@link #choice(String, List, int, IntConsumer)}, but the button shows a short form and the list the full text. */
    public LinearLayout choice(String label, List<String> shortNames, List<String> options, int selected, IntConsumer on) {
        LinearLayout r = row();
        r.setMinimumHeight(dp(TOUCH_DP));
        TextView l = text(label, 14, Color.WHITE);
        l.setSingleLine(true);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(l, weight(1f));
        final int[] sel = { options.isEmpty() ? 0 : Math.max(0, Math.min(selected, options.size() - 1)) };
        final Button b = choiceButton(options.isEmpty() ? "" : shortNames.get(sel[0]));
        b.setOnClickListener(v -> pickFromList(label, options, sel[0], which -> {
            sel[0] = which;
            b.setText(shortNames.get(which) + "  \u25be");
            on.accept(which);
        }));
        r.addView(b, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(TOUCH_DP)));
        return r;
    }

    /** A button that shows the current choice with a small arrow, on one line, cut with an ellipsis if it is long. */
    public Button choiceButton(String current) {
        Button b = button(current + "  \u25be", false, null);
        b.setMaxWidth(dp(280));
        return b;
    }

    public void pickFromList(String title, List<String> options, int selected, IntConsumer on) {
        dialog(ctx)
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
        s.setTextSize(14);
        s.setTextColor(Color.WHITE);
        s.setChecked(value);
        s.setMinHeight(dp(TOUCH_DP));
        s.setMinimumHeight(dp(TOUCH_DP));
        s.setPadding(0, 0, dp(2), 0);
        s.setSwitchPadding(dp(20));
        s.setOnCheckedChangeListener((b, c) -> on.accept(c));
        return s;
    }

    /** A labelled slider with a value readout. */
    public LinearLayout slider(String label, int min, int max, int value, IntFunction<String> fmt, IntConsumer on) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = row();
        top.addView(text(label, 14, Color.WHITE), weight(1f));
        final TextView val = text(fmt.apply(value), 14, ACCENT);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(val);
        box.setPadding(0, dp(2), 0, 0);
        box.addView(top);
        SeekBar sb = new SeekBar(ctx);
        sb.setMax(max - min);
        sb.setProgress(value - min);
        sb.setPadding(dp(14), dp(4), dp(14), dp(4));
        // visible when switched off as well: the default tints of a disabled bar vanish on the green panel
        int[][] st = { { -android.R.attr.state_enabled }, {} };
        sb.setProgressTintList(new android.content.res.ColorStateList(st, new int[] { Color.rgb(130, 154, 134), ACCENT }));
        sb.setProgressBackgroundTintList(new android.content.res.ColorStateList(st, new int[] { Color.rgb(100, 126, 106), Color.rgb(96, 130, 104) }));
        sb.setThumbTintList(new android.content.res.ColorStateList(st, new int[] { Color.rgb(170, 190, 174), ACCENT }));
        sb.setMinimumHeight(dp(36));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                val.setText(fmt.apply(p + min));
                if (fromUser) on.accept(p + min);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        return box;
    }

    // Pictures ----------------------------------------------------------------------------------

    private static final java.util.concurrent.ExecutorService PICTURES = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "pictures");
        t.setDaemon(true);
        return t;
    });

    /**
     * Puts the picture made by {@code load} into the view without holding up the UI thread: decoding the game's
     * artwork (a map preview can be large) happens on a worker, and the result is dropped if the view has been
     * given another picture in the meantime.
     */
    public void loadPicture(ImageView iv, java.util.concurrent.Callable<Bitmap> load) {
        final Object ticket = new Object();
        iv.setTag(ticket);
        PICTURES.execute(() -> {
            Bitmap b;
            try {
                b = load.call();
            } catch (Exception e) {
                return;
            }
            if (b == null) return;
            handler.post(() -> {
                if (iv.getTag() == ticket) iv.setImageBitmap(b);
            });
        });
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
        dlg[0] = dialog(ctx).setTitle(title).setView(grid).setNegativeButton("Cancel", null).create();
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
