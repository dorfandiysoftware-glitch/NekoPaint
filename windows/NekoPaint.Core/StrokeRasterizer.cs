// SPDX-License-Identifier: GPL-3.0-or-later
using System;

namespace NekoPaint.Core
{
    /// <summary>
    /// Paints one stroke into a layer. Uses the C core when available; the managed path below is
    /// the reference implementation and produces identical pixels.
    /// </summary>
    public sealed class StrokeRasterizer
    {
        private const float MaxCoord = 100000f;

        private readonly Layer _layer;
        private readonly Brush _brush;
        private readonly int[] _pixels;
        private readonly int _width, _height;
        private readonly bool _useNative = NativeStroke.Available;
        private NativeStroke.NpStroke _native;

        private bool _hasLast;
        private float _lastX, _lastY, _lastPressure, _carry;
        private int _minX = int.MaxValue, _minY = int.MaxValue, _maxX = -1, _maxY = -1;
        private int _pendMinX = int.MaxValue, _pendMinY = int.MaxValue, _pendMaxX = -1, _pendMaxY = -1;

        public StrokeRasterizer(Layer layer, Brush brush) : this(layer, brush, true) { }

        /// <summary>allowNative=false forces the managed path (used by parity tests).</summary>
        public StrokeRasterizer(Layer layer, Brush brush, bool allowNative)
        {
            if (layer == null || brush == null) throw new ArgumentException("Layer and brush are required");
            _layer = layer; _brush = brush;
            _pixels = layer.Pixels; _width = layer.Width; _height = layer.Height;
            _useNative = allowNative && NativeStroke.Available;
            if (_useNative) NativeStroke.Init(ref _native);
        }

        public Layer Layer => _layer;

        public void AddPoint(float x, float y, float pressure)
        {
            if (_useNative)
            {
                NativeStroke.AddPoint(ref _native, _pixels, _width, _height, _brush, x, y, pressure);
                return;
            }
            if (float.IsNaN(x) || float.IsNaN(y) || float.IsInfinity(x) || float.IsInfinity(y)) return;
            x = Math.Max(-MaxCoord, Math.Min(MaxCoord, x));
            y = Math.Max(-MaxCoord, Math.Min(MaxCoord, y));
            float p = float.IsNaN(pressure) ? 0f : Math.Max(0f, Math.Min(1f, pressure));
            float step = Math.Max(0.5f, _brush.Spacing * 2f * _brush.Radius);

            if (!_hasLast)
            {
                Stamp(x, y, p);
                _hasLast = true; _lastX = x; _lastY = y; _lastPressure = p; _carry = step;
                return;
            }

            float dx = x - _lastX, dy = y - _lastY;
            float dist = (float)Math.Sqrt(dx * dx + dy * dy);
            if (dist > 0f)
            {
                float pos = _carry;
                while (pos <= dist)
                {
                    float t = pos / dist;
                    Stamp(_lastX + dx * t, _lastY + dy * t, _lastPressure + (p - _lastPressure) * t);
                    pos += step;
                }
                _carry = pos - dist;
            }
            _lastX = x; _lastY = y; _lastPressure = p;
        }

        /// <summary>Area changed since the last call.</summary>
        public Rect TakeChanged()
        {
            if (_useNative)
            {
                Rect n = _native.PendMaxX < 0 ? Rect.Empty
                    : new Rect(_native.PendMinX, _native.PendMinY, _native.PendMaxX + 1, _native.PendMaxY + 1);
                NativeStroke.ResetPending(ref _native);
                return n;
            }
            Rect r = _pendMaxX < 0 ? Rect.Empty : new Rect(_pendMinX, _pendMinY, _pendMaxX + 1, _pendMaxY + 1);
            _pendMinX = int.MaxValue; _pendMinY = int.MaxValue; _pendMaxX = -1; _pendMaxY = -1;
            return r;
        }

