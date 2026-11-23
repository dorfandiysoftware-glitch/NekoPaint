// SPDX-License-Identifier: GPL-3.0-or-later
using System;

namespace NekoPaint.Core
{
    /// <summary>One raster layer: premultiplied ARGB pixels plus compositing settings.</summary>
    public sealed class Layer
    {
        public const int MaxSize = 16384;
        public const int MaxPixels = 50000000;

        private readonly int[] _pixels;
        private string _name;
        private int _opacity = 255;
        private BlendMode _blendMode = BlendMode.Normal;

        public int Width { get; }
        public int Height { get; }
        public bool Visible { get; set; } = true;
        public bool Locked { get; set; }
        public bool AlphaLock { get; set; }
        public bool Clipped { get; set; }
        /// <summary>Selection layers are skipped when flattening and drawn as a tinted overlay.</summary>
        public bool Selection { get; set; }

        public Layer(int width, int height, string name)
        {
            CheckSize(width, height);
            Width = width; Height = height;
            _pixels = new int[width * height];
            Name = name;
        }

        internal static void CheckSize(int width, int height)
        {
            if (width < 1 || height < 1 || width > MaxSize || height > MaxSize || (long)width * height > MaxPixels)
                throw new ArgumentException("Unsupported canvas size");
        }

        public string Name
        {
            get => _name;
            set => _name = value ?? throw new ArgumentException("Name is missing");
        }

        public int Opacity
        {
            get => _opacity;
            set
            {
                if (value < 0 || value > 255) throw new ArgumentException("Opacity must be 0..255");
                _opacity = value;
            }
        }

        public BlendMode BlendMode
        {
            get => _blendMode;
            set => _blendMode = value;
        }

        public int GetPixel(int x, int y) { CheckPoint(x, y); return _pixels[y * Width + x]; }
        public void SetPixel(int x, int y, int premultipliedArgb) { CheckPoint(x, y); _pixels[y * Width + x] = premultipliedArgb; }

        public int[] CopyPixels() => (int[])_pixels.Clone();

        public int[] CopyRect(Rect rect)
        {
            CheckRect(rect);
            int w = rect.Width;
            var o = new int[w * rect.Height];
            for (int y = 0; y < rect.Height; y++)
                Array.Copy(_pixels, (rect.Top + y) * Width + rect.Left, o, y * w, w);
            return o;
        }

        public void PasteRect(Rect rect, int[] data)
        {
            CheckRect(rect);
            int w = rect.Width;
            if (data == null || data.Length != w * rect.Height) throw new ArgumentException("Data does not match the rectangle");
            for (int y = 0; y < rect.Height; y++)
                Array.Copy(data, y * w, _pixels, (rect.Top + y) * Width + rect.Left, w);
        }

        /// <summary>Replaces the content from straight (non-premultiplied) ARGB.</summary>
        public void SetStraightArgb(int[] argb)
        {
            if (argb == null || argb.Length != _pixels.Length) throw new ArgumentException("Data does not match the layer");
            for (int i = 0; i < argb.Length; i++) _pixels[i] = PixelOps.Premultiply(argb[i]);
        }

        /// <summary>Direct pixel access for the engine.</summary>
        internal int[] Pixels => _pixels;

        private void CheckPoint(int x, int y)
        {
            if (x < 0 || y < 0 || x >= Width || y >= Height) throw new IndexOutOfRangeException("Pixel is outside the layer");
        }

        private void CheckRect(Rect rect)
        {
            if (rect == null || rect.Left < 0 || rect.Top < 0 || rect.Right > Width || rect.Bottom > Height)
                throw new ArgumentException("Rectangle is outside the layer");
        }
    }
}
