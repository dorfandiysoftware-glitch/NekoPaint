// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import nekopaint.core.Document;
import nekopaint.core.OpenRaster;
import nekopaint.core.PngCodec;
import nekopaint.core.ProjectLog;

/** "My Gallery": all drawings in the save folder, with actions for the selected ones. */
public class GalleryActivity extends AppCompatActivity {

    private final List<GalleryStore.Item> items = new ArrayList<GalleryStore.Item>();
    private final Set<String> selected = new LinkedHashSet<String>();
    private boolean selectMode;

    private ThumbLoader thumbs;
    private Adapter adapter;
    private TextView title;
    private View empty;
    private MaterialButton selectButton;
    private View playButton;
    private View shareButton;
    private View editButton;
    private ActivityResultLauncher<Uri> pickFolder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);
        thumbs = new ThumbLoader(this);

        title = findViewById(R.id.gallery_title);
        empty = findViewById(R.id.gallery_empty);
        selectButton = findViewById(R.id.gallery_select);
        playButton = findViewById(R.id.gallery_play);
        shareButton = findViewById(R.id.gallery_share);
        editButton = findViewById(R.id.gallery_edit);

        RecyclerView grid = findViewById(R.id.gallery_grid);
        int columns = Math.max(2, (int) (getResources().getDisplayMetrics().widthPixels
                / (getResources().getDisplayMetrics().density * 170)));
        grid.setLayoutManager(new GridLayoutManager(this, columns));
        adapter = new Adapter();
        grid.setAdapter(adapter);

        findViewById(R.id.gallery_back).setOnClickListener(v -> finish());
        selectButton.setOnClickListener(v -> {
            selectMode = !selectMode;
            if (!selectMode && selected.size() > 1) {
                selected.clear();
            }
            adapter.notifyDataSetChanged();
            updateUi();
        });
        findViewById(R.id.gallery_new).setOnClickListener(v -> newDrawing());
        findViewById(R.id.gallery_info).setOnClickListener(v -> showInfo());
        playButton.setOnClickListener(v -> {
            GalleryStore.Item it = single();
            if (it != null) {
                Intent i = new Intent(this, TimelapseActivity.class);
                i.setData(it.uri());
                startActivity(i);
            }
        });
        shareButton.setOnClickListener(v -> share());
        findViewById(R.id.gallery_more).setOnClickListener(v -> showMoreMenu());
        editButton.setOnClickListener(v -> {
            GalleryStore.Item it = single();
            if (it != null) {
                Intent i = new Intent(this, MainActivity.class);
                i.setData(it.uri());
                startActivity(i);
            }
        });

        pickFolder = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),
                uri -> {
                    if (uri == null) {
                        return;
                    }
                    try {
                        getContentResolver().takePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    } catch (SecurityException e) {
                        Toast.makeText(this, R.string.folder_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    Prefs.setTreeUri(this, uri.toString());
                    selected.clear();
                    Toast.makeText(this, R.string.folder_changed, Toast.LENGTH_SHORT).show();
                    refresh();
                });
        updateUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        thumbs.shutdown();
    }

    // ------------------------------------------------------------------ list

    private void refresh() {
        new Thread(() -> {
            final List<GalleryStore.Item> found = GalleryStore.list(this, false);
            runOnUiThread(() -> {
                items.clear();
                items.addAll(found);
                Set<String> still = new LinkedHashSet<String>();
                for (GalleryStore.Item it : items) {
                    if (selected.contains(it.uri().toString())) {
                        still.add(it.uri().toString());
                    }
                }
                selected.clear();
                selected.addAll(still);
                adapter.notifyDataSetChanged();
                updateUi();
            });
        }, "gallery-list").start();
    }

    private GalleryStore.Item single() {
        if (selected.size() != 1) {
            return null;
        }
        String key = selected.iterator().next();
        for (GalleryStore.Item it : items) {
            if (it.uri().toString().equals(key)) {
                return it;
            }
        }
        return null;
    }

    private List<GalleryStore.Item> selectedItems() {
        List<GalleryStore.Item> out = new ArrayList<GalleryStore.Item>();
        for (GalleryStore.Item it : items) {
            if (selected.contains(it.uri().toString())) {
                out.add(it);
            }
        }
        return out;
    }

    private void updateUi() {
        title.setText(getString(R.string.gallery_title, items.size()));
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        selectButton.setText(selectMode ? R.string.done : R.string.select);
        boolean one = selected.size() == 1;
        playButton.setEnabled(one);
        editButton.setEnabled(one);
        shareButton.setEnabled(!selected.isEmpty());
    }

    // ------------------------------------------------------------------ actions

    private void newDrawing() {
        CanvasSizeDialog.show(this, Prefs.width(this), Prefs.height(this), (w, h) -> {
            Prefs.setSize(this, w, h);
            new Thread(() -> {
                Uri uri = null;
                try {
                    DocumentFile file = GalleryStore.create(this, getString(R.string.drawing));
                    if (file != null) {
                        Document doc = CanvasView.newBlankDocument(w, h);
                        ImageIo.save(this, file.getUri(), doc, new ProjectLog(w, h));
                        uri = file.getUri();
                    }
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    uri = null;
                }
                final Uri result = uri;
                runOnUiThread(() -> {
                    if (result == null) {
                        Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show();
                    } else {
                        Intent i = new Intent(this, MainActivity.class);
                        i.setData(result);
                        startActivity(i);
                    }
                });
            }, "gallery-new").start();
        });
    }

    private void showInfo() {
        StringBuilder text = new StringBuilder();
        List<GalleryStore.Item> sel = selectedItems();
        if (sel.size() == 1) {
            GalleryStore.Item it = sel.get(0);
            text.append(getString(R.string.info_name, it.title())).append('\n');
            text.append(getString(R.string.info_modified, DateFormat.getMediumDateFormat(this)
                    .format(new Date(it.modified)) + " "
                    + DateFormat.getTimeFormat(this).format(new Date(it.modified)))).append('\n');
            text.append(getString(R.string.info_size,
                    Formatter.formatShortFileSize(this, it.size))).append('\n');
        } else {
            text.append(getString(R.string.info_count, items.size())).append('\n');
            if (sel.size() > 1) {
                text.append(getString(R.string.info_selected, sel.size())).append('\n');
            }
        }
        text.append(getString(R.string.info_folder, GalleryStore.displayPath(this)));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.info)
                .setMessage(text.toString())
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void showMoreMenu() {
        final boolean hasSelection = !selected.isEmpty();
        final List<String> labels = new ArrayList<String>();
        final List<Integer> ids = new ArrayList<Integer>();
        if (hasSelection) {
            labels.add(getString(R.string.delete_selected));
            ids.add(0);
        }
        labels.add(getString(R.string.recently_deleted));
        ids.add(1);
        labels.add(getString(R.string.change_folder));
        ids.add(2);
        labels.add(getString(R.string.show_import_path));
        ids.add(3);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.please_choose)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    switch (ids.get(which)) {
                        case 0:
                            deleteSelected();
                            break;
                        case 1:
                            showTrash();
                            break;
                        case 2:
                            pickFolder.launch(null);
                            break;
                        default:
                            showImportPath();
                            break;
                    }
                })
                .show();
    }

    private void deleteSelected() {
        final List<GalleryStore.Item> sel = selectedItems();
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.delete_confirm, sel.size()))
                .setPositiveButton(R.string.delete, (d, w) -> new Thread(() -> {
                    for (GalleryStore.Item it : sel) {
                        GalleryStore.softDelete(it);
                    }
                    runOnUiThread(() -> {
                        selected.clear();
                        refresh();
                    });
                }, "gallery-delete").start())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showTrash() {
        new Thread(() -> {
            final List<GalleryStore.Item> trash = GalleryStore.list(this, true);
            runOnUiThread(() -> {
                if (trash.isEmpty()) {
                    Toast.makeText(this, R.string.trash_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] names = new String[trash.size()];
                for (int i = 0; i < names.length; i++) {
                    names[i] = trash.get(i).title();
                }
                final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.recently_deleted)
                        .setSingleChoiceItems(names, -1, null)
                        .setPositiveButton(R.string.restore, null)
                        .setNegativeButton(R.string.delete_forever, null)
                        .setNeutralButton(R.string.close, null)
                        .create();
                dialog.setOnShowListener(x -> {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                        int pos = dialog.getListView().getCheckedItemPosition();
                        if (pos >= 0) {
                            final GalleryStore.Item it = trash.get(pos);
                            dialog.dismiss();
                            new Thread(() -> {
                                GalleryStore.restore(this, it);
                                runOnUiThread(this::refresh);
                            }, "gallery-restore").start();
                        }
                    });
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                        int pos = dialog.getListView().getCheckedItemPosition();
                        if (pos >= 0) {
                            final GalleryStore.Item it = trash.get(pos);
                            dialog.dismiss();
                            new Thread(() -> GalleryStore.deleteForever(it),
                                    "gallery-purge").start();
                        }
                    });
                });
                dialog.show();
            });
        }, "gallery-trash").start();
    }

    private void showImportPath() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.show_import_path)
                .setMessage(GalleryStore.displayPath(this) + "\n\n"
                        + getString(R.string.import_path_note))
                .setPositiveButton(R.string.close, null)
                .setNeutralButton(R.string.use_default_folder, (d, w) -> {
                    Prefs.setTreeUri(this, null);
                    selected.clear();
                    refresh();
                })
                .show();
    }

    private void share() {
        final List<GalleryStore.Item> sel = selectedItems();
        if (sel.isEmpty()) {
            return;
        }
        Toast.makeText(this, "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final ArrayList<Uri> uris = new ArrayList<Uri>();
            try {
                File dir = new File(getCacheDir(), "share");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) {
                    for (File f : old) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
                for (GalleryStore.Item it : sel) {
                    Document doc;
                    try (InputStream in = getContentResolver().openInputStream(it.uri())) {
                        doc = ImageIo.open(in);
                    }
                    int[] flat = doc.flatten(0xFFFFFFFF);
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    PngCodec.encode(doc.getWidth(), doc.getHeight(), flat, png);
                    String safe = it.title().replaceAll("[^A-Za-z0-9_-]", "_");
                    File out = new File(dir, safe + "_" + uris.size() + ".png");
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        png.writeTo(fos);
                    }
                    uris.add(FileProvider.getUriForFile(this, getPackageName() + ".files", out));
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                uris.clear();
            }
            runOnUiThread(() -> {
                if (uris.isEmpty()) {
                    Toast.makeText(this, R.string.share_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                Intent send = new Intent(uris.size() == 1 ? Intent.ACTION_SEND
                        : Intent.ACTION_SEND_MULTIPLE);
                send.setType("image/png");
                if (uris.size() == 1) {
                    send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                } else {
                    send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                }
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(send, getString(R.string.share)));
            });
        }, "gallery-share").start();
    }

    // ------------------------------------------------------------------ adapter

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView thumb;
            final ImageView check;
            final TextView name;

            Holder(View v) {
                super(v);
                thumb = v.findViewById(R.id.item_thumb);
                check = v.findViewById(R.id.item_check);
                name = v.findViewById(R.id.item_name);
            }
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_gallery, parent, false));
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            final GalleryStore.Item it = items.get(position);
            h.name.setText(it.title());
            thumbs.load(it, h.thumb);
            final String key = it.uri().toString();
            boolean on = selected.contains(key);
            h.check.setVisibility(on ? View.VISIBLE : View.GONE);
            ((com.google.android.material.card.MaterialCardView) h.itemView).setStrokeWidth(
                    on ? (int) (3 * getResources().getDisplayMetrics().density) : 0);
            h.itemView.setOnClickListener(v -> {
                if (selected.contains(key)) {
                    selected.remove(key);
                } else {
                    if (!selectMode) {
                        selected.clear();
                    }
                    selected.add(key);
                }
                notifyDataSetChanged();
                updateUi();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
