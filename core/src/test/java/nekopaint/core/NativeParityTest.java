// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.Random;
import org.junit.Assume;
import org.junit.Test;

/**
 * The C core must paint exactly what the Java reference paints. Runs only when the native
 * library is loaded: {@code -Dnekopaint.native=/path/libnekocore.so}.
 */
public class NativeParityTest {

    private static int[] paint(boolean java, long seed, Brush brush, int w, int h, int[] base,
            Rect[] rects) {
        System.setProperty("nekopaint.java", java ? "true" : "false");
        try {
            Layer layer = new Layer(w, h, "t");
            System.arraycopy(base, 0, layer.pixels(), 0, base.length);
            StrokeRasterizer s = new StrokeRasterizer(layer, brush);
            Random rnd = new Random(seed);
            float x = rnd.nextFloat() * w;
            float y = rnd.nextFloat() * h;
            for (int i = 0; i < 25; i++) {
                x += (rnd.nextFloat() - 0.5f) * 90f;
                y += (rnd.nextFloat() - 0.5f) * 90f;
                s.addPoint(x, y, rnd.nextFloat());
                if (i % 7 == 0) {
                    rects[0] = s.takeChanged();
                }
            }
            rects[0] = s.takeChanged();
            rects[1] = s.getDirty();
            return layer.pixels().clone();
        } finally {
            System.clearProperty("nekopaint.java");
        }
    }

    @Test
    public void nativeMatchesJava() {
        Assume.assumeTrue(NativeStroke.AVAILABLE);
        Random rnd = new Random(42);
        int w = 211;
        int h = 173;
        int[] base = new int[w * h];
        for (int i = 0; i < base.length; i++) {
            int a = rnd.nextInt(256);
            base[i] = PixelOps.premultiply((a << 24) | rnd.nextInt(0x1000000));
        }
        float[] radii = {0.6f, 3f, 17.5f, 60f};
        float[] hard = {0f, 0.3f, 0.8f, 1f};
        float[] flows = {0.2f, 0.7f, 1f};
        int n = 0;
        for (float r : radii) {
            for (float hd : hard) {
                for (float fl : flows) {
                    for (int er = 0; er < 2; er++) {
                        Brush b = new Brush(rnd.nextInt(0x1000000), r, hd, fl, 0.15f, er == 1);
                        Rect[] ra = new Rect[2];
                        Rect[] rb = new Rect[2];
                        int[] j = paint(true, n, b, w, h, base, ra);
                        int[] c = paint(false, n, b, w, h, base, rb);
                        assertArrayEquals("pixels r=" + r + " hard=" + hd + " flow=" + fl
                                + " eraser=" + er, j, c);
                        assertEquals(ra[0].left + "," + ra[0].top + "," + ra[0].right + ","
                                + ra[0].bottom, rb[0].left + "," + rb[0].top + "," + rb[0].right
                                + "," + rb[0].bottom);
                        assertEquals(ra[1].left, rb[1].left);
                        assertEquals(ra[1].bottom, rb[1].bottom);
                        n++;
                    }
                }
            }
        }
    }

    /** Big stamps take the multi-threaded path in C. */
    @Test
    public void bigBrushMatchesJava() {
        Assume.assumeTrue(NativeStroke.AVAILABLE);
        Random rnd = new Random(7);
        int w = 700;
        int h = 600;
        int[] base = new int[w * h];
        for (int i = 0; i < base.length; i++) {
            int a = rnd.nextInt(256);
            base[i] = PixelOps.premultiply((a << 24) | rnd.nextInt(0x1000000));
        }
        float[] hard = {0f, 0.6f, 1f};
        for (int k = 0; k < hard.length; k++) {
            for (int er = 0; er < 2; er++) {
                Brush b = new Brush(rnd.nextInt(0x1000000), 220f, hard[k], 0.8f, 0.3f, er == 1);
                Rect[] ra = new Rect[2];
                Rect[] rb = new Rect[2];
                int[] j = paint(true, k, b, w, h, base, ra);
                int[] c = paint(false, k, b, w, h, base, rb);
                assertArrayEquals("big hard=" + hard[k] + " eraser=" + er, j, c);
                assertEquals(ra[1].left, rb[1].left);
                assertEquals(ra[1].top, rb[1].top);
                assertEquals(ra[1].right, rb[1].right);
                assertEquals(ra[1].bottom, rb[1].bottom);
            }
        }
    }
}
