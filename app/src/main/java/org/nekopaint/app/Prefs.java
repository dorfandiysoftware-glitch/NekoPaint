// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.Context;
import android.content.SharedPreferences;

/** The few settings NekoPaint keeps. */
final class Prefs {

    static final int MAX_SIDE = 4096;
    static final int MAX_PIXELS = 12_000_000;

    private static final String FILE = "nekopaint";

    private Prefs() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean isSetupDone(Context c) {
        return prefs(c).getBoolean("setup_done", false);
    }

    static void setSetupDone(Context c) {
        prefs(c).edit().putBoolean("setup_done", true).apply();
    }

    static int width(Context c) {
        return prefs(c).getInt("width", 2048);
    }

    static int height(Context c) {
        return prefs(c).getInt("height", 2048);
    }

    static void setSize(Context c, int w, int h) {
        prefs(c).edit().putInt("width", w).putInt("height", h).apply();
    }

    /** 0 follow system, 1 light, 2 dark. */
    static int theme(Context c) {
        return prefs(c).getInt("theme", 0);
    }

    static void setTheme(Context c, int mode) {
        prefs(c).edit().putInt("theme", mode).apply();
    }

    /** The folder picked by the user for drawings, or null for the default folder. */
    static String treeUri(Context c) {
        return prefs(c).getString("tree_uri", null);
    }

    static void setTreeUri(Context c, String uri) {
        if (uri == null) {
            prefs(c).edit().remove("tree_uri").apply();
        } else {
            prefs(c).edit().putString("tree_uri", uri).apply();
        }
    }

    /** Whether the editor uses Material 3 colours instead of the grey ibis-like ones. */
    static boolean md3Colors(Context c) {
        return prefs(c).getBoolean("md3_colors", false);
    }

    static void setMd3Colors(Context c, boolean on) {
        prefs(c).edit().putBoolean("md3_colors", on).apply();
    }

    static int getInt(Context c, String key, int def) {
        return prefs(c).getInt(key, def);
    }

    static void putInt(Context c, String key, int value) {
        prefs(c).edit().putInt(key, value).apply();
    }

    static String getString(Context c, String key, String def) {
        return prefs(c).getString(key, def);
    }

    static void putString(Context c, String key, String value) {
        prefs(c).edit().putString(key, value).apply();
    }

    /** Whether a canvas of this size is allowed. */
    static boolean sizeOk(int w, int h) {
        return w >= 1 && h >= 1 && w <= MAX_SIDE && h <= MAX_SIDE && (long) w * h <= MAX_PIXELS;
    }

    /** Whether the editor shows the small frame-time meter. */
    static boolean perfOverlay(Context c) {
        return prefs(c).getBoolean("perf_overlay", true);
    }

    static void setPerfOverlay(Context c, boolean on) {
        prefs(c).edit().putBoolean("perf_overlay", on).apply();
    }

    /** "opengl", "vulkan" or "cpu"; null until chosen. */
    static String gpuApi(Context c) {
        return prefs(c).getString("gpu_api", null);
    }

    static void setGpuApi(Context c, String api) {
        prefs(c).edit().putString("gpu_api", api).apply();
    }
}
