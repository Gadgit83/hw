package org.hedgewars.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The controls editor (issue #21): laid over the running game, which stays behind a dimmed picture, so the controls can be placed
 * over the real view. Each control is an outline: drag it to move it, drag its bottom-right corner to resize it (the top-left
 * corner stays put). Reset to default, Cancel and Save are in a bar in the top-left corner. Nothing is saved or sent to the
 * engine until Save; the places come from the engine (its report of every control, see GameActivity).
 */
public class ControlsLayoutView extends FrameLayout {

    /** What the editor does with its result. The places are a layout string (TouchLayout), "" for the default layout. */
    public interface Listener {
        void onSave(String spec);
        void onCancel();
    }

    /** One control: centre x and y, size s (fractions of the default size), and its default place and drawn size. */
    private static final class Ctl {
        final String name, label;
        double x, y, s;
        double defX, defY, baseW, baseH;

        Ctl(String name, String label) {
            this.name = name;
            this.label = label;
        }

        double w() { return baseW * s; }
        double h() { return baseH * s; }
    }

    private final Listener listener;
    private final boolean twoJump;
    private final List<Ctl> controls = new ArrayList<>();
    private final float density;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimPaint = new Paint();
    private final TextView hint;
    private final Button save;
    private boolean ready;

    // the control being dragged, and the screen position and size it had when the drag began
    private int drag = -1;
    private boolean resizing;
    private float lastX, lastY, downX;
    private double startX, startY, startS;

    public ControlsLayoutView(Context context, boolean twoJump, Listener listener) {
        super(context);
        this.listener = listener;
        this.twoJump = twoJump;
        density = context.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setClickable(true);

        scrimPaint.setColor(0x99000000);
        fillPaint.setColor(0x33F2B632);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2 * density);
        strokePaint.setColor(Color.rgb(242, 182, 50));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(13 * density);
        textPaint.setTextAlign(Paint.Align.CENTER);

        Ui ui = new Ui(context);
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        panel.setBackground(ui.round(Ui.PANEL, 10));
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(ui.button("Reset to default", false, v -> resetToDefault()),
                Ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT, ui.dp(Ui.TOUCH_DP)));
        bar.addView(ui.button("Cancel", false, v -> listener.onCancel()),
                ui.margins(Ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT, ui.dp(Ui.TOUCH_DP)), 8, 0, 0, 0));
        save = ui.button("Save", true, v -> listener.onSave(currentSpec()));
        save.setEnabled(false);
        bar.addView(save, ui.margins(Ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT, ui.dp(Ui.TOUCH_DP)), 8, 0, 0, 0));
        panel.addView(bar);
        hint = ui.hint("Reading the controls from the game...");
        panel.addView(hint, ui.margins(Ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT), 0, 6, 0, 0));
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.setMargins(ui.dp(12), ui.dp(12), 0, 0);
        addView(panel, lp);
    }

    /**
     * The engine's places are known: defaults are its own places, reported with the default layout; the saved layout (a layout
     * string, "" for none) moves the controls from there.
     */
    public void setDefaults(Map<String, TouchLayout.Place> reported, String savedLayout) {
        Map<String, double[]> saved = TouchLayout.parse(savedLayout);
        controls.clear();
        for (int i = 0; i < TouchLayout.NAMES.length; i++) {
            String name = TouchLayout.NAMES[i];
            if (name.equals("highjump") && !twoJump) continue;
            TouchLayout.Place p = reported.get(name);
            if (p == null || p.s <= 0) continue;
            Ctl c = new Ctl(name, TouchLayout.LABELS[i]);
            c.defX = p.x;
            c.defY = p.y;
            c.baseW = p.w / p.s;
            c.baseH = p.h / p.s;
            double[] v = saved.get(name);
            c.x = v != null ? v[0] : c.defX;
            c.y = v != null ? v[1] : c.defY;
            c.s = v != null ? v[2] : 1.0;
            controls.add(c);
        }
        ready = !controls.isEmpty();
        save.setEnabled(ready);
        hint.setText("Drag a control to move it. Drag its corner to make it bigger or smaller.");
        invalidate();
    }

    /** The game did not report its places in time: nothing can be placed, and Save stays off. */
    public void setError(String message) {
        hint.setText(message);
        save.setEnabled(false);
    }

    private void resetToDefault() {
        for (Ctl c : controls) {
            c.x = c.defX;
            c.y = c.defY;
            c.s = 1.0;
        }
        invalidate();
    }

    /** The layout to save: "" when every control is at its default place, else all the places. */
    String currentSpec() {
        boolean same = true;
        Map<String, double[]> places = new java.util.LinkedHashMap<>();
        for (Ctl c : controls) {
            places.put(c.name, new double[] { c.x, c.y, c.s });
            if (Math.abs(c.x - c.defX) > TouchLayout.SAME || Math.abs(c.y - c.defY) > TouchLayout.SAME
                    || Math.abs(c.s - 1.0) > TouchLayout.SAME) {
                same = false;
            }
        }
        return same ? "" : TouchLayout.format(places);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);
        if (!ready) return;
        float handle = 9 * density;
        for (Ctl c : controls) {
            RectF r = rect(c);
            canvas.drawRoundRect(r, 8 * density, 8 * density, fillPaint);
            canvas.drawRoundRect(r, 8 * density, 8 * density, strokePaint);
            canvas.drawCircle(r.right, r.bottom, handle, strokePaint);
            canvas.drawText(c.label, r.centerX(), r.centerY() + textPaint.getTextSize() / 3, textPaint);
        }
    }

    private RectF rect(Ctl c) {
        float cx = (float) (c.x * getWidth()), cy = (float) (c.y * getHeight());
        float hw = (float) (c.w() * getWidth() / 2), hh = (float) (c.h() * getHeight() / 2);
        return new RectF(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    private boolean onHandle(Ctl c, float x, float y) {
        RectF r = rect(c);
        float reach = 26 * density;
        return Math.abs(x - r.right) <= reach && Math.abs(y - r.bottom) <= reach;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!ready) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                drag = -1;
                // the last control drawn is on top, so it is looked at first
                for (int i = controls.size() - 1; i >= 0 && drag < 0; i--) {
                    Ctl c = controls.get(i);
                    if (onHandle(c, e.getX(), e.getY())) {
                        drag = i;
                        resizing = true;
                    } else if (rect(c).contains(e.getX(), e.getY())) {
                        drag = i;
                        resizing = false;
                    }
                }
                if (drag >= 0) {
                    Ctl c = controls.get(drag);
                    lastX = e.getX();
                    lastY = e.getY();
                    downX = e.getX();
                    // where the control was when the drag began: its top-left corner (fractions) and its scale
                    startX = c.x - c.w() / 2;
                    startY = c.y - c.h() / 2;
                    startS = c.s;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (drag < 0) return true;
                Ctl c = controls.get(drag);
                if (resizing) {
                    // the bottom-right corner follows the finger; the top-left corner stays where it was
                    double startPx = startS * c.baseW * getWidth();
                    double targetPx = Math.max(36 * density, startPx + (e.getX() - downX));
                    c.s = TouchLayout.clamp(startS * targetPx / startPx, TouchLayout.MIN_SCALE, TouchLayout.MAX_SCALE);
                    c.x = startX + c.w() / 2;
                    c.y = startY + c.h() / 2;
                } else {
                    c.x += (e.getX() - lastX) / getWidth();
                    c.y += (e.getY() - lastY) / getHeight();
                    lastX = e.getX();
                    lastY = e.getY();
                }
                keepOnScreen(c);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                drag = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    /** The control stays inside the screen; when it is larger than the screen it is centred. */
    private void keepOnScreen(Ctl c) {
        double hw = c.w() / 2, hh = c.h() / 2;
        c.x = hw * 2 >= 1 ? 0.5 : TouchLayout.clamp(c.x, hw, 1 - hw);
        c.y = hh * 2 >= 1 ? 0.5 : TouchLayout.clamp(c.y, hh, 1 - hh);
    }
}
