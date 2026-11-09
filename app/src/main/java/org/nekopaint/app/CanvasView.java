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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private volatile Document document;
    private volatile Editor editor;
    private volatile int tilesAcross;
    private int activeLayer;

    /**
     * Drawing is done on a worker thread so a big brush never blocks touch and screen updates.
     * Everything that touches the document while drawing goes through this one thread, in order.
     */
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>());

    /** The stroke being drawn: the points the finger gave and the stroke the worker fills. */
    private static final class Job {
        final ConcurrentLinkedQueue<float[]> points = new ConcurrentLinkedQueue<float[]>();
        final AtomicBoolean scheduled = new AtomicBoolean();
        final Brush brush;
        final int layer;
        Editor.Stroke stroke; // worker thread only

        Job(Brush brush, int layer) {
            this.brush = brush;
            this.layer = layer;
        }
    }

    /** A freshly flattened piece of a tile, made by the worker and copied in by the UI thread. */
    private static final class TileUpdate {
        final int key;
        final int left;
        final int top;
        final int width;
        final int height;
        final int[] pixels;

        TileUpdate(int key, int left, int top, int width, int height, int[] pixels) {
            this.key = key;
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }
    }

    private Job job;
    private volatile boolean canUndoNow;
    private volatile boolean canRedoNow;

    // Performance counters for the on-screen meter.
    private final Paint perfPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint perfBack = new Paint();
    private boolean perfVisible;
    private volatile float rasterMs;
    private volatile float rasterWorstMs;
    private float drawMs;
    private int frames;
    private long frameClock;
    private int fps;
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

    /** Waits until everything handed to the drawing thread is done. */
    public void flush() {
        try {
            worker.submit(new Runnable() {
                @Override
                public void run() {
                }
            }).get();
        } catch (java.util.concurrent.ExecutionException e) {
            // nothing to wait for any more
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Shows or hides the small frame-time meter in the corner. */
    public void setPerfVisible(boolean on) {
        perfVisible = on;
        invalidate();
    }

    public Document getDocument() {
        flush();
        return document;
    }

    /** @return true when no stroke is being drawn */
    public boolean isIdle() {
        return job == null;
    }

    public Editor getEditor() {
        flush();
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
        flush();
        userMoved = false;
        document = doc;
        Editor fresh = new Editor(doc, MAX_HISTORY);
        fresh.setLog(log);
        editor = fresh;
        canUndoNow = false;
        canRedoNow = false;
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
        return canUndoNow;
    }

    public boolean canRedo() {
        return canRedoNow;
    }

    public void undo() {
        history(true);
    }

    public void redo() {
        history(false);
    }

    private void history(final boolean undo) {
        if (job != null) {
            return;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final Editor e = editor;
                boolean ok = undo ? e.undo() : e.redo();
                if (!ok) {
                    return;
                }
                final Rect changed = e.getLastChanged();
                canUndoNow = e.canUndo();
                canRedoNow = e.canRedo();
                post(new Runnable() {
                    @Override
                    public void run() {
                        markStale(changed);
                        invalidateCanvasRect(changed);
                        notifyHistory();
                    }
                });
            }
        });
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
        flush();
        editor.clearHistory();
        canUndoNow = false;
        canRedoNow = false;
        activeLayer = Math.max(0, Math.min(activeLayer, document.getLayerCount() - 1));
        layersChanged();
        notifyHistory();
    }

    /** @return the picture on white paper as a new bitmap */
    public Bitmap exportBitmap() {
        flush();
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
        long start = System.nanoTime();
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
        if (perfVisible) {
            drawMs = drawMs * 0.8f + (System.nanoTime() - start) / 1e6f * 0.2f;
            frames++;
            long now = System.nanoTime();
            if (now - frameClock >= 1_000_000_000L) {
                fps = frames;
                frames = 0;
                frameClock = now;
            }
            String text = String.format(java.util.Locale.ROOT,
                    "draw %.1f ms | stroke %.1f ms (max %.0f) | %d fps",
                    drawMs, rasterMs, rasterWorstMs, fps);
            float d = getResources().getDisplayMetrics().density;
            perfPaint.setTextSize(11 * d);
            perfPaint.setColor(0xFFFFFFFF);
            perfBack.setColor(0xAA000000);
            float w = perfPaint.measureText(text);
            canvas.drawRect(4 * d, getHeight() - 24 * d, 12 * d + w, getHeight() - 4 * d, perfBack);
            canvas.drawText(text, 8 * d, getHeight() - 10 * d, perfPaint);
        }
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
        final Job j = new Job(brush, activeLayer);
        job = j;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                j.stroke = editor.beginStroke(j.layer, j.brush);
            }
        });
    }

    private void addPoints(MotionEvent e) {
        final Job j = job;
        if (j == null) {
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
            j.points.add(new float[] {point[0], point[1], pressure});
        }
        if (j.scheduled.compareAndSet(false, true)) {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    j.scheduled.set(false);
                    long t0 = System.nanoTime();
                    if (j.stroke != null && feed(j)) {
                        publish(j.stroke.takeChanged(), t0);
                    }
                }
            });
        }
    }

    /** Worker: pushes every waiting point into the stroke. @return true if there were any */
    private boolean feed(Job j) {
        boolean any = false;
        float[] p;
        while ((p = j.points.poll()) != null) {
            j.stroke.addPoint(p[0], p[1], p[2]);
            any = true;
        }
        return any;
    }

    /** Worker: flattens the changed tiles and hands them to the UI thread. */
    private void publish(final Rect changed, long startNanos) {
        if (changed.isEmpty()) {
            return;
        }
        final Document doc = document;
        final int across = tilesAcross;
        final List<TileUpdate> updates = new ArrayList<TileUpdate>();
        for (int ty = changed.top / TILE; ty <= (changed.bottom - 1) / TILE; ty++) {
            for (int tx = changed.left / TILE; tx <= (changed.right - 1) / TILE; tx++) {
                int ox = tx * TILE;
                int oy = ty * TILE;
                int l = Math.max(changed.left, ox);
                int t = Math.max(changed.top, oy);
                int r = Math.min(Math.min(changed.right, ox + TILE), doc.getWidth());
                int b = Math.min(Math.min(changed.bottom, oy + TILE), doc.getHeight());
                if (r <= l || b <= t) {
                    continue;
                }
                int[] px = new int[(r - l) * (b - t)];
                doc.flattenRect(new Rect(l, t, r, b), PAPER, px);
                updates.add(new TileUpdate(ty * across + tx, l - ox, t - oy, r - l, b - t, px));
            }
        }
        float ms = (System.nanoTime() - startNanos) / 1e6f;
        rasterMs = ms;
        rasterWorstMs = Math.max(ms, rasterWorstMs * 0.98f);
        post(new Runnable() {
            @Override
            public void run() {
                for (TileUpdate u : updates) {
                    Bitmap bmp = tiles.get(u.key);
                    if (bmp != null) {
                        bmp.setPixels(u.pixels, 0, u.width, u.left, u.top, u.width, u.height);
                    }
                }
                invalidateCanvasRect(changed);
            }
        });
    }

    private void endStroke() {
        final Job j = job;
        if (j == null) {
            return;
        }
        job = null;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                Editor.Stroke s = j.stroke;
                if (s == null) {
                    return;
                }
                long t0 = System.nanoTime();
                feed(j);
                Rect rest = s.takeChanged();
                s.end();
                canUndoNow = editor.canUndo();
                canRedoNow = editor.canRedo();
                publish(rest, t0);
                post(new Runnable() {
                    @Override
                    public void run() {
                        notifyHistory();
                    }
                });
            }
        });
    }

    private void cancelStroke() {
        final Job j = job;
        if (j == null) {
            return;
        }
        job = null;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                Editor.Stroke s = j.stroke;
                if (s == null) {
                    return;
                }
                j.points.clear();
                final Rect dirty = s.getDirty();
                s.cancel();
                post(new Runnable() {
                    @Override
                    public void run() {
                        markStale(dirty);
                        invalidateCanvasRect(dirty);
                    }
                });
            }
        });
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
