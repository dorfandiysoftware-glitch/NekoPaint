// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The folder that holds the user's drawings. By default it is a private folder of the app;
 * the user can pick any folder with Android's file picker (which also reaches cloud folders).
 * "Deleted" drawings stay in the same folder under a name ending in {@code .trash}.
 */
final class GalleryStore {

    static final String TRASH = ".trash";

    /** One drawing file. */
    static final class Item {
        final DocumentFile file;
        final String name;
        final long modified;
        final long size;

        Item(DocumentFile file, String name) {
            this.file = file;
            this.name = name;
            this.modified = file.lastModified();
            this.size = file.length();
        }

        Uri uri() {
            return file.getUri();
        }

        /** Name without the extension, for showing to the user. */
        String title() {
            String n = name;
            if (n.endsWith(TRASH)) {
                n = n.substring(0, n.length() - TRASH.length());
            }
            int dot = n.lastIndexOf('.');
            return dot > 0 ? n.substring(0, dot) : n;
        }

        boolean isPng() {
            return name.toLowerCase(java.util.Locale.ROOT).endsWith(".png");
        }
    }

    private GalleryStore() {
    }

    static DocumentFile root(Context c) {
        String tree = Prefs.treeUri(c);
        if (tree != null) {
            DocumentFile dir = DocumentFile.fromTreeUri(c, Uri.parse(tree));
            if (dir != null && dir.canWrite()) {
                return dir;
            }
        }
        File base = c.getExternalFilesDir(null);
        if (base == null) {
            base = c.getFilesDir();
        }
        File dir = new File(base, "gallery");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return DocumentFile.fromFile(dir);
    }

    /** A readable description of the folder, for the "show folder path" dialog. */
    static String displayPath(Context c) {
        String tree = Prefs.treeUri(c);
        DocumentFile r = root(c);
        if (tree != null && r.getUri().toString().equals(tree)) {
            String path = Uri.parse(tree).getPath();
            if (path != null && path.startsWith("/tree/")) {
                path = path.substring("/tree/".length());
            }
            return path == null ? tree : path;
        }
        File base = c.getExternalFilesDir(null);
        if (base == null) {
            base = c.getFilesDir();
        }
        return new File(base, "gallery").getAbsolutePath();
    }

    /** Sub-folders of a folder, by name (trash and hidden folders excluded). */
    static List<DocumentFile> folders(DocumentFile dir) {
        List<DocumentFile> out = new ArrayList<DocumentFile>();
        for (DocumentFile f : dir.listFiles()) {
            String name = f.getName();
            if (name != null && f.isDirectory() && !name.startsWith(".")) {
                out.add(f);
            }
        }
        Collections.sort(out, new Comparator<DocumentFile>() {
            @Override
            public int compare(DocumentFile a, DocumentFile b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return out;
    }

    /** Creates a sub-folder with a free name; null if that fails. */
    static DocumentFile createFolder(DocumentFile dir, String baseName) {
        String name = baseName;
        int n = 1;
        while (dir.findFile(name) != null) {
            n++;
            name = baseName + " " + n;
        }
        return dir.createDirectory(name);
    }

    /** Deletes a folder together with everything in it. */
    static boolean deleteFolder(DocumentFile dir) {
        return dir.delete();
    }

    /** Counts of what is inside a folder, including all sub-folders. */
    static final class Stats {
        int drawings;
        int folders;
        long bytes;
    }

    static Stats stats(DocumentFile dir) {
        Stats s = new Stats();
        collect(dir, s, true);
        return s;
    }

    private static void collect(DocumentFile dir, Stats s, boolean top) {
        for (DocumentFile f : dir.listFiles()) {
            String name = f.getName();
            if (name == null) {
                continue;
            }
            if (f.isDirectory()) {
                s.folders++;
                collect(f, s, false);
            } else {
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (lower.endsWith(".ora") || lower.endsWith(".png")) {
                    s.drawings++;
                    s.bytes += f.length();
                }
            }
        }
    }

    static List<Item> list(DocumentFile dir, boolean trash) {
        List<Item> items = new ArrayList<Item>();
        DocumentFile[] files = dir.listFiles();
        for (DocumentFile f : files) {
            String name = f.getName();
            if (name == null || !f.isFile()) {
                continue;
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".ora.bin") && !trash) {
                // Earlier versions saved drawings with a stray ".bin" ending; fix it.
                if (f.renameTo(name.substring(0, name.length() - 4))) {
                    name = name.substring(0, name.length() - 4);
                    lower = lower.substring(0, lower.length() - 4);
                }
            }
            boolean deleted = lower.endsWith(TRASH);
            if (deleted != trash) {
                continue;
            }
            String base = deleted ? lower.substring(0, lower.length() - TRASH.length()) : lower;
            if (base.endsWith(".ora") || base.endsWith(".png")) {
                items.add(new Item(f, name));
            }
        }
        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Long.compare(b.modified, a.modified);
            }
        });
        return items;
    }

    /** Creates an empty file with a name that is not taken yet; null if that fails. */
    static DocumentFile create(DocumentFile root, String baseName) {
        String name = baseName + ".ora";
        int n = 1;
        while (root.findFile(name) != null || root.findFile(name + TRASH) != null) {
            n++;
            name = baseName + " " + n + ".ora";
        }
        // An unknown type keeps the name as it is; "octet-stream" would add ".bin" to it.
        DocumentFile file = root.createFile("image/openraster", name);
        if (file != null && file.getName() != null && !file.getName().equals(name)) {
            file.renameTo(name);
        }
        return file;
    }

    static boolean softDelete(Item item) {
        return item.file.renameTo(item.name + TRASH);
    }

    static boolean restore(DocumentFile root, Item item) {
        String target = item.name.substring(0, item.name.length() - TRASH.length());
        if (root.findFile(target) != null) {
            int dot = target.lastIndexOf('.');
            String stem = dot > 0 ? target.substring(0, dot) : target;
            String ext = dot > 0 ? target.substring(dot) : "";
            int n = 2;
            while (root.findFile(stem + " " + n + ext) != null) {
                n++;
            }
            target = stem + " " + n + ext;
        }
        return item.file.renameTo(target);
    }

    static boolean deleteForever(Item item) {
        return item.file.delete();
    }

    /** Moves a drawing into another folder by copying it and removing the original. */
    static boolean move(Context c, Item item, DocumentFile target) {
        String name = item.name;
        String stem = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            stem = name.substring(0, dot);
            ext = name.substring(dot);
        }
        String free = name;
        int n = 1;
        while (target.findFile(free) != null) {
            n++;
            free = stem + " " + n + ext;
        }
        DocumentFile copy = target.createFile(
                ext.equalsIgnoreCase(".png") ? "image/png" : "image/openraster", free);
        if (copy == null) {
            return false;
        }
        if (copy.getName() != null && !copy.getName().equals(free)) {
            copy.renameTo(free);
        }
        try (java.io.InputStream in = c.getContentResolver().openInputStream(item.uri());
                java.io.OutputStream out = c.getContentResolver().openOutputStream(copy.getUri(), "wt")) {
            if (in == null || out == null) {
                copy.delete();
                return false;
            }
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
        } catch (java.io.IOException | RuntimeException e) {
            copy.delete();
            return false;
        }
        return item.file.delete();
    }
}
