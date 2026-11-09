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
    private final List<DocumentFile> folders = new ArrayList<DocumentFile>();
    /** Folders we have opened, innermost last; empty means the gallery root. */
    private final ArrayList<DocumentFile> path = new ArrayList<DocumentFile>();
    private ActivityResultLauncher<String> pickImage;
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
    private View backButton;
    private View selectAllButton;
    private View detail;
    private ImageView detailImage;
    private TextView detailText;
    private View barNormal;
    private View barSelect;
    /** The drawing opened from the grid (shown large with its information), or null. */
    private GalleryStore.Item detailItem;
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
        backButton = findViewById(R.id.gallery_back);
        selectAllButton = findViewById(R.id.gallery_select_all);
        detail = findViewById(R.id.gallery_detail);
        detailImage = findViewById(R.id.detail_image);
        detailText = findViewById(R.id.detail_text);
        barNormal = findViewById(R.id.gallery_bar);
        barSelect = findViewById(R.id.gallery_bar_select);

        RecyclerView grid = findViewById(R.id.gallery_grid);
        int columns = Math.max(2, (int) (getResources().getDisplayMetrics().widthPixels
                / (getResources().getDisplayMetrics().density * 170)));
        grid.setLayoutManager(new GridLayoutManager(this, columns));
        adapter = new Adapter();
        grid.setAdapter(adapter);

        findViewById(R.id.gallery_back).setOnClickListener(v -> goBack());
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        goBack();
                    }
                });
        pickImage = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        importPicture(uri);
                    }
                });
        selectButton.setOnClickListener(v -> {
            if (selectMode) {
                selectMode = false;
                selected.clear();
            } else {
                // From the large view the opened drawing becomes the first selected one.
                detailItem = null;
                selectMode = true;
            }
            adapter.notifyDataSetChanged();
            updateUi();
        });
        selectAllButton.setOnClickListener(v -> {
            if (selected.size() == items.size()) {
                selected.clear();
            } else {
                for (GalleryStore.Item it : items) {
                    selected.add(it.uri().toString());
                }
            }
            adapter.notifyDataSetChanged();
            updateUi();
        });
        findViewById(R.id.gallery_delete_sel).setOnClickListener(v -> {
            if (!selected.isEmpty()) {
                deleteSelected();
            }
        });
        findViewById(R.id.gallery_move_sel).setOnClickListener(v -> {
            if (!selected.isEmpty()) {
                chooseMoveTarget();
            }
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
                    path.clear();
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
            final DocumentFile dir = currentDir();
            final List<GalleryStore.Item> found = GalleryStore.list(dir, false);
            final List<DocumentFile> subs = GalleryStore.folders(dir);
            runOnUiThread(() -> {
                items.clear();
                items.addAll(found);
                folders.clear();
                folders.addAll(subs);
                Set<String> still = new LinkedHashSet<String>();
                for (GalleryStore.Item it : items) {
                    if (selected.contains(it.uri().toString())) {
                        still.add(it.uri().toString());
                    }
                }
                selected.clear();
                selected.addAll(still);
                if (detailItem != null) {
                    GalleryStore.Item fresh = null;
                    for (GalleryStore.Item it : items) {
                        if (it.uri().toString().equals(detailItem.uri().toString())) {
                            fresh = it;
                        }
                    }
                    detailItem = fresh;
                }
                adapter.notifyDataSetChanged();
                updateUi();
            });
        }, "gallery-list").start();
    }

    private DocumentFile currentDir() {
        return path.isEmpty() ? GalleryStore.root(this) : path.get(path.size() - 1);
    }

    private void goBack() {
        if (detailItem != null) {
            detailItem = null;
            selected.clear();
            adapter.notifyDataSetChanged();
            updateUi();
            return;
        }
        if (selectMode) {
            selectMode = false;
            selected.clear();
            adapter.notifyDataSetChanged();
            updateUi();
            return;
        }
        if (path.isEmpty()) {
            finish();
            return;
        }
        path.remove(path.size() - 1);
        selected.clear();
        refresh();
    }

    private GalleryStore.Item single() {
        return detailItem;
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
        if (selectMode) {
            title.setText(getString(R.string.items_selected, selected.size()));
        } else if (path.isEmpty()) {
            title.setText(getString(R.string.gallery_title, items.size()));
        } else {
            title.setText(getString(R.string.folder_title, currentDir().getName(), items.size()));
        }
        empty.setVisibility(items.isEmpty() && folders.isEmpty() && detailItem == null
                ? View.VISIBLE : View.GONE);
        selectButton.setText(selectMode ? R.string.done : R.string.select);
        backButton.setVisibility(selectMode ? View.GONE : View.VISIBLE);
        selectAllButton.setVisibility(selectMode ? View.VISIBLE : View.GONE);
        barNormal.setVisibility(selectMode ? View.GONE : View.VISIBLE);
        barSelect.setVisibility(selectMode ? View.VISIBLE : View.GONE);

        if (detailItem != null) {
            detail.setVisibility(View.VISIBLE);
            thumbs.load(detailItem, detailImage);
            detailText.setText(getString(R.string.detail_title, detailItem.title()) + "\n"
                    + getString(R.string.detail_changed, DateFormat.getMediumDateFormat(this)
                            .format(new Date(detailItem.modified)) + " "
                            + DateFormat.getTimeFormat(this).format(new Date(detailItem.modified))));
        } else {
            detail.setVisibility(View.GONE);
        }
        boolean opened = detailItem != null;
        playButton.setEnabled(opened);
        shareButton.setEnabled(opened);
        editButton.setEnabled(opened);
    }

    // ------------------------------------------------------------------ actions

    private void newDrawing() {
        NewDrawingDialog.show(this, new NewDrawingDialog.Listener() {
            @Override
            public void onCreate(int w, int h) {
                createDrawing(w, h, null);
            }

            @Override
            public void onFolder() {
                createFolder();
            }

            @Override
            public void onImport() {
                pickImage.launch("image/*");
            }
        });
    }

    private void createFolder() {
        final DocumentFile dir = currentDir();
        new Thread(() -> {
            final DocumentFile f = GalleryStore.createFolder(dir, getString(R.string.folder_default));
            runOnUiThread(() -> {
                if (f == null) {
                    Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, R.string.folder_created, Toast.LENGTH_SHORT).show();
                    refresh();
                }
            });
        }, "gallery-folder").start();
    }

    private void importPicture(final Uri uri) {
        new Thread(() -> {
            final Document doc = ImageImport.load(this, uri);
            runOnUiThread(() -> {
                if (doc == null) {
                    Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show();
                } else {
                    createDrawing(doc.getWidth(), doc.getHeight(), doc);
                }
            });
        }, "gallery-import").start();
    }

    private void createDrawing(final int w, final int h, final Document base) {
        Prefs.setSize(this, w, h);
        final DocumentFile dir = currentDir();
        new Thread(() -> {
            Uri uri = null;
            try {
                DocumentFile file = GalleryStore.create(dir, getString(R.string.drawing));
                if (file != null) {
                    Document doc = base != null ? base : CanvasView.newBlankDocument(w, h);
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
            showFolderInfo();
            return;
        }
        text.append(getString(R.string.info_folder, GalleryStore.displayPath(this)));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.info)
                .setMessage(text.toString())
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void showFolderInfo() {
        final DocumentFile dir = currentDir();
        final String name = path.isEmpty() ? getString(R.string.folder_unnamed) : dir.getName();
        final String place = getString(R.string.gallery_name);
        new Thread(() -> {
            final GalleryStore.Stats st = GalleryStore.stats(dir);
            runOnUiThread(() -> {
                String text = getString(R.string.fi_name, name) + "\n"
                        + getString(R.string.fi_place, place) + "\n"
                        + getString(R.string.fi_size, Formatter.formatShortFileSize(this, st.bytes),
                                st.drawings) + "\n"
                        + getString(R.string.fi_data, st.drawings, st.folders);
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.folder_info)
                        .setMessage(text)
                        .setPositiveButton(R.string.close, null)
                        .show();
            });
        }, "gallery-stats").start();
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
                        detailItem = null;
                        selectMode = false;
                        refresh();
                    });
                }, "gallery-delete").start())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showTrash() {
        new Thread(() -> {
            final List<GalleryStore.Item> trash = GalleryStore.list(currentDir(), true);
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
                                GalleryStore.restore(currentDir(), it);
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

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

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

        final class FolderHolder extends RecyclerView.ViewHolder {
            final TextView name;
            final View delete;

            FolderHolder(View v) {
                super(v);
                name = v.findViewById(R.id.folder_name);
                delete = v.findViewById(R.id.folder_delete);
            }
        }

        @Override
        public int getItemViewType(int position) {
            return position < folders.size() ? 1 : 0;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater in = LayoutInflater.from(parent.getContext());
            if (viewType == 1) {
                return new FolderHolder(in.inflate(R.layout.item_folder, parent, false));
            }
            return new Holder(in.inflate(R.layout.item_gallery, parent, false));
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder vh, int position) {
            if (vh instanceof FolderHolder) {
                final DocumentFile f = folders.get(position);
                FolderHolder fh = (FolderHolder) vh;
                fh.name.setText(f.getName());
                fh.itemView.setOnClickListener(v -> {
                    path.add(f);
                    selected.clear();
                    refresh();
                });
                fh.delete.setVisibility(selectMode ? View.VISIBLE : View.GONE);
                fh.delete.setOnClickListener(v -> confirmDeleteFolder(f));
                return;
            }
            Holder h = (Holder) vh;
            final GalleryStore.Item it = items.get(position - folders.size());
            h.name.setText(it.title());
            thumbs.load(it, h.thumb);
            final String key = it.uri().toString();
            boolean on = selected.contains(key);
            h.check.setVisibility(on ? View.VISIBLE : View.GONE);
            ((com.google.android.material.card.MaterialCardView) h.itemView).setStrokeWidth(
                    on ? (int) (3 * getResources().getDisplayMetrics().density) : 0);
            h.itemView.setOnClickListener(v -> {
                if (!selectMode) {
                    // A plain tap shows the drawing large, with its information.
                    detailItem = it;
                    selected.clear();
                    selected.add(key);
                    updateUi();
                    return;
                }
                if (selected.contains(key)) {
                    selected.remove(key);
                } else {
                    selected.add(key);
                }
                notifyDataSetChanged();
                updateUi();
            });
        }

        @Override
        public int getItemCount() {
            return folders.size() + items.size();
        }
    }

    private void chooseMoveTarget() {
        final List<DocumentFile> targets = new ArrayList<DocumentFile>();
        final List<String> names = new ArrayList<String>();
        if (!path.isEmpty()) {
            targets.add(path.size() >= 2 ? path.get(path.size() - 2) : GalleryStore.root(this));
            names.add(getString(R.string.move_folder_up));
        }
        for (DocumentFile f : folders) {
            targets.add(f);
            names.add(f.getName());
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.no_folders, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.move_to)
                .setItems(names.toArray(new String[0]), (d, which) -> moveSelected(targets.get(which)))
                .show();
    }

    private void moveSelected(final DocumentFile target) {
        final List<GalleryStore.Item> sel = selectedItems();
        new Thread(() -> {
            boolean ok = true;
            for (GalleryStore.Item it : sel) {
                ok &= GalleryStore.move(this, it, target);
            }
            final boolean all = ok;
            runOnUiThread(() -> {
                if (!all) {
                    Toast.makeText(this, R.string.move_failed, Toast.LENGTH_LONG).show();
                }
                selected.clear();
                selectMode = false;
                refresh();
            });
        }, "gallery-move").start();
    }

    private void confirmDeleteFolder(final DocumentFile f) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.folder_delete_confirm, f.getName()))
                .setPositiveButton(R.string.delete, (d, w) -> new Thread(() -> {
                    GalleryStore.deleteFolder(f);
                    runOnUiThread(this::refresh);
                }, "gallery-folder-delete").start())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
