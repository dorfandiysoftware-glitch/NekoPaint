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
    private ProjectLog log;

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

    /** The recording of this drawing, or null if nothing is being recorded. */
    public ProjectLog getLog() {
        return log;
    }

    /** Starts (or stops, with null) recording strokes and layer changes. */
    public void setLog(ProjectLog log) {
        this.log = log;
    }

    // ------------------------------------------------------------------ layers

    /**
     * Adds a transparent layer at a position (0 is the bottom). Not undoable: the undo history
     * is cleared.
     */
    public Layer addLayer(String name, int at) {
        requireNoStroke();
        Layer layer = document.addLayer(name);
        int target = Math.max(0, Math.min(at, document.getLayerCount() - 1));
        document.moveLayer(document.getLayerCount() - 1, target);
        clearHistory();
        if (log != null) {
            log.addOp(ProjectLog.ADD_LAYER, target, 0, name);
        }
        return layer;
    }

    /** Removes a layer (the last one cannot be removed). Clears the undo history. */
    public void removeLayer(int index) {
        requireNoStroke();
        document.removeLayer(index);
        clearHistory();
        if (log != null) {
            log.addOp(ProjectLog.REMOVE_LAYER, index, 0, null);
        }
    }

    /** Moves a layer in the stack. Clears the undo history. */
    public void moveLayer(int from, int to) {
        requireNoStroke();
        document.moveLayer(from, to);
        clearHistory();
        if (log != null) {
            log.addOp(ProjectLog.MOVE_LAYER, from, to, null);
        }
    }

    public void setLayerVisible(int index, boolean visible) {
        document.getLayer(index).setVisible(visible);
        if (log != null) {
            log.addOp(ProjectLog.VISIBLE, index, visible ? 1 : 0, null);
        }
    }

    public void setLayerOpacity(int index, int opacity) {
        document.getLayer(index).setOpacity(opacity);
        if (log != null) {
            log.addOp(ProjectLog.OPACITY, index, opacity, null);
        }
    }

    public void setLayerBlendMode(int index, BlendMode mode) {
        document.getLayer(index).setBlendMode(mode);
        if (log != null) {
            log.addOp(ProjectLog.BLEND, index, mode.ordinal(), null);
        }
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
        edit.apply(document.getLayer(edit.layerIndex), edit.before);
        lastChanged = edit.bounds;
        if (log != null && edit.event != null) {
            log.remove(edit.event);
        }
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
        edit.apply(document.getLayer(edit.layerIndex), edit.after);
        lastChanged = edit.bounds;
        if (log != null && edit.event != null) {
            log.restore(edit.event);
        }
        undoStack.addLast(edit);
        return true;
    }

    private void requireNoStroke() {
        if (activeStroke != null) {
            throw new IllegalStateException("A stroke is in progress");
        }
    }

    /** Upper limit for the memory the undo history may use (older entries are dropped). */
    private static final long MAX_HISTORY_BYTES = 96L * 1024 * 1024;

    private long historyBytes() {
        long total = 0;
        for (Edit e : undoStack) {
            total += e.bytes();
        }
        return total;
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
        private final Brush brush;
        private float[] recorded = new float[192];
        private int recordedCount;
        private boolean hasLast;
        private float lastX;
        private float lastY;
        private boolean finished;

        private Stroke(int layerIndex, Layer layer, Brush brush) {
            this.layerIndex = layerIndex;
            this.layer = layer;
            this.tilesAcross = (layer.getWidth() + TILE - 1) / TILE;
            this.radius = brush.getRadius();
            this.brush = brush;
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
                if (log != null) {
                    record(x, y, Float.isNaN(pressure) ? 0f
                            : Math.max(0f, Math.min(1f, pressure)));
                }
            }
            rasterizer.addPoint(x, y, pressure);
        }

        private void record(float x, float y, float p) {
            if (recordedCount * 3 + 3 > recorded.length) {
                recorded = java.util.Arrays.copyOf(recorded, recorded.length * 2);
            }
            recorded[recordedCount * 3] = x;
            recorded[recordedCount * 3 + 1] = y;
            recorded[recordedCount * 3 + 2] = p;
            recordedCount++;
        }

        /** @return what changed since the previous call of this method, or empty */
        public Rect takeChanged() {
            return rasterizer.takeChanged();
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
            ProjectLog.Event event = log == null
                    ? null : log.addStroke(layerIndex, brush, recorded, recordedCount);
            Edit edit = buildEdit(event);
            if (edit == null) {
                return;
            }
            undoStack.addLast(edit);
            redoStack.clear();
            while (undoStack.size() > maxHistory
                    || (undoStack.size() > 1 && historyBytes() > MAX_HISTORY_BYTES)) {
                undoStack.pollFirst();
            }
        }

        /** Throws the stroke away: the layer returns to how it was and no history is added. */
        public void cancel() {
            requireActive();
            finished = true;
            activeStroke = null;
            for (java.util.Map.Entry<Integer, int[]> e : savedTiles.entrySet()) {
                int key = e.getKey();
                layer.pasteRect(tileRectOf(key), e.getValue());
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
            return tileRectOf(ty * tilesAcross + tx);
        }

        private Rect tileRectOf(int key) {
            return Editor.tileRect(key, tilesAcross, layer.getWidth(), layer.getHeight());
        }

        /** Keeps only the tiles that really changed: their original and final pixels. */
        private Edit buildEdit(ProjectLog.Event event) {
            int n = savedTiles.size();
            int[] keys = new int[n];
            int[][] before = new int[n][];
            int[][] after = new int[n][];
            int count = 0;
            Rect bounds = Rect.EMPTY;
            for (java.util.Map.Entry<Integer, int[]> e : savedTiles.entrySet()) {
                int key = e.getKey();
                Rect r = tileRectOf(key);
                int[] now = layer.copyRect(r);
                if (java.util.Arrays.equals(now, e.getValue())) {
                    continue;
                }
                keys[count] = key;
                before[count] = e.getValue();
                after[count] = now;
                count++;
                bounds = bounds.union(r);
            }
            if (count == 0) {
                return null;
            }
            return new Edit(layerIndex, tilesAcross, java.util.Arrays.copyOf(keys, count),
                    java.util.Arrays.copyOf(before, count), java.util.Arrays.copyOf(after, count),
                    bounds, event);
        }
    }

    private static Rect tileRect(int key, int across, int width, int height) {
        int tx = key % across;
        int ty = key / across;
        return new Rect(tx * TILE, ty * TILE, Math.min(width, (tx + 1) * TILE),
                Math.min(height, (ty + 1) * TILE));
    }

    /** One history entry: the tiles a stroke changed, before and after. */
    private final class Edit {
        final int layerIndex;
        final int across;
        final int[] keys;
        final int[][] before;
        final int[][] after;
        final Rect bounds;
        final ProjectLog.Event event;

        Edit(int layerIndex, int across, int[] keys, int[][] before, int[][] after, Rect bounds,
                ProjectLog.Event event) {
            this.layerIndex = layerIndex;
            this.across = across;
            this.keys = keys;
            this.before = before;
            this.after = after;
            this.bounds = bounds;
            this.event = event;
        }

        long bytes() {
            long n = 0;
            for (int i = 0; i < keys.length; i++) {
                n += (before[i].length + after[i].length) * 4L;
            }
            return n;
        }

        void apply(Layer layer, int[][] pixels) {
            for (int i = 0; i < keys.length; i++) {
                layer.pasteRect(tileRect(keys[i], across, layer.getWidth(), layer.getHeight()),
                        pixels[i]);
            }
        }
    }
}
