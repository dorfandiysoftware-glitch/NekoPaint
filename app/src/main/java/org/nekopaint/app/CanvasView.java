// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.SparseArray;
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
 *
 * <p>The picture is shown through 256x256 tile bitmaps that are created only when visible and
 * rebuilt only when the brush changes them, so drawing stays fast on large canvases.
 */
public class CanvasView extends View {

    /** Called whenever the history or the layer structure may have changed. */
    public interface Listener {
        void onHistoryChanged();
    }

    private static final int PAPER = 0xFFFFFFFF;
    private static final int TILE = 128;
    private static final int MAX_HISTORY = 50;

    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint backgroundPaint = new Paint();
    private final Matrix matrix = new Matrix();
    private final Matrix inverse = new Matrix();
    private final float[] point = new float[2];
    private final RectF tmp = new RectF();
    private final SparseArray<Bitmap> tiles = new SparseArray<Bitmap>();
    /** Tiles whose picture is out of date, with the changed part {left, top, right, bottom}. */
    private final SparseArray<int[]> stale = new SparseArray<int[]>();
    private final int[] scratch = new int[TILE * TILE];
    private final ScaleGestureDetector scaleDetector;

    private Document document;
    private Editor editor;
    private int tilesAcross;
    private int activeLayer;

    private Editor.Stroke stroke;
    private Listener listener;
    private boolean gesturing;
    private boolean userMoved;
    private boolean interactive = true;
    private float lastMidX;
    private float lastMidY;

    private int color = 0x2B2B2B;
    private float radius = 8f;
    private float flow = 1f;
    private boolean eraser;

