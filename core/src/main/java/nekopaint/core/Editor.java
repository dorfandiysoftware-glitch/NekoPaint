// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

import java.util.ArrayDeque;

/**
 * Edits a {@link Document} with undo and redo.
 *
 * <p>Each finished stroke becomes one history entry that stores only the rectangle it changed
 * (before and after), so undo is exact and cheap in memory. While a stroke is being drawn only
 * the 64x64 tiles the brush comes near are copied, the first time they are touched, so
 * starting a stroke costs nothing even on a huge canvas.
 *
 * <p>Only one stroke can be in progress at a time. Layer structure changes (add, remove,
 * reorder) are not undoable; call {@link #clearHistory()} after them.
 *
 * <p>Not thread safe.
 */
public final class Editor {

    private final Document document;
    private final int maxHistory;
    private final ArrayDeque<Edit> undoStack = new ArrayDeque<Edit>();
    private final ArrayDeque<Edit> redoStack = new ArrayDeque<Edit>();
    private Stroke activeStroke;
    private Rect lastChanged = Rect.EMPTY;

    /**
     * @param maxHistory how many edits to remember; at least 1
     */
    public Editor(Document document, int maxHistory) {
        if (document == null || maxHistory < 1) {
            throw new IllegalArgumentException("A document and a history size of at least 1 "
                    + "are required");
        }
        this.document = document;
        this.maxHistory = maxHistory;
    }

    public Document getDocument() {
        return document;
    }

    /**
     * Starts a stroke on a layer.
     *
     * @throws IllegalStateException    if another stroke is in progress
     * @throws IllegalArgumentException if the layer index or brush is invalid
     */
    public Stroke beginStroke(int layerIndex, Brush brush) {
        if (activeStroke != null) {
            throw new IllegalStateException("A stroke is already in progress");
        }
        if (layerIndex < 0 || layerIndex >= document.getLayerCount() || brush == null) {
            throw new IllegalArgumentException("Invalid layer or brush");
        }
        activeStroke = new Stroke(layerIndex, document.getLayer(layerIndex), brush);
        return activeStroke;
    }

    /** @return the area changed by the latest successful undo or redo, or empty */
    public Rect getLastChanged() {
        return lastChanged;
    }

