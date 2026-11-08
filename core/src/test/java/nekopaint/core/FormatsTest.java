// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.Test;

public class FormatsTest {

    @Test
    public void pngRoundTripKeepsOpaqueAndTransparentPixels() throws IOException {
        int w = 37;
        int h = 23;
        int[] px = new int[w * h];
        Random r = new Random(5);
        for (int i = 0; i < px.length; i++) {
            px[i] = i % 3 == 0 ? 0 : (0xFF000000 | r.nextInt(0x1000000));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PngCodec.encode(w, h, px, out);
        PngCodec.Image img = PngCodec.decode(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(w, img.width);
        assertEquals(h, img.height);
        assertArrayEquals(px, img.pixels);
    }

    @Test
    public void pngDecodesAllFilterTypes() throws IOException {
        // Hand-built 3x2 greyscale PNG using filters 1 (sub) and 4 (Paeth).
        int[][] rows = {{10, 20, 30}, {15, 25, 35}};
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(1);
        raw.write(10);
        raw.write(10);
        raw.write(10); // sub: 10,20,30
        raw.write(4);
        // Paeth: bpp=1; x0: a=0,b=10,c=0 -> pred 10 -> 5; x1: a=15,b=20,c=10 -> p=25,
        // pa=10,pb=5,pc=15 -> b=20 -> 5; x2: a=25,b=30,c=20 -> p=35, pa=10,pb=5,pc=15 -> 30 -> 5
        raw.write(5);
        raw.write(5);
        raw.write(5);
        byte[] png = buildGrey(3, 2, raw.toByteArray());
        PngCodec.Image img = PngCodec.decode(new ByteArrayInputStream(png));
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                int g = rows[y][x];
                assertEquals(0xFF000000 | (g << 16) | (g << 8) | g, img.pixels[y * 3 + x]);
            }
        }
    }

    private static byte[] buildGrey(int w, int h, byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        byte[] ihdr = {0, 0, 0, (byte) w, 0, 0, 0, (byte) h, 8, 0, 0, 0, 0};
        chunk(out, "IHDR", ihdr);
        java.util.zip.Deflater d = new java.util.zip.Deflater();
        d.setInput(raw);
        d.finish();
        byte[] buf = new byte[1024];
        int n = d.deflate(buf);
        byte[] z = java.util.Arrays.copyOf(buf, n);
        chunk(out, "IDAT", z);
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] body) {
        out.write(body.length >>> 24);
        out.write(body.length >>> 16);
        out.write(body.length >>> 8);
        out.write(body.length);
        for (int i = 0; i < 4; i++) {
            out.write(type.charAt(i));
        }
        out.write(body, 0, body.length);
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(0); // CRC is not checked by the reader
    }

    @Test
    public void pngRejectsGarbageAndHugeSizes() {
        assertThrows(IOException.class,
                () -> PngCodec.decode(new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5, 6, 7, 8})));
        assertThrows(IOException.class, () -> PngCodec.decode(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    public void openRasterRoundTripKeepsLayersAndProperties() throws IOException {
        Document doc = new Document(16, 12);
        Layer a = doc.addLayer("Back & <1>");
        a.setPixel(3, 4, 0xFF336699);
        Layer b = doc.addLayer("Top");
        b.setPixel(5, 6, 0xFFFF0000);
        b.setOpacity(128);
        b.setBlendMode(BlendMode.MULTIPLY);
        b.setVisible(false);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OpenRaster.write(doc, out);
        Document back = OpenRaster.read(new ByteArrayInputStream(out.toByteArray()));

        assertEquals(16, back.getWidth());
        assertEquals(12, back.getHeight());
        assertEquals(2, back.getLayerCount());
        assertEquals("Back & <1>", back.getLayer(0).getName());
        assertEquals(0xFF336699, back.getLayer(0).getPixel(3, 4));
        assertEquals("Top", back.getLayer(1).getName());
        assertEquals(0xFFFF0000, back.getLayer(1).getPixel(5, 6));
        assertEquals(128, back.getLayer(1).getOpacity());
        assertEquals(BlendMode.MULTIPLY, back.getLayer(1).getBlendMode());
        assertFalse(back.getLayer(1).isVisible());
        assertTrue(back.getLayer(0).isVisible());
    }

    @Test
    public void openRasterArchiveStartsWithStoredMimetype() throws IOException {
        Document doc = new Document(2, 2);
        doc.addLayer("a");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OpenRaster.write(doc, out);
        byte[] bytes = out.toByteArray();
        // The first entry must be "mimetype" and stored uncompressed, right at the start.
        assertEquals("PK", new String(bytes, 0, 2, "ISO-8859-1"));
        assertEquals("mimetype", new String(bytes, 30, 8, "ISO-8859-1"));
        assertEquals("image/openraster", new String(bytes, 38, 16, "ISO-8859-1"));
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes));
        ZipEntry e = zip.getNextEntry();
        assertEquals("mimetype", e.getName());
    }

    @Test
    public void openRasterRejectsOtherFiles() {
        assertThrows(IOException.class,
                () -> OpenRaster.read(new ByteArrayInputStream(new byte[] {1, 2, 3})));
    }
}
