// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Reads and writes OpenRaster (.ora) files, the open layered-image format also used by Krita
 * and MyPaint. Supported: layer name, visibility, opacity, offsets and the normal, multiply
 * and screen blend modes. Unknown blend modes are read as normal.
 */
public final class OpenRaster {

    /** Largest single file inside the archive that will be read, to stop zip bombs. */
    private static final long MAX_ENTRY_BYTES = 400L * 1024 * 1024;
    private static final int MAX_LAYERS = 200;
    private static final int THUMB = 256;
    private static final String THUMB_PATH = "Thumbnails/thumbnail.png";
    private static final String LOG_PATH = "nekopaint/log.bin";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private OpenRaster() {
    }

    /** A drawing read from a file, with its recording if the file has one. */
    public static final class Project {
        public final Document document;
        /** The recording, or null if the file has none. */
        public final ProjectLog log;

        public Project(Document document, ProjectLog log) {
            this.document = document;
            this.log = log;
        }
    }

    /** Writes a document as an OpenRaster archive. */
    public static void write(Document document, OutputStream out) throws IOException {
        write(document, null, out);
    }

    /**
     * Writes a document, a small preview and, if given, the recording of how it was drawn.
     * Other programs ignore the recording.
     */
    public static void write(Document document, ProjectLog log, OutputStream out)
            throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.setLevel(0);
        byte[] mime = "image/openraster".getBytes(UTF8);
        ZipEntry mimeEntry = new ZipEntry("mimetype");
        mimeEntry.setMethod(ZipEntry.STORED);
        mimeEntry.setSize(mime.length);
        mimeEntry.setCompressedSize(mime.length);
        CRC32 crc = new CRC32();
        crc.update(mime);
        mimeEntry.setCrc(crc.getValue());
        zip.putNextEntry(mimeEntry);
        zip.write(mime);
        zip.closeEntry();
        zip.setLevel(6);

