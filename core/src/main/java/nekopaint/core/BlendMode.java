// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

import static nekopaint.core.PixelOps.alpha;
import static nekopaint.core.PixelOps.blue;
import static nekopaint.core.PixelOps.div255;
import static nekopaint.core.PixelOps.green;
import static nekopaint.core.PixelOps.pack;
import static nekopaint.core.PixelOps.red;

/**
 * How a layer's pixels combine with what is below them. All modes work on premultiplied ARGB
 * pixels and follow the W3C compositing model for separable blend modes.
 */
public enum BlendMode {

    /** Ordinary "source over". */
    NORMAL {
        @Override
        int blend(int src, int dst) {
            int sa = alpha(src);
            if (sa == 255) {
                return src;
            }
            if (sa == 0) {
                return dst;
            }
            int inv = 255 - sa;
            return pack(sa + div255(alpha(dst) * inv),
                    red(src) + div255(red(dst) * inv),
                    green(src) + div255(green(dst) * inv),
                    blue(src) + div255(blue(dst) * inv));
        }
    },

    /** Darkens: the colours are multiplied. White leaves the base unchanged. */
    MULTIPLY {
        @Override
        int blend(int src, int dst) {
            int sa = alpha(src);
            int da = alpha(dst);
            return pack(sa + da - div255(sa * da),
                    multiply(red(src), sa, red(dst), da),
                    multiply(green(src), sa, green(dst), da),
                    multiply(blue(src), sa, blue(dst), da));
        }
    },

    /** Lightens: the inverse colours are multiplied. Black leaves the base unchanged. */
    SCREEN {
        @Override
        int blend(int src, int dst) {
            int sa = alpha(src);
            int da = alpha(dst);
            return pack(sa + da - div255(sa * da),
                    red(src) + red(dst) - div255(red(src) * red(dst)),
                    green(src) + green(dst) - div255(green(src) * green(dst)),
                    blue(src) + blue(dst) - div255(blue(src) * blue(dst)));
        }
    },

    /** Separable mode, see the W3C compositing specification. */
    DARKEN {
        @Override
        int blend(int src, int dst) {
            return separable(src, dst, this);
        }
    },

    /** Separable mode, see the W3C compositing specification. */
    LIGHTEN {
        @Override
        int blend(int src, int dst) {
            return separable(src, dst, this);
        }
    },

    /** Separable mode, see the W3C compositing specification. */
    COLOR_BURN {
        @Override
        int blend(int src, int dst) {
            return separable(src, dst, this);
        }
    },

    /** Separable mode, see the W3C compositing specification. */
    COLOR_DODGE {
        @Override
        int blend(int src, int dst) {
            return separable(src, dst, this);
        }
    },

    /** Separable mode, see the W3C compositing specification. */
    ADD {
        @Override
        int blend(int src, int dst) {
            return separable(src, dst, this);
        }
    },

    /** Separable mode, see the W3C compositing specification. */
    OVERLAY {
        @Override
        int blend(int src, int dst) {
            return separable(src, dst, this);
        }
    },

    /** Separable mode, see the W3C compositing specification. */
    SOFT_LIGHT {
        @Override
        int blend(int src, int dst) {
            return separable(src, dst, this);
        }
    },

    /** Separable mode, see the W3C compositing specification. */
    HARD_LIGHT {
        @Override
        int blend(int src, int dst) {
            return separable(src, dst, this);
        }
    },

    /** Separable mode, see the W3C compositing specification. */
    DIFFERENCE {
        @Override
        int blend(int src, int dst) {
            return separable(src, dst, this);
        }
    },

    /** Separable mode, see the W3C compositing specification. */
    EXCLUSION {
        @Override
        int blend(int src, int dst) {
            return separable(src, dst, this);
        }
    };

    /**
     * Combines a premultiplied source pixel with a premultiplied destination pixel.
     *
     * @return the premultiplied result
     */
    abstract int blend(int src, int dst);

    private static int multiply(int sc, int sa, int dc, int da) {
        return div255(sc * (255 - da)) + div255(dc * (255 - sa)) + div255(sc * dc);
    }

    private static int separable(int src, int dst, BlendMode m) {
        int sa = alpha(src);
        int da = alpha(dst);
        if (sa == 0) {
            return dst;
        }
        if (da == 0) {
            return src;
        }
        return pack(sa + da - div255(sa * da),
                channel(red(src), sa, red(dst), da, m),
                channel(green(src), sa, green(dst), da, m),
                channel(blue(src), sa, blue(dst), da, m));
    }

    private static int channel(int sp, int sa, int dp, int da, BlendMode m) {
        int cs = Math.min(255, sp * 255 / sa);
        int cb = Math.min(255, dp * 255 / da);
        int b = mix(m, cb, cs);
        return div255(sp * (255 - da)) + div255(dp * (255 - sa))
                + (sa * da * b + 32512) / 65025;
    }

    private static int hardLight(int cb, int cs) {
        return cs <= 127 ? div255(cb * 2 * cs) : cb + (2 * cs - 255) - div255(cb * (2 * cs - 255));
    }

    /** The blend function B(cb, cs) on straight 0..255 values. */
    private static int mix(BlendMode m, int cb, int cs) {
        switch (m) {
            case DARKEN:
                return Math.min(cb, cs);
            case LIGHTEN:
                return Math.max(cb, cs);
            case COLOR_BURN:
                return cb == 255 ? 255 : cs == 0 ? 0 : 255 - Math.min(255, (255 - cb) * 255 / cs);
            case COLOR_DODGE:
                return cb == 0 ? 0 : cs == 255 ? 255 : Math.min(255, cb * 255 / (255 - cs));
            case ADD:
                return Math.min(255, cb + cs);
            case OVERLAY:
                return hardLight(cs, cb);
            case HARD_LIGHT:
                return hardLight(cb, cs);
            case SOFT_LIGHT: {
                double b = cb / 255.0;
                double s = cs / 255.0;
                double r;
                if (s <= 0.5) {
                    r = b - (1 - 2 * s) * b * (1 - b);
                } else {
                    double d = b <= 0.25 ? ((16 * b - 12) * b + 4) * b : Math.sqrt(b);
                    r = b + (2 * s - 1) * (d - b);
                }
                return clamp255((int) Math.round(r * 255));
            }
            case DIFFERENCE:
                return Math.abs(cb - cs);
            case EXCLUSION:
                return clamp255(cb + cs - div255(2 * cb * cs));
            default:
                return cs;
        }
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
