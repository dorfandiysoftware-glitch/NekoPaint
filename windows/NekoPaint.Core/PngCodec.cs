// SPDX-License-Identifier: GPL-3.0-or-later
using System;
using System.IO;
using System.IO.Compression;
using System.Text;

namespace NekoPaint.Core
{
    /// <summary>Small PNG reader/writer on premultiplied ARGB. Writes 8-bit RGBA; reads grey, RGB, palette, grey+alpha, RGBA (8/16-bit, non-interlaced).</summary>
    public static class PngCodec
    {
        public sealed class Image
        {
            public readonly int Width, Height;
            /// <summary>Premultiplied ARGB.</summary>
            public readonly int[] Pixels;
            internal Image(int w, int h, int[] px) { Width = w; Height = h; Pixels = px; }

            public int[] ToStraightArgb()
            {
                var o = new int[Pixels.Length];
                for (int i = 0; i < o.Length; i++) o[i] = PixelOps.Unpremultiply(Pixels[i]);
                return o;
            }
        }

        private static readonly byte[] Signature = { 0x89, (byte)'P', (byte)'N', (byte)'G', 13, 10, 0x1A, 10 };
        private static readonly uint[] CrcTable = MakeCrcTable();

        private static uint[] MakeCrcTable()
        {
            var t = new uint[256];
            for (uint n = 0; n < 256; n++)
            {
                uint c = n;
                for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
                t[n] = c;
            }
            return t;
        }

        private static uint Crc(uint crc, byte[] buf, int off, int len)
        {
            uint c = ~crc;
            for (int i = 0; i < len; i++) c = CrcTable[(c ^ buf[off + i]) & 0xFF] ^ (c >> 8);
            return ~c;
        }

        public static void Encode(int width, int height, int[] pixels, Stream output)
        {
            Layer.CheckSize(width, height);
            if (pixels.Length != width * height) throw new ArgumentException("Pixel count does not match the size");
            output.Write(Signature, 0, 8);
            var header = new byte[13];
            PutInt(header, 0, width); PutInt(header, 4, height);
            header[8] = 8; header[9] = 6;
            WriteChunk(output, "IHDR", header, 0, header.Length);

            var compressed = new MemoryStream();
            using (var z = new ZLibStream(compressed, CompressionLevel.Optimal, true))
            {
                var row = new byte[1 + width * 4];
                for (int y = 0; y < height; y++)
                {
                    row[0] = 0;
                    int o = 1;
                    for (int x = 0; x < width; x++)
                    {
                        int p = PixelOps.Unpremultiply(pixels[y * width + x]);
                        row[o++] = (byte)(p >> 16);
                        row[o++] = (byte)(p >> 8);
                        row[o++] = (byte)p;
                        row[o++] = (byte)((uint)p >> 24);
                    }
                    z.Write(row, 0, row.Length);
                }
            }
            byte[] data = compressed.ToArray();
            WriteChunk(output, "IDAT", data, 0, data.Length);
            WriteChunk(output, "IEND", new byte[0], 0, 0);
        }

