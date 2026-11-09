// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import nekopaint.core.BlendMode;
import nekopaint.core.Document;
import nekopaint.core.Editor;
import nekopaint.core.Layer;
import nekopaint.core.OpenRaster;
import nekopaint.core.ProjectLog;

/** The drawing screen. It edits one drawing from the gallery and saves it when you leave. */
public class MainActivity extends AppCompatActivity {

    private CanvasView canvas;
    private View undoButton;
    private View redoButton;
    private MaterialButton layersButton;
    private BrushPreview preview;
    private TextView sizeValue;
    private TextView flowValue;
    private Slider sizeSlider;
    private Slider flowSlider;
    private MaterialButton colorButton;
    private View layersPanel;
    private LinearLayout layerList;
    private Slider layerOpacity;
    private MaterialButtonToggleGroup layerBlend;
    private boolean updatingPanel;

    private Uri projectUri;
    private boolean loaded;
    private boolean dirty;

    private ActivityResultLauncher<String> savePng;
    private ActivityResultLauncher<String> saveCopy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectUri = getIntent().getData();
        if (!Prefs.isSetupDone(this) || projectUri == null) {
            finish();
            return;
        }
        boolean minimal = Prefs.minimalUi(this);
        setContentView(minimal ? R.layout.activity_main_minimal : R.layout.activity_main);

        canvas = findViewById(R.id.canvas);
        int backdrop = minimal ? 0xFFC0C0C0 : getColor(R.color.canvas_bg);
        if (minimal) {
            canvas.setBackdropColor(backdrop);
        }
        // The top bar sits on the canvas colour, so the status bar takes the same colour.
        getWindow().setStatusBarColor(backdrop);
        androidx.core.view.WindowCompat.getInsetsController(getWindow(),
                getWindow().getDecorView()).setAppearanceLightStatusBars(
                        androidx.core.graphics.ColorUtils.calculateLuminance(backdrop) > 0.5);
        undoButton = findViewById(R.id.btn_undo);
        redoButton = findViewById(R.id.btn_redo);
        layersButton = findViewById(R.id.layers_button);
        preview = findViewById(R.id.brush_preview);
        sizeValue = findViewById(R.id.size_value);
        flowValue = findViewById(R.id.flow_value);
        colorButton = findViewById(R.id.color_button);
        layersPanel = findViewById(R.id.layers_panel);
        layerList = findViewById(R.id.layer_list);
        layerOpacity = findViewById(R.id.layer_opacity);
        layerBlend = findViewById(R.id.layer_blend);

