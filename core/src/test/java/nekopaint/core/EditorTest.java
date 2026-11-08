// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class EditorTest {

    private Document doc;
    private Layer layer;
    private Editor editor;
    private final Brush brush = new Brush(0xFF0000, 3f, 1f, 1f, 0.25f, false);

    @Before
    public void setUp() {
        doc = new Document(20, 20);
        layer = doc.addLayer("paint");
        editor = new Editor(doc, 100);
    }

    private void dot(float x, float y) {
        Editor.Stroke stroke = editor.beginStroke(0, brush);
        stroke.addPoint(x, y, 1f);
        stroke.end();
    }

    @Test
    public void undoRestoresPixelsExactlyAndRedoReappliesThem() {
        int[] empty = layer.copyPixels();
        dot(10f, 10f);
        int[] painted = layer.copyPixels();
        assertFalse(java.util.Arrays.equals(empty, painted));

        assertTrue(editor.canUndo());
        assertTrue(editor.undo());
        assertArrayEquals(empty, layer.copyPixels());
        assertTrue(editor.canRedo());

        assertTrue(editor.redo());
        assertArrayEquals(painted, layer.copyPixels());
    }

    @Test
    public void undoOnlyAffectsTheLatestStroke() {
        dot(5f, 5f);
        int[] afterFirst = layer.copyPixels();
        dot(15f, 15f);
        assertNotEquals(0, layer.getPixel(15, 15));

        editor.undo();
        assertArrayEquals(afterFirst, layer.copyPixels());
        assertEquals(0, layer.getPixel(15, 15));

        editor.undo();
        for (int p : layer.copyPixels()) {
            assertEquals(0, p);
        }
    }

    @Test
    public void newStrokeClearsRedo() {
        dot(5f, 5f);
        editor.undo();
        assertTrue(editor.canRedo());
        dot(15f, 15f);
        assertFalse(editor.canRedo());
        assertFalse(editor.redo());
    }

    @Test
    public void emptyHistoryCannotBeUndoneOrRedone() {
        assertFalse(editor.canUndo());
        assertFalse(editor.undo());
        assertFalse(editor.redo());
    }

    @Test
    public void strokesThatChangeNothingAreNotRecorded() {
        Editor.Stroke empty = editor.beginStroke(0, brush);
        empty.end();
        assertFalse(editor.canUndo());

        Editor.Stroke outside = editor.beginStroke(0, brush);
        outside.addPoint(-100f, -100f, 1f);
        outside.end();
        assertFalse(editor.canUndo());
    }

    @Test
    public void historyIsLimited() {
        Editor small = new Editor(doc, 2);
        for (int i = 0; i < 3; i++) {
            Editor.Stroke s = small.beginStroke(0, brush);
            s.addPoint(4f + i * 5, 10f, 1f);
            s.end();
        }
        assertTrue(small.undo());
        assertTrue(small.undo());
        assertFalse(small.undo());
        // The oldest stroke could no longer be undone, so its dot is still there.
        assertNotEquals(0, layer.getPixel(4, 10));
    }

    @Test
    public void onlyOneStrokeAtATime() {
        Editor.Stroke first = editor.beginStroke(0, brush);
        assertThrows(IllegalStateException.class, () -> editor.beginStroke(0, brush));
        assertThrows(IllegalStateException.class, () -> editor.undo());
        first.addPoint(10f, 10f, 1f);
        first.end();
        // Now a new one is fine.
        editor.beginStroke(0, brush).cancel();
    }

    @Test
    public void finishedStrokeRejectsFurtherUse() {
        Editor.Stroke stroke = editor.beginStroke(0, brush);
        stroke.end();
        assertThrows(IllegalStateException.class, () -> stroke.addPoint(1f, 1f, 1f));
        assertThrows(IllegalStateException.class, stroke::end);
        assertThrows(IllegalStateException.class, stroke::cancel);
    }

    @Test
    public void cancelRestoresTheLayerAndAddsNoHistory() {
        dot(5f, 5f);
        int[] before = layer.copyPixels();
        Editor.Stroke stroke = editor.beginStroke(0, brush);
        stroke.addPoint(15f, 15f, 1f);
        assertNotEquals(0, layer.getPixel(15, 15));
        stroke.cancel();
        assertArrayEquals(before, layer.copyPixels());

        editor.undo(); // undoes the first dot, not the cancelled stroke
        for (int p : layer.copyPixels()) {
            assertEquals(0, p);
        }
    }

    @Test
    public void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> editor.beginStroke(1, brush));
        assertThrows(IllegalArgumentException.class, () -> editor.beginStroke(-1, brush));
        assertThrows(IllegalArgumentException.class, () -> editor.beginStroke(0, null));
        assertThrows(IllegalArgumentException.class, () -> new Editor(null, 5));
        assertThrows(IllegalArgumentException.class, () -> new Editor(doc, 0));
    }

    @Test
    public void undoWorksPerLayer() {
        Layer second = doc.addLayer("second");
        dot(5f, 5f); // on layer 0
        Editor.Stroke stroke = editor.beginStroke(1, brush);
        stroke.addPoint(15f, 15f, 1f);
        stroke.end();
        assertNotEquals(0, second.getPixel(15, 15));

        editor.undo();
        assertEquals(0, second.getPixel(15, 15));
        assertNotEquals(0, layer.getPixel(5, 5));
    }

    @Test
    public void longStrokeAcrossManyTilesUndoesExactly() {
        Document big = new Document(300, 200);
        Layer l = big.addLayer("x");
        l.setPixel(150, 100, 0xFF00FF00);
        Editor e = new Editor(big, 10);
        int[] before = l.copyPixels();
        Editor.Stroke s = e.beginStroke(0, new Brush(0x0000FF, 7f, 0.5f, 0.8f, 0.2f, false));
        s.addPoint(5f, 5f, 1f);
        s.addPoint(290f, 40f, 0.7f);
        s.addPoint(20f, 190f, 1f);
        s.end();
        int[] after = l.copyPixels();
        assertFalse(java.util.Arrays.equals(before, after));
        assertTrue(e.undo());
        assertArrayEquals(before, l.copyPixels());
        assertTrue(e.redo());
        assertArrayEquals(after, l.copyPixels());
    }

    @Test
    public void eraserStrokeOverExistingPaintUndoesExactly() {
        Document d = new Document(100, 100);
        Layer l = d.addLayer("x");
        Editor e = new Editor(d, 10);
        Editor.Stroke paint = e.beginStroke(0, new Brush(0xFF0000, 20f, 1f, 1f, 0.1f, false));
        paint.addPoint(50f, 50f, 1f);
        paint.end();
        int[] painted = l.copyPixels();
        Editor.Stroke erase = e.beginStroke(0, Brush.eraser(10f));
        erase.addPoint(40f, 50f, 1f);
        erase.addPoint(60f, 50f, 1f);
        erase.cancel();
        assertArrayEquals(painted, l.copyPixels());
    }

    @Test
    public void layerRemoveMoveAndClearHistory() {
        Document d = new Document(4, 4);
        d.addLayer("a").setPixel(0, 0, 0xFFFF0000);
        d.addLayer("b").setPixel(0, 0, 0xFF0000FF);
        d.moveLayer(0, 1);
        assertEquals("b", d.getLayer(0).getName());
        d.removeLayer(0);
        assertEquals(1, d.getLayerCount());
        assertThrows(IllegalStateException.class, () -> d.removeLayer(0));
        Editor e = new Editor(d, 5);
        Editor.Stroke s = e.beginStroke(0, brush);
        s.addPoint(2f, 2f, 1f);
        s.end();
        assertTrue(e.canUndo());
        e.clearHistory();
        assertFalse(e.canUndo());
    }
}
