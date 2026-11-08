// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

/**
 * An immutable round brush.
 *
 * <ul>
 *   <li><b>radius</b> in pixels;</li>
 *   <li><b>hardness</b> from 0 (soft, fades from the centre) to 1 (hard edge with a one pixel
 *       anti-aliased rim);</li>
 *   <li><b>flow</b> from 0 to 1: how opaque one stamp is at full pressure;</li>
 *   <li><b>spacing</b>: distance between stamps as a fraction of the diameter;</li>
 *   <li>an <b>eraser</b> brush removes paint instead of adding it.</li>
 * </ul>
 */
public final class Brush {

    private final int rgb;
    private final float radius;
    private final float hardness;
    private final float flow;
    private final float spacing;
    private final boolean eraser;

    /**
     * @param rgb      colour as {@code 0xRRGGBB}; the top byte is ignored (use {@code flow})
     * @param radius   greater than 0 and at most 2048
     * @param hardness 0..1
     * @param flow     0..1
     * @param spacing  0.01..2
     * @param eraser   true to erase instead of paint
     * @throws IllegalArgumentException if a value is out of range
     */
    public Brush(int rgb, float radius, float hardness, float flow, float spacing,
            boolean eraser) {
        if (!(radius > 0f && radius <= 2048f)) {
            throw new IllegalArgumentException("Radius must be in (0, 2048]");
        }
        if (!(hardness >= 0f && hardness <= 1f)) {
            throw new IllegalArgumentException("Hardness must be in [0, 1]");
        }
        if (!(flow >= 0f && flow <= 1f)) {
            throw new IllegalArgumentException("Flow must be in [0, 1]");
        }
        if (!(spacing >= 0.01f && spacing <= 2f)) {
            throw new IllegalArgumentException("Spacing must be in [0.01, 2]");
        }
        this.rgb = rgb & 0xFFFFFF;
        this.radius = radius;
        this.hardness = hardness;
        this.flow = flow;
        this.spacing = spacing;
        this.eraser = eraser;
    }

    /** A fairly hard painting brush. */
    public static Brush pen(int rgb, float radius) {
        return new Brush(rgb, radius, 0.8f, 1f, 0.15f, false);
    }

    /** A hard eraser. */
    public static Brush eraser(float radius) {
        return new Brush(0, radius, 1f, 1f, 0.15f, true);
    }

    /** @return the colour as {@code 0xRRGGBB} */
    public int getRgb() {
        return rgb;
    }

    public float getRadius() {
        return radius;
    }

    public float getHardness() {
        return hardness;
    }

    public float getFlow() {
        return flow;
    }

    public float getSpacing() {
        return spacing;
    }

    public boolean isEraser() {
        return eraser;
    }
}
