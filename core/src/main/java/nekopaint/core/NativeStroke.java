// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

/**
 * Bridge to the portable C drawing core ({@code native/}). When the library cannot be loaded
 * (plain JVM tests, unsupported device) {@link #AVAILABLE} is false and the Java code paints.
 */
final class NativeStroke {

    static final boolean AVAILABLE = load();

    private NativeStroke() {
    }

    private static boolean load() {
        try {
            String path = System.getProperty("nekopaint.native");
            if (path != null && path.length() > 0) {
                System.load(path);
            } else {
                System.loadLibrary("nekocore");
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Paints up to the given point. {@code fs} = lastX, lastY, lastP, carry;
     * {@code is} = hasLast, pending minX, minY, maxX, maxY, total minX, minY, maxX, maxY.
     */
    static native void addPoint(int[] pixels, int width, int height, int rgb, float radius,
            float hardness, float flow, float spacing, boolean eraser, float[] fs, int[] is,
            float x, float y, float pressure);
}
