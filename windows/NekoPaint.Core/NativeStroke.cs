// SPDX-License-Identifier: GPL-3.0-or-later
using System;
using System.Runtime.InteropServices;

namespace NekoPaint.Core
{
    /// <summary>
    /// P/Invoke bridge to the portable C drawing core (native/, built as nekocore.dll).
    /// If the library is missing, or NEKOPAINT_MANAGED=1 is set, the managed code paints instead.
    /// </summary>
    internal static class NativeStroke
    {
        [StructLayout(LayoutKind.Sequential)]
        internal struct NpBrush
        {
            public uint Rgb;
            public float Radius, Hardness, Flow, Spacing;
            public int Eraser;
        }

        [StructLayout(LayoutKind.Sequential)]
        internal struct NpStroke
        {
            public int HasLast;
            public float LastX, LastY, LastP, Carry;
            public int PendMinX, PendMinY, PendMaxX, PendMaxY;
            public int TotalMinX, TotalMinY, TotalMaxX, TotalMaxY;
        }

        [StructLayout(LayoutKind.Sequential)]
        internal struct NpLayer
        {
            public IntPtr Px;
            public int Opacity;
        }

        private const string Lib = "nekocore";

        [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
        private static extern void np_stroke_init(ref NpStroke s);

        [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
        private static extern void np_stroke_add_point(ref NpStroke s, int[] px, int width, int height,
            ref NpBrush b, float x, float y, float pressure);

        [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
        private static extern void np_stroke_reset_pending(ref NpStroke s);

        [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
        private static extern void np_flatten_tiles([In] NpLayer[] layers, int nlayers, int width, int height,
            uint background, [In] int[] tiles, int ntiles, int tile, [Out] int[] output, int rgbaBytes);

        public static readonly bool Available = Probe();

        private static bool Probe()
        {
            if (Environment.GetEnvironmentVariable("NEKOPAINT_MANAGED") == "1") return false;
            try
            {
                var s = new NpStroke();
                np_stroke_init(ref s);
                return s.PendMaxX == -1;
            }
            catch (DllNotFoundException) { return false; }
            catch (EntryPointNotFoundException) { return false; }
            catch (BadImageFormatException) { return false; }
        }

        public static void Init(ref NpStroke s) => np_stroke_init(ref s);
        public static void ResetPending(ref NpStroke s) => np_stroke_reset_pending(ref s);

        public static void AddPoint(ref NpStroke s, int[] px, int width, int height, Brush brush, float x, float y, float pressure)
        {
            var b = new NpBrush
            {
                Rgb = (uint)brush.Rgb, Radius = brush.Radius, Hardness = brush.Hardness,
                Flow = brush.Flow, Spacing = brush.Spacing, Eraser = brush.IsEraser ? 1 : 0
            };
            np_stroke_add_point(ref s, px, width, height, ref b, x, y, pressure);
        }

        /// <summary>Composites NORMAL layers into tiles. Returns false if the call could not be made.</summary>
        public static bool FlattenTiles(int[][] layers, int[] opacity, int count, int width, int height,
            int background, int[] tiles, int ntiles, int tile, int[] output, bool rgbaBytes)
        {
            var handles = new GCHandle[count];
            var native = new NpLayer[count];
            try
            {
                for (int i = 0; i < count; i++)
                {
                    handles[i] = GCHandle.Alloc(layers[i], GCHandleType.Pinned);
                    native[i].Px = handles[i].AddrOfPinnedObject();
                    native[i].Opacity = opacity[i];
                }
                np_flatten_tiles(native, count, width, height, (uint)background, tiles, ntiles, tile, output, rgbaBytes ? 1 : 0);
                return true;
            }
            catch (Exception e) when (e is DllNotFoundException || e is EntryPointNotFoundException)
            {
                return false;
            }
            finally
            {
                for (int i = 0; i < count; i++) if (handles[i].IsAllocated) handles[i].Free();
            }
        }
    }
}
