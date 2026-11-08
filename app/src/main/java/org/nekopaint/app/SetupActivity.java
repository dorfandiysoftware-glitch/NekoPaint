// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

/** First-run screen: canvas size, theme, and an optional existing drawing to open. */
public class SetupActivity extends AppCompatActivity {

    private MaterialButton sizeButton;
    private ActivityResultLauncher<String[]> openLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

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

        openLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        finishSetup(uri);
                    }
                });
        findViewById(R.id.setup_open).setOnClickListener(
                v -> openLauncher.launch(new String[] {"*/*"}));
        findViewById(R.id.setup_start).setOnClickListener(v -> finishSetup(null));
    }

    private void showSize() {
        sizeButton.setText(Prefs.width(this) + " × " + Prefs.height(this));
    }

    private void finishSetup(Uri open) {
        Prefs.setSetupDone(this);
        Intent intent = new Intent(this, MainActivity.class);
        if (open != null) {
            intent.setData(open);
        }
        startActivity(intent);
        finish();
    }
}
