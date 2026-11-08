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
}
