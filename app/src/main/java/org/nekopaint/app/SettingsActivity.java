// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.slider.Slider;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** All settings on one page: canvas size, theme and the minimalist editor layout. */
public class SettingsActivity extends AppCompatActivity {

    private MaterialButton sizeButton;
    private LinearLayout box;

    private final ActivityResultLauncher<String> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), this::writeBackup);
    private final ActivityResultLauncher<String[]> importLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::readBackup);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        sizeButton = findViewById(R.id.setup_size);
        showSize();
        sizeButton.setOnClickListener(v -> CanvasSizeDialog.show(this, Prefs.width(this),
                Prefs.height(this), (w, h) -> {
                    Prefs.setSize(this, w, h);
                    showSize();
                }));

        MaterialButtonToggleGroup theme = findViewById(R.id.setup_theme);
        int current = Prefs.theme(this);
        theme.check(current == 1 ? R.id.theme_light
                : current == 2 ? R.id.theme_dark : R.id.theme_system);
        theme.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) {
                return;
            }
            int mode = id == R.id.theme_light ? 1 : id == R.id.theme_dark ? 2 : 0;
            Prefs.setTheme(this, mode);
            NekoApp.applyTheme(mode);
        });

        CompoundButton minimal = findViewById(R.id.setup_minimal);
        minimal.setChecked(Prefs.md3Colors(this));
        minimal.setOnCheckedChangeListener((b, on) -> Prefs.setMd3Colors(this, on));

        GpuPicker.bind(this, findViewById(R.id.settings_gpu));
        CompoundButton perf = findViewById(R.id.setting_perf);
        perf.setChecked(Prefs.perfOverlay(this));
        perf.setOnCheckedChangeListener((b, on) -> Prefs.setPerfOverlay(this, on));

        box = findViewById(R.id.settings_root);
        View done = findViewById(R.id.setup_start);
        box.removeView(done);
        buildEditorOptions();
        box.addView(done);
        done.setOnClickListener(v -> finish());
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void section(int title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(28);
        box.addView(t, lp);
    }

    private void hint(int text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        box.addView(t);
    }

    private void toggle(String key, int label) {
        MaterialCheckBox c = new MaterialCheckBox(this);
        c.setText(label);
        c.setChecked(Opts.flag(this, key));
        c.setOnCheckedChangeListener((b, on) -> Opts.setFlag(this, key, on));
        box.addView(c);
    }

    /** A slider with its value shown next to the title. */
    private void slider(String key, int label, int min, int max, int step, int fmt) {
        TextView t = new TextView(this);
        t.setPadding(0, dp(8), 0, 0);
        box.addView(t);
        Slider s = new Slider(this);
        s.setValueFrom(min);
        s.setValueTo(max);
        s.setStepSize(step);
        int cur = Math.max(min, Math.min(max, Opts.num(this, key)));
        cur = min + Math.round((cur - min) / (float) step) * step;
        s.setValue(cur);
        t.setText(getString(label) + ": " + getString(fmt, cur));
        s.addOnChangeListener((sl, v, fromUser) -> {
            t.setText(getString(label) + ": " + getString(fmt, (int) v));
            if (fromUser) {
                Opts.setNum(this, key, (int) v);
            }
        });
        box.addView(s);
    }

    /** One of several choices, stored as the value next to the picked label. */
    private void choice(String key, int label, int[] names, int[] values) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setPadding(0, dp(12), 0, dp(4));
        box.addView(t);
        MaterialButtonToggleGroup g = new MaterialButtonToggleGroup(this);
        g.setSingleSelection(true);
        g.setSelectionRequired(true);
        int cur = Opts.num(this, key);
        for (int i = 0; i < names.length; i++) {
            MaterialButton b = new MaterialButton(this, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setId(View.generateViewId());
            b.setText(names[i]);
            g.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (values[i] == cur) {
                g.check(b.getId());
            }
            final int value = values[i];
            b.addOnCheckedChangeListener((btn, on) -> {
                if (on) {
                    Opts.setNum(this, key, value);
                }
            });
        }
        if (g.getCheckedButtonId() == View.NO_ID && g.getChildCount() > 0) {
            g.check(g.getChildAt(0).getId());
        }
        box.addView(g);
    }

    private void action(int label, Runnable r) {
        MaterialButton b = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(label);
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        box.addView(b, lp);
    }

    private void buildEditorOptions() {
        section(R.string.sec_gestures);
        toggle(Opts.UNDO_TAP, R.string.opt_undo_tap);
        toggle(Opts.REDO_TAP, R.string.opt_redo_tap);
        toggle(Opts.QUICK_PICK, R.string.opt_quick_pick);
        slider(Opts.QUICK_MS, R.string.opt_quick_ms, 200, 1200, 100, R.string.ms_fmt);

        section(R.string.sec_interface);
        toggle(Opts.SMOOTH_ZOOM, R.string.opt_smooth);
        toggle(Opts.UNDO_MSG, R.string.opt_undo_msg);
        toggle(Opts.ADD_CURRENT, R.string.opt_add_current);
        toggle(Opts.TOOL_LABELS, R.string.opt_tool_labels);
        choice(Opts.TOOL_COLS, R.string.opt_tool_cols,
                new int[] {R.string.cols_1, R.string.cols_2, R.string.cols_3}, new int[] {1, 2, 3});
        slider(Opts.ROW_HEIGHT, R.string.opt_row_height, 40, 96, 8, R.string.px_fmt);
        choice(Opts.BACKDROP, R.string.opt_backdrop,
                new int[] {R.string.bd_default, R.string.bd_light, R.string.bd_dark, R.string.bd_black},
                new int[] {0, 0xFFE0E0E0, 0xFF303030, 0xFF000000});

        section(R.string.sec_pressure);
        toggle(Opts.PRESSURE, R.string.opt_pressure);
        choice(Opts.PRESSURE_CURVE, R.string.opt_curve,
                new int[] {R.string.curve_weak, R.string.curve_normal, R.string.curve_strong}, new int[] {0, 1, 2});

        section(R.string.sec_touch);
        slider(Opts.OFFSET_X, R.string.opt_off_x, -20, 20, 1, R.string.px_fmt);
        slider(Opts.OFFSET_Y, R.string.opt_off_y, -20, 20, 1, R.string.px_fmt);

        section(R.string.sec_stylus);
        choice(Opts.PALM, R.string.opt_palm, new int[] {R.string.palm_off, R.string.palm_stylus}, new int[] {0, 1});
        hint(R.string.palm_hint);

        section(R.string.sec_other);
        action(R.string.opt_export, () -> exportLauncher.launch("nekopaint-settings.json"));
        action(R.string.opt_import, () -> importLauncher.launch(new String[] {"application/json", "text/plain", "*/*"}));
        action(R.string.opt_reset, () -> {
            Opts.reset(this);
            Toast.makeText(this, R.string.opt_reset_done, Toast.LENGTH_SHORT).show();
            recreate();
        });
    }

    private void writeBackup(Uri uri) {
        if (uri == null) {
            return;
        }
        try (OutputStream o = getContentResolver().openOutputStream(uri)) {
            o.write(Opts.export(this).getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, R.string.opt_saved, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private void readBackup(Uri uri) {
        if (uri == null) {
            return;
        }
        try (InputStream i = getContentResolver().openInputStream(uri)) {
            Opts.importJson(this, readAll(i));
            Toast.makeText(this, R.string.opt_restored, Toast.LENGTH_SHORT).show();
            recreate();
        } catch (org.json.JSONException e) {
            Toast.makeText(this, R.string.opt_bad_file, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void showSize() {
        sizeButton.setText(Prefs.width(this) + " × " + Prefs.height(this));
    }
}
