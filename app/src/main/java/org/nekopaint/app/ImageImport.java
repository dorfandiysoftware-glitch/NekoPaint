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

    /** Decodes a picture and fits it inside w x h, centred, as straight ARGB pixels; null on failure. */
    static int[] fitToCanvas(Context c, Uri uri, int w, int h) {
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
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap src;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                src = in == null ? null : BitmapFactory.decodeStream(in, null, opts);
            }
            if (src == null) {
                return null;
            }
            float k = Math.min(w / (float) src.getWidth(), h / (float) src.getHeight());
            float dw = src.getWidth() * k;
            float dh = src.getHeight() * k;
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(out);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
            cv.drawBitmap(src, null, new android.graphics.RectF((w - dw) / 2f, (h - dh) / 2f,
                    (w + dw) / 2f, (h + dh) / 2f), p);
            src.recycle();
            int[] px = new int[w * h];
            out.getPixels(px, 0, w, 0, 0, w, h);
            out.recycle();
            return px;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Decodes a picture, shrunk to fit inside w x h if it is bigger; null on failure. */
    static Bitmap decodeFit(Context c, Uri uri, int w, int h) {
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
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap src;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                src = in == null ? null : BitmapFactory.decodeStream(in, null, opts);
            }
            if (src == null) {
                return null;
            }
            float k = Math.min(1f, Math.min(w / (float) src.getWidth(), h / (float) src.getHeight()));
            if (k < 1f) {
                Bitmap scaled = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(src.getWidth() * k)),
                        Math.max(1, Math.round(src.getHeight() * k)), true);
                if (scaled != src) {
                    src.recycle();
                }
                src = scaled;
            }
            return src;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }
}
