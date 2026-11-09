// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

/**
 * One raster layer: a grid of premultiplied ARGB pixels plus the settings used when layers are
 * combined (visibility, opacity, blend mode).
 */
public final class Layer {

    /** Largest accepted width or height. */
    public static final int MAX_SIZE = 16384;

    /** Largest accepted pixel count (width times height). */
    public static final int MAX_PIXELS = 50000000;

    private final int width;
    private final int height;
    private final int[] pixels;
    private String name;
    private boolean visible = true;
    private int opacity = 255;
    private BlendMode blendMode = BlendMode.NORMAL;

    /**
     * Creates a fully transparent layer.
     *
     * @throws IllegalArgumentException if the size is out of range
     */
    public Layer(int width, int height, String name) {
        checkSize(width, height);
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
        setName(name);
    }

    static void checkSize(int width, int height) {
        if (width < 1 || height < 1 || width > MAX_SIZE || height > MAX_SIZE
                || (long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("Unsupported canvas size");
        }
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Name is missing");
        }
        this.name = name;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    /** @return opacity from 0 (invisible) to 255 (opaque) */
    public int getOpacity() {
        return opacity;
    }

    /** @throws IllegalArgumentException unless {@code opacity} is in 0..255 */
    public void setOpacity(int opacity) {
        if (opacity < 0 || opacity > 255) {
            throw new IllegalArgumentException("Opacity must be 0..255");
        }
        this.opacity = opacity;
    }

    public BlendMode getBlendMode() {
        return blendMode;
    }

    public void setBlendMode(BlendMode blendMode) {
        if (blendMode == null) {
            throw new IllegalArgumentException("Blend mode is missing");
        }
        this.blendMode = blendMode;
    }

    /** @return the premultiplied ARGB pixel at (x, y) */
    public int getPixel(int x, int y) {
        checkPoint(x, y);
        return pixels[y * width + x];
    }

    /** Sets a pixel. The value must be premultiplied ARGB. */
    public void setPixel(int x, int y, int premultipliedArgb) {
        checkPoint(x, y);
        pixels[y * width + x] = premultipliedArgb;
    }

    /** @return a copy of all pixels, row by row */
    public int[] copyPixels() {
        return pixels.clone();
    }

    /**
     * Copies a rectangle of pixels, row by row.
     *
     * @throws IllegalArgumentException if the rectangle is not inside the layer
     */
    public int[] copyRect(Rect rect) {
        checkRect(rect);
        int w = rect.width();
        int[] out = new int[w * rect.height()];
        for (int y = 0; y < rect.height(); y++) {
            System.arraycopy(pixels, (rect.top + y) * width + rect.left, out, y * w, w);
        }
        return out;
    }

    /**
     * Overwrites a rectangle of pixels with {@code data} (row by row).
     *
     * @throws IllegalArgumentException if the rectangle is not inside the layer or the data
     *                                  length does not match
     */
    public void pasteRect(Rect rect, int[] data) {
        checkRect(rect);
        int w = rect.width();
        if (data == null || data.length != w * rect.height()) {
            throw new IllegalArgumentException("Data does not match the rectangle");
        }
        for (int y = 0; y < rect.height(); y++) {
            System.arraycopy(data, y * w, pixels, (rect.top + y) * width + rect.left, w);
        }
    }

    /**
     * Replaces the whole layer with an image given as ordinary (non-premultiplied) ARGB pixels.
     *
     * @throws IllegalArgumentException if the length does not match the layer size
     */
    public void setStraightArgb(int[] argb) {
        if (argb == null || argb.length != pixels.length) {
            throw new IllegalArgumentException("Data does not match the layer");
        }
        for (int i = 0; i < argb.length; i++) {
            pixels[i] = PixelOps.premultiply(argb[i]);
        }
    }

    /** The live pixel array, for use inside this package. */
    int[] pixels() {
        return pixels;
    }

    private void checkPoint(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            throw new IndexOutOfBoundsException("Pixel is outside the layer");
        }
    }

    private void checkRect(Rect rect) {
        if (rect == null || rect.left < 0 || rect.top < 0 || rect.right > width
                || rect.bottom > height) {
            throw new IllegalArgumentException("Rectangle is outside the layer");
        }
    }
}
