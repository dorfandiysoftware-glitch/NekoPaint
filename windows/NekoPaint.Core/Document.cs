// SPDX-License-Identifier: GPL-3.0-or-later
using System;
using System.Collections.Generic;

namespace NekoPaint.Core
{
    /// <summary>A canvas: ordered layers, bottom first.</summary>
    public sealed class Document
    {
        private readonly List<Layer> _layers = new List<Layer>();

        public int Width { get; }
        public int Height { get; }

        public Document(int width, int height)
        {
            Layer.CheckSize(width, height);
            Width = width; Height = height;
        }

        public Layer AddLayer(string name)
        {
            var layer = new Layer(Width, Height, name);
            _layers.Add(layer);
            return layer;
        }

        public void RemoveLayer(int index)
        {
            if (_layers.Count <= 1) throw new InvalidOperationException("A document needs at least one layer");
            _layers.RemoveAt(index);
        }

        public void InsertLayer(int at, Layer layer) => _layers.Insert(Math.Max(0, Math.Min(at, _layers.Count)), layer);

        public Layer DetachLayer(int index)
        {
            var l = _layers[index];
            _layers.RemoveAt(index);
            return l;
        }

        public int IndexOf(Layer layer) => _layers.IndexOf(layer);

        public void MoveLayer(int from, int to)
        {
            if (to < 0 || to >= _layers.Count) throw new IndexOutOfRangeException("Bad target position: " + to);
            var l = _layers[from];
            _layers.RemoveAt(from);
            _layers.Insert(to, l);
        }

        public int LayerCount => _layers.Count;
        public Layer GetLayer(int index) => _layers[index];

        public Layer GetSelectionLayer()
        {
            foreach (var l in _layers) if (l.Selection) return l;
            return null;
        }

        public int[] Flatten() => Flatten(0);
        public int[] Flatten(int backgroundArgb) => FlattenRect(new Rect(0, 0, Width, Height), backgroundArgb);

        public int[] FlattenRect(Rect rect, int backgroundArgb)
        {
            var o = new int[rect.Width * rect.Height];
            FlattenRect(rect, backgroundArgb, o);
            return o;
        }

        /// <summary>Composites a rectangle of the canvas over a background into out (rect-sized, row major).</summary>
        public void FlattenRect(Rect rect, int backgroundArgb, int[] output)
        {
            if (rect.Left < 0 || rect.Top < 0 || rect.Right > Width || rect.Bottom > Height)
                throw new ArgumentException("Rectangle outside the canvas: " + rect);
            int w = rect.Width, h = rect.Height;
            if (output == null || output.Length < w * h) throw new ArgumentException("Buffer too small");
            int background = PixelOps.Premultiply(backgroundArgb);
            Array.Fill(output, background, 0, w * h);
            Layer baseLayer = null; // nearest layer below that is not clipped
            foreach (var layer in _layers)
            {
                if (layer.Selection) continue;
                bool clipped = layer.Clipped && baseLayer != null;
                if (!clipped) baseLayer = layer;
                int opacity = layer.Opacity;
                if (!layer.Visible || opacity == 0) continue;
                int[] mask = null;
                int maskOpacity = 255;
                if (clipped)
                {
                    if (!baseLayer.Visible || baseLayer.Opacity == 0) continue;
                    mask = baseLayer.Pixels;
                    maskOpacity = baseLayer.Opacity;
                }
                int[] source = layer.Pixels;
                BlendMode mode = layer.BlendMode;
                for (int y = 0; y < h; y++)
                {
                    int src = (rect.Top + y) * Width + rect.Left;
                    int dst = y * w;
                    for (int x = 0; x < w; x++)
                    {
                        int s = source[src + x];
                        if (s == 0) continue;
                        if (mask != null)
                        {
                            int ma = (int)((uint)mask[src + x] >> 24);
                            if (maskOpacity != 255) ma = PixelOps.Div255(ma * maskOpacity);
                            if (ma == 0) continue;
                            if (ma != 255) s = PixelOps.Scale(s, ma);
                        }
                        if (opacity != 255) s = PixelOps.Scale(s, opacity);
                        if (PixelOps.Alpha(s) == 0) continue;
                        output[dst + x] = (int)((uint)s >> 24) == 255 && mode == BlendMode.Normal
                            ? s : Blend.Apply(mode, s, output[dst + x]);
                    }
                }
            }
        }

        /// <summary>
        /// Composites tiles (origins = x,y pairs) into out; tile k lands at k*tile*tile, packed with the
        /// tile's real width. rgbaBytes stores words as 0xAABBGGRR. Uses the C core for plain NORMAL stacks.
        /// </summary>
        public void FlattenTiles(int[] origins, int count, int tile, int backgroundArgb, int[] output, bool rgbaBytes)
        {
            int background = PixelOps.Premultiply(backgroundArgb);
            int visible = 0;
            bool plain = NativeStroke.Available;
            foreach (var layer in _layers)
            {
                if (layer.Visible && layer.Opacity > 0 && !layer.Selection)
                {
                    visible++;
                    if (layer.BlendMode != BlendMode.Normal || layer.Clipped) plain = false;
                }
            }
            if (plain && visible <= 64)
            {
                var px = new int[visible][];
                var op = new int[visible];
                int n = 0;
                foreach (var layer in _layers)
                {
                    if (layer.Visible && layer.Opacity > 0 && !layer.Selection)
                    {
                        px[n] = layer.Pixels; op[n++] = layer.Opacity;
                    }
                }
                if (NativeStroke.FlattenTiles(px, op, visible, Width, Height, background, origins, count, tile, output, rgbaBytes))
                    return;
            }
            var scratch = new int[tile * tile];
            for (int k = 0; k < count; k++)
            {
                int x = origins[2 * k], y = origins[2 * k + 1];
                int w = Math.Min(tile, Width - x), h = Math.Min(tile, Height - y);
                if (w <= 0 || h <= 0) continue;
                FlattenRect(new Rect(x, y, x + w, y + h), backgroundArgb, scratch);
                int b = k * tile * tile;
                for (int i = 0; i < w * h; i++)
                {
                    int p = scratch[i];
                    output[b + i] = rgbaBytes
                        ? (p & unchecked((int)0xFF00FF00)) | (int)(((uint)p >> 16) & 0xFF) | ((p & 0xFF) << 16)
                        : p;
                }
            }
        }
    }
}
