// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

import java.util.ArrayDeque;

/**
 * Edits a {@link Document} with undo and redo.
 *
 * <p>Each finished stroke becomes one history entry that stores only the rectangle it changed
 * (before and after), so undo is exact and cheap in memory. While a stroke is being drawn a
 * full copy of the layer is kept so the changed area can be extracted; this is fine for now
 * and can be replaced by lazily saved tiles for very large canvases.
 *
 * <p>Only one stroke can be in progress at a time. Layers cannot be removed or reordered yet:
 * when those operations are added they must become history entries too.
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

    private static int[] extract(int[] source, int width, Rect rect) {
        int w = rect.width();
        int[] out = new int[w * rect.height()];
        for (int y = 0; y < rect.height(); y++) {
            System.arraycopy(source, (rect.top + y) * width + rect.left, out, y * w, w);
        }
        return out;
    }

    /** A stroke in progress. Call {@link #end()} or {@link #cancel()} exactly once. */
    public final class Stroke {
        private final int layerIndex;
        private final Layer layer;
        private final int[] before;
        private final StrokeRasterizer rasterizer;
        private boolean finished;

        private Stroke(int layerIndex, Layer layer, Brush brush) {
            this.layerIndex = layerIndex;
            this.layer = layer;
            this.before = layer.copyPixels();
            this.rasterizer = new StrokeRasterizer(layer, brush);
        }

        /** Adds an input point; see {@link StrokeRasterizer#addPoint(float, float, float)}. */
        public void addPoint(float x, float y, float pressure) {
            requireActive();
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
            Edit edit = new Edit(layerIndex, dirty, extract(before, layer.getWidth(), dirty),
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
                layer.pasteRect(dirty, extract(before, layer.getWidth(), dirty));
            }
        }

        private void requireActive() {
            if (finished) {
                throw new IllegalStateException("The stroke is already finished");
            }
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
