package org.hedgewars.android;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The default places of the touch controls, computed in Java for a screen that the engine is not running on (the controls
 * layout editor, issue #26, opened from the Options screen over a static picture). This mirrors the engine's default
 * placement, so the editor shows what the engine would report: InitTouchInterface, InitTouchLegacy (every shape value 0),
 * InitTouchPlaced (any shape value non-zero), SafeInset, VInset, HInset, CornerClearPx, PlaceJumpPair (--two-jump-buttons,
 * --swap-fire-jump) and reportTouchControl and ReportTouchLayout, all in hedgewars/uWorld.pas. Its arithmetic follows the
 * generated C: Round half away from zero, div and shr as integers, the UI scale and the corner clearance in single precision.
 * The golden test (TouchDefaultsTest, data from scripts/touch-defaults-golden.sh) holds it equal to the engine, to the
 * thousandth. Re-run the script when hedgewars/uWorld.pas's touch placement changes.
 * Pure Java (no Android classes), like TouchLayout.java, so it can be tested on a plain JVM.
 */
public final class TouchDefaults {
    private TouchDefaults() { }

    /** The sprite sizes in pixels (the PNG sizes in Data/Graphics/Buttons), before the button scale. */
    private static final int FIRE_W = 128, FIRE_H = 128;
    private static final int ARROW_W = 100, ARROW_H = 100;
    private static final int JUMP_W = 128, JUMP_H = 128;
    private static final int HIGHJUMP_W = 128, HIGHJUMP_H = 128;
    private static final int WEAPONS_W = 128, WEAPONS_H = 128;
    private static final int PAUSE_W = 120, PAUSE_H = 100;
    private static final int TIMER_W = 128, TIMER_H = 128;
    private static final int BOUNCE_W = 128, BOUNCE_H = 128;
    /** buttonScale = 1 / cDefaultZoomLevel (uWorld.pas), so every sprite is drawn at half its PNG size. */
    private static final int BUTTON_DIV = 2;

    /**
     * The inputs of the default placement: the real screen, the UI scale, the safe area and the screen shape, and the Options
     * flags. screenW is the wider side and screenH the shorter one (landscape). The insets, corners, margin and cut-out rows
     * are in real pixels, as the engine's --inset-*, --screen-shape take them; uiScalePct is --ui-scale (100 is desktop).
     */
    public static final class Inputs {
        public final int screenW, screenH, uiScalePct;
        public final int insetL, insetT, insetR, insetB;
        public final int cornerTL, cornerTR, cornerBL, cornerBR, margin;
        public final int cutLY0, cutLY1, cutRY0, cutRY1;
        public final int jumpInsetPct;
        public final boolean twoJump, swapFireJump;

        public Inputs(int screenW, int screenH, int uiScalePct,
                      int insetL, int insetT, int insetR, int insetB,
                      int cornerTL, int cornerTR, int cornerBL, int cornerBR, int margin,
                      int cutLY0, int cutLY1, int cutRY0, int cutRY1,
                      int jumpInsetPct, boolean twoJump, boolean swapFireJump) {
            this.screenW = screenW;
            this.screenH = screenH;
            this.uiScalePct = uiScalePct;
            this.insetL = insetL;
            this.insetT = insetT;
            this.insetR = insetR;
            this.insetB = insetB;
            this.cornerTL = cornerTL;
            this.cornerTR = cornerTR;
            this.cornerBL = cornerBL;
            this.cornerBR = cornerBR;
            this.margin = margin;
            this.cutLY0 = cutLY0;
            this.cutLY1 = cutLY1;
            this.cutRY0 = cutRY0;
            this.cutRY1 = cutRY1;
            this.jumpInsetPct = jumpInsetPct;
            this.twoJump = twoJump;
            this.swapFireJump = swapFireJump;
        }
    }

    /**
     * The inputs for a display, from the 15 values of DisplayCalc.measure in its order: {HUD %, menu %, insL, insT, insR, insB,
     * corner TL, TR, BL, BR, margin, cut-out left rows 0 and 1, cut-out right rows 0 and 1}. The launcher (native/launcher/hp_main.c)
     * passes them as --ui-scale (HUD %, the menu % is not a placement input), --inset-left/top/right/bottom and --screen-shape
     * (corners TL, TR, BL, BR, margin, then the four cut-out rows, in that order, as setScreenShape reads them). The engine
     * reads a negative shape value as 0.
     */
    public static Inputs fromDisplay(int screenW, int screenH, int[] display15, int jumpInsetPct, boolean twoJump, boolean swapFireJump) {
        if (display15 == null || display15.length != 15) {
            throw new IllegalArgumentException("display values: 15 expected");
        }
        int[] shape = new int[9];
        for (int i = 0; i < 9; i++) shape[i] = Math.max(0, display15[6 + i]);
        return new Inputs(screenW, screenH, display15[0],
                display15[2], display15[3], display15[4], display15[5],
                shape[0], shape[1], shape[2], shape[3], shape[4],
                shape[5], shape[6], shape[7], shape[8],
                jumpInsetPct, twoJump, swapFireJump);
    }

    /**
     * The default places, by TouchLayout.NAMES: the centre x and y, the size scale 1.000 and the drawn width and height, each as
     * the engine reports it (thousandths of the screen, see reportTouchControl). highjump is included only with twoJump.
     */
    public static Map<String, TouchLayout.Place> compute(Inputs in) {
        // UISwapIn: the layout works in UI units when cUIScale is not 1 (cUIScale is a GLfloat, so single precision)
        float scale = (float) ((1.0 * in.uiScalePct) / 100.0);
        int w = in.screenW, h = in.screenH;
        if (scale != 1.0f) {
            w = pasRound((float) in.screenW / scale);
            h = pasRound((float) in.screenH / scale);
        }
        boolean noShape = in.insetL == 0 && in.insetT == 0 && in.insetR == 0 && in.insetB == 0
                && in.cornerTL == 0 && in.cornerTR == 0 && in.cornerBL == 0 && in.cornerBR == 0
                && in.margin == 0 && in.cutLY0 == 0 && in.cutLY1 == 0 && in.cutRY0 == 0 && in.cutRY1 == 0;

        Frame fire = sized(FIRE_W, FIRE_H), jump = sized(JUMP_W, JUMP_H), high = sized(HIGHJUMP_W, HIGHJUMP_H);
        Frame left = sized(ARROW_W, ARROW_H), right = sized(ARROW_W, ARROW_H);
        Frame up = sized(ARROW_W, ARROW_H), down = sized(ARROW_W, ARROW_H);
        Frame weapons = sized(WEAPONS_W, WEAPONS_H), pause = sized(PAUSE_W, PAUSE_H);
        Frame timer = sized(TIMER_W, TIMER_H), bounce = sized(BOUNCE_W, BOUNCE_H);

        if (noShape) {
            // InitTouchLegacy
            int sl = safeInset(scale, in.insetL), st = safeInset(scale, in.insetT);
            int sr = safeInset(scale, in.insetR), sb = safeInset(scale, in.insetB);
            jump.x = (w >> 1) - pasRound(jump.w * 1.2) - sr - (jump.w * in.jumpInsetPct) / 100;
            jump.y = h - jump.h * 2 - sb;
            weapons.x = (w >> 1) - weapons.w * 2 - sr;
            weapons.y = h - pasRound(weapons.h * 1.2) - sb;
            left.x = -(w >> 1) + pasRound(left.w * 0.25) + sl;
            left.y = h - pasRound(left.h * 1.5) - sb;
            right.x = -(w >> 1) + pasRound(right.w * 1.5) + sl;
            right.y = h - pasRound(right.h * 1.5) - sb;
            fire.x = right.x + right.w;
            fire.y = right.y + (right.w >> 1) - (fire.w >> 1);
            up.x = (w >> 1) - up.w * 2 - sr;
            up.y = jump.y - pasRound(up.h * 1.25);
            down.x = up.x;
            down.y = up.y;
            pause.x = w / 2 - pause.w - sr;
            pause.y = st;
            timer.x = left.x;
            timer.y = left.y - pasRound(timer.h * 1.25);
            bounce.x = timer.x + pasRound(bounce.w * 1.25);
            bounce.y = left.y - pasRound(bounce.h * 1.25);
        } else {
            // InitTouchPlaced
            int vt = vInset(scale, in, true), vb = vInset(scale, in, false);
            int pad;
            jump.y = h - jump.h * 2 - vb;
            pad = hInset(scale, in, h, false, false, jump.y, jump.y + jump.h);
            if (pad < pasRound(jump.w * 0.2)) pad = pasRound(jump.w * 0.2);
            jump.x = (w >> 1) - jump.w - pad - (jump.w * in.jumpInsetPct) / 100;

            weapons.y = h - pasRound(weapons.h * 1.2) - vb;
            pad = hInset(scale, in, h, false, false, weapons.y, weapons.y + weapons.h);
            if (pad < pasRound(weapons.w * 0.2)) pad = pasRound(weapons.w * 0.2);
            weapons.x = (w >> 1) - weapons.w - pad;
            if (weapons.x > jump.x - pasRound(weapons.w * 0.8)) weapons.x = jump.x - pasRound(weapons.w * 0.8);

            left.y = h - pasRound(left.h * 1.2) - vb;
            pad = hInset(scale, in, h, true, false, left.y, left.y + left.h);
            if (pad < pasRound(left.w * 0.1)) pad = pasRound(left.w * 0.1);
            left.x = -(w >> 1) + pad;

            right.x = left.x + pasRound(right.w * 1.25);
            right.y = left.y;
            fire.x = right.x + right.w;
            fire.y = right.y + (right.w >> 1) - (fire.w >> 1);
            up.x = weapons.x;
            up.y = jump.y - pasRound(up.h * 1.25);
            down.x = weapons.x;
            down.y = up.y;

            pause.y = vt;
            pause.x = w / 2 - pause.w - hInset(scale, in, h, false, true, pause.y, pause.y + pause.h);

            timer.x = left.x;
            timer.y = left.y - pasRound(timer.h * 1.25);
            bounce.x = timer.x + pasRound(bounce.w * 1.25);
            bounce.y = left.y - pasRound(bounce.h * 1.25);
        }

        // PlaceJumpPair: --two-jump-buttons puts the high jump left of the jump, --swap-fire-jump exchanges fire and jump centres
        if (in.twoJump) {
            high.w = jump.w;
            high.h = jump.h;
            int gap = jump.w / 4;
            high.x = jump.x - high.w - gap;
            high.y = jump.y;
        }
        if (in.swapFireJump) {
            int fcx = fire.x + fire.w / 2, fcy = fire.y + fire.h / 2;
            int jcx = jump.x + jump.w / 2, jcy = jump.y + jump.h / 2;
            fire.x = jcx - fire.w / 2;
            fire.y = jcy - fire.h / 2;
            jump.x = fcx - jump.w / 2;
            jump.y = fcy - jump.h / 2;
            if (in.twoJump) {
                high.x = jump.x + jump.w + (jump.w / 4);
                high.y = jump.y;
            }
        }

        // ReportTouchLayout (over the UI size) and reportTouchControl: the centre and the drawn size, in thousandths
        Map<String, TouchLayout.Place> out = new LinkedHashMap<>();
        out.put("fire", report(fire, w, h));
        out.put("jump", report(jump, w, h));
        if (in.twoJump) out.put("highjump", report(high, w, h));
        out.put("left", report(left, w, h));
        out.put("right", report(right, w, h));
        out.put("aim", report(up, w, h));
        out.put("weapons", report(weapons, w, h));
        out.put("pause", report(pause, w, h));
        out.put("timer", report(timer, w, h));
        out.put("bounce", report(bounce, w, h));
        return out;
    }

    /** One control's frame in UI units (the engine's frame record). */
    private static final class Frame {
        int x, y, w, h;
    }

    private static Frame sized(int texW, int texH) {
        Frame f = new Frame();
        f.w = pasRound(texW * (double) BUTTON_SCALE);
        f.h = pasRound(texH * (double) BUTTON_SCALE);
        return f;
    }

    /** buttonScale: a sprite is drawn at half its PNG size (1 / cDefaultZoomLevel). */
    private static final float BUTTON_SCALE = 1.0f / BUTTON_DIV;

    /** Round in pas2c: half away from zero (project_files/hwc/rtl/misc.c, fpcrtl_round). Not Java's Math.round. */
    static int pasRound(double v) {
        return v >= 0 ? (int) (v + 0.5) : (int) (v - 0.5);
    }

    /** SafeInset: real pixels to UI units, round(px / cUIScale) in single precision. */
    private static int safeInset(float scale, int px) {
        return pasRound((float) px / scale);
    }

    /** VInset: the room kept at the top or the bottom edge, the cut-out or bar plus the margin. */
    private static int vInset(float scale, Inputs in, boolean isTop) {
        return safeInset(scale, (isTop ? in.insetT : in.insetB) + in.margin);
    }

    /** CornerClearPx: the horizontal clearance of a rounded corner of radius rpx, v pixels from the top or bottom edge. */
    static int cornerClearPx(int rpx, int vpx) {
        if (rpx <= 0) return 0;
        float r = rpx;
        float v = vpx;
        if (v < 0) v = 0;
        if (v >= r) return 0;
        float inner = (r * r) - ((r - v) * (r - v));
        float d = (float) (r - Math.sqrt(inner));
        return pasRound(d + 0.5);
    }

    /**
     * HInset: the distance from the left or right edge that a control covering the UI rows yTopU to yBotU must keep clear:
     * the cut-out (only when it reaches those rows), the rounded corner and the margin. H is the UI height.
     */
    private static int hInset(float scale, Inputs in, int h, boolean isLeft, boolean isTop, int yTopU, int yBotU) {
        int depth, y0, y1, rad;
        if (isLeft) {
            depth = in.insetL;
            y0 = in.cutLY0;
            y1 = in.cutLY1;
            rad = isTop ? in.cornerTL : in.cornerBL;
        } else {
            depth = in.insetR;
            y0 = in.cutRY0;
            y1 = in.cutRY1;
            rad = isTop ? in.cornerTR : in.cornerBR;
        }
        if (y1 > y0 && ((float) yBotU * scale <= y0 || (float) yTopU * scale >= y1)) depth = 0;
        int v = isTop ? pasRound((float) yTopU * scale) : pasRound((float) (h - yBotU) * scale);
        int c = cornerClearPx(rad, v);
        if (c > depth) depth = c;
        return safeInset(scale, depth + in.margin);
    }

    /** reportTouchControl: centre and drawn size as thousandths of the UI screen; touchFraction clamps a negative to 0. */
    private static TouchLayout.Place report(Frame f, int w, int h) {
        int cx = ((f.x + (f.w >> 1)) + (w >> 1)) * 1000 / w;
        int cy = (f.y + (f.h >> 1)) * 1000 / h;
        int dw = f.w * 1000 / w;
        int dh = f.h * 1000 / h;
        return new TouchLayout.Place(fraction(cx), fraction(cy), 1.0, fraction(dw), fraction(dh));
    }

    /** A thousandth as the engine prints it (N.NNN), read back as a double: the same value as the reported text. */
    private static double fraction(int thousandths) {
        return Math.max(0, thousandths) / 1000.0;
    }
}
