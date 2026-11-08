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
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private OpenRaster() {
    }

    /** Writes a document as an OpenRaster archive. */
    public static void write(Document document, OutputStream out) throws IOException {
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

        for (int i = 0; i < count; i++) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            PngCodec.encode(w, h, document.getLayer(i).copyPixels(), png);
            putEntry(zip, "data/layer" + i + ".png", png.toByteArray());
        }
        ByteArrayOutputStream merged = new ByteArrayOutputStream();
        PngCodec.encode(w, h, document.flatten(), merged);
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
        Map<String, byte[]> files = new HashMap<String, byte[]>();
        ZipInputStream zip = new ZipInputStream(in);
        ZipEntry entry;
        int entries = 0;
        long total = 0;
        while ((entry = zip.getNextEntry()) != null) {
            if (++entries > MAX_LAYERS * 2 + 10) {
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
        return doc;
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