        public static Image Decode(Stream input)
        {
            var sig = new byte[8];
            ReadFully(input, sig, 8);
            for (int i = 0; i < 8; i++) if (sig[i] != Signature[i]) throw new IOException("Not a PNG file");

            int width = 0, height = 0, depth = 0, colorType = -1;
            byte[] palette = null, paletteAlpha = null;
            var idat = new MemoryStream();
            bool sawHeader = false, sawEnd = false;
            var lenBytes = new byte[4];
            var typeBytes = new byte[4];

            while (!sawEnd)
            {
                if (input.Read(lenBytes, 0, 1) != 1) throw new IOException("PNG ends early");
                ReadFully(input, lenBytes, 3, 1);
                int length = GetInt(lenBytes, 0);
                ReadFully(input, typeBytes, 4);
                string type = Encoding.Latin1.GetString(typeBytes);
                if (length < 0 || length > (1 << 28)) throw new IOException("Bad PNG chunk size");
                var body = new byte[length];
                ReadFully(input, body, length);
                ReadFully(input, new byte[4], 4); // CRC, not checked

                switch (type)
                {
                    case "IHDR":
                        if (length != 13) throw new IOException("Bad PNG header");
                        width = GetInt(body, 0); height = GetInt(body, 4);
                        depth = body[8]; colorType = body[9];
                        if (body[10] != 0 || body[11] != 0) throw new IOException("Unsupported PNG compression or filter method");
                        if (body[12] != 0) throw new IOException("Interlaced PNG is not supported");
                        try { Layer.CheckSize(width, height); }
                        catch (ArgumentException) { throw new IOException("Image size not supported: " + width + "x" + height); }
                        if (depth != 8 && depth != 16 && !(colorType == 3 && depth <= 8)) throw new IOException("Unsupported PNG bit depth " + depth);
                        if (colorType != 0 && colorType != 2 && colorType != 3 && colorType != 4 && colorType != 6)
                            throw new IOException("Unsupported PNG colour type " + colorType);
                        sawHeader = true;
                        break;
                    case "PLTE": palette = body; break;
                    case "tRNS": paletteAlpha = body; break;
                    case "IDAT": idat.Write(body, 0, body.Length); break;
                    case "IEND": sawEnd = true; break;
                }
            }
            if (!sawHeader) throw new IOException("PNG header missing");
            if (colorType == 3 && palette == null) throw new IOException("PNG palette missing");
            if (colorType == 0 || colorType == 2) paletteAlpha = null; // colour-key transparency is ignored

            int channels = colorType == 0 ? 1 : colorType == 2 ? 3 : colorType == 3 ? 1 : colorType == 4 ? 2 : 4;
            int bitsPerPixel = channels * depth;
            int stride = (width * bitsPerPixel + 7) / 8;
            int bpp = Math.Max(1, bitsPerPixel / 8);
            var raw = new byte[(stride + 1) * height];
            idat.Position = 0;
            try
            {
                using (var z = new ZLibStream(idat, CompressionMode.Decompress))
                {
                    int filled = 0;
                    while (filled < raw.Length)
                    {
                        int n = z.Read(raw, filled, raw.Length - filled);
                        if (n <= 0) break;
                        filled += n;
                    }
                    if (filled < raw.Length) throw new IOException("PNG image data is incomplete");
                }
            }
            catch (InvalidDataException) { throw new IOException("PNG image data is damaged"); }

            var prev = new byte[stride];
            var cur = new byte[stride];
            var pixels = new int[width * height];
            for (int y = 0; y < height; y++)
            {
                int b = y * (stride + 1);
                int filter = raw[b];
                Array.Copy(raw, b + 1, cur, 0, stride);
                Unfilter(filter, cur, prev, bpp);
                for (int x = 0; x < width; x++)
                    pixels[y * width + x] = PixelOps.Premultiply(PixelAt(cur, x, colorType, depth, palette, paletteAlpha));
                var t = prev; prev = cur; cur = t;
            }
            return new Image(width, height, pixels);
        }

        private static void Unfilter(int filter, byte[] cur, byte[] prev, int bpp)
        {
            int n = cur.Length;
            switch (filter)
            {
                case 0: break;
                case 1: for (int i = bpp; i < n; i++) cur[i] = (byte)(cur[i] + cur[i - bpp]); break;
                case 2: for (int i = 0; i < n; i++) cur[i] = (byte)(cur[i] + prev[i]); break;
                case 3:
                    for (int i = 0; i < n; i++)
                    {
                        int left = i >= bpp ? cur[i - bpp] : 0;
                        cur[i] = (byte)(cur[i] + ((left + prev[i]) >> 1));
                    }
                    break;
                case 4:
                    for (int i = 0; i < n; i++)
                    {
                        int a = i >= bpp ? cur[i - bpp] : 0, b = prev[i], c = i >= bpp ? prev[i - bpp] : 0;
                        int p = a + b - c;
                        int pa = Math.Abs(p - a), pb = Math.Abs(p - b), pc = Math.Abs(p - c);
                        int pred = pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
                        cur[i] = (byte)(cur[i] + pred);
                    }
                    break;
                default: throw new IOException("Bad PNG filter type " + filter);
            }
        }

