package com.termux.localgames.activity;

import android.content.Context;
import android.content.res.Configuration;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Content root for self-sized dialogs. The library screens handle rotation themselves (they are
 * not recreated), so a dialog that sets its window size in pixels must be fitted again when the
 * configuration changes; the framework delivers that change to the dialog's view tree, and this
 * root forwards it.
 */
final class ConfigurationAwareFrameLayout extends FrameLayout {

    interface Listener {
        void onConfigurationChanged(@NonNull Configuration configuration);
    }

    @Nullable private Listener listener;
    private int maxHeightPx;

    ConfigurationAwareFrameLayout(@NonNull Context context) {
        super(context);
    }

    void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /** Upper bound for this root's height (a dialog never taller than its window); 0 = none. */
    void setMaxHeightPx(int maxHeightPx) {
        if (this.maxHeightPx == maxHeightPx) return;
        this.maxHeightPx = maxHeightPx;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (maxHeightPx > 0) {
            int mode = MeasureSpec.getMode(heightMeasureSpec);
            int size = MeasureSpec.getSize(heightMeasureSpec);
            int bounded = mode == MeasureSpec.UNSPECIFIED ? maxHeightPx : Math.min(size, maxHeightPx);
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(bounded, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (listener != null) listener.onConfigurationChanged(newConfig);
    }
}
