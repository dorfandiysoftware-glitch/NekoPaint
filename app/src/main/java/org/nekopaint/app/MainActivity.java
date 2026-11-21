// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.button.MaterialButton;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import nekopaint.core.Document;
import nekopaint.core.OpenRaster;
import nekopaint.core.ProjectLog;

/** The drawing screen. It edits one drawing from the gallery and saves it when you leave. */
public class MainActivity extends AppCompatActivity {

    private CanvasView canvas;
    private View undoButton;
    private View redoButton;
    private EditorUi ui;

    private Uri projectUri;
    private boolean loaded;
    private boolean dirty;

    private ActivityResultLauncher<String> savePng;
    private ActivityResultLauncher<String> saveCopy;

    @Override
    protected void onResume() {
        super.onResume();
        if (canvas != null) {
            canvas.applySettings();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectUri = getIntent().getData();
        if (!Prefs.isSetupDone(this) || projectUri == null) {
            finish();
            return;
        }
        boolean minimal = !Prefs.md3Colors(this);
        setContentView(R.layout.activity_main);

        canvas = findViewById(R.id.canvas);
        canvas.setPerfVisible(Prefs.perfOverlay(this));
        int custom = Opts.num(this, Opts.BACKDROP);
        int backdrop = custom != 0 ? custom : minimal ? 0xFFC0C0C0 : getColor(R.color.canvas_bg);
        findViewById(R.id.root).setBackgroundColor(backdrop);
        if (minimal || custom != 0) {
            canvas.setBackdropColor(backdrop);
        }
        // The top bar sits on the canvas colour, so the status bar takes the same colour.
        getWindow().setStatusBarColor(backdrop);
        androidx.core.view.WindowCompat.getInsetsController(getWindow(),
                getWindow().getDecorView()).setAppearanceLightStatusBars(
                        androidx.core.graphics.ColorUtils.calculateLuminance(backdrop) > 0.5);
        undoButton = findViewById(R.id.btn_undo);
        redoButton = findViewById(R.id.btn_redo);
        View togglesButton = findViewById(R.id.btn_toggles);
        if (minimal) {
            for (View b : new View[] {undoButton, redoButton, togglesButton}) {
                ((MaterialButton) b).setBackgroundTintList(ColorStateList.valueOf(0x99808080));
                ((MaterialButton) b).setIconTint(ColorStateList.valueOf(Color.WHITE));
            }
        }

        pickLayerImage = registerForActivityResult(
                new ActivityResultContracts.GetContent(), this::importLayerImage);
        savePng = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("image/png"), this::exportPng);
        saveCopy = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("image/openraster"), this::saveCopyTo);

        ui = new EditorUi(this, canvas, findViewById(R.id.bar), findViewById(R.id.top_bar),
                minimal, new EditorUi.Host() {
                    @Override
                    public void onLayersEdited() {
                        dirty = true;
                    }

                    @Override
                    public void showMenu(View anchor) {
                        showMainMenu(anchor);
                    }
                });
        togglesButton.setOnClickListener(v -> ui.showToggles(v));
        canvas.setHistoryListener((undo, kind) -> ui.showHistoryNotice(undo, kind));
        undoButton.setOnClickListener(v -> canvas.undo());
        redoButton.setOnClickListener(v -> canvas.redo());
        canvas.setListener(() -> {
            dirty = true;
            updateHistoryButtons();
            ui.refreshLayers();
        });
        updateHistoryButtons();
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
                    DocumentFile copy = GalleryStore.create(GalleryStore.root(this), name.substring(0,
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
                canvas.setDocument(result.document, result.log != null ? result.log
                        : nekopaint.core.ProjectLog.forExisting(result.document));
                loaded = true;
                dirty = wasConverted;
                ui.refreshLayers();
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
        PopupMenu menu = new PopupMenu(this, anchor, Gravity.END);
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
        // The flat tint of the minimalist layout does not change by itself when disabled.
        undoButton.setAlpha(canvas.canUndo() ? 1f : 0.3f);
        redoButton.setAlpha(canvas.canRedo() ? 1f : 0.3f);
        if (ui != null) {
            ui.updateLayerCount();
        }
    }

    int themeColor(int attr) {
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

    private androidx.activity.result.ActivityResultLauncher<String> pickLayerImage;

    void pickImageForLayer() {
        pickLayerImage.launch("image/*");
    }

    /** Puts a picture from the phone on a new layer, scaled to fit the canvas and centred. */
    private void importLayerImage(final android.net.Uri uri) {
        if (uri == null || !canvas.isIdle()) {
            return;
        }
        final int cw = canvas.getDocument().getWidth();
        final int ch = canvas.getDocument().getHeight();
        new Thread(() -> {
            final int[] px = ImageImport.fitToCanvas(this, uri, cw, ch);
            runOnUiThread(() -> {
                if (px == null) {
                    Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                nekopaint.core.Document doc = canvas.getDocument();
                if (doc.getWidth() != cw || doc.getHeight() != ch || doc.getLayerCount() >= 30) {
                    return;
                }
                int at = canvas.getActiveLayer() + 1;
                canvas.getEditor().addLayer("Image", at).setStraightArgb(px);
                canvas.setActiveLayer(at);
                canvas.layerStructureChanged();
                ui.layersChangedFromOutside();
            });
        }, "layer-import").start();
    }
}
