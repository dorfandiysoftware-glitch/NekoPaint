// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

public class NekoApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        applyTheme(Prefs.theme(this));
    }

    static void applyTheme(int mode) {
        AppCompatDelegate.setDefaultNightMode(mode == 1
                ? AppCompatDelegate.MODE_NIGHT_NO
                : mode == 2 ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }
}