        private static int PixelAt(byte[] row, int x, int colorType, int depth, byte[] palette, byte[] paletteAlpha)
        {
            int a = 255, r, g, b;
            switch (colorType)
            {
                case 0: r = g = b = Sample(row, x, depth); break;
                case 2:
                    r = Channel(row, x * 3, depth); g = Channel(row, x * 3 + 1, depth); b = Channel(row, x * 3 + 2, depth);
                    break;
                case 3:
                {
                    int index = RawSample(row, x, depth);
                    if (index * 3 + 2 >= palette.Length) throw new IOException("PNG palette index out of range");
                    r = palette[index * 3]; g = palette[index * 3 + 1]; b = palette[index * 3 + 2];
                    if (paletteAlpha != null && index < paletteAlpha.Length) a = paletteAlpha[index];
                    break;
                }
                case 4: r = g = b = Channel(row, x * 2, depth); a = Channel(row, x * 2 + 1, depth); break;
                default:
                    r = Channel(row, x * 4, depth); g = Channel(row, x * 4 + 1, depth);
                    b = Channel(row, x * 4 + 2, depth); a = Channel(row, x * 4 + 3, depth);
                    break;
            }
            return (a << 24) | (r << 16) | (g << 8) | b;
        }

        private static int Sample(byte[] row, int x, int depth)
        {
            int v = RawSample(row, x, depth);
            switch (depth)
            {
                case 1: return v * 255;
                case 2: return v * 85;
                case 4: return v * 17;
                default: return depth == 16 ? row[x * 2] : v;
            }
        }

        private static int RawSample(byte[] row, int x, int depth)
        {
            if (depth == 8) return row[x];
            if (depth == 16) return row[x * 2];
            int perByte = 8 / depth;
            int shift = 8 - depth * (x % perByte + 1);
            return (row[x / perByte] >> shift) & ((1 << depth) - 1);
        }

        private static int Channel(byte[] row, int index, int depth) => depth == 16 ? row[index * 2] : row[index];

        private static void WriteChunk(Stream o, string type, byte[] body, int off, int len)
        {
            var head = new byte[8];
            PutInt(head, 0, len);
            for (int i = 0; i < 4; i++) head[4 + i] = (byte)type[i];
            o.Write(head, 0, 8);
            o.Write(body, off, len);
            uint crc = Crc(0, head, 4, 4);
            crc = Crc(crc, body, off, len);
            var tail = new byte[4];
            PutInt(tail, 0, unchecked((int)crc));
            o.Write(tail, 0, 4);
        }

        private static void PutInt(byte[] b, int off, int v)
        {
            b[off] = (byte)((uint)v >> 24); b[off + 1] = (byte)((uint)v >> 16); b[off + 2] = (byte)((uint)v >> 8); b[off + 3] = (byte)v;
        }

        private static int GetInt(byte[] b, int off) => (b[off] << 24) | (b[off + 1] << 16) | (b[off + 2] << 8) | b[off + 3];

        private static void ReadFully(Stream s, byte[] buf, int count) => ReadFully(s, buf, count, 0);

        private static void ReadFully(Stream s, byte[] buf, int count, int offset)
        {
            int got = 0;
            while (got < count)
            {
                int n = s.Read(buf, offset + got, count - got);
                if (n <= 0) throw new IOException("PNG ends early");
                got += n;
            }
        }
    }
}
