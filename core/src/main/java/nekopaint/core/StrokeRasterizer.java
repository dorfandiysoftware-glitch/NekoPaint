// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

/**
 * Paints one stroke onto a layer by stamping a round brush along the path of the input points.
 *
 * <p>Stamps are placed at regular distances (see {@link Brush#getSpacing()}) along the straight
 * lines between consecutive points, with the pressure interpolated. Pressure scales the flow
 * only; the size stays constant. Overlapping stamps accumulate, like paint.
 *
 * <p>The rasterizer remembers which area it actually changed, see {@link #getDirty()}.
 *
 * <p>Not thread safe.
 */
public final class StrokeRasterizer {

    /** Input coordinates are clamped to this range so a bad value cannot stall the loop. */
    private static final float MAX_COORD = 100000f;

    private final Layer layer;
    private final Brush brush;
    private final int[] pixels;
    private final int width;
    private final int height;

    private boolean hasLast;
    private float lastX;
    private float lastY;
    private float lastPressure;
    private float carry;

    private int minX = Integer.MAX_VALUE;
    private int minY = Integer.MAX_VALUE;
    private int maxX = -1;
    private int maxY = -1;

    private int pendMinX = Integer.MAX_VALUE;
    private int pendMinY = Integer.MAX_VALUE;
    private int pendMaxX = -1;
    private int pendMaxY = -1;

    public StrokeRasterizer(Layer layer, Brush brush) {
        if (layer == null || brush == null) {
            throw new IllegalArgumentException("Layer and brush are required");
        }
        this.layer = layer;
        this.brush = brush;
        this.pixels = layer.pixels();
        this.width = layer.getWidth();
        this.height = layer.getHeight();
    }

    /**
     * Adds an input point and paints the part of the stroke up to it. A single point paints one
     * dot. Points with NaN or infinite coordinates are ignored.
     *
     * @param pressure 0..1; values outside are clamped, NaN counts as 0
     */
    public void addPoint(float x, float y, float pressure) {
        if (Float.isNaN(x) || Float.isNaN(y) || Float.isInfinite(x) || Float.isInfinite(y)) {
            return;
        }
        x = Math.max(-MAX_COORD, Math.min(MAX_COORD, x));
        y = Math.max(-MAX_COORD, Math.min(MAX_COORD, y));
        float p = Float.isNaN(pressure) ? 0f : Math.max(0f, Math.min(1f, pressure));
        float step = Math.max(0.5f, brush.getSpacing() * 2f * brush.getRadius());

        if (!hasLast) {
            stamp(x, y, p);
            hasLast = true;
            lastX = x;
            lastY = y;
            lastPressure = p;
            carry = step;
            return;
        }

        float dx = x - lastX;
        float dy = y - lastY;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist > 0f) {
            float pos = carry;
            while (pos <= dist) {
                float t = pos / dist;
                stamp(lastX + dx * t, lastY + dy * t, lastPressure + (p - lastPressure) * t);
                pos += step;
            }
            carry = pos - dist;
        }
        lastX = x;
        lastY = y;
        lastPressure = p;
    }

    /**
     * @return the smallest rectangle containing every pixel that changed since the previous call
     *         of this method (or since the start), or empty. Lets a screen redraw only what the
     *         latest input points touched.
     */
    public Rect takeChanged() {
        Rect r = pendMaxX < 0
                ? Rect.EMPTY
                : new Rect(pendMinX, pendMinY, pendMaxX + 1, pendMaxY + 1);
        pendMinX = Integer.MAX_VALUE;
        pendMinY = Integer.MAX_VALUE;
        pendMaxX = -1;
        pendMaxY = -1;
        return r;
    }

    /** @return the smallest rectangle containing every pixel that changed, or empty */
    public Rect getDirty() {
        return maxX < 0 ? Rect.EMPTY : new Rect(minX, minY, maxX + 1, maxY + 1);
    }

    private void stamp(float cx, float cy, float pressure) {
        float r = brush.getRadius();
        int x0 = Math.max(0, (int) Math.floor(cx - r));
        int x1 = Math.min(width - 1, (int) Math.ceil(cx + r));
        int y0 = Math.max(0, (int) Math.floor(cy - r));
        int y1 = Math.min(height - 1, (int) Math.ceil(cy + r));
        if (x0 > x1 || y0 > y1) {
            return;
        }
        // The soft rim is at least one pixel wide, which anti-aliases hard brushes.
        float fall = Math.max(r * (1f - brush.getHardness()), 1f);
        float inner = r - fall;
        float strength = brush.getFlow() * pressure;
        boolean erase = brush.isEraser();
        int rgb = brush.getRgb();
        float rSq = r * r;
        float innerSq = inner > 0f ? inner * inner : -1f;
        // Inside the solid core every pixel gets the same value, so work it out only once.
        int fullAlpha = Math.round(strength * 255f);
        int fullSource = erase ? 0 : PixelOps.premultiply((fullAlpha << 24) | rgb);

        for (int y = y0; y <= y1; y++) {
            float dy = y + 0.5f - cy;
            float rest = rSq - dy * dy;
            if (rest <= 0f) {
                continue;
            }
            // Only the part of this row that lies inside the circle needs a look.
            float half = (float) Math.sqrt(rest);
            int xa = Math.max(x0, (int) Math.floor(cx - half - 0.5f));
            int xb = Math.min(x1, (int) Math.ceil(cx + half - 0.5f));
            int coreA = 1;
            int coreB = 0;
            float coreRest = innerSq - dy * dy;
            if (coreRest > 0f) {
                float coreHalf = (float) Math.sqrt(coreRest);
                coreA = (int) Math.ceil(cx - coreHalf - 0.5f);
                coreB = (int) Math.floor(cx + coreHalf - 0.5f);
            }
            int rowStart = y * width;
            int firstChanged = -1;
            int lastChanged = -1;
            for (int x = xa; x <= xb; x++) {
                int a;
                int source;
                if (x >= coreA && x <= coreB) {
                    a = fullAlpha;
                    source = fullSource;
                } else {
                    float dx = x + 0.5f - cx;
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    if (d >= r) {
                        continue;
                    }
                    float coverage = d <= inner ? 1f : (r - d) / fall;
                    a = Math.round(coverage * strength * 255f);
                    source = erase ? 0 : PixelOps.premultiply((a << 24) | rgb);
                }
                if (a <= 0) {
                    continue;
                }
                int index = rowStart + x;
                int old = pixels[index];
                int result = erase
                        ? PixelOps.scale(old, 255 - a)
                        : BlendMode.NORMAL.blend(source, old);
                if (result != old) {
                    pixels[index] = result;
                    if (firstChanged < 0) {
                        firstChanged = x;
                    }
                    lastChanged = x;
                }
            }
            if (firstChanged >= 0) {
                markDirty(firstChanged, y);
                markDirty(lastChanged, y);
            }
        }
    }

    private void markDirty(int x, int y) {
        if (x < pendMinX) {
            pendMinX = x;
        }
        if (y < pendMinY) {
            pendMinY = y;
        }
        if (x > pendMaxX) {
            pendMaxX = x;
        }
        if (y > pendMaxY) {
            pendMaxY = y;
        }
        if (x < minX) {
            minX = x;
        }
        if (y < minY) {
            minY = y;
        }
        if (x > maxX) {
            maxX = x;
        }
        if (y > maxY) {
            maxY = y;
        }
    }

    /** The layer this rasterizer paints on. */
    public Layer getLayer() {
        return layer;
    }
}
