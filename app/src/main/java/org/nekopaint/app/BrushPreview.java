// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** A round preview of the brush: its colour and (roughly) its size. */
public class BrushPreview extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float fraction = 0.1f;

    public BrushPreview(Context context, AttributeSet attrs) {
        super(context, attrs);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2f * context.getResources().getDisplayMetrics().density);
        ring.setColor(0x99888888);
        fill.setColor(0xFF000000);
    }

    /** @param fraction brush size relative to the maximum, 0 to 1 */
    public void setBrush(int rgb, float fraction) {
        fill.setColor(0xFF000000 | rgb);
        this.fraction = Math.max(0.05f, Math.min(1f, fraction));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float max = Math.min(getWidth(), getHeight()) / 2f - ring.getStrokeWidth();
        canvas.drawCircle(cx, cy, max, ring);
        canvas.drawCircle(cx, cy, Math.max(2f, max * fraction), fill);
    }
}
