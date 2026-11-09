// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

/**
 * First-run setup, also used as the settings screen (with {@link #EXTRA_SETTINGS}): canvas
 * size, theme and the minimalist editor layout.
 */
public class SetupActivity extends AppCompatActivity {

    static final String EXTRA_SETTINGS = "settings";

    private MaterialButton sizeButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);
        final boolean settings = getIntent().getBooleanExtra(EXTRA_SETTINGS, false);

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
        minimal.setChecked(Prefs.minimalUi(this));
        minimal.setOnCheckedChangeListener((b, on) -> Prefs.setMinimalUi(this, on));

        MaterialButton start = findViewById(R.id.setup_start);
        if (settings) {
            findViewById(R.id.setup_title).setVisibility(View.GONE);
            findViewById(R.id.setup_intro).setVisibility(View.GONE);
            findViewById(R.id.setup_note).setVisibility(View.GONE);
            start.setText(R.string.done);
            start.setOnClickListener(v -> finish());
        } else {
            start.setOnClickListener(v -> {
                Prefs.setSetupDone(this);
                startActivity(new Intent(this, HomeActivity.class));
                finish();
            });
        }
    }

    private void showSize() {
        sizeButton.setText(Prefs.width(this) + " × " + Prefs.height(this));
    }
}
