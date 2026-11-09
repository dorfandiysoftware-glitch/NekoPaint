// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;

import nekopaint.core.Document;
import nekopaint.core.Layer;

/** Turns a picture from the phone (PNG, JPEG, WebP...) into a one-layer document. */
final class ImageImport {

    private ImageImport() {
    }

    /** @return the document, or null if the picture cannot be read */
    static Document load(Context c, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    return null;
                }
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth < 1 || bounds.outHeight < 1) {
                return null;
            }
            int sample = 1;
            while (!Prefs.sizeOk(bounds.outWidth / sample, bounds.outHeight / sample)) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    return null;
                }
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) {
                return null;
            }
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            if (!Prefs.sizeOk(w, h)) {
                bmp.recycle();
                return null;
            }
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            bmp.recycle();
            Document doc = new Document(w, h);
            Layer layer = doc.addLayer("Layer 1");
            layer.setStraightArgb(px);
            return doc;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }
}
