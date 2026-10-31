package com.ansub.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.Arrays;

// Tiny white outline icons on a 24x24 grid, so the app needs no image resources.
// Each icon is a list of ops: 0=moveTo x y, 1=lineTo x y, 2=close, 3=circle cx cy r,
// 4=arc cx cy r startDegrees sweepDegrees (0 degrees is 3 o'clock, going clockwise).
final class LineIcon extends Drawable {
    static final int[] PLAY = {0, 60, 45, 1, 60, 195, 1, 190, 120, 2};
    static final int[] HDMI = {0, 30, 90, 1, 210, 90, 1, 210, 130, 1, 185, 160, 1, 55, 160, 1, 30, 130, 2,
            0, 80, 120, 1, 160, 120};
    static final int[] SETTINGS = {0, 40, 70, 1, 140, 70, 0, 180, 70, 1, 200, 70, 0, 40, 170, 1, 60, 170,
            0, 100, 170, 1, 200, 170, 3, 160, 70, 20, 3, 80, 170, 20};
    static final int[] PLUS = {0, 120, 50, 1, 120, 190, 0, 50, 120, 1, 190, 120};
    static final int[] WIFI = {4, 120, 195, 135, 225, 90, 4, 120, 195, 92, 225, 90, 4, 120, 195, 50, 225, 90,
            3, 120, 195, 4};
    static final int[] WIFI_2 = {4, 120, 195, 92, 225, 90, 4, 120, 195, 50, 225, 90, 3, 120, 195, 4};
    static final int[] WIFI_1 = {4, 120, 195, 50, 225, 90, 3, 120, 195, 4};
    static final int[] SLASH = {0, 50, 45, 1, 190, 205};
    static final int[] BLUETOOTH = {0, 70, 80, 1, 170, 160, 1, 120, 205, 1, 120, 35, 1, 170, 80, 1, 70, 160};
    static final int[] SOUND = {0, 35, 95, 1, 75, 95, 1, 120, 55, 1, 120, 185, 1, 75, 145, 1, 35, 145, 2,
            4, 120, 120, 40, -45, 90, 4, 120, 120, 78, -45, 90};
    static final int[] ETHERNET = {0, 40, 60, 1, 200, 60, 1, 200, 165, 1, 160, 165, 1, 160, 195, 1, 80, 195,
            1, 80, 165, 1, 40, 165, 2, 0, 85, 60, 1, 85, 100, 0, 120, 60, 1, 120, 100, 0, 155, 60, 1, 155, 100};
    static final int[] PICTURE ={0, 30, 50, 1, 210, 50, 1, 210, 165, 1, 30, 165, 2, 0, 120, 165, 1, 120, 200,
            0, 80, 200, 1, 160, 200};

    // Weather, for the forecast next to the clock.
    static final int[] SUN = {3, 120, 120, 40,
            0, 120, 58, 1, 120, 32, 0, 120, 182, 1, 120, 208, 0, 58, 120, 1, 32, 120, 0, 182, 120, 1, 208, 120,
            0, 164, 76, 1, 182, 58, 0, 76, 76, 1, 58, 58, 0, 164, 164, 1, 182, 182, 0, 76, 164, 1, 58, 182};
    // A crescent: most of one circle, closed by an arc of a second one inside it.
    static final int[] MOON = {4, 120, 120, 72, 15, 253, 4, 155, 92, 58, 53, 176};
    // Three bumps on a flat base. The arcs meet where their circles cross.
    static final int[] CLOUD = {4, 72, 150, 30, 90, 180, 4, 118, 118, 44, 178, 184, 4, 176, 148, 32, 244, 206,
            0, 72, 180, 1, 176, 180};
    // The sun's top left, peeking out from behind a smaller cloud.
    // The sun's arc stops where the cloud covers it (about 15 to 85 degrees).
    static final int[] PARTLY = join(new int[] {4, 95, 95, 38, 85, 290,
            0, 45, 95, 1, 29, 95, 0, 60, 60, 1, 48, 48, 0, 95, 45, 1, 95, 29, 0, 130, 60, 1, 142, 48, 0, 60, 130, 1, 48, 142},
            move(CLOUD, 80, 40, 40));
    static final int[] FOG = {0, 40, 80, 1, 200, 80, 0, 40, 120, 1, 200, 120, 0, 60, 160, 1, 180, 160, 0, 80, 200, 1, 160, 200};
    static final int[] RAIN = join(move(CLOUD, 85, 12, -30),
            new int[] {0, 90, 148, 1, 76, 190, 0, 128, 148, 1, 114, 190, 0, 166, 148, 1, 152, 190});
    static final int[] SNOW = join(move(CLOUD, 85, 12, -30),
            new int[] {3, 85, 160, 6, 3, 125, 160, 6, 3, 165, 160, 6, 3, 105, 195, 6, 3, 145, 195, 6});
    static final int[] STORM = join(move(CLOUD, 85, 12, -30),
            new int[] {0, 130, 140, 1, 105, 180, 1, 135, 180, 1, 110, 215});

    // An icon scaled to pct percent (around the top left), then shifted by dx, dy.
    static int[] move(int[] ops, int pct, int dx, int dy) {
        int[] out = ops.clone();
        for (int i = 0; i < out.length; ) {
            switch (out[i]) {
                case 0: case 1:
                    out[i + 1] = out[i + 1] * pct / 100 + dx; out[i + 2] = out[i + 2] * pct / 100 + dy; i += 3; break;
                case 2: i += 1; break;
                case 3:
                    out[i + 1] = out[i + 1] * pct / 100 + dx; out[i + 2] = out[i + 2] * pct / 100 + dy;
                    out[i + 3] = out[i + 3] * pct / 100; i += 4; break;
                default: // an arc keeps its angles
                    out[i + 1] = out[i + 1] * pct / 100 + dx; out[i + 2] = out[i + 2] * pct / 100 + dy;
                    out[i + 3] = out[i + 3] * pct / 100; i += 6;
            }
        }
        return out;
    }

    static int[] join(int[] a, int[] b) {
        int[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private final int[] ops;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF arc = new RectF();

    LineIcon(int[] ops) {
        this.ops = ops;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(0xEBFFFFFF);
    }

    @Override
    public void draw(Canvas c) {
        if (ops == null) return;
        float s = getBounds().width() / 240f; // ops are in tenths of the 24-unit grid
        paint.setStrokeWidth(16 * s);
        path.reset();
        for (int i = 0; i < ops.length; ) {
            switch (ops[i]) {
                case 0: path.moveTo(ops[i + 1] * s, ops[i + 2] * s); i += 3; break;
                case 1: path.lineTo(ops[i + 1] * s, ops[i + 2] * s); i += 3; break;
                case 2: path.close(); i += 1; break;
                case 3: path.addCircle(ops[i + 1] * s, ops[i + 2] * s, ops[i + 3] * s, Path.Direction.CW); i += 4; break;
                default:
                    float cx = ops[i + 1] * s, cy = ops[i + 2] * s, r = ops[i + 3] * s;
                    arc.set(cx - r, cy - r, cx + r, cy + r);
                    path.addArc(arc, ops[i + 4], ops[i + 5]);
                    i += 6;
            }
        }
        c.save();
        c.translate(getBounds().left, getBounds().top);
        c.drawPath(path, paint);
        c.restore();
    }

    @Override public void setAlpha(int a) { paint.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return 44; }
    @Override public int getIntrinsicHeight() { return 44; }
}
