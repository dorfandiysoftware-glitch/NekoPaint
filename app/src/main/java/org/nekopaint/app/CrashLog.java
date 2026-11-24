// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Writes the reason of a crash to a file, and shows it the next time the app starts, so a crash
 * can be reported with its real stack trace.
 */
final class CrashLog {

    private static final String FILE = "last_crash.txt";

    private CrashLog() {
    }

    static void install(final Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter text = new StringWriter();
                PrintWriter out = new PrintWriter(text);
                out.println("NekoPaint crash, thread " + thread.getName());
                out.println(Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE);
                error.printStackTrace(out);
                out.flush();
                try (FileOutputStream f = new FileOutputStream(new File(app.getFilesDir(), FILE))) {
                    f.write(text.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException | RuntimeException ignored) {
                // nothing more can be done while crashing
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    /** Shows the crash of the previous run, once. */
    static void showIfAny(final Activity activity) {
        final File f = new File(activity.getFilesDir(), FILE);
        if (!f.isFile()) {
            return;
        }
        String text;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        f.delete();
        final String shown = text.length() > 6000 ? text.substring(0, 6000) : text;
        android.widget.TextView view = new android.widget.TextView(activity);
        view.setText(shown);
        view.setTextSize(11f);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.addView(view);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.crash_title)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.crash_copy, (d, w) -> {
                    ClipboardManager cm = (ClipboardManager)
                            activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("crash", shown));
                })
                .show();
    }
}
