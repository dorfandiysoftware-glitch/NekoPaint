// SPDX-License-Identifier: GPL-3.0-or-later
using System;

namespace NekoPaint.Core
{
    /// <summary>Premultiplied ARGB (0xAARRGGBB) helpers, same arithmetic as the Java reference.</summary>
    public static class PixelOps
    {
        public static int Div255(int x) => (x + 127) / 255;
        public static int Alpha(int p) => (int)((uint)p >> 24);
        public static int Red(int p) => (p >> 16) & 0xFF;
        public static int Green(int p) => (p >> 8) & 0xFF;
        public static int Blue(int p) => p & 0xFF;
        public static int Clamp(int v, int lo, int hi) => v < lo ? lo : (v > hi ? hi : v);

        public static int Pack(int a, int r, int g, int b)
        {
            int alpha = Clamp(a, 0, 255);
            return (alpha << 24) | (Clamp(r, 0, alpha) << 16) | (Clamp(g, 0, alpha) << 8) | Clamp(b, 0, alpha);
        }

        public static int Premultiply(int argb)
        {
            int a = (int)((uint)argb >> 24);
            if (a == 255) return argb;
            if (a == 0) return 0;
            return (a << 24) | (Div255(Red(argb) * a) << 16) | (Div255(Green(argb) * a) << 8) | Div255(Blue(argb) * a);
        }

        public static int Unpremultiply(int p)
        {
            int a = Alpha(p);
            if (a == 255) return p;
            if (a == 0) return 0;
            return (a << 24) | (UnpremultiplyChannel(Red(p), a) << 16)
                | (UnpremultiplyChannel(Green(p), a) << 8) | UnpremultiplyChannel(Blue(p), a);
        }

        private static int UnpremultiplyChannel(int c, int a) => Math.Min(255, (c * 255 + a / 2) / a);

        public static int Scale(int p, int factor)
        {
            if (factor >= 255) return p;
            if (factor <= 0) return 0;
            return Pack(Div255(Alpha(p) * factor), Div255(Red(p) * factor), Div255(Green(p) * factor), Div255(Blue(p) * factor));
        }
    }
}
