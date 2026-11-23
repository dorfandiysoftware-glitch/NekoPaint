// SPDX-License-Identifier: GPL-3.0-or-later
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Text;
using System.Xml;

namespace NekoPaint.Core
{
    /// <summary>OpenRaster (.ora) reader/writer. Same attributes as the Android app, so files open on both.</summary>
    public static class OpenRaster
    {
        private const long MaxEntryBytes = 400L * 1024 * 1024;
        private const int MaxLayers = 200;
        private const int Thumb = 256;
        private const string ThumbPath = "Thumbnails/thumbnail.png";
        private static readonly UTF8Encoding Utf8 = new UTF8Encoding(false);

        public static void Write(Document document, Stream output, int paperArgb = 0)
        {
            using (var zip = new ZipArchive(output, ZipArchiveMode.Create, true, Utf8))
            {
                // mimetype first and uncompressed, as the format requires
                PutEntry(zip, "mimetype", Utf8.GetBytes("image/openraster"), CompressionLevel.NoCompression);

                int w = document.Width, h = document.Height;
                var xml = new StringBuilder();
                xml.Append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                xml.Append("<image version=\"0.0.3\" w=\"").Append(w).Append("\" h=\"").Append(h).Append("\">\n<stack>\n");
                int count = document.LayerCount;
                for (int i = count - 1; i >= 0; i--)
                {
                    var layer = document.GetLayer(i);
                    xml.Append("<layer name=\"").Append(Escape(layer.Name))
                        .Append("\" src=\"data/layer").Append(i).Append(".png\"")
                        .Append(" visibility=\"").Append(layer.Visible ? "visible" : "hidden")
                        .Append("\" opacity=\"").Append((layer.Opacity / 255f).ToString("0.0000", CultureInfo.InvariantCulture))
                        .Append("\" clipping=\"").Append(Bool(layer.Clipped))
                        .Append("\" alpha-lock=\"").Append(Bool(layer.AlphaLock))
                        .Append("\" edit-locked=\"").Append(Bool(layer.Locked))
                        .Append("\" nekopaint-selection=\"").Append(Bool(layer.Selection))
                        .Append("\" composite-op=\"").Append(CompositeOp(layer.BlendMode))
                        .Append("\" x=\"0\" y=\"0\"/>\n");
                }
                xml.Append("</stack>\n</image>\n");
                PutEntry(zip, "stack.xml", Utf8.GetBytes(xml.ToString()), CompressionLevel.Optimal);

                int[] flat = document.Flatten(paperArgb);
                int[] preview = ScaleDown(flat, w, h, Thumb);
                int tw = Math.Max(1, w * Math.Min(Thumb, Math.Max(w, h)) / Math.Max(w, h));
                int th = preview.Length / tw;
                var thumb = new MemoryStream();
                PngCodec.Encode(tw, th, preview, thumb);
                PutEntry(zip, ThumbPath, thumb.ToArray(), CompressionLevel.NoCompression);

                for (int i = 0; i < count; i++)
                {
                    var png = new MemoryStream();
                    PngCodec.Encode(w, h, document.GetLayer(i).CopyPixels(), png);
                    PutEntry(zip, "data/layer" + i + ".png", png.ToArray(), CompressionLevel.NoCompression);
                }
                var merged = new MemoryStream();
                PngCodec.Encode(w, h, flat, merged);
                PutEntry(zip, "mergedimage.png", merged.ToArray(), CompressionLevel.NoCompression);
            }
        }

        public static Document Read(Stream input)
        {
            var files = new Dictionary<string, byte[]>();
            int entries = 0;
            long total = 0;
            using (var zip = new ZipArchive(input, ZipArchiveMode.Read, true, Utf8))
            {
                foreach (var entry in zip.Entries)
                {
                    if (++entries > MaxLayers * 2 + 12) throw new IOException("Too many files in the archive");
                    if (entry.FullName.EndsWith("/")) continue;
                    if (entry.Length > MaxEntryBytes) throw new IOException("The file is too large");
                    using (var s = entry.Open())
                    {
                        var buf = new MemoryStream();
                        var chunk = new byte[65536];
                        long size = 0;
                        int n;
                        while ((n = s.Read(chunk, 0, chunk.Length)) > 0)
                        {
                            size += n; total += n;
                            if (size > MaxEntryBytes || total > MaxEntryBytes * 2) throw new IOException("The file is too large");
                            buf.Write(chunk, 0, n);
                        }
                        files[entry.FullName] = buf.ToArray();
                    }
                }
            }
            files.TryGetValue("mimetype", out var mime);
            if (mime == null || Utf8.GetString(mime).Trim() != "image/openraster") throw new IOException("Not an OpenRaster file");
            if (!files.TryGetValue("stack.xml", out var stack)) throw new IOException("stack.xml is missing");

            XmlElement root;
            try
            {
                var settings = new XmlReaderSettings { DtdProcessing = DtdProcessing.Prohibit, XmlResolver = null };
                var doc = new XmlDocument { XmlResolver = null };
                using (var reader = XmlReader.Create(new MemoryStream(stack), settings)) doc.Load(reader);
                root = doc.DocumentElement;
            }
            catch (XmlException) { throw new IOException("stack.xml is damaged"); }

            int width = IntAttr(root, "w", -1), height = IntAttr(root, "h", -1);
            try { Layer.CheckSize(width, height); }
            catch (ArgumentException) { throw new IOException("Image size not supported: " + width + "x" + height); }
            var document = new Document(width, height);

            var layers = new List<XmlElement>();
            CollectLayers(root, layers);
            if (layers.Count > MaxLayers) throw new IOException("Too many layers");
            for (int i = layers.Count - 1; i >= 0; i--)
            {
                var e = layers[i];
                if (!files.TryGetValue(e.GetAttribute("src"), out var png)) continue;
                var image = PngCodec.Decode(new MemoryStream(png));
                string name = e.GetAttribute("name");
                var layer = document.AddLayer(name.Length == 0 ? "Layer" : name);
                Blit(image, layer, IntAttr(e, "x", 0), IntAttr(e, "y", 0));
                layer.Visible = e.GetAttribute("visibility") != "hidden";
                layer.Opacity = OpacityAttr(e);
                layer.BlendMode = ParseBlend(e.GetAttribute("composite-op"));
                layer.Clipped = e.GetAttribute("clipping") == "true" && document.LayerCount > 1;
                layer.AlphaLock = e.GetAttribute("alpha-lock") == "true";
                layer.Locked = e.GetAttribute("edit-locked") == "true";
                layer.Selection = e.GetAttribute("nekopaint-selection") == "true" && document.GetSelectionLayer() == null;
            }
            if (document.LayerCount == 0) document.AddLayer("Layer 1");
            return document;
        }

