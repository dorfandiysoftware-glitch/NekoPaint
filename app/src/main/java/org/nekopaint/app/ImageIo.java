// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import nekopaint.core.Document;
import nekopaint.core.OpenRaster;
import nekopaint.core.PngCodec;

/** Opens a drawing from a stream: either an OpenRaster file or a plain PNG. */
final class ImageIo {

    private static final int MAX_BYTES = 300 * 1024 * 1024;

    private ImageIo() {
    }

    static Document open(InputStream in) throws IOException {
        return openProject(in).document;
    }

    /** Opens a drawing together with its recording (null for PNG files and other programs' files). */
    static OpenRaster.Project openProject(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[65536];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_BYTES) {
                throw new IOException("File too large");
            }
        }
        byte[] data = buf.toByteArray();
        Document doc;
        nekopaint.core.ProjectLog log = null;
        if (data.length > 4 && (data[0] & 0xFF) == 0x89 && data[1] == 'P') {
            PngCodec.Image image = PngCodec.decode(new ByteArrayInputStream(data));
            if (image.width > Prefs.MAX_SIDE || image.height > Prefs.MAX_SIDE
                    || (long) image.width * image.height > Prefs.MAX_PIXELS) {
                throw new IOException("Image is too big for this device");
            }
            doc = new Document(image.width, image.height);
            nekopaint.core.Layer layer = doc.addLayer("Image");
            for (int y = 0; y < image.height; y++) {
                for (int x = 0; x < image.width; x++) {
                    layer.setPixel(x, y, image.pixels[y * image.width + x]);
                }
            }
        } else {
            OpenRaster.Project project = OpenRaster.readProject(new ByteArrayInputStream(data));
            doc = project.document;
            log = project.log;
            if (doc.getWidth() > Prefs.MAX_SIDE || doc.getHeight() > Prefs.MAX_SIDE
                    || (long) doc.getWidth() * doc.getHeight() * doc.getLayerCount()
                            > Prefs.MAX_PIXELS * 4L) {
                throw new IOException("Drawing is too big for this device");
            }
        }
        return new OpenRaster.Project(doc, log);
    }

    /** Writes a drawing (and its recording) to a file, replacing what was there. */
    static void save(android.content.Context c, android.net.Uri uri, Document doc,
            nekopaint.core.ProjectLog log) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OpenRaster.write(doc, log, bytes);
        try (java.io.OutputStream out = c.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) {
                throw new IOException("Cannot open file");
            }
            bytes.writeTo(out);
        }
    }
}
