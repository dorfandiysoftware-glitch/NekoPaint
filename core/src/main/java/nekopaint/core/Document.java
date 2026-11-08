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
        int[] out = new int[width * height];
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
            for (int i = 0; i < out.length; i++) {
                int s = PixelOps.scale(source[i], opacity);
                if (PixelOps.alpha(s) != 0) {
                    out[i] = mode.blend(s, out[i]);
                }
            }
        }
        return out;
    }
}