        int w = document.getWidth();
        int h = document.getHeight();
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<image version=\"0.0.3\" w=\"").append(w).append("\" h=\"").append(h)
                .append("\">\n<stack>\n");
        int count = document.getLayerCount();
        // The stack lists the top layer first.
        for (int i = count - 1; i >= 0; i--) {
            Layer layer = document.getLayer(i);
            xml.append("<layer name=\"").append(escape(layer.getName()))
                    .append("\" src=\"data/layer").append(i).append(".png\"")
                    .append(" visibility=\"").append(layer.isVisible() ? "visible" : "hidden")
                    .append("\" opacity=\"")
                    .append(String.format(Locale.ROOT, "%.4f", layer.getOpacity() / 255f))
                    .append("\" composite-op=\"").append(compositeOp(layer.getBlendMode()))
                    .append("\" x=\"0\" y=\"0\"/>\n");
        }
        xml.append("</stack>\n</image>\n");
        putEntry(zip, "stack.xml", xml.toString().getBytes(UTF8));

        int[] flat = document.flatten();
        int[] preview = scaleDown(flat, w, h, THUMB);
        int tw = Math.max(1, w * Math.min(THUMB, Math.max(w, h)) / Math.max(w, h));
        int th = preview.length / tw;
        ByteArrayOutputStream thumb = new ByteArrayOutputStream();
        PngCodec.encode(tw, th, preview, thumb);
        putEntry(zip, THUMB_PATH, thumb.toByteArray());

        if (log != null) {
            ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
            java.io.DataOutputStream data = new java.io.DataOutputStream(logBytes);
            log.write(data);
            data.flush();
            putEntry(zip, LOG_PATH, logBytes.toByteArray());
        }

        for (int i = 0; i < count; i++) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            PngCodec.encode(w, h, document.getLayer(i).copyPixels(), png, 3);
            putEntry(zip, "data/layer" + i + ".png", png.toByteArray());
        }
        ByteArrayOutputStream merged = new ByteArrayOutputStream();
        PngCodec.encode(w, h, flat, merged, 3);
        putEntry(zip, "mergedimage.png", merged.toByteArray());
        zip.finish();
        zip.flush();
    }

    /**
     * Reads an OpenRaster archive.
     *
     * @throws IOException if the file is damaged, unsupported or too large
     */
    public static Document read(InputStream in) throws IOException {
        return readProject(in).document;
    }

    /**
     * Reads only the small preview picture of a file, or null if it has none.
     *
     * @throws IOException if the file is not a readable archive
     */
    public static PngCodec.Image readThumbnail(InputStream in) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        ZipEntry entry;
        int seen = 0;
        while ((entry = zip.getNextEntry()) != null && seen++ < 8) {
            String name = entry.getName();
            if (name.equals(THUMB_PATH)) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[16384];
                int n;
                while ((n = zip.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > 4 * 1024 * 1024) {
                        return null;
                    }
                }
                return PngCodec.decode(new ByteArrayInputStream(buf.toByteArray()));
            }
            if (name.startsWith("data/") || name.equals("mergedimage.png")) {
                break;
            }
        }
        return null;
    }

    /**
     * Reads an archive together with its recording, if it has one.
     *
     * @throws IOException if the file is damaged, unsupported or too large
     */
    public static Project readProject(InputStream in) throws IOException {
        Map<String, byte[]> files = new HashMap<String, byte[]>();
        ZipInputStream zip = new ZipInputStream(in);
        ZipEntry entry;
        int entries = 0;
        long total = 0;
        while ((entry = zip.getNextEntry()) != null) {
            if (++entries > MAX_LAYERS * 2 + 12) {
                throw new IOException("Too many files in the archive");
            }
            if (entry.isDirectory()) {
                continue;
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[65536];
            long size = 0;
            int n;
            while ((n = zip.read(chunk)) > 0) {
                size += n;
                total += n;
                if (size > MAX_ENTRY_BYTES || total > MAX_ENTRY_BYTES * 2) {
                    throw new IOException("The file is too large");
                }
                buf.write(chunk, 0, n);
            }
            files.put(entry.getName(), buf.toByteArray());
        }
        byte[] mime = files.get("mimetype");
        if (mime == null || !new String(mime, UTF8).trim().equals("image/openraster")) {
            throw new IOException("Not an OpenRaster file");
        }
        byte[] stack = files.get("stack.xml");
        if (stack == null) {
            throw new IOException("stack.xml is missing");
        }

        Element root;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            trySet(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            trySet(factory, "http://xml.org/sax/features/external-general-entities", false);
            trySet(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            root = factory.newDocumentBuilder()
                    .parse(new InputSource(new ByteArrayInputStream(stack)))
                    .getDocumentElement();
        } catch (Exception e) {
            throw new IOException("stack.xml is damaged");
        }
        int width = intAttr(root, "w", -1);
        int height = intAttr(root, "h", -1);
        try {
            Layer.checkSize(width, height);
        } catch (IllegalArgumentException e) {
            throw new IOException("Image size not supported: " + width + "x" + height);
        }
        Document doc = new Document(width, height);

        java.util.List<Element> layers = new java.util.ArrayList<Element>();
        collectLayers(root, layers);
        if (layers.size() > MAX_LAYERS) {
            throw new IOException("Too many layers");
        }
        // The file lists the top layer first; the document wants the bottom first.
        for (int i = layers.size() - 1; i >= 0; i--) {
            Element e = layers.get(i);
            byte[] png = files.get(e.getAttribute("src"));
            if (png == null) {
                continue; // a layer without pixels (for example a nested stack) is skipped
            }
            PngCodec.Image image = PngCodec.decode(new ByteArrayInputStream(png));
            String name = e.getAttribute("name");
            Layer layer = doc.addLayer(name.isEmpty() ? "Layer" : name);
            blit(image, layer, intAttr(e, "x", 0), intAttr(e, "y", 0));
            layer.setVisible(!"hidden".equals(e.getAttribute("visibility")));
            layer.setOpacity(opacityAttr(e));
            layer.setBlendMode(blendMode(e.getAttribute("composite-op")));
        }
        if (doc.getLayerCount() == 0) {
            doc.addLayer("Layer 1");
        }
        ProjectLog log = null;
        byte[] logBytes = files.get(LOG_PATH);
        if (logBytes != null) {
            try {
                log = ProjectLog.read(new java.io.DataInputStream(
                        new ByteArrayInputStream(logBytes)));
                if (log.getWidth() != width || log.getHeight() != height) {
                    log = null;
                }
            } catch (IOException e) {
                log = null; // a damaged recording must not stop the drawing from opening
            }
        }
        return new Project(doc, log);
    }

    private static void collectLayers(Element parent, java.util.List<Element> out) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element)) {
                continue;
            }
            Element e = (Element) node;
            if (e.getTagName().equals("layer")) {
                out.add(e);
            } else if (e.getTagName().equals("stack")) {
                collectLayers(e, out);
            }
        }
    }

    private static void blit(PngCodec.Image image, Layer layer, int offX, int offY) {
        int lw = layer.getWidth();
        int lh = layer.getHeight();
        for (int y = 0; y < image.height; y++) {
            int ty = y + offY;
            if (ty < 0 || ty >= lh) {
                continue;
            }
            for (int x = 0; x < image.width; x++) {
                int tx = x + offX;
                if (tx < 0 || tx >= lw) {
                    continue;
                }
                layer.setPixel(tx, ty, image.pixels[y * image.width + x]);
            }
        }
    }

    /** Area-average downscale of premultiplied pixels so the longer side is at most max. */
    static int[] scaleDown(int[] src, int w, int h, int max) {
        int longer = Math.max(w, h);
        if (longer <= max) {
            return src.clone();
        }
        int tw = Math.max(1, w * max / longer);
        int th = Math.max(1, h * max / longer);
        int[] out = new int[tw * th];
        for (int y = 0; y < th; y++) {
            int y0 = (int) ((long) y * h / th);
            int y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * h / th));
            for (int x = 0; x < tw; x++) {
                int x0 = (int) ((long) x * w / tw);
                int x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * w / tw));
                long a = 0;
                long r = 0;
                long g = 0;
                long b = 0;
                int n = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        int p = src[yy * w + xx];
                        a += p >>> 24;
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                        n++;
                    }
                }
                out[y * tw + x] = ((int) (a / n) << 24) | ((int) (r / n) << 16)
                        | ((int) (g / n) << 8) | (int) (b / n);
            }
        }
        return out;
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] data)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static String compositeOp(BlendMode mode) {
        switch (mode) {
            case MULTIPLY:
                return "svg:multiply";
            case SCREEN:
                return "svg:screen";
            default:
                return "svg:src-over";
        }
    }

    private static BlendMode blendMode(String op) {
        if ("svg:multiply".equals(op)) {
            return BlendMode.MULTIPLY;
        }
        if ("svg:screen".equals(op)) {
            return BlendMode.SCREEN;
        }
        return BlendMode.NORMAL;
    }

    private static int intAttr(Element e, String name, int fallback) {
        String v = e.getAttribute(name);
        if (v == null || v.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static int opacityAttr(Element e) {
        String v = e.getAttribute("opacity");
        if (v == null || v.isEmpty()) {
            return 255;
        }
        try {
            float f = Float.parseFloat(v.trim());
            if (Float.isNaN(f)) {
                return 255;
            }
            return Math.max(0, Math.min(255, Math.round(f * 255f)));
        } catch (NumberFormatException ex) {
            return 255;
        }
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    b.append("&amp;");
                    break;
                case '<':
                    b.append("&lt;");
                    break;
                case '>':
                    b.append("&gt;");
                    break;
                case '"':
                    b.append("&quot;");
                    break;
                default:
                    if (c >= 0x20 || c == '\t') {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }

    private static void trySet(DocumentBuilderFactory f, String feature, boolean value) {
        try {
            f.setFeature(feature, value);
        } catch (Exception ignored) {
            // Not every XML parser knows every feature (Android's does not).
        }
    }
}
