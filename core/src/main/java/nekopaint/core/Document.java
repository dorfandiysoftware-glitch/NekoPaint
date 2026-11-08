// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A drawing: a canvas size and a stack of layers, bottom first. */
public final class Document {

    private final int width;
    private final int height;
    private final List<Layer> layers = new ArrayList<Layer>();

    /** @throws IllegalArgumentException if the size is out of range */
    public Document(int width, int height) {
        Layer.checkSize(width, height);
        this.width = width;
        this.height = height;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /** Adds a new transparent layer on top of the stack and returns it. */
    public Layer addLayer(String name) {
        Layer layer = new Layer(width, height, name);
        layers.add(layer);
        return layer;
    }

    /** Removes a layer; a document always keeps at least one. */
    public void removeLayer(int index) {
        if (layers.size() <= 1) {
            throw new IllegalStateException("A document needs at least one layer");
        }
        layers.remove(index);
    }

    /** Moves a layer to another position in the stack (0 is the bottom). */
    public void moveLayer(int from, int to) {
        if (to < 0 || to >= layers.size()) {
            throw new IndexOutOfBoundsException("Bad target position: " + to);
        }
        layers.add(to, layers.remove(from));
    }

    public int getLayerCount() {
        return layers.size();
    }

    /** @param index 0 is the bottom layer */
    public Layer getLayer(int index) {
        return layers.get(index);
    }

    /** Combines all visible layers on a transparent background. */
    public int[] flatten() {
        return flatten(0);
    }

    /**
     * Combines all visible layers on top of a background.
     *
     * @param backgroundArgb straight (non-premultiplied) ARGB colour; 0 means transparent
     * @return premultiplied ARGB pixels, row by row
     */
    public int[] flatten(int backgroundArgb) {
        return flattenRect(new Rect(0, 0, width, height), backgroundArgb);
    }

    /**
     * Like {@link #flatten(int)} but only for one rectangle of the canvas.
     *
     * @return premultiplied ARGB pixels of the rectangle, row by row
     * @throws IllegalArgumentException if the rectangle is not inside the canvas
     */
    public int[] flattenRect(Rect rect, int backgroundArgb) {
        if (rect.left < 0 || rect.top < 0 || rect.right > width || rect.bottom > height) {
            throw new IllegalArgumentException("Rectangle outside the canvas: " + rect);
        }
        int w = rect.width();
        int h = rect.height();
        int[] out = new int[w * h];
        int background = PixelOps.premultiply(backgroundArgb);
        if (background != 0) {
            Arrays.fill(out, background);
        }
        for (Layer layer : layers) {
            int opacity = layer.getOpacity();
            if (!layer.isVisible() || opacity == 0) {
                continue;
            }
            int[] source = layer.pixels();
            BlendMode mode = layer.getBlendMode();
            for (int y = 0; y < h; y++) {
                int src = (rect.top + y) * width + rect.left;
                int dst = y * w;
                for (int x = 0; x < w; x++) {
                    int s = source[src + x];
                    if (s == 0) {
                        continue;
                    }
                    if (opacity != 255) {
                        s = PixelOps.scale(s, opacity);
                        if (PixelOps.alpha(s) == 0) {
                            continue;
                        }
                    }
                    out[dst + x] = (s >>> 24) == 255 && mode == BlendMode.NORMAL
                            ? s : mode.blend(s, out[dst + x]);
                }
            }
        }
        return out;
    }
}
