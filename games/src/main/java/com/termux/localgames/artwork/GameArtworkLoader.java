package com.termux.localgames.artwork;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

/** Samples private covers to the rendered size instead of decoding full-resolution images. */
public final class GameArtworkLoader {

    private final GameArtworkStore store;

    public GameArtworkLoader(GameArtworkStore store) {
        this.store = store;
    }

    public Bitmap load(String reference, int targetPixels) {
        if (targetPixels <= 0) throw new IllegalArgumentException("targetPixels must be positive");
        File file = store.resolve(reference);
        if (file == null || !file.isFile()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth > GameArtworkStore.MAX_IMAGE_EDGE ||
                bounds.outHeight > GameArtworkStore.MAX_IMAGE_EDGE) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / (options.inSampleSize * 2) >= targetPixels) {
                options.inSampleSize *= 2;
            }
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError | RuntimeException error) {
            return null;
        }
    }
}