    public CanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        backgroundPaint.setColor(context.getColor(R.color.canvas_bg));
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        float factor = d.getScaleFactor();
                        userMoved = true;
                        matrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
                        invalidate();
                        return true;
                    }
                });
        setDocument(newBlankDocument(64, 64));
    }

    public static Document newBlankDocument(int width, int height) {
        Document d = new Document(width, height);
        d.addLayer("Layer 1");
        return d;
    }

    public Document getDocument() {
        return document;
    }

    /** @return true when no stroke is being drawn */
    public boolean isIdle() {
        return stroke == null;
    }

    public Editor getEditor() {
        return editor;
    }

    /** Switches touch handling on or off (off for the time-lapse player). */
    public void setInteractive(boolean on) {
        interactive = on;
    }

    public void setBackdropColor(int color) {
        backgroundPaint.setColor(color);
        invalidate();
    }

    /** Tells the view that something outside it changed part of the document. */
    public void externalChanged(Rect r) {
        markStale(r);
        invalidateCanvasRect(r);
    }

    /** Shows another document and fits it to the screen. */
    public void setDocument(Document doc) {
        setDocument(doc, null);
    }

    /** Shows another document; strokes and layer changes are recorded into the log if given. */
    public void setDocument(Document doc, nekopaint.core.ProjectLog log) {
        cancelStroke();
        userMoved = false;
        document = doc;
        editor = new Editor(doc, MAX_HISTORY);
        editor.setLog(log);
        tilesAcross = (doc.getWidth() + TILE - 1) / TILE;
        activeLayer = Math.min(doc.getLayerCount() - 1, Math.max(0, doc.getLayerCount() - 1));
        for (int i = 0; i < tiles.size(); i++) {
            tiles.valueAt(i).recycle();
        }
        tiles.clear();
        stale.clear();
        fit();
        invalidate();
        notifyHistory();
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

    /** @param value 0.01 to 1 */
    public void setFlow(float value) {
        flow = Math.max(0.01f, Math.min(1f, value));
    }

    public void setEraser(boolean eraser) {
        this.eraser = eraser;
    }

    public int getActiveLayer() {
        return activeLayer;
    }

    public void setActiveLayer(int index) {
        if (index >= 0 && index < document.getLayerCount()) {
            activeLayer = index;
        }
    }

    public boolean canUndo() {
        return editor.canUndo();
    }

    public boolean canRedo() {
        return editor.canRedo();
    }

    public void undo() {
        if (stroke == null && editor.undo()) {
            markStale(editor.getLastChanged());
            invalidateCanvasRect(editor.getLastChanged());
            notifyHistory();
        }
    }

    public void redo() {
        if (stroke == null && editor.redo()) {
            markStale(editor.getLastChanged());
            invalidateCanvasRect(editor.getLastChanged());
            notifyHistory();
        }
    }

    /** Call after changing a layer's visibility, opacity, blend mode or after adding one. */
    public void layersChanged() {
        stale.clear();
        for (int i = 0; i < tiles.size(); i++) {
            stale.put(tiles.keyAt(i), new int[] {0, 0, TILE, TILE});
        }
        invalidate();
    }

    /** Call after adding, removing or moving layers; the history cannot follow those. */
    public void layerStructureChanged() {
        cancelStroke();
        editor.clearHistory();
        activeLayer = Math.max(0, Math.min(activeLayer, document.getLayerCount() - 1));
        layersChanged();
        notifyHistory();
    }

    /** @return the picture on white paper as a new bitmap */
    public Bitmap exportBitmap() {
        int w = document.getWidth();
        int h = document.getHeight();
        int[] px = document.flatten(PAPER);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    private void notifyHistory() {
        if (listener != null) {
            listener.onHistoryChanged();
        }
    }

    private void fit() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || document == null) {
            return;
        }
        float scale = Math.min(w / (float) document.getWidth(), h / (float) document.getHeight());
        matrix.setScale(scale, scale);
        matrix.postTranslate((w - document.getWidth() * scale) / 2f,
                (h - document.getHeight() * scale) / 2f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (!userMoved) {
            fit();
        }
    }

    // ------------------------------------------------------------------ tiles

    private void markStale(Rect r) {
        if (r.isEmpty()) {
            return;
        }
        for (int ty = r.top / TILE; ty <= (r.bottom - 1) / TILE; ty++) {
            for (int tx = r.left / TILE; tx <= (r.right - 1) / TILE; tx++) {
                int key = ty * tilesAcross + tx;
                if (tiles.get(key) == null) {
                    continue;
                }
                int ox = tx * TILE;
                int oy = ty * TILE;
                int l = Math.max(r.left - ox, 0);
                int t = Math.max(r.top - oy, 0);
                int rr = Math.min(r.right - ox, TILE);
                int bb = Math.min(r.bottom - oy, TILE);
                int[] old = stale.get(key);
                if (old == null) {
                    stale.put(key, new int[] {l, t, rr, bb});
                } else {
                    old[0] = Math.min(old[0], l);
                    old[1] = Math.min(old[1], t);
                    old[2] = Math.max(old[2], rr);
                    old[3] = Math.max(old[3], bb);
                }
            }
        }
    }

    private void invalidateCanvasRect(Rect r) {
        if (r.isEmpty()) {
            return;
        }
        tmp.set(r.left, r.top, r.right, r.bottom);
        matrix.mapRect(tmp);
        invalidate((int) Math.floor(tmp.left) - 2, (int) Math.floor(tmp.top) - 2,
                (int) Math.ceil(tmp.right) + 2, (int) Math.ceil(tmp.bottom) + 2);
    }

    private Bitmap tileBitmap(int tx, int ty) {
        int key = ty * tilesAcross + tx;
        Bitmap b = tiles.get(key);
        int[] dirty = stale.get(key);
        if (b != null && dirty == null) {
            return b;
        }
        int left = tx * TILE;
        int top = ty * TILE;
        int right = Math.min(document.getWidth(), left + TILE);
        int bottom = Math.min(document.getHeight(), top + TILE);
        int w = right - left;
        int h = bottom - top;
        int l = 0;
        int t = 0;
        int r = w;
        int bt = h;
        if (b == null) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            tiles.put(key, b);
        } else {
            // Only the part that really changed is flattened and copied.
            l = Math.min(dirty[0], w);
            t = Math.min(dirty[1], h);
            r = Math.min(dirty[2], w);
            bt = Math.min(dirty[3], h);
        }
        if (r > l && bt > t) {
            document.flattenRect(new Rect(left + l, top + t, left + r, top + bt), PAPER, scratch);
            b.setPixels(scratch, 0, r - l, l, t, r - l, bt - t);
        }
        stale.remove(key);
        return b;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), backgroundPaint);
        matrix.invert(inverse);
        tmp.set(0, 0, getWidth(), getHeight());
        inverse.mapRect(tmp);
        int tx0 = Math.max(0, (int) Math.floor(tmp.left / TILE));
        int ty0 = Math.max(0, (int) Math.floor(tmp.top / TILE));
        int tx1 = Math.min(tilesAcross - 1, (int) Math.floor(tmp.right / TILE));
        int ty1 = Math.min((document.getHeight() - 1) / TILE, (int) Math.floor(tmp.bottom / TILE));
        canvas.save();
        canvas.concat(matrix);
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                canvas.drawBitmap(tileBitmap(tx, ty), tx * TILE, ty * TILE, tilePaint);
            }
        }
        canvas.restore();
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!interactive) {
            return false;
        }
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
                        userMoved = true;
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
                gesturing = true;
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
        if (!document.getLayer(activeLayer).isVisible()) {
            return; // painting on a hidden layer would be invisible
        }
        Brush brush = eraser
                ? Brush.eraser(radius)
                : new Brush(color, radius, 0.8f, flow, 0.15f, false);
        stroke = editor.beginStroke(activeLayer, brush);
    }

    private void addPoints(MotionEvent e) {
        if (stroke == null) {
            return;
        }
        boolean finger = e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER;
        matrix.invert(inverse);
        int n = e.getHistorySize();
        for (int i = 0; i <= n; i++) {
            boolean last = i == n;
            point[0] = last ? e.getX(0) : e.getHistoricalX(0, i);
            point[1] = last ? e.getY(0) : e.getHistoricalY(0, i);
            float pressure = finger ? 1f
                    : last ? e.getPressure(0) : e.getHistoricalPressure(0, i);
            inverse.mapPoints(point);
            stroke.addPoint(point[0], point[1], pressure);
        }
        // Redraw only the pixels this batch of points really changed.
        Rect changed = stroke.takeChanged();
        markStale(changed);
        invalidateCanvasRect(changed);
    }

    private void endStroke() {
        if (stroke != null) {
            Rect rest = stroke.takeChanged();
            stroke.end();
            stroke = null;
            markStale(rest);
            invalidateCanvasRect(rest);
            notifyHistory();
        }
    }

    private void cancelStroke() {
        if (stroke != null) {
            Rect dirty = stroke.getDirty();
            stroke.cancel();
            stroke = null;
            markStale(dirty);
            invalidateCanvasRect(dirty);
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
