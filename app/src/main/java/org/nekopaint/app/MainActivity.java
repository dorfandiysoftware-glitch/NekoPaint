// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
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
import java.io.InputStream;
import java.io.OutputStream;

import nekopaint.core.BlendMode;
import nekopaint.core.Document;
import nekopaint.core.Layer;
import nekopaint.core.OpenRaster;

public class MainActivity extends AppCompatActivity {

    private CanvasView canvas;
    private MaterialToolbar toolbar;
    private MaterialButton colorButton;
    private View layersPanel;
    private LinearLayout layerList;
    private Slider layerOpacity;
    private MaterialButtonToggleGroup layerBlend;
    private boolean updatingPanel;

    private ActivityResultLauncher<String> savePng;
    private ActivityResultLauncher<String> saveOra;
    private ActivityResultLauncher<String[]> openFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Prefs.isSetupDone(this)) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);

        canvas = findViewById(R.id.canvas);
        toolbar = findViewById(R.id.toolbar);
        colorButton = findViewById(R.id.color_button);
        layersPanel = findViewById(R.id.layers_panel);
        layerList = findViewById(R.id.layer_list);
        layerOpacity = findViewById(R.id.layer_opacity);
        layerBlend = findViewById(R.id.layer_blend);

        savePng = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("image/png"), this::exportPng);
        saveOra = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("image/openraster"), this::saveOra);
        openFile = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                this::openUri);

        toolbar.inflateMenu(R.menu.main);
        toolbar.setNavigationOnClickListener(this::showMainMenu);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_undo) {
                canvas.undo();
            } else if (item.getItemId() == R.id.action_redo) {
                canvas.redo();
            }
            return true;
        });
        canvas.setListener(this::updateHistoryButtons);
        updateHistoryButtons();

        ((MaterialButtonToggleGroup) findViewById(R.id.tools)).check(R.id.tool_brush);
        ((MaterialButtonToggleGroup) findViewById(R.id.tools)).addOnButtonCheckedListener(
                (group, id, checked) -> {
                    if (checked) {
                        canvas.setEraser(id == R.id.tool_eraser);
                    }
                });

        Slider size = findViewById(R.id.size);
        canvas.setRadius(size.getValue());
        size.addOnChangeListener((s, value, fromUser) -> canvas.setRadius(value));
        Slider flow = findViewById(R.id.flow);
        canvas.setFlow(flow.getValue() / 100f);
        flow.addOnChangeListener((s, value, fromUser) -> canvas.setFlow(value / 100f));

        showColor();
        colorButton.setOnClickListener(v -> pickColor());

        findViewById(R.id.layers_button).setOnClickListener(v -> {
            boolean show = layersPanel.getVisibility() != View.VISIBLE;
            layersPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                rebuildLayerPanel();
            }
        });
        setUpLayerControls();

        Uri data = getIntent().getData();
        if (data != null) {
            openUri(data);
        } else {
            canvas.setDocument(CanvasView.newBlankDocument(Prefs.width(this),
                    Prefs.height(this)));
        }
    }

    // ------------------------------------------------------------------ menus

    private void showMainMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor, Gravity.START);
        menu.getMenu().add(0, 1, 0, R.string.new_drawing);
        menu.getMenu().add(0, 2, 1, R.string.open_drawing);
        menu.getMenu().add(0, 3, 2, R.string.save_ora);
        menu.getMenu().add(0, 4, 3, R.string.export_png);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    CanvasSizeDialog.show(this, Prefs.width(this), Prefs.height(this),
                            (w, h) -> {
                                Prefs.setSize(this, w, h);
                                canvas.setDocument(CanvasView.newBlankDocument(w, h));
                                rebuildLayerPanel();
                            });
                    return true;
                case 2:
                    openFile.launch(new String[] {"*/*"});
                    return true;
                case 3:
                    saveOra.launch("drawing.ora");
                    return true;
                case 4:
                    savePng.launch("drawing.png");
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void updateHistoryButtons() {
        setMenuEnabled(R.id.action_undo, canvas.canUndo());
        setMenuEnabled(R.id.action_redo, canvas.canRedo());
    }

    private void setMenuEnabled(int id, boolean enabled) {
        android.view.MenuItem item = toolbar.getMenu().findItem(id);
        if (item == null) {
            return;
        }
        item.setEnabled(enabled);
        Drawable icon = item.getIcon();
        if (icon != null) {
            icon.mutate().setAlpha(enabled ? 255 : 90);
        }
    }

    // ------------------------------------------------------------------ colour

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

    // ------------------------------------------------------------------ layers

    private void setUpLayerControls() {
        findViewById(R.id.layer_add).setOnClickListener(v -> {
            Document doc = canvas.getDocument();
            if (doc.getLayerCount() >= 30) {
                return;
            }
            int at = canvas.getActiveLayer() + 1;
            doc.addLayer("Layer " + (doc.getLayerCount() + 1));
            doc.moveLayer(doc.getLayerCount() - 1, at);
            canvas.setActiveLayer(at);
            canvas.layerStructureChanged();
            rebuildLayerPanel();
        });
        findViewById(R.id.layer_delete).setOnClickListener(v -> {
            Document doc = canvas.getDocument();
            if (doc.getLayerCount() <= 1) {
                return;
            }
            int index = canvas.getActiveLayer();
            doc.removeLayer(index);
            canvas.setActiveLayer(Math.max(0, index - 1));
            canvas.layerStructureChanged();
            rebuildLayerPanel();
        });
        findViewById(R.id.layer_up).setOnClickListener(v -> moveActive(1));
        findViewById(R.id.layer_down).setOnClickListener(v -> moveActive(-1));

        layerOpacity.addOnChangeListener((s, value, fromUser) -> {
            if (updatingPanel || !fromUser) {
                return;
            }
            canvas.getDocument().getLayer(canvas.getActiveLayer())
                    .setOpacity(Math.round(value * 255f / 100f));
            canvas.layersChanged();
        });
        layerBlend.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked || updatingPanel) {
                return;
            }
            BlendMode mode = id == R.id.blend_multiply ? BlendMode.MULTIPLY
                    : id == R.id.blend_screen ? BlendMode.SCREEN : BlendMode.NORMAL;
            canvas.getDocument().getLayer(canvas.getActiveLayer()).setBlendMode(mode);
            canvas.layersChanged();
        });
    }

    private void moveActive(int delta) {
        Document doc = canvas.getDocument();
        int from = canvas.getActiveLayer();
        int to = from + delta;
        if (to < 0 || to >= doc.getLayerCount()) {
            return;
        }
        doc.moveLayer(from, to);
        canvas.setActiveLayer(to);
        canvas.layerStructureChanged();
        rebuildLayerPanel();
    }

    private void rebuildLayerPanel() {
        Document doc = canvas.getDocument();
        updatingPanel = true;
        layerList.removeAllViews();
        // Show the top layer first, like most painting apps.
        for (int i = doc.getLayerCount() - 1; i >= 0; i--) {
            final int index = i;
            final Layer layer = doc.getLayer(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight((int) (48 * getResources().getDisplayMetrics().density));
            boolean active = index == canvas.getActiveLayer();
            if (active) {
                row.setBackgroundColor(themeColor(
                        com.google.android.material.R.attr.colorSecondaryContainer));
            }

            CheckBox eye = new CheckBox(this);
            eye.setButtonDrawable(layer.isVisible() ? R.drawable.ic_eye : R.drawable.ic_eye_off);
            eye.setChecked(layer.isVisible());
            eye.setContentDescription(getString(R.string.visibility));
            eye.setOnClickListener(v -> {
                layer.setVisible(!layer.isVisible());
                canvas.layersChanged();
                rebuildLayerPanel();
            });
            row.addView(eye);

            TextView name = new TextView(this);
            name.setText(layer.getName());
            name.setSingleLine(true);
            name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(name, lp);
            row.setOnClickListener(v -> {
                canvas.setActiveLayer(index);
                rebuildLayerPanel();
            });
            layerList.addView(row);
        }

        Layer active = doc.getLayer(canvas.getActiveLayer());
        layerOpacity.setValue(Math.round(active.getOpacity() * 100f / 255f));
        layerBlend.check(active.getBlendMode() == BlendMode.MULTIPLY ? R.id.blend_multiply
                : active.getBlendMode() == BlendMode.SCREEN ? R.id.blend_screen
                : R.id.blend_normal);
        updatingPanel = false;
    }

    private int themeColor(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    // ------------------------------------------------------------------ files

    private void openUri(Uri uri) {
        if (uri == null) {
            return;
        }
        Toast.makeText(this, "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Document doc = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    doc = ImageIo.open(in);
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                doc = null;
            }
            final Document result = doc;
            runOnUiThread(() -> {
                if (result == null) {
                    Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show();
                } else {
                    canvas.setDocument(result);
                    rebuildLayerPanel();
                }
            });
        }, "open").start();
    }

    private void saveOra(Uri uri) {
        if (uri == null) {
            return;
        }
        final Document doc = canvas.getDocument();
        new Thread(() -> {
            boolean ok = false;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out != null) {
                    OpenRaster.write(doc, out);
                    ok = true;
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                ok = false;
            }
            final boolean saved = ok;
            runOnUiThread(() -> Toast.makeText(this,
                    saved ? R.string.saved : R.string.save_failed, Toast.LENGTH_SHORT).show());
        }, "save").start();
    }

    private void exportPng(Uri uri) {
        if (uri == null) {
            return;
        }
        Bitmap bitmap = canvas.exportBitmap();
        boolean ok = false;
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            ok = out != null && bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            ok = false;
        } finally {
            bitmap.recycle();
        }
        Toast.makeText(this, ok ? R.string.saved : R.string.save_failed,
                Toast.LENGTH_SHORT).show();
    }
}
