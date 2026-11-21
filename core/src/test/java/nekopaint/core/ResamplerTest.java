// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ResamplerTest {

    @Test
    public void flatColourStaysFlatForEveryFilter() {
        int[] px = new int[8 * 8];
        java.util.Arrays.fill(px, 0xFF336699);
        for (int k = Resampler.BOX; k <= Resampler.LANCZOS3; k++) {
            int[] big = Resampler.resize(px, 8, 8, 20, 13, k);
            int[] small = Resampler.resize(px, 8, 8, 3, 5, k);
            assertEquals(20 * 13, big.length);
            for (int p : big) {
                assertEquals(0xFF336699, p);
            }
            for (int p : small) {
                assertEquals(0xFF336699, p);
            }
        }
    }

    @Test
    public void transparentStaysTransparentAndEdgesBlend() {
        int[] px = new int[4 * 1];
        px[0] = 0xFFFF0000;
        px[1] = 0xFFFF0000;
        int[] out = Resampler.resize(px, 4, 1, 8, 1, Resampler.TRIANGLE);
        assertEquals(0xFFFF0000, out[0]);
        assertEquals(0, out[7]);
        int mid = out[3] >>> 24;
        assertTrue(mid > 0 && mid < 255);
        assertEquals(0xFF0000, out[3] & 0xFFFFFF); // blended edge stays pure red
    }
}
