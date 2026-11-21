// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

/** Resizes a picture with a chosen filter, for sharp or smooth scaling. */
public final class Resampler {

    public static final int BOX = 0;
    public static final int TRIANGLE = 1;
    public static final int CATMULL_ROM = 2;
    public static final int LANCZOS2 = 3;
    public static final int LANCZOS3 = 4;

    private Resampler() {
    }

    private static float radius(int kernel) {
        switch (kernel) {
            case BOX: return 0.5f;
            case TRIANGLE: return 1f;
            case CATMULL_ROM: return 2f;
            case LANCZOS2: return 2f;
            default: return 3f;
        }
    }

    private static float sinc(float x) {
        if (x == 0f) {
            return 1f;
        }
        double p = Math.PI * x;
        return (float) (Math.sin(p) / p);
    }

    private static float weight(int kernel, float x) {
        float a = Math.abs(x);
        switch (kernel) {
            case BOX:
                return a <= 0.5f ? 1f : 0f;
            case TRIANGLE:
                return a < 1f ? 1f - a : 0f;
            case CATMULL_ROM:
                if (a < 1f) {
                    return 1.5f * a * a * a - 2.5f * a * a + 1f;
                }
                return a < 2f ? -0.5f * a * a * a + 2.5f * a * a - 4f * a + 2f : 0f;
            case LANCZOS2:
                return a < 2f ? sinc(a) * sinc(a / 2f) : 0f;
            default:
                return a < 3f ? sinc(a) * sinc(a / 3f) : 0f;
        }
    }

    private static void taps(int in, int out, int kernel, int[][] idx, float[][] wt) {
        float scale = out / (float) in;
        float fscale = Math.max(1f, 1f / scale);
        float r = radius(kernel) * fscale;
        for (int o = 0; o < out; o++) {
            float center = (o + 0.5f) / scale;
            int lo = (int) Math.floor(center - r);
            int hi = (int) Math.ceil(center + r);
            int n = hi - lo;
            int[] ii = new int[n];
            float[] ww = new float[n];
            float sum = 0f;
            for (int k = 0; k < n; k++) {
                int i = lo + k;
                float w = weight(kernel, (i + 0.5f - center) / fscale);
                ii[k] = Math.max(0, Math.min(in - 1, i));
                ww[k] = w;
                sum += w;
            }
            if (sum != 0f) {
                for (int k = 0; k < n; k++) {
                    ww[k] /= sum;
                }
            }
            idx[o] = ii;
            wt[o] = ww;
        }
    }

    /**
     * @param straight non-premultiplied ARGB pixels, row by row
     * @return the resized picture, non-premultiplied ARGB
     */
    public static int[] resize(int[] straight, int w, int h, int nw, int nh, int kernel) {
        int[][] xi = new int[nw][];
        float[][] xw = new float[nw][];
        taps(w, nw, kernel, xi, xw);
        int[][] yi = new int[nh][];
        float[][] yw = new float[nh][];
        taps(h, nh, kernel, yi, yw);

        // Horizontal pass: premultiplied channels, one float per channel.
        float[] mid = new float[nw * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < nw; x++) {
                float a = 0;
                float r = 0;
                float g = 0;
                float b = 0;
                int[] ii = xi[x];
                float[] ww = xw[x];
                for (int k = 0; k < ii.length; k++) {
                    int p = straight[y * w + ii[k]];
                    float pa = (p >>> 24) * ww[k];
                    a += pa;
                    r += ((p >> 16) & 0xFF) / 255f * pa;
                    g += ((p >> 8) & 0xFF) / 255f * pa;
                    b += (p & 0xFF) / 255f * pa;
                }
                int o = (y * nw + x) * 4;
                mid[o] = a;
                mid[o + 1] = r;
                mid[o + 2] = g;
                mid[o + 3] = b;
            }
        }
        int[] out = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            int[] ii = yi[y];
            float[] ww = yw[y];
            for (int x = 0; x < nw; x++) {
                float a = 0;
                float r = 0;
                float g = 0;
                float b = 0;
                for (int k = 0; k < ii.length; k++) {
                    int o = (ii[k] * nw + x) * 4;
                    a += mid[o] * ww[k];
                    r += mid[o + 1] * ww[k];
                    g += mid[o + 2] * ww[k];
                    b += mid[o + 3] * ww[k];
                }
                a = Math.max(0f, Math.min(255f, a));
                if (a < 0.5f) {
                    out[y * nw + x] = 0;
                    continue;
                }
                float inv = 255f / a;
                int ia = Math.round(a);
                int ir = Math.max(0, Math.min(255, Math.round(r * inv)));
                int ig = Math.max(0, Math.min(255, Math.round(g * inv)));
                int ib = Math.max(0, Math.min(255, Math.round(b * inv)));
                out[y * nw + x] = (ia << 24) | (ir << 16) | (ig << 8) | ib;
            }
        }
        return out;
    }
}
