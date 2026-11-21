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

    @Test
    public void clippedLayerShowsOnlyOverTheBase() {
        Document d = new Document(8, 8);
        Layer base = d.addLayer("base");
        base.setPixel(1, 1, 0xFF00FF00);
        Layer clip = d.addLayer("clip");
        clip.setClipped(true);
        clip.setPixel(1, 1, RED);
        clip.setPixel(3, 3, RED);
        int[] flat = d.flatten();
        assertEquals(RED, flat[1 * 8 + 1]);
        assertEquals(0, flat[3 * 8 + 3]);
        base.setVisible(false);
        assertEquals(0, d.flatten()[1 * 8 + 1]);
    }

    @Test
    public void alphaLockKeepsTransparentPixelsEmpty() {
        Document d = new Document(40, 20);
        Layer l = d.addLayer("a");
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 20; x++) {
                l.setPixel(x, y, 0xFF00FF00);
            }
        }
        l.setAlphaLock(true);
        Editor e = new Editor(d, 10);
        Editor.Stroke s = e.beginStroke(0, new Brush(0xFF0000, 6f, 1f, 1f, 0.15f, false));
        s.addPoint(2, 10, 1f);
        s.addPoint(38, 10, 1f);
        s.end();
        assertEquals(0, l.getPixel(30, 10));
        assertEquals(0, l.getPixel(25, 9));
        assertEquals(255, l.getPixel(10, 10) >>> 24);
        assertEquals(RED, l.getPixel(10, 10));
        assertTrue(e.undo());
        assertEquals(0xFF00FF00, l.getPixel(10, 10));
    }

    @Test
    public void alphaLockOnEmptyLayerAddsNoHistory() {
        Document d = new Document(40, 20);
        Layer l = d.addLayer("a");
        l.setAlphaLock(true);
        Editor e = new Editor(d, 10);
        Editor.Stroke s = e.beginStroke(0, new Brush(0xFF0000, 6f, 1f, 1f, 0.15f, false));
        s.addPoint(2, 10, 1f);
        s.addPoint(38, 10, 1f);
        s.end();
        assertEquals(0, l.getPixel(10, 10));
        assertTrue(!e.canUndo());
    }

    @Test
    public void undoReportsWhatItUndid() {
        Document d = new Document(40, 20);
        d.addLayer("a");
        Editor e = new Editor(d, 10);
        Editor.Stroke s = e.beginStroke(0, Brush.eraser(4f));
        s.addPoint(5, 5, 1f);
        s.end();
        Editor.Stroke p = e.beginStroke(0, new Brush(0xFF0000, 6f, 1f, 1f, 0.15f, false));
        p.addPoint(10, 10, 1f);
        p.end();
        assertTrue(e.undo());
        assertEquals(Editor.KIND_STROKE, e.getLastKind());
        assertTrue(e.redo());
        e.clearLayer(0);
        assertTrue(e.undo());
        assertEquals(Editor.KIND_CLEAR, e.getLastKind());
    }

    @Test
    public void selectionLimitsPaintingAndIsNotFlattened() {
        Document d = new Document(60, 30);
        Layer l = d.addLayer("a");
        Editor e = new Editor(d, 10);
        Layer sel = e.addSelectionLayer();
        int selIndex = d.getLayerCount() - 1;
        Editor.Stroke s = e.beginStroke(selIndex, new Brush(0x000000, 5f, 1f, 1f, 0.15f, false));
        s.addPoint(30, 15, 1f);
        s.addPoint(58, 15, 1f);
        s.end();
        assertTrue(sel.getPixel(50, 15) >>> 24 == 255);
        assertEquals(0, d.flatten()[15 * 60 + 50]);
        Editor.Stroke p = e.beginStroke(0, new Brush(0xFF0000, 8f, 1f, 1f, 0.15f, false));
        p.addPoint(2, 15, 1f);
        p.addPoint(58, 15, 1f);
        p.end();
        assertEquals(0, l.getPixel(10, 15));
        assertEquals(0xFFFF0000, l.getPixel(50, 15));
        sel.setVisible(false);
        Editor.Stroke q = e.beginStroke(0, new Brush(0x00FF00, 8f, 1f, 1f, 0.15f, false));
        q.addPoint(2, 5, 1f);
        q.addPoint(20, 5, 1f);
        q.end();
        assertEquals(0xFF00FF00, l.getPixel(10, 5));
    }

    @Test
    public void layerOperationsAreUndoable() {
        Document d = new Document(40, 20);
        d.addLayer("a");
        Editor e = new Editor(d, 20);
        Layer b = e.addLayer("b", 1);
        b.setPixel(1, 1, RED);
        assertEquals(2, d.getLayerCount());
        assertTrue(e.undo());
        assertEquals(1, d.getLayerCount());
        assertTrue(e.wasStructural());
        assertTrue(e.redo());
        assertEquals(2, d.getLayerCount());
        assertEquals(RED, d.getLayer(1).getPixel(1, 1));

        e.setLayerOpacity(1, 200);
        e.setLayerOpacity(1, 100);
        assertEquals(100, d.getLayer(1).getOpacity());
        assertTrue(e.undo());
        assertEquals(255, d.getLayer(1).getOpacity()); // one entry for the whole drag

        e.moveLayer(1, 0);
        assertEquals(b, d.getLayer(0));
        assertTrue(e.undo());
        assertEquals(b, d.getLayer(1));

        e.mergeDown(1);
        assertEquals(1, d.getLayerCount());
        assertEquals(RED, d.getLayer(0).getPixel(1, 1));
        assertTrue(e.undo());
        assertEquals(2, d.getLayerCount());
        assertEquals(0, d.getLayer(0).getPixel(1, 1));

        e.removeLayer(1);
        assertEquals(1, d.getLayerCount());
        assertTrue(e.undo());
        assertEquals(b, d.getLayer(1));
        e.fillLayer(0, 0x00FF00);
        assertEquals(0xFF00FF00, d.getLayer(0).getPixel(5, 5));
        assertTrue(e.undo());
        assertEquals(0, d.getLayer(0).getPixel(5, 5));
    }

    @Test
    public void transformCommitIsOneUndoableStep() {
        Editor e = editor();
        Layer l = e.getDocument().getLayer(0);
        l.setPixel(2, 2, RED);
        int[] original = l.copyPixels();
        l.pasteRect(new Rect(0, 0, 70, 70), new int[70 * 70]); // lifted
        int[] result = new int[70 * 70];
        result[5 * 70 + 5] = BLUE;
        e.commitTransform(0, original, result);
        assertEquals(0, l.getPixel(2, 2));
        assertEquals(BLUE, l.getPixel(5, 5));
        assertTrue(e.undo());
        assertEquals(RED, l.getPixel(2, 2));
        assertEquals(0, l.getPixel(5, 5));
    }
}
