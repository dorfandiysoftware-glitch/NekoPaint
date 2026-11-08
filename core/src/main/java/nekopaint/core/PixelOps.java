// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

/**
 * Integer helpers for <em>premultiplied</em> ARGB pixels packed as {@code 0xAARRGGBB}, every
 * channel in 0..255. In a valid premultiplied pixel each colour channel is at most the alpha
 * channel.
 */
final class PixelOps {

    private PixelOps() {
    }

    /** Divides by 255, rounding to nearest. Valid for 0..65025. */
    static int div255(int x) {
        return (x + 127) / 255;
    }

    static int alpha(int p) {
        return p >>> 24;
    }

    static int red(int p) {
        return (p >> 16) & 0xFF;
    }

    static int green(int p) {
        return (p >> 8) & 0xFF;
    }

    static int blue(int p) {
        return p & 0xFF;
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Packs channels into a pixel. Alpha is clamped to 0..255 and every colour channel to
     * 0..alpha, so the result is always a valid premultiplied pixel even after rounding.
     */
    static int pack(int a, int r, int g, int b) {
        int alpha = clamp(a, 0, 255);
        return (alpha << 24) | (clamp(r, 0, alpha) << 16) | (clamp(g, 0, alpha) << 8)
                | clamp(b, 0, alpha);
    }

    /** Converts straight (non-premultiplied) ARGB to premultiplied ARGB. */
    static int premultiply(int argb) {
        int a = argb >>> 24;
        if (a == 255) {
            return argb;
        }
        if (a == 0) {
            return 0;
        }
        return (a << 24) | (div255(red(argb) * a) << 16) | (div255(green(argb) * a) << 8)
                | div255(blue(argb) * a);
    }

    /** Converts premultiplied ARGB back to straight ARGB. Lossy for low alpha values. */
    static int unpremultiply(int p) {
        int a = alpha(p);
        if (a == 255) {
            return p;
        }
        if (a == 0) {
            return 0;
        }
        return (a << 24) | (unpremultiplyChannel(red(p), a) << 16)
                | (unpremultiplyChannel(green(p), a) << 8) | unpremultiplyChannel(blue(p), a);
    }

    private static int unpremultiplyChannel(int c, int a) {
        return Math.min(255, (c * 255 + a / 2) / a);
    }

    /** Multiplies every channel of a premultiplied pixel by {@code factor / 255}. */
    static int scale(int p, int factor) {
        if (factor >= 255) {
            return p;
        }
        if (factor <= 0) {
            return 0;
        }
        return pack(div255(alpha(p) * factor), div255(red(p) * factor),
                div255(green(p) * factor), div255(blue(p) * factor));
    }
}
