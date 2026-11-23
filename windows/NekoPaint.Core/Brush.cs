// SPDX-License-Identifier: GPL-3.0-or-later
using System;

namespace NekoPaint.Core
{
    /// <summary>Immutable brush settings.</summary>
    public sealed class Brush
    {
        public int Rgb { get; }
        public float Radius { get; }
        public float Hardness { get; }
        public float Flow { get; }
        public float Spacing { get; }
        public bool IsEraser { get; }

        public Brush(int rgb, float radius, float hardness, float flow, float spacing, bool eraser)
        {
            if (!(radius > 0f && radius <= 2048f)) throw new ArgumentException("Radius must be in (0, 2048]");
            if (!(hardness >= 0f && hardness <= 1f)) throw new ArgumentException("Hardness must be in [0, 1]");
            if (!(flow >= 0f && flow <= 1f)) throw new ArgumentException("Flow must be in [0, 1]");
            if (!(spacing >= 0.01f && spacing <= 2f)) throw new ArgumentException("Spacing must be in [0.01, 2]");
            Rgb = rgb & 0xFFFFFF;
            Radius = radius; Hardness = hardness; Flow = flow; Spacing = spacing; IsEraser = eraser;
        }

        public static Brush Pen(int rgb, float radius) => new Brush(rgb, radius, 0.8f, 1f, 0.15f, false);
        public static Brush Eraser(float radius) => new Brush(0, radius, 1f, 1f, 0.15f, true);
    }
}
