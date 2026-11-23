using System;
using System.IO;
using NekoPaint.Core;
using Xunit;

namespace NekoPaint.Core.Tests
{
    public class CoreTests
    {
        private static Document NewDoc(int w = 200, int h = 150)
        {
            var d = new Document(w, h);
            d.AddLayer("Background");
            return d;
        }

        [Fact]
        public void PixelOpsRoundTrip()
        {
            int straight = unchecked((int)0x80FF8040);
            int p = PixelOps.Premultiply(straight);
            Assert.Equal(0x80, PixelOps.Alpha(p));
            Assert.True(PixelOps.Red(p) <= 0x80);
            int back = PixelOps.Unpremultiply(p);
            Assert.InRange(PixelOps.Red(back), 0xFE, 0xFF);
        }

        [Fact]
        public void NormalOpaqueSourceReplaces()
        {
            int src = unchecked((int)0xFF102030);
            Assert.Equal(src, Blend.Apply(BlendMode.Normal, src, unchecked((int)0xFFFFFFFF)));
        }

        [Fact]
        public void MultiplyWithWhiteKeepsSource()
        {
            int src = unchecked((int)0xFF204080);
            Assert.Equal(src, Blend.Apply(BlendMode.Multiply, src, unchecked((int)0xFFFFFFFF)));
        }

        [Fact]
        public void BrushValidation()
        {
            Assert.Throws<ArgumentException>(() => new Brush(0, 0f, 0.5f, 1f, 0.1f, false));
            Assert.Throws<ArgumentException>(() => new Brush(0, 5f, 2f, 1f, 0.1f, false));
        }

        [Fact]
        public void StrokePaintsAndUndoRestores()
        {
            var doc = NewDoc();
            var ed = new Editor(doc, 50);
            var s = ed.BeginStroke(0, Brush.Pen(0xFF0000, 6f));
            s.AddPoint(20, 20, 1f);
            s.AddPoint(120, 80, 1f);
            s.End();
            Assert.NotEqual(0, doc.GetLayer(0).GetPixel(70, 50));
            Assert.True(ed.CanUndo);
            Assert.True(ed.Undo());
            Assert.Equal(0, doc.GetLayer(0).GetPixel(70, 50));
            Assert.True(ed.Redo());
            Assert.NotEqual(0, doc.GetLayer(0).GetPixel(70, 50));
        }

        [Fact]
        public void EraserClears()
        {
            var doc = NewDoc();
            var ed = new Editor(doc, 50);
            ed.FillLayer(0, 0x00FF00);
            var s = ed.BeginStroke(0, Brush.Eraser(10f));
            s.AddPoint(50, 50, 1f);
            s.End();
            Assert.Equal(0, PixelOps.Alpha(doc.GetLayer(0).GetPixel(50, 50)));
            Assert.Equal(255, PixelOps.Alpha(doc.GetLayer(0).GetPixel(150, 120)));
        }

        [Fact]
        public void CancelRestoresPixels()
        {
            var doc = NewDoc();
            var ed = new Editor(doc, 50);
            var s = ed.BeginStroke(0, Brush.Pen(0x0000FF, 8f));
            s.AddPoint(30, 30, 1f);
            s.AddPoint(90, 90, 1f);
            s.Cancel();
            Assert.Equal(0, doc.GetLayer(0).GetPixel(60, 60));
            Assert.False(ed.CanUndo);
        }

        [Fact]
        public void LayerOpsUndo()
        {
            var doc = NewDoc();
            var ed = new Editor(doc, 50);
            ed.AddLayer("Top", 1);
            Assert.Equal(2, doc.LayerCount);
            ed.SetLayerOpacity(1, 100);
            ed.SetLayerOpacity(1, 60); // coalesced with the previous change
            Assert.Equal(60, doc.GetLayer(1).Opacity);
            ed.Undo();
            Assert.Equal(255, doc.GetLayer(1).Opacity);
            ed.Undo();
            Assert.Equal(1, doc.LayerCount);
            ed.Redo();
            Assert.Equal(2, doc.LayerCount);
        }

        [Fact]
        public void MergeDownAndUndo()
        {
            var doc = NewDoc(20, 20);
            var ed = new Editor(doc, 50);
            ed.FillLayer(0, 0xFF0000);
            ed.AddLayer("Top", 1);
            ed.FillLayer(1, 0x0000FF);
            ed.MergeDown(1);
            Assert.Equal(1, doc.LayerCount);
            Assert.Equal(unchecked((int)0xFF0000FF), doc.GetLayer(0).GetPixel(5, 5));
            ed.Undo();
            Assert.Equal(2, doc.LayerCount);
            Assert.Equal(unchecked((int)0xFFFF0000), doc.GetLayer(0).GetPixel(5, 5));
        }

