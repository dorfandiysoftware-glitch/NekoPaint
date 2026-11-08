// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import nekopaint.core.Brush;
import nekopaint.core.Document;
import nekopaint.core.Editor;
import nekopaint.core.Rect;

/**
 * Shows a {@link Document} and lets the user draw on it. One finger or a stylus draws; two
 * fingers pan and pinch-zoom.
 */
public class CanvasView extends View {

    /** Called whenever undo/redo availability may have changed. */
    public interface Listener {
        void onHistoryChanged();
    }

    private static final int PAPER = 0xFFFFFFFF;
    private static final int MAX_HISTORY = 50;

    private final Document document = new Document(2048, 2048);
    private final Editor editor;
    private final Bitmap bitmap;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint backgroundPaint = new Paint();
    private final Matrix matrix = new Matrix();
    private final Matrix inverse = new Matrix();
    private final float[] point = new float[2];
    private final ScaleGestureDetector scaleDetector;

    private Editor.Stroke stroke;
    private Listener listener;
    private boolean fitted;
    private boolean gesturing;
    private float lastMidX;
    private float lastMidY;

    private int color = 0x2B2B2B;
    private float radius = 8f;
    private boolean eraser;

    public CanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        document.addLayer("Layer 1");
        editor = new Editor(document, MAX_HISTORY);
        bitmap = Bitmap.createBitmap(document.getWidth(), document.getHeight(),
                Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(PAPER);
        backgroundPaint.setColor(0xFF9E9E9E);
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        float factor = d.getScaleFactor();
                        matrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
                        invalidate();
                        return true;
                    }
                });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setColor(int rgb) {
        color = rgb & 0xFFFFFF;
    }

    public int getColor() {
        return color;
    }

    /** @param canvasPixels brush radius in canvas pixels */
    public void setRadius(float canvasPixels) {
        radius = Math.max(0.5f, canvasPixels);
    }

    public void setEraser(boolean eraser) {
        this.eraser = eraser;
    }

    public boolean canUndo() {
        return editor.canUndo();
    }

    public boolean canRedo() {
        return editor.canRedo();
    }

    public void undo() {
        if (stroke == null && editor.undo()) {
            refresh(editor.getLastChanged());
            notifyHistory();
        }
    }

    public void redo() {
        if (stroke == null && editor.redo()) {
            refresh(editor.getLastChanged());
            notifyHistory();
        }
    }

    /** @return a copy of the picture on white paper (a new bitmap) */
    public Bitmap exportBitmap() {
        return bitmap.copy(Bitmap.Config.ARGB_8888, false);
    }

    private void notifyHistory() {
        if (listener != null) {
            listener.onHistoryChanged();
        }
    }

    private void refresh(Rect rect) {
        if (rect.isEmpty()) {
            return;
        }
        int w = rect.width();
        int[] pixels = document.flattenRect(rect, PAPER);
        bitmap.setPixels(pixels, 0, w, rect.left, rect.top, w, rect.height());
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (!fitted && w > 0 && h > 0) {
            float scale = Math.min(w / (float) bitmap.getWidth(), h / (float) bitmap.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate((w - bitmap.getWidth() * scale) / 2f,
                    (h - bitmap.getHeight() * scale) / 2f);
            fitted = true;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), backgroundPaint);
        canvas.drawBitmap(bitmap, matrix, bitmapPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gesturing = false;
                beginStroke();
                addPoints(e);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                cancelStroke();
                gesturing = true;
                lastMidX = midX(e);
                lastMidY = midY(e);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (gesturing) {
                    if (e.getPointerCount() >= 2) {
                        float mx = midX(e);
                        float my = midY(e);
                        matrix.postTranslate(mx - lastMidX, my - lastMidY);
                        lastMidX = mx;
                        lastMidY = my;
                        invalidate();
                    }
                } else {
                    addPoints(e);
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerCount() == 2) {
                    // One finger stays down; do not start drawing, just stop the gesture.
                    gesturing = true;
                }
                lastMidX = midXExcluding(e, e.getActionIndex());
                lastMidY = midYExcluding(e, e.getActionIndex());
                return true;
            case MotionEvent.ACTION_UP:
                if (!gesturing) {
                    addPoints(e);
                    endStroke();
                }
                gesturing = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelStroke();
                gesturing = false;
                return true;
            default:
                return true;
        }
    }

    private void beginStroke() {
        Brush brush = eraser ? Brush.eraser(radius) : Brush.pen(color, radius);
        stroke = editor.beginStroke(0, brush);
    }

    private void addPoints(MotionEvent e) {
        if (stroke == null) {
            return;
        }
        boolean finger = e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER;
        for (int i = 0; i < e.getHistorySize(); i++) {
            addPoint(e.getHistoricalX(0, i), e.getHistoricalY(0, i),
                    finger ? 1f : e.getHistoricalPressure(0, i));
        }
        addPoint(e.getX(0), e.getY(0), finger ? 1f : e.getPressure(0));
        refresh(stroke.getDirty());
    }

    private void addPoint(float viewX, float viewY, float pressure) {
        matrix.invert(inverse);
        point[0] = viewX;
        point[1] = viewY;
        inverse.mapPoints(point);
        stroke.addPoint(point[0], point[1], pressure);
    }

    private void endStroke() {
        if (stroke != null) {
            stroke.end();
            stroke = null;
            notifyHistory();
        }
    }

    private void cancelStroke() {
        if (stroke != null) {
            Rect dirty = stroke.getDirty();
            stroke.cancel();
            stroke = null;
            refresh(dirty);
        }
    }

    private static float midX(MotionEvent e) {
        return (e.getX(0) + e.getX(1)) / 2f;
    }

    private static float midY(MotionEvent e) {
        return (e.getY(0) + e.getY(1)) / 2f;
    }

    private static float midXExcluding(MotionEvent e, int skip) {
        float sum = 0;
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i != skip) {
                sum += e.getX(i);
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    private static float midYExcluding(MotionEvent e, int skip) {
        float sum = 0;
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i != skip) {
                sum += e.getY(i);
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }
}
