// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LayerOpsTest {

    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;

    private static Editor editor() {
        Document d = new Document(70, 70);
        d.addLayer("a");
        return new Editor(d, 50);
    }

    @Test
    public void clearIsUndoable() {
        Editor e = editor();
        Layer l = e.getDocument().getLayer(0);
        l.setPixel(3, 4, RED);
        l.setPixel(69, 69, RED);
        e.clearLayer(0);
        assertEquals(0, l.getPixel(3, 4));
        assertEquals(0, l.getPixel(69, 69));
        assertTrue(e.undo());
        assertEquals(RED, l.getPixel(3, 4));
        assertEquals(RED, l.getPixel(69, 69));
        assertTrue(e.redo());
        assertEquals(0, l.getPixel(3, 4));
    }

    @Test
    public void flipMirrorsAndUndoes() {
        Editor e = editor();
        Layer l = e.getDocument().getLayer(0);
        l.setPixel(0, 0, RED);
        e.flipLayer(0, true);
        assertEquals(RED, l.getPixel(69, 0));
        assertEquals(0, l.getPixel(0, 0));
        e.flipLayer(0, false);
        assertEquals(RED, l.getPixel(69, 69));
        assertTrue(e.undo());
        assertTrue(e.undo());
        assertEquals(RED, l.getPixel(0, 0));
    }

    @Test
    public void duplicateCopiesEverything() {
        Editor e = editor();
        Layer l = e.getDocument().getLayer(0);
        l.setPixel(5, 5, RED);
        l.setOpacity(100);
        l.setBlendMode(BlendMode.MULTIPLY);
        Layer c = e.duplicateLayer(0);
        assertEquals(2, e.getDocument().getLayerCount());
        assertEquals(c, e.getDocument().getLayer(1));
        assertEquals(RED, c.getPixel(5, 5));
        assertEquals(100, c.getOpacity());
        assertEquals(BlendMode.MULTIPLY, c.getBlendMode());
    }

    @Test
    public void mergeDownCombinesAndRemoves() {
        Editor e = editor();
        Document d = e.getDocument();
        d.getLayer(0).setPixel(1, 1, RED);
        Layer top = e.addLayer("top", 1);
        top.setPixel(1, 1, BLUE);
        top.setPixel(2, 2, BLUE);
        e.mergeDown(1);
        assertEquals(1, d.getLayerCount());
        assertEquals(BLUE, d.getLayer(0).getPixel(1, 1));
        assertEquals(BLUE, d.getLayer(0).getPixel(2, 2));
    }

    @Test
    public void newBlendModesStayValid() {
        int[] samples = {0xFF336699, 0x80402010, 0x40FFFFFF, 0xFF000000, 0x01010101};
        for (BlendMode m : BlendMode.values()) {
            for (int s : samples) {
                for (int d : samples) {
                    int r = m.blend(PixelOps.premultiply(s), PixelOps.premultiply(d));
                    int a = r >>> 24;
                    assertTrue(m + " red", ((r >> 16) & 0xFF) <= a);
                    assertTrue(m + " green", ((r >> 8) & 0xFF) <= a);
                    assertTrue(m + " blue", (r & 0xFF) <= a);
                }
            }
        }
    }

    @Test
    public void opaqueModesMatchTheFormulas() {
        int white = 0xFFFFFFFF;
        int grey = 0xFF808080;
        assertEquals(0xFF000000 | 0x808080, BlendMode.DARKEN.blend(white, grey));
        assertEquals(0xFFFFFFFF, BlendMode.LIGHTEN.blend(white, grey));
        assertEquals(0xFF7F7F7F, BlendMode.DIFFERENCE.blend(white, grey));
        assertEquals(0xFFFFFFFF, BlendMode.ADD.blend(white, grey));
    }

    @Test
    public void openRasterKeepsNewBlendModes() throws Exception {
        Document d = new Document(8, 8);
        d.addLayer("a").setBlendMode(BlendMode.OVERLAY);
        d.addLayer("b").setBlendMode(BlendMode.ADD);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        OpenRaster.write(d, null, out);
        Document back = OpenRaster.read(new java.io.ByteArrayInputStream(out.toByteArray()));
        assertEquals(BlendMode.OVERLAY, back.getLayer(0).getBlendMode());
        assertEquals(BlendMode.ADD, back.getLayer(1).getBlendMode());
    }
}