        /// <summary>Area changed since the start of the stroke.</summary>
        public Rect GetDirty()
        {
            if (_useNative)
                return _native.TotalMaxX < 0 ? Rect.Empty
                    : new Rect(_native.TotalMinX, _native.TotalMinY, _native.TotalMaxX + 1, _native.TotalMaxY + 1);
            return _maxX < 0 ? Rect.Empty : new Rect(_minX, _minY, _maxX + 1, _maxY + 1);
        }

        // Java Math.round(float)
        private static int Round(float v) => (int)Math.Floor((double)v + 0.5);

        private void Stamp(float cx, float cy, float pressure)
        {
            float r = _brush.Radius;
            int x0 = Math.Max(0, (int)Math.Floor(cx - r));
            int x1 = Math.Min(_width - 1, (int)Math.Ceiling(cx + r));
            int y0 = Math.Max(0, (int)Math.Floor(cy - r));
            int y1 = Math.Min(_height - 1, (int)Math.Ceiling(cy + r));
            if (x0 > x1 || y0 > y1) return;

            float fall = Math.Max(r * (1f - _brush.Hardness), 1f);
            float inner = r - fall;
            float strength = _brush.Flow * pressure;
            bool erase = _brush.IsEraser;
            int rgb = _brush.Rgb;
            float rSq = r * r;
            float innerSq = inner > 0f ? inner * inner : -1f;
            int fullAlpha = Round(strength * 255f);
            int fullSource = erase ? 0 : PixelOps.Premultiply((fullAlpha << 24) | rgb);

            for (int y = y0; y <= y1; y++)
            {
                float dy = y + 0.5f - cy;
                float rest = rSq - dy * dy;
                if (rest <= 0f) continue;
                float half = (float)Math.Sqrt(rest);
                int xa = Math.Max(x0, (int)Math.Floor(cx - half - 0.5f));
                int xb = Math.Min(x1, (int)Math.Ceiling(cx + half - 0.5f));
                int coreA = 1, coreB = 0;
                float coreRest = innerSq - dy * dy;
                if (coreRest > 0f)
                {
                    float coreHalf = (float)Math.Sqrt(coreRest);
                    coreA = (int)Math.Ceiling(cx - coreHalf - 0.5f);
                    coreB = (int)Math.Floor(cx + coreHalf - 0.5f);
                }
                int rowStart = y * _width;
                int firstChanged = -1, lastChanged = -1;
                for (int x = xa; x <= xb; x++)
                {
                    int a, source;
                    if (x >= coreA && x <= coreB)
                    {
                        a = fullAlpha; source = fullSource;
                    }
                    else
                    {
                        float dx = x + 0.5f - cx;
                        float d = (float)Math.Sqrt(dx * dx + dy * dy);
                        if (d >= r) continue;
                        float coverage = d <= inner ? 1f : (r - d) / fall;
                        a = Round(coverage * strength * 255f);
                        source = erase ? 0 : PixelOps.Premultiply((a << 24) | rgb);
                    }
                    if (a <= 0) continue;
                    int index = rowStart + x;
                    int old = _pixels[index];
                    int result = erase ? PixelOps.Scale(old, 255 - a) : Blend.Apply(BlendMode.Normal, source, old);
                    if (result != old)
                    {
                        _pixels[index] = result;
                        if (firstChanged < 0) firstChanged = x;
                        lastChanged = x;
                    }
                }
                if (firstChanged >= 0) { MarkDirty(firstChanged, y); MarkDirty(lastChanged, y); }
            }
        }

        private void MarkDirty(int x, int y)
        {
            if (x < _pendMinX) _pendMinX = x;
            if (y < _pendMinY) _pendMinY = y;
            if (x > _pendMaxX) _pendMaxX = x;
            if (y > _pendMaxY) _pendMaxY = y;
            if (x < _minX) _minX = x;
            if (y < _minY) _minY = y;
            if (x > _maxX) _maxX = x;
            if (y > _maxY) _maxY = y;
        }
    }
}
