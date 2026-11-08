// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class StrokeRasterizerTest {

    private static final int SIZE = 40;

    private static Layer newLayer() {
        return new Layer(SIZE, SIZE, "test");
    }

    /** Red brush with a hard edge and full flow. */
    private static Brush hard(float radius) {
        return new Brush(0xFF0000, radius, 1f, 1f, 0.25f, false);
    }

    private static int alphaAt(Layer layer, int x, int y) {
        return PixelOps.alpha(layer.getPixel(x, y));
    }

    @Test
    public void hardBrushPaintsCentreAndLeavesFarPixelsAlone() {
        Layer layer = newLayer();
        StrokeRasterizer r = new StrokeRasterizer(layer, hard(5f));
        r.addPoint(20f, 20f, 1f);
        assertEquals(0xFFFF0000, layer.getPixel(20, 20));
        assertEquals(0, layer.getPixel(20, 30));
        assertEquals(0, layer.getPixel(30, 20));
        assertEquals(0, layer.getPixel(0, 0));
    }

    @Test
    public void softBrushFadesTowardsTheEdge() {
        Layer layer = newLayer();
        Brush soft = new Brush(0xFF0000, 10f, 0f, 1f, 0.25f, false);
        new StrokeRasterizer(layer, soft).addPoint(20f, 20f, 1f);
        int centre = alphaAt(layer, 20, 20);
        int middle = alphaAt(layer, 25, 20);
        assertTrue(centre > middle);
        assertTrue(middle > 0);
        assertEquals(0, alphaAt(layer, 35, 20));
    }

    @Test
    public void strokeBetweenTwoPointsIsContinuous() {
        Layer layer = newLayer();
        StrokeRasterizer r = new StrokeRasterizer(layer, hard(2f));
        r.addPoint(5f, 20f, 1f);
        r.addPoint(35f, 20f, 1f);
        for (int x = 6; x <= 34; x++) {
            assertEquals("column " + x, 255, alphaAt(layer, x, 20));
        }
        assertEquals(0, alphaAt(layer, 20, 25));
    }

    @Test
    public void strokeFollowsMultipleSegments() {
        Layer layer = newLayer();
        StrokeRasterizer r = new StrokeRasterizer(layer, hard(2f));
        r.addPoint(5f, 5f, 1f);
        r.addPoint(30f, 5f, 1f);
        r.addPoint(30f, 30f, 1f);
        assertEquals(255, alphaAt(layer, 15, 5));
        assertEquals(255, alphaAt(layer, 30, 15));
        assertEquals(0, alphaAt(layer, 15, 15));
    }

    @Test
    public void pressureScalesOpacity() {
        Layer layer = newLayer();
        new StrokeRasterizer(layer, hard(5f)).addPoint(20f, 20f, 0.5f);
        assertEquals(128, alphaAt(layer, 20, 20));
    }

    @Test
    public void pressureIsInterpolatedAlongTheSegment() {
        Layer layer = newLayer();
        StrokeRasterizer r = new StrokeRasterizer(layer, hard(2f));
        r.addPoint(5f, 20f, 0f);
        r.addPoint(35f, 20f, 1f);
        assertTrue(alphaAt(layer, 8, 20) < alphaAt(layer, 30, 20));
        assertTrue(alphaAt(layer, 30, 20) > 200);
    }

    @Test
    public void flowScalesOpacity() {
        Layer layer = newLayer();
        Brush half = new Brush(0xFF0000, 5f, 1f, 0.5f, 0.25f, false);
        new StrokeRasterizer(layer, half).addPoint(20f, 20f, 1f);
        assertEquals(128, alphaAt(layer, 20, 20));
    }

    @Test
    public void zeroPressureDrawsNothing() {
        Layer layer = newLayer();
        StrokeRasterizer r = new StrokeRasterizer(layer, hard(5f));
        r.addPoint(20f, 20f, 0f);
        assertEquals(0, layer.getPixel(20, 20));
        assertTrue(r.getDirty().isEmpty());
    }

    @Test
    public void eraserRemovesPaint() {
        Layer layer = newLayer();
        new StrokeRasterizer(layer, hard(5f)).addPoint(20f, 20f, 1f);
        assertEquals(255, alphaAt(layer, 20, 20));
        new StrokeRasterizer(layer, Brush.eraser(8f)).addPoint(20f, 20f, 1f);
        assertEquals(0, layer.getPixel(20, 20));
    }

    @Test
    public void eraserOnTransparentPixelsChangesNothing() {
        Layer layer = newLayer();
        StrokeRasterizer r = new StrokeRasterizer(layer, Brush.eraser(8f));
        r.addPoint(20f, 20f, 1f);
        assertTrue(r.getDirty().isEmpty());
    }

    @Test
    public void paintAccumulatesOverExistingPaint() {
        Layer layer = newLayer();
        Brush half = new Brush(0xFF0000, 5f, 1f, 0.5f, 0.25f, false);
        new StrokeRasterizer(layer, half).addPoint(20f, 20f, 1f);
        int once = alphaAt(layer, 20, 20);
        new StrokeRasterizer(layer, half).addPoint(20f, 20f, 1f);
        assertTrue(alphaAt(layer, 20, 20) > once);
    }

    @Test
    public void dirtyRectCoversExactlyTheChangedPixels() {
        Layer layer = newLayer();
        StrokeRasterizer r = new StrokeRasterizer(layer, hard(5f));
        r.addPoint(20f, 20f, 1f);
        assertEquals(new Rect(15, 15, 25, 25), r.getDirty());
    }

    @Test
    public void drawingOutsideOrAcrossTheEdgeIsSafe() {
        Layer layer = newLayer();
        StrokeRasterizer r = new StrokeRasterizer(layer, hard(6f));
        r.addPoint(-100f, -100f, 1f);
        assertTrue(r.getDirty().isEmpty());
        r.addPoint(0f, 0f, 1f);
        r.addPoint(39f, 39f, 1f);
        r.addPoint(1000f, 1000f, 1f);
        r.addPoint(-3f, 20f, 1f);
        assertEquals(255, alphaAt(layer, 0, 0));
        Rect dirty = r.getDirty();
        assertTrue(dirty.left >= 0 && dirty.top >= 0 && dirty.right <= SIZE
                && dirty.bottom <= SIZE);
    }

    @Test
    public void badInputIsIgnored() {
        Layer layer = newLayer();
        StrokeRasterizer r = new StrokeRasterizer(layer, hard(5f));
        r.addPoint(Float.NaN, 5f, 1f);
        r.addPoint(5f, Float.POSITIVE_INFINITY, 1f);
        assertTrue(r.getDirty().isEmpty());
        r.addPoint(20f, 20f, Float.NaN); // treated as zero pressure
        assertEquals(0, layer.getPixel(20, 20));
        Layer other = newLayer();
        new StrokeRasterizer(other, hard(5f)).addPoint(20f, 20f, 5f); // clamped to 1
        assertEquals(255, alphaAt(other, 20, 20));
    }

    @Test
    public void brushValidatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> new Brush(0, 0f, 1f, 1f, 0.1f, false));
        assertThrows(IllegalArgumentException.class,
                () -> new Brush(0, Float.NaN, 1f, 1f, 0.1f, false));
        assertThrows(IllegalArgumentException.class, () -> new Brush(0, 5f, 1.5f, 1f, 0.1f, false));
        assertThrows(IllegalArgumentException.class, () -> new Brush(0, 5f, 1f, -0.1f, 0.1f, false));
        assertThrows(IllegalArgumentException.class, () -> new Brush(0, 5f, 1f, 1f, 0f, false));
        assertEquals(0xFF0000, new Brush(0xFFFF0000, 5f, 1f, 1f, 0.1f, false).getRgb());
        assertThrows(IllegalArgumentException.class, () -> new StrokeRasterizer(null, hard(5f)));
    }

    @Test
    public void takeChangedReportsOnlyNewChanges() {
        Layer layer = newLayer();
        StrokeRasterizer r = new StrokeRasterizer(layer, hard(3f));
        r.addPoint(10f, 10f, 1f);
        Rect first = r.takeChanged();
        assertEquals(new Rect(7, 7, 13, 13), first);
        assertTrue(r.takeChanged().isEmpty());
        r.addPoint(30f, 10f, 1f);
        Rect second = r.takeChanged();
        assertTrue(second.left >= 10);
        assertTrue(second.right <= 34);
        assertEquals(first.union(second), r.getDirty());
    }
}
