// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

/** A hue ring around a saturation/brightness square. */
public class ColorWheelView extends View {

    /** Called when the person changes the colour. */
    public interface Listener {
        void onColor(int rgb);
    }

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint squarePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] hsv = {0f, 1f, 1f};
    private final RectF square = new RectF();
    private float cx;
    private float cy;
    private float outer;
    private float inner;
    private int dragging; // 0 none, 1 ring, 2 square
    private Listener listener;

    public ColorWheelView(Context context) {
        super(context);
        ringPaint.setStyle(Paint.Style.STROKE);
        knob.setStyle(Paint.Style.STROKE);
        knob.setStrokeWidth(3f * context.getResources().getDisplayMetrics().density);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setColor(int rgb) {
        Color.colorToHSV(rgb, hsv);
        rebuildSquare();
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = Math.min(MeasureSpec.getSize(w), MeasureSpec.getSize(h) == 0
                ? MeasureSpec.getSize(w) : MeasureSpec.getSize(h));
        setMeasuredDimension(MeasureSpec.getSize(w), size > 0 ? size : MeasureSpec.getSize(w));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        cx = w / 2f;
        cy = h / 2f;
        outer = Math.min(w, h) / 2f - 4f;
        float width = outer * 0.2f;
        inner = outer - width;
        ringPaint.setStrokeWidth(width);
        int[] colors = new int[7];
        for (int i = 0; i < 7; i++) {
            colors[i] = Color.HSVToColor(new float[] {i * 60f, 1f, 1f});
        }
        ringPaint.setShader(new SweepGradient(cx, cy, colors, null));
        float half = (inner - width * 0.2f) * 0.7071f;
        square.set(cx - half, cy - half, cx + half, cy + half);
        rebuildSquare();
    }

    private void rebuildSquare() {
        if (square.isEmpty()) {
            return;
        }
        int pure = Color.HSVToColor(new float[] {hsv[0], 1f, 1f});
        Shader h = new LinearGradient(square.left, 0, square.right, 0, Color.WHITE, pure,
                Shader.TileMode.CLAMP);
        Shader v = new LinearGradient(0, square.top, 0, square.bottom, 0x00000000, Color.BLACK,
                Shader.TileMode.CLAMP);
        squarePaint.setShader(new ComposeShader(h, v, PorterDuff.Mode.SRC_OVER));
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawCircle(cx, cy, (outer + inner) / 2f, ringPaint);
        c.drawRect(square, squarePaint);
        double a = Math.toRadians(hsv[0]);
        float rr = (outer + inner) / 2f;
        float kx = cx + (float) Math.cos(a) * rr;
        float ky = cy + (float) Math.sin(a) * rr;
        float kr = (outer - inner) * 0.42f;
        knob.setColor(Color.WHITE);
        c.drawCircle(kx, ky, kr, knob);
        float sx = square.left + hsv[1] * square.width();
        float sy = square.top + (1f - hsv[2]) * square.height();
        knob.setColor(hsv[2] > 0.5f && hsv[1] < 0.5f ? Color.BLACK : Color.WHITE);
        c.drawCircle(sx, sy, kr * 0.8f, knob);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            float d = (float) Math.hypot(x - cx, y - cy);
            if (d >= inner - 4f && d <= outer + 8f) {
                dragging = 1;
            } else if (square.contains(x, y)) {
                dragging = 2;
            } else {
                dragging = 0;
            }
        }
        if (dragging == 0) {
            return false;
        }
        if (dragging == 1) {
            float deg = (float) Math.toDegrees(Math.atan2(y - cy, x - cx));
            hsv[0] = (deg + 360f) % 360f;
            rebuildSquare();
        } else {
            hsv[1] = Math.max(0f, Math.min(1f, (x - square.left) / square.width()));
            hsv[2] = 1f - Math.max(0f, Math.min(1f, (y - square.top) / square.height()));
        }
        invalidate();
        if (listener != null) {
            listener.onColor(Color.HSVToColor(hsv));
        }
        return true;
    }
}
