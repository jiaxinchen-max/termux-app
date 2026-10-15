package com.termux.x11.controller.inputcontrols;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.termux.x11.controller.core.FileUtils;
import com.termux.x11.controller.core.ImageUtils;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public abstract class IconPackUtils {
    public static ArrayList<Uri> getSelectedUris(Intent data) {
        ArrayList<Uri> uris = new ArrayList<>();
        if (data == null) return uris;

        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        }
        else if (data.getData() != null) uris.add(data.getData());
        return uris;
    }

    public static ArrayList<IconPackManager.SourceIcon> loadSourceIcons(Context context, List<Uri> uris, int targetSize) {
        ArrayList<IconPackManager.SourceIcon> icons = new ArrayList<>();
        if (uris == null) return icons;

        for (Uri uri : uris) {
            Bitmap bitmap = ImageUtils.getBitmapFromUri(context, uri, targetSize);
            if (bitmap == null) continue;

            String displayName = getDisplayName(context, uri);
            Bitmap normalizedBitmap = normalizeIconBitmap(bitmap, targetSize);
            byte[] bytes = encodeBitmapToBytes(normalizedBitmap);
            if (bytes.length == 0) continue;

            icons.add(new IconPackManager.SourceIcon(displayName, uri.toString(), 0, bytes));
        }
        return icons;
    }

    public static String getDisplayName(Context context, Uri uri) {
        if (context == null || uri == null) return "";
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) return cursor.getString(idx);
                }
            }
        }

        String path = uri.getPath();
        return path != null ? FileUtils.getName(path) : "";
    }

    public static Bitmap normalizeIconBitmap(Bitmap bitmap, int size) {
        Bitmap normalizedBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(normalizedBitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float scale = Math.min((float)size / bitmap.getWidth(), (float)size / bitmap.getHeight());
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        float left = (size - width) * 0.5f;
        float top = (size - height) * 0.5f;
        canvas.drawBitmap(bitmap, null, new RectF(left, top, left + width, top + height), paint);
        return normalizedBitmap;
    }

    public static byte[] encodeBitmapToBytes(Bitmap bitmap) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
        return outputStream.toByteArray();
    }

    public static int[] getBitmapSize(byte[] bytes) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        return new int[]{options.outWidth, options.outHeight};
    }
}