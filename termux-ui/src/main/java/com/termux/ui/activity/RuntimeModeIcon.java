package com.termux.ui.activity;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.termux.ui.R;

/** Filled, overlapping runtime symbols. The active mode is drawn last, in front. */
public final class RuntimeModeIcon extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cube = new Path();
    private final boolean proot;
    private final int foreground, background, rear, size;
    private int alpha = 255;

    public RuntimeModeIcon(Context context, boolean proot) {
        this.proot = proot;
        background = ContextCompat.getColor(context, R.color.local_games_background);
        foreground = ContextCompat.getColor(context, R.color.local_games_primary_text);
        rear = ColorUtils.blendARGB(background,
            ContextCompat.getColor(context, R.color.local_games_on_surface_secondary), 0.55f);
        size = Math.round(32 * context.getResources().getDisplayMetrics().density);
        cube.moveTo(12, 1);
        cube.lineTo(22, 6.5f);
        cube.lineTo(22, 17.5f);
        cube.lineTo(12, 23);
        cube.lineTo(2, 17.5f);
        cube.lineTo(2, 6.5f);
        cube.close();
    }

    @Override public void draw(@NonNull Canvas canvas) {
        int save = canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 32f, getBounds().height() / 32f);
        drawMode(canvas, !proot, rear);
        drawMode(canvas, proot, foreground);
        canvas.restoreToCount(save);
    }

    private void drawMode(Canvas canvas, boolean container, int tint) {
        int save = canvas.save();
        if (container) canvas.translate(9, 9);
        canvas.scale(23f / 24f, 23f / 24f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(tint);
        paint.setAlpha(alpha);
        if (container) {
            canvas.drawPath(cube, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            paint.setColor(background);
            paint.setAlpha(alpha);
            canvas.drawLine(12, 12, 12, 23, paint);
            canvas.drawLine(12, 12, 22, 6.5f, paint);
            canvas.drawLine(12, 12, 2, 6.5f, paint);
        } else {
            canvas.drawRoundRect(4.5f, 4.5f, 19.5f, 19.5f, 2, 2, paint);
            paint.setStrokeWidth(2.4f);
            for (int pin = 7; pin <= 17; pin += 5) {
                canvas.drawLine(pin, 1, pin, 5, paint);
                canvas.drawLine(pin, 19, pin, 23, paint);
                canvas.drawLine(1, pin, 5, pin, paint);
                canvas.drawLine(19, pin, 23, pin, paint);
            }
            paint.setColor(background);
            paint.setAlpha(alpha);
            canvas.drawRoundRect(9, 9, 15, 15, 0.5f, 0.5f, paint);
        }
        canvas.restoreToCount(save);
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
    @Override public void setColorFilter(@Nullable ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
