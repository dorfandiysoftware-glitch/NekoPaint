// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Asks for a canvas size: a few presets or custom width and height. */
final class CanvasSizeDialog {

    interface Callback {
        void onSize(int width, int height);
    }

    private static final int[][] PRESETS = {
        {1080, 1080}, {1080, 1920}, {2048, 2048}, {2480, 3508}, {4096, 2304}
    };

    private CanvasSizeDialog() {
    }

    static void show(Context context, int currentW, int currentH, Callback callback) {
        float d = context.getResources().getDisplayMetrics().density;
        int pad = (int) (20 * d);

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText wField = numberField(context, currentW);
        final EditText hField = numberField(context, currentH);

        ChipGroup chips = new ChipGroup(context);
        for (final int[] p : PRESETS) {
            Chip chip = new Chip(context);
            chip.setText(p[0] + " × " + p[1]);
            chip.setCheckable(false);
            chip.setOnClickListener(v -> {
                wField.setText(String.valueOf(p[0]));
                hField.setText(String.valueOf(p[1]));
            });
            chips.addView(chip);
        }
        box.addView(chips);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(wField, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView times = new TextView(context);
        times.setText("×");
        times.setPadding(pad / 2, 0, pad / 2, 0);
        row.addView(times);
        row.addView(hField, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);

        TextView hint = new TextView(context);
        hint.setText("Up to 4096 px per side and 12 megapixels in total.");
        hint.setPadding(0, pad / 2, 0, 0);
        box.addView(hint);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.canvas_size)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (dlg, which) -> {
                    int w = parse(wField);
                    int h = parse(hField);
                    if (w < 1 || h < 1 || w > Prefs.MAX_SIDE || h > Prefs.MAX_SIDE
                            || (long) w * h > Prefs.MAX_PIXELS) {
                        Toast.makeText(context, R.string.bad_size, Toast.LENGTH_LONG).show();
                        return;
                    }
                    callback.onSize(w, h);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static EditText numberField(Context context, int value) {
        EditText e = new EditText(context);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        e.setSelectAllOnFocus(true);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return e;
    }

    private static int parse(EditText e) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}
