// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class DocumentTest {

    @Test
    public void emptyDocumentFlattensToTransparent() {
        Document doc = new Document(3, 2);
        doc.addLayer("a");
        int[] pixels = doc.flatten();
        assertEquals(6, pixels.length);
        for (int p : pixels) {
            assertEquals(0, p);
        }
    }

    @Test
    public void opaquePixelSurvivesFlatten() {
        Document doc = new Document(2, 2);
        doc.addLayer("a").setPixel(1, 0, 0xFF336699);
        int[] pixels = doc.flatten();
        assertEquals(0xFF336699, pixels[1]);
        assertEquals(0, pixels[0]);
    }

    @Test
    public void hiddenLayerIsIgnored() {
        Document doc = new Document(1, 1);
        Layer layer = doc.addLayer("a");
        layer.setPixel(0, 0, 0xFFFF0000);
        layer.setVisible(false);
        assertEquals(0, doc.flatten()[0]);
    }

    @Test
    public void zeroOpacityLayerIsIgnored() {
        Document doc = new Document(1, 1);
        Layer layer = doc.addLayer("a");
        layer.setPixel(0, 0, 0xFFFF0000);
        layer.setOpacity(0);
        assertEquals(0, doc.flatten()[0]);
    }

    @Test
    public void layerOpacityBlendsWithBackground() {
        Document doc = new Document(1, 1);
        Layer layer = doc.addLayer("a");
        layer.setPixel(0, 0, 0xFFFF0000);
        layer.setOpacity(128);
        assertEquals(0xFFFF7F7F, doc.flatten(0xFFFFFFFF)[0]);
    }

    @Test
    public void layersAreCombinedBottomFirst() {
        Document doc = new Document(1, 1);
        doc.addLayer("bottom").setPixel(0, 0, 0xFFFF0000);
        doc.addLayer("top").setPixel(0, 0, 0xFF0000FF);
        assertEquals(0xFF0000FF, doc.flatten()[0]);
    }

    @Test
    public void layerBlendModeIsApplied() {
        Document doc = new Document(1, 1);
        doc.addLayer("base").setPixel(0, 0, 0xFF808080);
        Layer top = doc.addLayer("top");
        top.setPixel(0, 0, 0xFF808080);
        top.setBlendMode(BlendMode.MULTIPLY);
        assertEquals(0xFF404040, doc.flatten()[0]);
    }

    @Test
    public void backgroundColourIsPremultiplied() {
        Document doc = new Document(1, 1);
        doc.addLayer("a");
        assertEquals(0x80800000, doc.flatten(0x80FF0000)[0]);
    }

    @Test
    public void rejectsUnsupportedSizes() {
        assertThrows(IllegalArgumentException.class, () -> new Document(0, 5));
        assertThrows(IllegalArgumentException.class, () -> new Document(5, -1));
        assertThrows(IllegalArgumentException.class, () -> new Document(20000, 1));
        assertThrows(IllegalArgumentException.class, () -> new Document(10000, 10000));
    }

    @Test
    public void copyAndPasteRect() {
        Layer layer = new Layer(4, 4, "x");
        layer.setPixel(1, 1, 0xFF010203);
        layer.setPixel(2, 2, 0xFF040506);
        Rect rect = new Rect(1, 1, 3, 3);
        int[] data = layer.copyRect(rect);
        assertArrayEquals(new int[] {0xFF010203, 0, 0, 0xFF040506}, data);

        Layer other = new Layer(4, 4, "y");
        other.pasteRect(rect, data);
        assertEquals(0xFF010203, other.getPixel(1, 1));
        assertEquals(0xFF040506, other.getPixel(2, 2));
        assertEquals(0, other.getPixel(0, 0));
    }

    @Test
    public void rectAndPixelAccessAreBoundsChecked() {
        Layer layer = new Layer(4, 4, "x");
        assertThrows(IllegalArgumentException.class, () -> layer.copyRect(new Rect(0, 0, 5, 5)));
        assertThrows(IllegalArgumentException.class,
                () -> layer.pasteRect(new Rect(0, 0, 2, 2), new int[3]));
        assertThrows(IndexOutOfBoundsException.class, () -> layer.getPixel(4, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> layer.setPixel(0, -1, 0));
    }

    @Test
    public void layerSettersValidate() {
        Layer layer = new Layer(1, 1, "x");
        assertThrows(IllegalArgumentException.class, () -> layer.setOpacity(256));
        assertThrows(IllegalArgumentException.class, () -> layer.setOpacity(-1));
        assertThrows(IllegalArgumentException.class, () -> layer.setName(null));
        assertThrows(IllegalArgumentException.class, () -> layer.setBlendMode(null));
    }

    @Test
    public void rectUnionIgnoresEmpty() {
        Rect a = new Rect(1, 2, 5, 6);
        assertEquals(a, a.union(Rect.EMPTY));
        assertEquals(a, Rect.EMPTY.union(a));
        assertEquals(new Rect(0, 0, 8, 6), a.union(new Rect(0, 0, 8, 3)));
        assertThrows(IllegalArgumentException.class, () -> new Rect(5, 0, 1, 1));
    }

    @Test
    public void flattenRectMatchesTheSameAreaOfFullFlatten() {
        Document doc = new Document(5, 4);
        doc.addLayer("a").setPixel(2, 1, 0xFFFF0000);
        Layer top = doc.addLayer("b");
        top.setPixel(3, 2, 0x80008000);
        top.setOpacity(200);
        int[] full = doc.flatten(0xFFFFFFFF);
        Rect r = new Rect(1, 1, 4, 3);
        int[] part = doc.flattenRect(r, 0xFFFFFFFF);
        for (int y = 0; y < r.height(); y++) {
            for (int x = 0; x < r.width(); x++) {
                assertEquals(full[(r.top + y) * 5 + r.left + x], part[y * r.width() + x]);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> doc.flattenRect(new Rect(0, 0, 6, 1), 0));
    }

    @Test
    public void straightArgbIsPremultipliedOnImport() {
        Layer layer = new Layer(2, 1, "x");
        layer.setStraightArgb(new int[] {0x80FF0000, 0xFF336699});
        assertEquals(0x80800000, layer.getPixel(0, 0));
        assertEquals(0xFF336699, layer.getPixel(1, 0));
        assertThrows(IllegalArgumentException.class, () -> layer.setStraightArgb(new int[3]));
    }
}
