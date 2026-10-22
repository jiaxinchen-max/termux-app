package com.termux.ui.activity;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * LinearLayout whose wrap_content height can be capped at {@link OverlayDrawerSizing}'s bottom-sheet
 * share of the height its parent offers (weighted, scrollable children give way first). The cap is
 * off by default, so the side drawer keeps measuring exactly like a plain LinearLayout.
 */
public final class CappedHeightLinearLayout extends LinearLayout {

    private boolean capHeight;

    public CappedHeightLinearLayout(Context context) {
        super(context);
    }

    public CappedHeightLinearLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CappedHeightLinearLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    void setCapHeight(boolean capHeight) {
        if (this.capHeight == capHeight) return;
        this.capHeight = capHeight;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (capHeight && View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.UNSPECIFIED) {
            int offered = View.MeasureSpec.getSize(heightMeasureSpec);
            heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(
                Math.min(offered, OverlayDrawerSizing.bottomSheetMaxHeightPx(offered)),
                View.MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