        [Fact]
        public void FlattenHonoursOpacityAndVisibility()
        {
            var doc = NewDoc(4, 4);
            doc.GetLayer(0).SetPixel(1, 1, unchecked((int)0xFFFF0000));
            var top = doc.AddLayer("Top");
            top.SetPixel(1, 1, unchecked((int)0xFF0000FF));
            top.Opacity = 0;
            Assert.Equal(unchecked((int)0xFFFF0000), doc.Flatten()[1 * 4 + 1]);
            top.Opacity = 255; top.Visible = false;
            Assert.Equal(unchecked((int)0xFFFF0000), doc.Flatten()[1 * 4 + 1]);
            top.Visible = true;
            Assert.Equal(unchecked((int)0xFF0000FF), doc.Flatten()[1 * 4 + 1]);
        }

        [Fact]
        public void PngRoundTrip()
        {
            var px = new int[16 * 16];
            for (int i = 0; i < px.Length; i++) px[i] = PixelOps.Premultiply(unchecked((int)(0xFF000000u | (uint)(i * 0x010203))));
            px[5] = 0;
            var ms = new MemoryStream();
            PngCodec.Encode(16, 16, px, ms);
            ms.Position = 0;
            var img = PngCodec.Decode(ms);
            Assert.Equal(16, img.Width);
            Assert.Equal(px, img.Pixels);
        }

        [Fact]
        public void OpenRasterRoundTrip()
        {
            var doc = NewDoc(30, 20);
            doc.GetLayer(0).Name = "Base <&>";
            doc.GetLayer(0).SetPixel(3, 3, PixelOps.Premultiply(unchecked((int)0xC0FF8000)));
            var top = doc.AddLayer("Top");
            top.Opacity = 128; top.BlendMode = BlendMode.Multiply; top.Visible = false; top.Clipped = true;
            top.SetPixel(4, 4, unchecked((int)0xFF00FF00));
            var ms = new MemoryStream();
            OpenRaster.Write(doc, ms);
            ms.Position = 0;
            var back = OpenRaster.Read(ms);
            Assert.Equal(2, back.LayerCount);
            Assert.Equal("Base <&>", back.GetLayer(0).Name);
            Assert.Equal(doc.GetLayer(0).GetPixel(3, 3), back.GetLayer(0).GetPixel(3, 3));
            Assert.Equal(BlendMode.Multiply, back.GetLayer(1).BlendMode);
            Assert.False(back.GetLayer(1).Visible);
            Assert.True(back.GetLayer(1).Clipped);
            Assert.InRange(back.GetLayer(1).Opacity, 127, 129);
        }

        /// <summary>The C core must paint exactly what the managed reference paints.</summary>
        [Fact]
        public void NativeMatchesManaged()
        {
            var brushes = new[]
            {
                Brush.Pen(0x336699, 9.5f), Brush.Pen(0xFFAA00, 1.2f), new Brush(0x00AA55, 20f, 0.2f, 0.6f, 0.3f, false),
                Brush.Eraser(7f)
            };
            foreach (var brush in brushes)
            {
                var a = new Layer(160, 120, "a");
                var b = new Layer(160, 120, "b");
                a.SetStraightArgb(Fill(160 * 120));
                b.SetStraightArgb(Fill(160 * 120));
                var ra = new StrokeRasterizer(a, brush, false);
                var rb = new StrokeRasterizer(b, brush, true);
                var rnd = new Random(7);
                float x = 10, y = 10;
                for (int i = 0; i < 60; i++)
                {
                    x += (float)(rnd.NextDouble() * 6); y += (float)(rnd.NextDouble() * 4 - 1);
                    float p = (float)rnd.NextDouble();
                    ra.AddPoint(x, y, p); rb.AddPoint(x, y, p);
                }
                Assert.Equal(a.CopyPixels(), b.CopyPixels());
                Assert.Equal(ra.GetDirty(), rb.GetDirty());
            }
        }

        private static int[] Fill(int n)
        {
            var px = new int[n];
            for (int i = 0; i < n; i++) px[i] = unchecked((int)(0xFF000000u | (uint)((i * 2654435761u) & 0xFFFFFF)));
            return px;
        }

        [Fact]
        public void FlattenTilesNativeMatchesManaged()
        {
            var doc = new Document(150, 90);
            var l0 = doc.AddLayer("a"); l0.SetStraightArgb(Fill(150 * 90));
            var l1 = doc.AddLayer("b");
            var half = new int[150 * 90];
            for (int i = 0; i < half.Length; i++) half[i] = i % 3 == 0 ? 0 : unchecked((int)0x80804020);
            l1.SetStraightArgb(half); l1.Opacity = 200;
            int tile = 64;
            int[] origins = { 0, 0, 64, 0, 128, 0, 0, 64, 64, 64, 128, 64 };
            var native = new int[6 * tile * tile];
            doc.FlattenTiles(origins, 6, tile, unchecked((int)0xFFFFFFFF), native, false);
            // reference: per-tile managed flatten
            var scratch = new int[tile * tile];
            for (int k = 0; k < 6; k++)
            {
                int x = origins[2 * k], y = origins[2 * k + 1];
                int w = Math.Min(tile, 150 - x), h = Math.Min(tile, 90 - y);
                doc.FlattenRect(new Rect(x, y, x + w, y + h), unchecked((int)0xFFFFFFFF), scratch);
                for (int i = 0; i < w * h; i++) Assert.Equal(scratch[i], native[k * tile * tile + i]);
            }
        }
    }
}
