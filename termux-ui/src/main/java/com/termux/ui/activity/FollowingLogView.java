package com.termux.ui.activity;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.termux.ui.R;

/** Follows new output after layout; browsing older output pauses following until the bottom. */
class FollowingLogView extends ScrollView {
    private final TextView output;
    private boolean following = true;
    private boolean touching;
    private boolean computingScroll;
    private final Runnable scrollToLatest = this::scrollToLatest;

    private void scrollToLatest() {
        if (following && !touching) scrollTo(0, Math.max(0,
            output.getHeight() - getHeight() + getPaddingTop() + getPaddingBottom()));
    }

    FollowingLogView(Context context) {
        super(context);
        output = new TextView(context);

        output.setTextSize(13);
        output.setTypeface(Typeface.MONOSPACE);
        int padding = Math.round(8 * getResources().getDisplayMetrics().density);
        output.setPadding(padding, padding, padding, padding);
        output.setTextIsSelectable(true);
        addView(output);
        setFillViewport(true);
        applyTheme();
    }

    void applyTheme() {
        output.setTextColor(ContextCompat.getColor(getContext(), R.color.local_games_terminal_text));
        output.setHighlightColor(ContextCompat.getColor(getContext(), R.color.local_games_nav_indicator));
    }

    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applyTheme();
    }

    void setOutput(CharSequence text) {
        if (text.toString().contentEquals(output.getText())) return;
        output.setText(text);
        // setText can retain the same child bounds (for example a full bounded log tail).
        postFollow();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // A post directly after setText may run before measurement has seen the new lines.
        postFollow();
    }

    private void postFollow() {
        removeCallbacks(scrollToLatest);
        if (following && !touching) post(scrollToLatest);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) touching = true;
        boolean handled = super.dispatchTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP
            || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            following = !canScrollVertically(1);
            touching = false;
        }
        return handled;
    }

    @Override
    protected void onScrollChanged(int x, int y, int oldX, int oldY) {
        super.onScrollChanged(x, y, oldX, oldY);
        // Includes the fling after the finger is released. Layout-induced clamping must not
        // change following: resizing and a shrinking log tail are not user scrolling.
        if (touching || computingScroll) {
            following = !canScrollVertically(1);
        }
    }

    @Override
    public void computeScroll() {
        computingScroll = true;
        super.computeScroll();
        computingScroll = false;
    }
}