        private static void CollectLayers(XmlElement parent, List<XmlElement> output)
        {
            foreach (XmlNode node in parent.ChildNodes)
            {
                if (!(node is XmlElement e)) continue;
                if (e.Name == "layer") output.Add(e);
                else if (e.Name == "stack") CollectLayers(e, output);
            }
        }

        private static void Blit(PngCodec.Image image, Layer layer, int offX, int offY)
        {
            for (int y = 0; y < image.Height; y++)
            {
                int ty = y + offY;
                if (ty < 0 || ty >= layer.Height) continue;
                for (int x = 0; x < image.Width; x++)
                {
                    int tx = x + offX;
                    if (tx < 0 || tx >= layer.Width) continue;
                    layer.SetPixel(tx, ty, image.Pixels[y * image.Width + x]);
                }
            }
        }

        internal static int[] ScaleDown(int[] src, int w, int h, int max)
        {
            int longer = Math.Max(w, h);
            if (longer <= max) return (int[])src.Clone();
            int tw = Math.Max(1, w * max / longer), th = Math.Max(1, h * max / longer);
            var o = new int[tw * th];
            for (int y = 0; y < th; y++)
            {
                int y0 = (int)((long)y * h / th);
                int y1 = Math.Max(y0 + 1, (int)((long)(y + 1) * h / th));
                for (int x = 0; x < tw; x++)
                {
                    int x0 = (int)((long)x * w / tw);
                    int x1 = Math.Max(x0 + 1, (int)((long)(x + 1) * w / tw));
                    long a = 0, r = 0, g = 0, b = 0;
                    int n = 0;
                    for (int yy = y0; yy < y1; yy++)
                        for (int xx = x0; xx < x1; xx++)
                        {
                            int p = src[yy * w + xx];
                            a += (int)((uint)p >> 24); r += (p >> 16) & 0xFF; g += (p >> 8) & 0xFF; b += p & 0xFF;
                            n++;
                        }
                    o[y * tw + x] = ((int)(a / n) << 24) | ((int)(r / n) << 16) | ((int)(g / n) << 8) | (int)(b / n);
                }
            }
            return o;
        }

        private static void PutEntry(ZipArchive zip, string name, byte[] data, CompressionLevel level)
        {
            var entry = zip.CreateEntry(name, level);
            using (var s = entry.Open()) s.Write(data, 0, data.Length);
        }

        private static string Bool(bool b) => b ? "true" : "false";

        private static readonly (BlendMode, string)[] Ops =
        {
            (BlendMode.Normal, "svg:src-over"), (BlendMode.Multiply, "svg:multiply"), (BlendMode.Screen, "svg:screen"),
            (BlendMode.Darken, "svg:darken"), (BlendMode.Lighten, "svg:lighten"), (BlendMode.ColorBurn, "svg:color-burn"),
            (BlendMode.ColorDodge, "svg:color-dodge"), (BlendMode.Add, "svg:plus"), (BlendMode.Overlay, "svg:overlay"),
            (BlendMode.SoftLight, "svg:soft-light"), (BlendMode.HardLight, "svg:hard-light"),
            (BlendMode.Difference, "svg:difference"), (BlendMode.Exclusion, "svg:exclusion"),
        };

        private static string CompositeOp(BlendMode mode)
        {
            foreach (var (m, s) in Ops) if (m == mode) return s;
            return "svg:src-over";
        }

        private static BlendMode ParseBlend(string op)
        {
            foreach (var (m, s) in Ops) if (s == op) return m;
            return BlendMode.Normal;
        }

        private static int IntAttr(XmlElement e, string name, int fallback)
        {
            string v = e.GetAttribute(name);
            if (string.IsNullOrEmpty(v)) return fallback;
            return int.TryParse(v.Trim(), NumberStyles.Integer, CultureInfo.InvariantCulture, out int r) ? r : fallback;
        }

        private static int OpacityAttr(XmlElement e)
        {
            string v = e.GetAttribute("opacity");
            if (string.IsNullOrEmpty(v)) return 255;
            if (!float.TryParse(v.Trim(), NumberStyles.Float, CultureInfo.InvariantCulture, out float f) || float.IsNaN(f)) return 255;
            return Math.Max(0, Math.Min(255, (int)Math.Floor(f * 255f + 0.5f)));
        }

        private static string Escape(string s)
        {
            var b = new StringBuilder();
            foreach (char c in s)
            {
                switch (c)
                {
                    case '&': b.Append("&amp;"); break;
                    case '<': b.Append("&lt;"); break;
                    case '>': b.Append("&gt;"); break;
                    case '"': b.Append("&quot;"); break;
                    default: if (c >= 0x20 || c == '\t') b.Append(c); break;
                }
            }
            return b.ToString();
        }
    }
}
