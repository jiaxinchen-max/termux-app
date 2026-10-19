package com.termux.localgames.activity;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Keeps a screen-edge view clear of the system bars and the display cutout.
 *
 * <p>With the current theme (opaque bars, targetSdk 28) the system already fits the window between
 * the bars and delivers no insets to the content, so this is a no-op there. It matters when a
 * window is laid out behind the bars (edge-to-edge, cutout "short edges", freeform windows): the
 * view's own padding is kept and the insets are added on top, only on the requested sides.
 */
final class WindowInsetsPadding {

    private WindowInsetsPadding() {}

    static void apply(@NonNull View view, boolean left, boolean top, boolean right,
                      boolean bottom) {
        final int initialLeft = view.getPaddingLeft();
        final int initialTop = view.getPaddingTop();
        final int initialRight = view.getPaddingRight();
        final int initialBottom = view.getPaddingBottom();
        OnApplyWindowInsetsListener listener = (target, insets) -> {
            Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            target.setPadding(
                initialLeft + (left ? bars.left : 0),
                initialTop + (top ? bars.top : 0),
                initialRight + (right ? bars.right : 0),
                initialBottom + (bottom ? bars.bottom : 0));
            return insets;
        };
        ViewCompat.setOnApplyWindowInsetsListener(view, listener);
        ViewCompat.requestApplyInsets(view);
    }
}
