// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import nekopaint.core.OpenRaster;
import nekopaint.core.PngCodec;

/** Loads the small preview pictures of drawings in the background. */
final class ThumbLoader {

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(64);

    ThumbLoader(Context context) {
        this.context = context.getApplicationContext();
    }

    void load(GalleryStore.Item item, ImageView target) {
        final String key = item.uri() + "@" + item.modified;
        target.setTag(key);
        Bitmap hit = cache.get(key);
        if (hit != null) {
            target.setImageBitmap(hit);
            return;
        }
        target.setImageDrawable(null);
        final GalleryStore.Item it = item;
        final ImageView view = target;
        pool.execute(() -> {
            Bitmap bitmap = decode(it);
            if (bitmap == null) {
                return;
            }
            cache.put(key, bitmap);
            main.post(() -> {
                if (key.equals(view.getTag())) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }

    void shutdown() {
        pool.shutdownNow();
    }

    private Bitmap decode(GalleryStore.Item item) {
        try {
            if (item.isPng()) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream in = context.getContentResolver().openInputStream(item.uri())) {
                    BitmapFactory.decodeStream(in, null, bounds);
                }
                int sample = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) {
                    sample *= 2;
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                try (InputStream in = context.getContentResolver().openInputStream(item.uri())) {
                    return BitmapFactory.decodeStream(in, null, o);
                }
            }
            try (InputStream in = context.getContentResolver().openInputStream(item.uri())) {
                PngCodec.Image image = OpenRaster.readThumbnail(in);
                if (image == null) {
                    return null;
                }
                return Bitmap.createBitmap(image.toStraightArgb(), image.width, image.height,
                        Bitmap.Config.ARGB_8888);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
