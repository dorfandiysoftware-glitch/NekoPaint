// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A small PNG reader and writer that needs nothing but {@code java.util.zip}.
 *
 * <p>Writing produces 8-bit RGBA. Reading accepts non-interlaced images with 8 or 16 bits per
 * channel in greyscale, greyscale+alpha, RGB, RGBA and palette form. Pixels in memory are
 * premultiplied ARGB, as everywhere in the core.
 */
public final class PngCodec {

    /** A decoded picture. */
    public static final class Image {
        public final int width;
        public final int height;
        /** Premultiplied ARGB, row by row. */
        public final int[] pixels;

        Image(int width, int height, int[] pixels) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }
    }

    private static final byte[] SIGNATURE = {
        (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'
    };

    private PngCodec() {
    }

    /**
     * Writes premultiplied ARGB pixels as an RGBA PNG.
     *
     * @throws IllegalArgumentException if the size is unsupported or does not match the pixels
     */
    public static void encode(int width, int height, int[] pixels, OutputStream out)
            throws IOException {
        encode(width, height, pixels, out, 6);
    }

    /** Like {@link #encode(int, int, int[], OutputStream)} with a chosen zlib level 1..9. */
    public static void encode(int width, int height, int[] pixels, OutputStream out, int level)
            throws IOException {
        Layer.checkSize(width, height);
        if (pixels.length != width * height) {
            throw new IllegalArgumentException("Pixel count does not match the size");
        }
        out.write(SIGNATURE);
        byte[] header = new byte[13];
        putInt(header, 0, width);
        putInt(header, 4, height);
        header[8] = 8; // bit depth
        header[9] = 6; // RGBA
        writeChunk(out, "IHDR", header, 0, header.length);

        Deflater deflater = new Deflater(level);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] row = new byte[1 + width * 4];
        byte[] buffer = new byte[65536];
        try {
            for (int y = 0; y < height; y++) {
                row[0] = 0; // filter: none
                int o = 1;
                for (int x = 0; x < width; x++) {
                    int p = PixelOps.unpremultiply(pixels[y * width + x]);
                    row[o++] = (byte) (p >> 16);
                    row[o++] = (byte) (p >> 8);
                    row[o++] = (byte) p;
                    row[o++] = (byte) (p >>> 24);
                }
                deflater.setInput(row);
                while (!deflater.needsInput()) {
                    int n = deflater.deflate(buffer);
                    compressed.write(buffer, 0, n);
                }
            }
            deflater.finish();
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                compressed.write(buffer, 0, n);
            }
        } finally {
            deflater.end();
        }
        byte[] data = compressed.toByteArray();
        writeChunk(out, "IDAT", data, 0, data.length);
        writeChunk(out, "IEND", new byte[0], 0, 0);
    }

    /**
     * Reads a PNG.
     *
     * @throws IOException if the data is damaged or uses an unsupported feature, or the image
     *                     is larger than the core allows
     */
    public static Image decode(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        byte[] sig = new byte[8];
        data.readFully(sig);
        for (int i = 0; i < 8; i++) {
            if (sig[i] != SIGNATURE[i]) {
                throw new IOException("Not a PNG file");
            }
        }
        int width = 0;
        int height = 0;
        int depth = 0;
        int colorType = -1;
        byte[] palette = null;
        byte[] paletteAlpha = null;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        boolean sawHeader = false;
        boolean sawEnd = false;

        while (!sawEnd) {
            int length;
            try {
                length = data.readInt();
            } catch (EOFException e) {
                throw new IOException("PNG ends early");
            }
            byte[] typeBytes = new byte[4];
            data.readFully(typeBytes);
            String type = new String(typeBytes, "ISO-8859-1");
            if (length < 0 || length > (1 << 28)) {
                throw new IOException("Bad PNG chunk size");
            }
            byte[] body = new byte[length];
            data.readFully(body);
            data.readInt(); // CRC, not checked
            if (type.equals("IHDR")) {
                if (length != 13) {
                    throw new IOException("Bad PNG header");
                }
                width = getInt(body, 0);
                height = getInt(body, 4);
                depth = body[8] & 0xFF;
                colorType = body[9] & 0xFF;
                if (body[10] != 0 || body[11] != 0) {
                    throw new IOException("Unsupported PNG compression or filter method");
                }
                if (body[12] != 0) {
                    throw new IOException("Interlaced PNG is not supported");
                }
                try {
                    Layer.checkSize(width, height);
                } catch (IllegalArgumentException e) {
                    throw new IOException("Image size not supported: " + width + "x" + height);
                }
                if (depth != 8 && depth != 16 && !(colorType == 3 && depth <= 8)) {
                    throw new IOException("Unsupported PNG bit depth " + depth);
                }
                if (colorType != 0 && colorType != 2 && colorType != 3 && colorType != 4
                        && colorType != 6) {
                    throw new IOException("Unsupported PNG colour type " + colorType);
                }
                sawHeader = true;
            } else if (type.equals("PLTE")) {
                palette = body;
            } else if (type.equals("tRNS")) {
                paletteAlpha = body;
            } else if (type.equals("IDAT")) {
                idat.write(body, 0, body.length);
            } else if (type.equals("IEND")) {
                sawEnd = true;
            }
        }
        if (!sawHeader) {
            throw new IOException("PNG header missing");
        }
        if (colorType == 3 && palette == null) {
            throw new IOException("PNG palette missing");
        }
        if (colorType == 0 && paletteAlpha != null || colorType == 2 && paletteAlpha != null) {
            paletteAlpha = null; // colour-key transparency is ignored
        }

        int channels = colorType == 0 ? 1 : colorType == 2 ? 3 : colorType == 3 ? 1
                : colorType == 4 ? 2 : 4;
        int bitsPerPixel = channels * depth;
        int stride = (width * bitsPerPixel + 7) / 8;
        int bpp = Math.max(1, bitsPerPixel / 8);
        byte[] raw = new byte[(stride + 1) * height];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(idat.toByteArray());
            int filled = 0;
            while (filled < raw.length) {
                int n = inflater.inflate(raw, filled, raw.length - filled);
                if (n == 0 && (inflater.finished() || inflater.needsInput()
                        || inflater.needsDictionary())) {
                    break;
                }
                filled += n;
            }
            if (filled < raw.length) {
                throw new IOException("PNG image data is incomplete");
            }
        } catch (DataFormatException e) {
            throw new IOException("PNG image data is damaged");
        } finally {
            inflater.end();
        }

        byte[] prev = new byte[stride];
        byte[] cur = new byte[stride];
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int base = y * (stride + 1);
            int filter = raw[base] & 0xFF;
            System.arraycopy(raw, base + 1, cur, 0, stride);
            unfilter(filter, cur, prev, bpp);
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = PixelOps.premultiply(
                        pixelAt(cur, x, colorType, depth, palette, paletteAlpha));
            }
            byte[] t = prev;
            prev = cur;
            cur = t;
        }
        return new Image(width, height, pixels);
    }

    private static void unfilter(int filter, byte[] cur, byte[] prev, int bpp)
            throws IOException {
        int n = cur.length;
        switch (filter) {
            case 0:
                break;
            case 1:
                for (int i = bpp; i < n; i++) {
                    cur[i] = (byte) (cur[i] + cur[i - bpp]);
                }
                break;
            case 2:
                for (int i = 0; i < n; i++) {
                    cur[i] = (byte) (cur[i] + prev[i]);
                }
                break;
            case 3:
                for (int i = 0; i < n; i++) {
                    int left = i >= bpp ? cur[i - bpp] & 0xFF : 0;
                    cur[i] = (byte) (cur[i] + ((left + (prev[i] & 0xFF)) >> 1));
                }
                break;
            case 4:
                for (int i = 0; i < n; i++) {
                    int a = i >= bpp ? cur[i - bpp] & 0xFF : 0;
                    int b = prev[i] & 0xFF;
                    int c = i >= bpp ? prev[i - bpp] & 0xFF : 0;
                    int p = a + b - c;
                    int pa = Math.abs(p - a);
                    int pb = Math.abs(p - b);
                    int pc = Math.abs(p - c);
                    int pred = pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
                    cur[i] = (byte) (cur[i] + pred);
                }
                break;
            default:
                throw new IOException("Bad PNG filter type " + filter);
        }
    }

    /** @return straight (non-premultiplied) ARGB of pixel x in an unfiltered row */
    private static int pixelAt(byte[] row, int x, int colorType, int depth, byte[] palette,
            byte[] paletteAlpha) throws IOException {
        int a = 255;
        int r;
        int g;
        int b;
        switch (colorType) {
            case 0: {
                int v = sample(row, x, depth);
                r = g = b = v;
                break;
            }
            case 2:
                r = channel(row, x * 3, depth);
                g = channel(row, x * 3 + 1, depth);
                b = channel(row, x * 3 + 2, depth);
                break;
            case 3: {
                int index = rawSample(row, x, depth);
                if (index * 3 + 2 >= palette.length) {
                    throw new IOException("PNG palette index out of range");
                }
                r = palette[index * 3] & 0xFF;
                g = palette[index * 3 + 1] & 0xFF;
                b = palette[index * 3 + 2] & 0xFF;
                if (paletteAlpha != null && index < paletteAlpha.length) {
                    a = paletteAlpha[index] & 0xFF;
                }
                break;
            }
            case 4:
                r = g = b = channel(row, x * 2, depth);
                a = channel(row, x * 2 + 1, depth);
                break;
            default:
                r = channel(row, x * 4, depth);
                g = channel(row, x * 4 + 1, depth);
                b = channel(row, x * 4 + 2, depth);
                a = channel(row, x * 4 + 3, depth);
                break;
        }
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Grey value scaled to 0..255 for 1, 2, 4, 8 or 16 bits. */
    private static int sample(byte[] row, int x, int depth) {
        int v = rawSample(row, x, depth);
        switch (depth) {
            case 1:
                return v * 255;
            case 2:
                return v * 85;
            case 4:
                return v * 17;
            default:
                return depth == 16 ? row[x * 2] & 0xFF : v;
        }
    }

    private static int rawSample(byte[] row, int x, int depth) {
        if (depth == 8) {
            return row[x] & 0xFF;
        }
        if (depth == 16) {
            return row[x * 2] & 0xFF;
        }
        int perByte = 8 / depth;
        int shift = 8 - depth * (x % perByte + 1);
        return (row[x / perByte] >> shift) & ((1 << depth) - 1);
    }

    /** One 8- or 16-bit channel as 0..255 (16-bit keeps the high byte). */
    private static int channel(byte[] row, int index, int depth) {
        return depth == 16 ? row[index * 2] & 0xFF : row[index] & 0xFF;
    }

    private static void writeChunk(OutputStream out, String type, byte[] body, int off, int len)
            throws IOException {
        byte[] head = new byte[8];
        putInt(head, 0, len);
        for (int i = 0; i < 4; i++) {
            head[4 + i] = (byte) type.charAt(i);
        }
        out.write(head);
        out.write(body, off, len);
        CRC32 crc = new CRC32();
        crc.update(head, 4, 4);
        crc.update(body, off, len);
        byte[] tail = new byte[4];
        putInt(tail, 0, (int) crc.getValue());
        out.write(tail);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static int getInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}
