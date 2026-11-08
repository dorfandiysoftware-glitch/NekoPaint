// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import org.junit.Test;

public class RecordingTest {

    private static Editor newEditor(int w, int h) {
        Document d = new Document(w, h);
        d.addLayer("Layer 1");
        Editor e = new Editor(d, 100);
        e.setLog(new ProjectLog(w, h));
        return e;
    }

    private static void stroke(Editor e, int layer, Brush b, Random r, int points) {
        Editor.Stroke s = e.beginStroke(layer, b);
        float x = 10 + r.nextInt(60);
        float y = 10 + r.nextInt(60);
        for (int i = 0; i < points; i++) {
            x += r.nextInt(9) - 4;
            y += r.nextInt(9) - 4;
            s.addPoint(x, y, 0.3f + r.nextFloat() * 0.7f);
        }
        s.end();
    }

    private static void replayAll(ProjectLog log, Document into) {
        ProjectLog.Replay replay = new ProjectLog.Replay(log);
        while (!replay.isDone()) {
            replay.step(50);
        }
        assertArrayEquals(into.flatten(0xFFFFFFFF),
                replay.getDocument().flatten(0xFFFFFFFF));
    }

    @Test
    public void replayMatchesTheDrawingIncludingUndoRedoAndLayerOps() {
        Editor e = newEditor(80, 80);
        Random r = new Random(3);
        Brush pen = new Brush(0xCC3322, 5f, 0.7f, 0.9f, 0.2f, false);
        stroke(e, 0, pen, r, 30);
        stroke(e, 0, pen, r, 30);
        e.undo();
        e.addLayer("Ink", 1);
        stroke(e, 1, new Brush(0x2233CC, 8f, 1f, 1f, 0.15f, false), r, 25);
        e.setLayerOpacity(1, 150);
        e.setLayerBlendMode(1, BlendMode.MULTIPLY);
        stroke(e, 1, Brush.eraser(6f), r, 15);
        e.undo();
        e.redo();
        e.addLayer("Top", 2);
        stroke(e, 2, pen, r, 20);
        e.moveLayer(2, 0);
        e.setLayerVisible(1, false);
        e.removeLayer(1);
        stroke(e, 0, pen, r, 10);
        replayAll(e.getLog(), e.getDocument());
    }

    @Test
    public void logSurvivesAFileRoundTrip() throws IOException {
        Editor e = newEditor(60, 40);
        Random r = new Random(9);
        stroke(e, 0, new Brush(0x112233, 4f, 0.5f, 1f, 0.2f, false), r, 40);
        e.addLayer("B", 1);
        stroke(e, 1, new Brush(0xAA5500, 6f, 1f, 0.8f, 0.2f, false), r, 40);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OpenRaster.write(e.getDocument(), e.getLog(), out);
        OpenRaster.Project back = OpenRaster.readProject(new ByteArrayInputStream(out.toByteArray()));
        assertNotNull(back.log);
        assertEquals(e.getLog().getPointCount(), back.log.getPointCount());
        replayAll(back.log, back.document);
    }

    @Test
    public void fileWithoutLogGivesNullLog() throws IOException {
        Document d = new Document(8, 8);
        d.addLayer("a");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OpenRaster.write(d, out);
        assertNull(OpenRaster.readProject(new ByteArrayInputStream(out.toByteArray())).log);
    }

    @Test
    public void thumbnailIsSmallAndReadable() throws IOException {
        Document d = new Document(1000, 500);
        d.addLayer("a").setPixel(10, 10, 0xFFFF0000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OpenRaster.write(d, out);
        PngCodec.Image t = OpenRaster.readThumbnail(new ByteArrayInputStream(out.toByteArray()));
        assertNotNull(t);
        assertEquals(256, t.width);
        assertEquals(128, t.height);
    }

    @Test
    public void stepReportsChangedArea() {
        Editor e = newEditor(50, 50);
        Editor.Stroke s = e.beginStroke(0, new Brush(0xFF0000, 3f, 1f, 1f, 0.2f, false));
        s.addPoint(10f, 10f, 1f);
        s.addPoint(40f, 40f, 1f);
        s.end();
        ProjectLog.Replay replay = new ProjectLog.Replay(e.getLog());
        Rect all = Rect.EMPTY;
        while (!replay.isDone()) {
            all = all.union(replay.step(1));
        }
        assertTrue(!all.isEmpty());
    }

    @Test
    public void logStopsWhenFullButDrawingGoesOn() {
        Editor e = newEditor(30, 30);
        ProjectLog log = e.getLog();
        int guard = 0;
        while (!log.isTruncated() && guard++ < 2000) {
            // A different colour each time, so every stroke really changes pixels.
            Brush b = new Brush(guard * 977 & 0xFFFFFF, 2f, 1f, 1f, 0.5f, false);
            Editor.Stroke s = e.beginStroke(0, b);
            for (int i = 0; i < 1000; i++) {
                s.addPoint(5f + (i % 20), 5f + (i % 17), 1f);
            }
            s.end();
        }
        assertTrue(log.isTruncated());
        assertTrue(log.getPointCount() <= ProjectLog.MAX_POINTS);
    }
}
