// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

public class BlendModeTest {

    // ------------------------------------------------------------- PixelOps

    @Test
    public void premultiplyHandlesEdgeCases() {
        assertEquals(0, PixelOps.premultiply(0x00FFFFFF));
        assertEquals(0xFF123456, PixelOps.premultiply(0xFF123456));
        assertEquals(0x80800000, PixelOps.premultiply(0x80FF0000));
    }

    @Test
    public void unpremultiplyReversesPremultiply() {
        assertEquals(0, PixelOps.unpremultiply(0));
        assertEquals(0xFF123456, PixelOps.unpremultiply(0xFF123456));
        assertEquals(0x80FF0000, PixelOps.unpremultiply(0x80800000));
        Random random = new Random(1);
        for (int i = 0; i < 1000; i++) {
            int opaque = 0xFF000000 | random.nextInt(0x1000000);
            assertEquals(opaque, PixelOps.unpremultiply(PixelOps.premultiply(opaque)));
        }
    }

    @Test
    public void scaleMultipliesAllChannels() {
        assertEquals(0x80800000, PixelOps.scale(0xFFFF0000, 128));
        assertEquals(0xFFFF0000, PixelOps.scale(0xFFFF0000, 255));
        assertEquals(0, PixelOps.scale(0xFFFF0000, 0));
    }

    // ------------------------------------------------------------- NORMAL

    @Test
    public void normalOpaqueSourceReplacesDestination() {
        assertEquals(0xFFFF0000, BlendMode.NORMAL.blend(0xFFFF0000, 0xFF0000FF));
    }

    @Test
    public void normalTransparentSourceKeepsDestination() {
        assertEquals(0xFF0000FF, BlendMode.NORMAL.blend(0, 0xFF0000FF));
    }

    @Test
    public void normalHalfTransparentRedOverWhite() {
        int src = PixelOps.premultiply(0x80FF0000);
        assertEquals(0xFFFF7F7F, BlendMode.NORMAL.blend(src, 0xFFFFFFFF));
    }

    @Test
    public void normalHalfOverHalfAddsAlpha() {
        assertEquals(0xC0C00000, BlendMode.NORMAL.blend(0x80800000, 0x80800000));
    }

    // ------------------------------------------------------------- MULTIPLY

    @Test
    public void multiplyWithWhiteKeepsSource() {
        assertEquals(0xFF336699, BlendMode.MULTIPLY.blend(0xFF336699, 0xFFFFFFFF));
    }

    @Test
    public void multiplyWithBlackGivesBlack() {
        assertEquals(0xFF000000, BlendMode.MULTIPLY.blend(0xFF336699, 0xFF000000));
    }

    @Test
    public void multiplyMidGrays() {
        assertEquals(0xFF404040, BlendMode.MULTIPLY.blend(0xFF808080, 0xFF808080));
    }

    @Test
    public void multiplyOverTransparentKeepsSource() {
        assertEquals(0xFF336699, BlendMode.MULTIPLY.blend(0xFF336699, 0));
    }

    // ------------------------------------------------------------- SCREEN

    @Test
    public void screenWithBlackKeepsSource() {
        assertEquals(0xFF336699, BlendMode.SCREEN.blend(0xFF336699, 0xFF000000));
    }

    @Test
    public void screenWithWhiteGivesWhite() {
        assertEquals(0xFFFFFFFF, BlendMode.SCREEN.blend(0xFF336699, 0xFFFFFFFF));
    }

    @Test
    public void screenMidGrays() {
        assertEquals(0xFFC0C0C0, BlendMode.SCREEN.blend(0xFF808080, 0xFF808080));
    }

    // ------------------------------------------------------------- all modes

    @Test
    public void everyModeKeepsPixelsValidlyPremultiplied() {
        Random random = new Random(7);
        for (BlendMode mode : BlendMode.values()) {
            for (int i = 0; i < 5000; i++) {
                int src = PixelOps.premultiply(random.nextInt());
                int dst = PixelOps.premultiply(random.nextInt());
                int out = mode.blend(src, dst);
                int a = PixelOps.alpha(out);
                assertTrue(mode + " red", PixelOps.red(out) <= a);
                assertTrue(mode + " green", PixelOps.green(out) <= a);
                assertTrue(mode + " blue", PixelOps.blue(out) <= a);
            }
        }
    }

    @Test
    public void everyModeLeavesDestinationAloneForTransparentSource() {
        Random random = new Random(11);
        for (BlendMode mode : BlendMode.values()) {
            for (int i = 0; i < 1000; i++) {
                int dst = PixelOps.premultiply(random.nextInt());
                assertEquals(mode.toString(), dst, mode.blend(0, dst));
            }
        }
    }
}