        savePng = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("image/png"), this::exportPng);
        saveCopy = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("image/openraster"), this::saveCopyTo);

        findViewById(R.id.btn_menu).setOnClickListener(this::showMainMenu);
        undoButton.setOnClickListener(v -> canvas.undo());
        redoButton.setOnClickListener(v -> canvas.redo());
        canvas.setListener(() -> {
            dirty = true;
            updateHistoryButtons();
            if (layersPanel.getVisibility() == View.VISIBLE) {
                rebuildLayerPanel();
            }
        });
        updateHistoryButtons();

        MaterialButtonToggleGroup tools = findViewById(R.id.tools);
        tools.check(R.id.tool_brush);
        tools.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) {
                canvas.setEraser(id == R.id.tool_eraser);
            }
        });

        sizeSlider = findViewById(R.id.size);
        flowSlider = findViewById(R.id.flow);
        sizeSlider.addOnChangeListener((sl, value, fromUser) -> applyBrushSettings());
        flowSlider.addOnChangeListener((sl, value, fromUser) -> applyBrushSettings());
        findViewById(R.id.size_minus).setOnClickListener(v -> nudge(sizeSlider, -1));
        findViewById(R.id.size_plus).setOnClickListener(v -> nudge(sizeSlider, 1));
        findViewById(R.id.flow_minus).setOnClickListener(v -> nudge(flowSlider, -1));
        findViewById(R.id.flow_plus).setOnClickListener(v -> nudge(flowSlider, 1));
        applyBrushSettings();

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
        loadProject();
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveNow();
    }

    // ------------------------------------------------------------------ loading and saving

    private void loadProject() {
        final Uri uri = projectUri;
        Toast.makeText(this, "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            OpenRaster.Project project = null;
            Uri target = uri;
            boolean converted = false;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    project = ImageIo.openProject(in);
                }
                DocumentFile source = DocumentFile.fromSingleUri(this, uri);
                String name = source == null ? null : source.getName();
                if (project != null && name != null
                        && name.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
                    // A PNG is never overwritten: its picture becomes a new drawing.
                    DocumentFile copy = GalleryStore.create(this, name.substring(0,
                            name.length() - 4));
                    if (copy != null) {
                        target = copy.getUri();
                        converted = true;
                    }
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                project = null;
            }
            final OpenRaster.Project result = project;
            final Uri finalTarget = target;
            final boolean wasConverted = converted;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (result == null) {
                    Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                projectUri = finalTarget;
                canvas.setDocument(result.document, result.log);
                loaded = true;
                dirty = wasConverted;
                rebuildLayerPanel();
                updateHistoryButtons();
            });
        }, "open").start();
    }

    /** Writes the drawing back to its file if anything changed. */
    private void saveNow() {
        if (!loaded || !dirty) {
            return;
        }
        try {
            ImageIo.save(this, projectUri, canvas.getDocument(), canvas.getEditor().getLog());
            dirty = false;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ menus

    private void showMainMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor, Gravity.START);
        menu.getMenu().add(0, 1, 0, R.string.export_png);
        menu.getMenu().add(0, 2, 1, R.string.save_copy);
        menu.getMenu().add(0, 3, 2, R.string.to_gallery);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    savePng.launch("drawing.png");
                    return true;
                case 2:
                    saveCopy.launch("drawing.ora");
                    return true;
                case 3:
                    finish();
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void updateHistoryButtons() {
        undoButton.setEnabled(canvas.canUndo());
        redoButton.setEnabled(canvas.canRedo());
        layersButton.setText(String.valueOf(canvas.getDocument().getLayerCount()));
    }

    private void nudge(Slider slider, float delta) {
        float v = Math.max(slider.getValueFrom(), Math.min(slider.getValueTo(),
                slider.getValue() + delta));
        slider.setValue(v);
    }

    private void applyBrushSettings() {
        canvas.setRadius(sizeSlider.getValue());
        canvas.setFlow(flowSlider.getValue() / 100f);
        sizeValue.setText(String.valueOf((int) sizeSlider.getValue()));
        flowValue.setText(String.valueOf((int) flowSlider.getValue()));
        preview.setBrush(canvas.getColor(), sizeSlider.getValue() / sizeSlider.getValueTo());
    }

    // ------------------------------------------------------------------ colour

    private void showColor() {
        colorButton.setBackgroundTintList(ColorStateList.valueOf(0xFF000000 | canvas.getColor()));
        if (preview != null && sizeSlider != null) {
            applyBrushSettings();
        }
    }

    private void pickColor() {
        int current = canvas.getColor();
        int[] rgb = {Color.red(current), Color.green(current), Color.blue(current)};
        String[] names = {"R", "G", "B"};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final View swatch = new View(this);
        swatch.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, pad * 2));
        swatch.setBackgroundColor(0xFF000000 | current);
        box.addView(swatch);

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
                swatch.setBackgroundColor(Color.rgb(rgb[0], rgb[1], rgb[2]));
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
            if (doc.getLayerCount() >= 30 || !canvas.isIdle()) {
                return;
            }
            int at = canvas.getActiveLayer() + 1;
            canvas.getEditor().addLayer("Layer " + (doc.getLayerCount() + 1), at);
            canvas.setActiveLayer(at);
            canvas.layerStructureChanged();
            rebuildLayerPanel();
        });
        findViewById(R.id.layer_delete).setOnClickListener(v -> {
            Document doc = canvas.getDocument();
            if (doc.getLayerCount() <= 1 || !canvas.isIdle()) {
                return;
            }
            int index = canvas.getActiveLayer();
            canvas.getEditor().removeLayer(index);
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
            canvas.getEditor().setLayerOpacity(canvas.getActiveLayer(),
                    Math.round(value * 255f / 100f));
            dirty = true;
            canvas.layersChanged();
        });
        layerBlend.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked || updatingPanel) {
                return;
            }
            BlendMode mode = id == R.id.blend_multiply ? BlendMode.MULTIPLY
                    : id == R.id.blend_screen ? BlendMode.SCREEN : BlendMode.NORMAL;
            canvas.getEditor().setLayerBlendMode(canvas.getActiveLayer(), mode);
            dirty = true;
            canvas.layersChanged();
        });
    }

    private void moveActive(int delta) {
        Document doc = canvas.getDocument();
        int from = canvas.getActiveLayer();
        int to = from + delta;
        if (to < 0 || to >= doc.getLayerCount() || !canvas.isIdle()) {
            return;
        }
        canvas.getEditor().moveLayer(from, to);
        canvas.setActiveLayer(to);
        canvas.layerStructureChanged();
        rebuildLayerPanel();
    }

    private void rebuildLayerPanel() {
        final Document doc = canvas.getDocument();
        layersButton.setText(String.valueOf(doc.getLayerCount()));
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
            if (index == canvas.getActiveLayer()) {
                row.setBackgroundColor(themeColor(
                        com.google.android.material.R.attr.colorSecondaryContainer));
            }

            CheckBox eye = new CheckBox(this);
            eye.setButtonDrawable(layer.isVisible() ? R.drawable.ic_eye : R.drawable.ic_eye_off);
            eye.setChecked(layer.isVisible());
            eye.setContentDescription(getString(R.string.visibility));
            eye.setOnClickListener(v -> {
                canvas.getEditor().setLayerVisible(index, !layer.isVisible());
                dirty = true;
                canvas.layersChanged();
                rebuildLayerPanel();
            });
            row.addView(eye);

            TextView name = new TextView(this);
            name.setText(layer.getName());
            name.setSingleLine(true);
            name.setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
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

    // ------------------------------------------------------------------ export

    private void saveCopyTo(Uri uri) {
        if (uri == null) {
            return;
        }
        final Document doc = canvas.getDocument();
        final ProjectLog log = canvas.getEditor().getLog();
        boolean ok;
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                throw new IOException("no stream");
            }
            OpenRaster.write(doc, log, out);
            ok = true;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            ok = false;
        }
        Toast.makeText(this, ok ? R.string.saved : R.string.save_failed,
                Toast.LENGTH_SHORT).show();
    }

    private void exportPng(Uri uri) {
        if (uri == null) {
            return;
        }
        Bitmap bitmap = canvas.exportBitmap();
        boolean ok;
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
