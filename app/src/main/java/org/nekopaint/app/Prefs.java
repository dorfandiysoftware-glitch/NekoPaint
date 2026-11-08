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
}
