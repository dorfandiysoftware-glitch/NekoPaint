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
}
