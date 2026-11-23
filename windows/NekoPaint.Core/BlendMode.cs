// SPDX-License-Identifier: GPL-3.0-or-later
using System;
using static NekoPaint.Core.PixelOps;

namespace NekoPaint.Core
{
    public enum BlendMode
    {
        Normal, Multiply, Screen, Darken, Lighten, ColorBurn, ColorDodge, Add, Overlay, SoftLight,
        HardLight, Difference, Exclusion
    }

    /// <summary>Blend functions on premultiplied ARGB. Mirrors the Java reference bit for bit.</summary>
    public static class Blend
    {
        public static int Apply(BlendMode mode, int src, int dst)
        {
            switch (mode)
            {
                case BlendMode.Normal: return Normal(src, dst);
                case BlendMode.Multiply: return MultiplyMode(src, dst);
                case BlendMode.Screen: return ScreenMode(src, dst);
                default: return Separable(src, dst, mode);
            }
        }

        private static int Normal(int src, int dst)
        {
            int sa = Alpha(src);
            if (sa == 255) return src;
            if (sa == 0) return dst;
            int inv = 255 - sa;
            return Pack(sa + Div255(Alpha(dst) * inv),
                Red(src) + Div255(Red(dst) * inv),
                Green(src) + Div255(Green(dst) * inv),
                Blue(src) + Div255(Blue(dst) * inv));
        }

        private static int MultiplyMode(int src, int dst)
        {
            int sa = Alpha(src), da = Alpha(dst);
            return Pack(sa + da - Div255(sa * da),
                Mul(Red(src), sa, Red(dst), da),
                Mul(Green(src), sa, Green(dst), da),
                Mul(Blue(src), sa, Blue(dst), da));
        }

        private static int ScreenMode(int src, int dst)
        {
            int sa = Alpha(src), da = Alpha(dst);
            return Pack(sa + da - Div255(sa * da),
                Red(src) + Red(dst) - Div255(Red(src) * Red(dst)),
                Green(src) + Green(dst) - Div255(Green(src) * Green(dst)),
                Blue(src) + Blue(dst) - Div255(Blue(src) * Blue(dst)));
        }

        private static int Mul(int sc, int sa, int dc, int da)
            => Div255(sc * (255 - da)) + Div255(dc * (255 - sa)) + Div255(sc * dc);

        private static int Separable(int src, int dst, BlendMode m)
        {
            int sa = Alpha(src), da = Alpha(dst);
            if (sa == 0) return dst;
            if (da == 0) return src;
            return Pack(sa + da - Div255(sa * da),
                Channel(Red(src), sa, Red(dst), da, m),
                Channel(Green(src), sa, Green(dst), da, m),
                Channel(Blue(src), sa, Blue(dst), da, m));
        }

        private static int Channel(int sp, int sa, int dp, int da, BlendMode m)
        {
            int cs = Math.Min(255, sp * 255 / sa);
            int cb = Math.Min(255, dp * 255 / da);
            int b = Mix(m, cb, cs);
            return Div255(sp * (255 - da)) + Div255(dp * (255 - sa)) + (sa * da * b + 32512) / 65025;
        }

        private static int HardLight(int cb, int cs)
            => cs <= 127 ? Div255(cb * 2 * cs) : cb + (2 * cs - 255) - Div255(cb * (2 * cs - 255));

        private static int Mix(BlendMode m, int cb, int cs)
        {
            switch (m)
            {
                case BlendMode.Darken: return Math.Min(cb, cs);
                case BlendMode.Lighten: return Math.Max(cb, cs);
                case BlendMode.ColorBurn:
                    return cb == 255 ? 255 : cs == 0 ? 0 : 255 - Math.Min(255, (255 - cb) * 255 / cs);
                case BlendMode.ColorDodge:
                    return cb == 0 ? 0 : cs == 255 ? 255 : Math.Min(255, cb * 255 / (255 - cs));
                case BlendMode.Add: return Math.Min(255, cb + cs);
                case BlendMode.Overlay: return HardLight(cs, cb);
                case BlendMode.HardLight: return HardLight(cb, cs);
                case BlendMode.SoftLight:
                {
                    double b = cb / 255.0, s = cs / 255.0, r;
                    if (s <= 0.5) r = b - (1 - 2 * s) * b * (1 - b);
                    else
                    {
                        double d = b <= 0.25 ? ((16 * b - 12) * b + 4) * b : Math.Sqrt(b);
                        r = b + (2 * s - 1) * (d - b);
                    }
                    // Java Math.round: floor(x + 0.5)
                    return Clamp255((int)Math.Floor(r * 255 + 0.5));
                }
                case BlendMode.Difference: return Math.Abs(cb - cs);
                case BlendMode.Exclusion: return Clamp255(cb + cs - Div255(2 * cb * cs));
                default: return cs;
            }
        }

        private static int Clamp255(int v) => v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
