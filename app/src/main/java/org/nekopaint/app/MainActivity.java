// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

import java.io.IOException;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private CanvasView canvas;
    private MaterialToolbar toolbar;
    private MaterialButton colorButton;
    private ActivityResultLauncher<String> exportLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        canvas = findViewById(R.id.canvas);
        toolbar = findViewById(R.id.toolbar);
        colorButton = findViewById(R.id.color_button);
        toolbar.inflateMenu(R.menu.main);
        toolbar.setOnMenuItemClickListener(this::onMenuItem);
        canvas.setListener(this::updateHistoryButtons);
        updateHistoryButtons();

        MaterialButtonToggleGroup tools = findViewById(R.id.tools);
        tools.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) {
                canvas.setEraser(id == R.id.tool_eraser);
            }
        });

        Slider size = findViewById(R.id.size);
        canvas.setRadius(size.getValue());
        size.addOnChangeListener((slider, value, fromUser) -> canvas.setRadius(value));

        showColor();
        colorButton.setOnClickListener(v -> pickColor());

        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("image/png"), this::exportTo);
    }

    private boolean onMenuItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_undo) {
            canvas.undo();
            return true;
        } else if (id == R.id.action_redo) {
            canvas.redo();
            return true;
        } else if (id == R.id.action_export) {
            exportLauncher.launch("drawing.png");
            return true;
        }
        return false;
    }

    private void updateHistoryButtons() {
        Menu menu = toolbar.getMenu();
        MenuItem undo = menu.findItem(R.id.action_undo);
        MenuItem redo = menu.findItem(R.id.action_redo);
        if (undo != null) {
            undo.setEnabled(canvas.canUndo());
        }
        if (redo != null) {
            redo.setEnabled(canvas.canRedo());
        }
    }

    private void showColor() {
        colorButton.setBackgroundTintList(ColorStateList.valueOf(0xFF000000 | canvas.getColor()));
    }

    private void pickColor() {
        int current = canvas.getColor();
        int[] rgb = {Color.red(current), Color.green(current), Color.blue(current)};
        String[] names = {"R", "G", "B"};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final View preview = new View(this);
        preview.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, pad * 2));
        preview.setBackgroundColor(0xFF000000 | current);
        box.addView(preview);

        for (int i = 0; i < 3; i++) {
            final int channel = i;
            Slider slider = new Slider(this);
            slider.setValueFrom(0);
            slider.setValueTo(255);
            slider.setStepSize(1);
            slider.setValue(rgb[i]);
            slider.setContentDescription(names[i]);
            slider.addOnChangeListener((s, value, fromUser) -> {
                rgb[channel] = (int) value;
                preview.setBackgroundColor(Color.rgb(rgb[0], rgb[1], rgb[2]));
            });
            box.addView(slider);
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.color)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    canvas.setColor(Color.rgb(rgb[0], rgb[1], rgb[2]));
                    showColor();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void exportTo(Uri uri) {
        if (uri == null) {
            return;
        }
        Bitmap bitmap = canvas.exportBitmap();
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IOException("compress failed");
            }
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show();
        } finally {
            bitmap.recycle();
        }
    }
}
