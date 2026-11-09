// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.os.Bundle;
import android.widget.CompoundButton;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

/** All settings on one page: canvas size, theme and the minimalist editor layout. */
public class SettingsActivity extends AppCompatActivity {

    private MaterialButton sizeButton;

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

        findViewById(R.id.setup_start).setOnClickListener(v -> finish());
    }

    private void showSize() {
        sizeButton.setText(Prefs.width(this) + " × " + Prefs.height(this));
    }
}