    /** Forgets all undo and redo entries. */
    public void clearHistory() {
        requireNoStroke();
        undoStack.clear();
        redoStack.clear();
        lastChanged = Rect.EMPTY;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * Reverts the latest edit.
     *
     * @return false if there is nothing to undo
     * @throws IllegalStateException if a stroke is in progress
     */
    public boolean undo() {
        requireNoStroke();
        Edit edit = undoStack.pollLast();
        if (edit == null) {
            return false;
        }
        document.getLayer(edit.layerIndex).pasteRect(edit.rect, edit.before);
        lastChanged = edit.rect;
        redoStack.addLast(edit);
        return true;
    }

    /**
     * Re-applies the latest undone edit.
     *
     * @return false if there is nothing to redo
     * @throws IllegalStateException if a stroke is in progress
     */
    public boolean redo() {
        requireNoStroke();
        Edit edit = redoStack.pollLast();
        if (edit == null) {
            return false;
        }
        document.getLayer(edit.layerIndex).pasteRect(edit.rect, edit.after);
        lastChanged = edit.rect;
        undoStack.addLast(edit);
        return true;
    }

    private void requireNoStroke() {
        if (activeStroke != null) {
            throw new IllegalStateException("A stroke is in progress");
        }
    }

    /** Side length of the squares in which a stroke saves the original pixels. */
    private static final int TILE = 64;

    /** A stroke in progress. Call {@link #end()} or {@link #cancel()} exactly once. */
    public final class Stroke {
        private static final float MAX_COORD = 100000f;

        private final int layerIndex;
        private final Layer layer;
        private final int tilesAcross;
        private final java.util.HashMap<Integer, int[]> savedTiles =
                new java.util.HashMap<Integer, int[]>();
        private final StrokeRasterizer rasterizer;
        private final float radius;
        private boolean hasLast;
        private float lastX;
        private float lastY;
        private boolean finished;

        private Stroke(int layerIndex, Layer layer, Brush brush) {
            this.layerIndex = layerIndex;
            this.layer = layer;
            this.tilesAcross = (layer.getWidth() + TILE - 1) / TILE;
            this.radius = brush.getRadius();
            this.rasterizer = new StrokeRasterizer(layer, brush);
        }

        /** Adds an input point; see {@link StrokeRasterizer#addPoint(float, float, float)}. */
        public void addPoint(float x, float y, float pressure) {
            requireActive();
            if (!Float.isNaN(x) && !Float.isNaN(y) && !Float.isInfinite(x)
                    && !Float.isInfinite(y)) {
                x = Math.max(-MAX_COORD, Math.min(MAX_COORD, x));
                y = Math.max(-MAX_COORD, Math.min(MAX_COORD, y));
                float x0 = hasLast ? Math.min(lastX, x) : x;
                float x1 = hasLast ? Math.max(lastX, x) : x;
                float y0 = hasLast ? Math.min(lastY, y) : y;
                float y1 = hasLast ? Math.max(lastY, y) : y;
                saveTiles((int) Math.floor(x0 - radius) - 1, (int) Math.floor(y0 - radius) - 1,
                        (int) Math.ceil(x1 + radius) + 2, (int) Math.ceil(y1 + radius) + 2);
                hasLast = true;
                lastX = x;
                lastY = y;
            }
            rasterizer.addPoint(x, y, pressure);
        }

        /** @return the rectangle changed so far by this stroke, or empty */
        public Rect getDirty() {
            return rasterizer.getDirty();
        }

        /** Finishes the stroke and records it in the history if it changed anything. */
        public void end() {
            requireActive();
            finished = true;
            activeStroke = null;
            Rect dirty = rasterizer.getDirty();
            if (dirty.isEmpty()) {
                return;
            }
            Edit edit = new Edit(layerIndex, dirty, originalPixels(dirty),
                    layer.copyRect(dirty));
            undoStack.addLast(edit);
            redoStack.clear();
            while (undoStack.size() > maxHistory) {
                undoStack.pollFirst();
            }
        }

        /** Throws the stroke away: the layer returns to how it was and no history is added. */
        public void cancel() {
            requireActive();
            finished = true;
            activeStroke = null;
            Rect dirty = rasterizer.getDirty();
            if (!dirty.isEmpty()) {
                layer.pasteRect(dirty, originalPixels(dirty));
            }
        }

        private void requireActive() {
            if (finished) {
                throw new IllegalStateException("The stroke is already finished");
            }
        }

        /** Saves the original pixels of every tile that touches the given area. */
        private void saveTiles(int left, int top, int right, int bottom) {
            int w = layer.getWidth();
            int h = layer.getHeight();
            left = Math.max(0, left);
            top = Math.max(0, top);
            right = Math.min(w, right);
            bottom = Math.min(h, bottom);
            if (left >= right || top >= bottom) {
                return;
            }
            for (int ty = top / TILE; ty <= (bottom - 1) / TILE; ty++) {
                for (int tx = left / TILE; tx <= (right - 1) / TILE; tx++) {
                    Integer key = ty * tilesAcross + tx;
                    if (!savedTiles.containsKey(key)) {
                        savedTiles.put(key, layer.copyRect(tileRect(tx, ty)));
                    }
                }
            }
        }

        private Rect tileRect(int tx, int ty) {
            return new Rect(tx * TILE, ty * TILE,
                    Math.min(layer.getWidth(), (tx + 1) * TILE),
                    Math.min(layer.getHeight(), (ty + 1) * TILE));
        }

        /** Rebuilds the original pixels of a rectangle from the saved tiles. */
        private int[] originalPixels(Rect rect) {
            int w = rect.width();
            int[] out = new int[w * rect.height()];
            for (int ty = rect.top / TILE; ty <= (rect.bottom - 1) / TILE; ty++) {
                for (int tx = rect.left / TILE; tx <= (rect.right - 1) / TILE; tx++) {
                    int[] tile = savedTiles.get(ty * tilesAcross + tx);
                    if (tile == null) {
                        // Never touched by the brush, so the layer still holds the original.
                        tile = layer.copyRect(tileRect(tx, ty));
                    }
                    Rect t = tileRect(tx, ty);
                    int tw = t.width();
                    int x0 = Math.max(rect.left, t.left);
                    int x1 = Math.min(rect.right, t.right);
                    int y0 = Math.max(rect.top, t.top);
                    int y1 = Math.min(rect.bottom, t.bottom);
                    for (int y = y0; y < y1; y++) {
                        System.arraycopy(tile, (y - t.top) * tw + (x0 - t.left), out,
                                (y - rect.top) * w + (x0 - rect.left), x1 - x0);
                    }
                }
            }
            return out;
        }
    }

    private static final class Edit {
        final int layerIndex;
        final Rect rect;
        final int[] before;
        final int[] after;

        Edit(int layerIndex, Rect rect, int[] before, int[] after) {
            this.layerIndex = layerIndex;
            this.rect = rect;
            this.before = before;
            this.after = after;
        }
    }
}
