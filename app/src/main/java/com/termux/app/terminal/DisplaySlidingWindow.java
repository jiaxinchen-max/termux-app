package com.termux.app.terminal;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.os.Build.VERSION.SDK_INT;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import com.nineoldandroids.view.ViewHelper;
import com.termux.app.terminal.utils.ScreenUtils;

public class DisplaySlidingWindow extends HorizontalScrollView {
    private int mMenuWidth;
    private int mHalfMenuWidth;
    private boolean isOperateRight;
    private boolean isOperateLeft;
    private boolean once;
    private ViewGroup mLeftMenu;
    private ViewGroup mContent;
    private ViewGroup mRightMenu;
    private ViewGroup mWrapper;
    private boolean isLeftMenuOpen;
    private boolean isRightMenuOpen;

    /**
     * listener for menu changed
     */
    public interface OnMenuChangeListener {
        /**
         * @param isOpen true open menu，false close menu
         * @param flag   0 left， 1 right
         */
        void onMenuOpen(boolean isOpen, int flag);

        boolean sendTouchEvent(MotionEvent ev);

        void onEdgeReached();
    }

    public OnMenuChangeListener mOnMenuChangeListener;

    public void setOnMenuOpenListener(OnMenuChangeListener mOnMenuChangeListener) {
        this.mOnMenuChangeListener = mOnMenuChangeListener;
    }

    public DisplaySlidingWindow(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /**
     * content width
     */
    private int mContentWidth;
    private int mScreenWidth;
    private int mScreenHeight;
    public static boolean landscape = false;
    /**
     * dp menu padding from screen edge
     */
    private int mMenuRightPadding;
    private int verticalPadding;

    public boolean isSwitchSlider() {
        return switchSlider;
    }

    private boolean switchSlider;
    private float downX, downY;
    private boolean moving;
    private int statusHeight;
    public boolean hideCutout = false;

    public static void setLandscape(boolean isLandscape) {
        DisplaySlidingWindow.landscape = isLandscape;
    }

    public DisplaySlidingWindow(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        setClickable(true);
        switchSlider = true;
        mScreenWidth = ScreenUtils.getScreenWidth(context);
        mScreenHeight = ScreenUtils.getScreenHeight(context);
        statusHeight = ScreenUtils.getStatusHeight(context);


//        TypedArray a = context.getTheme().obtainStyledAttributes(attrs,
//            R.styleable.BinarySlidingMenu, defStyle, 0);
//        int n = a.getIndexCount();
//        for (int i = 0; i < n; i++) {
//            int attr = a.getIndex(i);
//            if (attr == R.styleable.BinarySlidingMenu_rightPadding) {
//                mMenuRightPadding = a.getDimensionPixelSize(attr,
//                    (int) TypedValue.applyDimension(
//                        TypedValue.COMPLEX_UNIT_DIP, 50f,
//                        getResources().getDisplayMetrics()));// 默认为10DP
//                break;
//            }
//        }
//        a.recycle();
        if (landscape) {
            mContentWidth = mScreenHeight;
            mMenuRightPadding = mContentWidth * 3 / 5;
        } else {
            verticalPadding = mMenuRightPadding;
            mContentWidth = mScreenWidth;
        }
    }

    public DisplaySlidingWindow(Context context) {
        this(context, null, 0);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        /**
         * measure width of content and menu
         */
        if (!once) {
            remeasure();
            mWrapper = (LinearLayout) getChildAt(0);
            mLeftMenu = (ViewGroup) mWrapper.getChildAt(0);
            mContent = (ViewGroup) mWrapper.getChildAt(1);
            mRightMenu = (ViewGroup) mWrapper.getChildAt(2);

            if (SDK_INT == Build.VERSION_CODES.S && landscape) {
                if(hideCutout){
                    ViewHelper.setTranslationX(mWrapper, statusHeight);
                    ViewHelper.setTranslationX(mLeftMenu, -statusHeight);
                    ViewHelper.setTranslationX(mRightMenu, -statusHeight);
                    mContentWidth -= statusHeight*2;
                }else{
                    if(mWrapper.getTranslationX()>0){
                        ViewHelper.setTranslationX(mWrapper, 0);
                    }
                    if(mLeftMenu.getTranslationX()<0){
                        ViewHelper.setTranslationX(mLeftMenu, 0);
                    }
                    if(mRightMenu.getTranslationX()<0){
                        ViewHelper.setTranslationX(mRightMenu, 0);
                    }
                }
            }
            mMenuWidth = mContentWidth - mMenuRightPadding;
            mHalfMenuWidth = mMenuWidth / 2;
            mLeftMenu.getLayoutParams().width = mMenuWidth;
            mContent.getLayoutParams().width = mContentWidth;
            mRightMenu.getLayoutParams().width = mMenuWidth;

//            Log.d("changeLayoutOrientation", "landscape:" + String.valueOf(landscape) + ", mContentWidth" + ":" + String.valueOf(mContentWidth) + ",mScreenHeight:" + String.valueOf(mScreenHeight) + ",mScreenWidth:" + String.valueOf(mScreenWidth));
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void remeasure() {
        if (landscape) {
            mContentWidth = mScreenHeight > mScreenWidth ? mScreenHeight : mScreenWidth;
            mMenuRightPadding = mContentWidth * 3 / 5;
            if (hideCutout) {
                mContentWidth += statusHeight;
            }
        } else {
            mContentWidth = mScreenWidth < mScreenHeight ? mScreenWidth : mScreenHeight;
            mMenuRightPadding = verticalPadding;
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed) {
            // hide menu at start up
            this.scrollTo(mMenuWidth, 0);
        }
        if (SDK_INT == Build.VERSION_CODES.S && landscape && hideCutout){
            ViewHelper.setTranslationX(mRightMenu,0);
        }
        once = true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
//        Log.d("onInterceptTouchEvent",String.valueOf(ev.getAction()));
        if (!switchSlider) {
            mOnMenuChangeListener.sendTouchEvent(ev);
            return false;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override

    public boolean onTouchEvent(MotionEvent ev) {
//        Log.d("onTouchEvent",String.valueOf(ev.getAction()));
        int action = ev.getAction();
        switch (action) {
            case MotionEvent.ACTION_MOVE: {
                if (!moving) {
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    moving = true;
                }
                break;
            }
        }
        switch (action) {
            // open menu if scroll to distance that more than half menu width
            case MotionEvent.ACTION_UP:
                int scrollX = getScrollX();
                moving = false;
                float dx = ev.getRawX() - downX;
                float dy = ev.getRawY() - downY;
                if (scrollX <= 0) {
                    if (dx > mMenuWidth * 0.6 && Math.abs(dx) > Math.abs(dy)) {
                        mOnMenuChangeListener.onEdgeReached();
                    }
                }
                int offset = hideCutout ? statusHeight : 0;
                //operate left
                if (isOperateLeft) {
                    // area hidden more than half of menu width close it
                    if (scrollX > mHalfMenuWidth) {
                        this.smoothScrollTo(mMenuWidth, 0);
                        if (SDK_INT == Build.VERSION_CODES.S && landscape && hideCutout){
                            ViewHelper.setTranslationX(mLeftMenu,-offset);
                        }
                        //notify listener that left meun opened
                        if (isLeftMenuOpen) {
                            mOnMenuChangeListener.onMenuOpen(false, 0);
                        }
                        isLeftMenuOpen = false;
                        switchSlider = false;
                    } else//open left menu
                    {
                        this.smoothScrollTo(0, 0);
                        if (SDK_INT == Build.VERSION_CODES.S && landscape && hideCutout){
                            ViewHelper.setTranslationX(mLeftMenu,0);
                        }
                        if (!isLeftMenuOpen) {
                            mOnMenuChangeListener.onMenuOpen(true, 0);
                        }
                        isLeftMenuOpen = true;
                    }
                }
                //operate right
                if (isOperateRight) {
                    if (scrollX > mHalfMenuWidth + mMenuWidth) {
                        this.smoothScrollTo(mMenuWidth + mMenuWidth + offset, 0);
                        if (SDK_INT == Build.VERSION_CODES.S && landscape && hideCutout){
                            ViewHelper.setTranslationX(mRightMenu,-offset);
                        }
                        if (!isRightMenuOpen) {
                            mOnMenuChangeListener.onMenuOpen(true, 1);
                        }
                        isRightMenuOpen = true;
//					mRightMenu.bringToFront();
                    } else//close right menu
                    {
                        this.smoothScrollTo(mMenuWidth, 0);
                        if (SDK_INT == Build.VERSION_CODES.S && landscape && hideCutout){
                            ViewHelper.setTranslationX(mRightMenu,0);
                        }
                        if (isRightMenuOpen) {
                            mOnMenuChangeListener.onMenuOpen(false, 1);
                        }
                        isRightMenuOpen = false;
                        switchSlider = false;
                    }
                }
                return false;
        }
        if (switchSlider) {
            super.onTouchEvent(ev);
        } else {
//           return mOnMenuChangeListener.sendTouchEvent(ev);
            return false;
        }
        return false;
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
//        Log.d("onScrollChanged","l:"+l+", t:"+t+",oldl:"+oldl+",oldt:"+oldt);
        if (l > mMenuWidth) {
            isOperateRight = true;
            isOperateLeft = false;
        } else {
            isOperateRight = false;
            isOperateLeft = true;
        }
        float scale = l * 1.0f / mMenuWidth;
        ViewHelper.setTranslationX(mContent, mMenuWidth * (scale - 1));
    }

    public void setX11PreferenceSwitchSlider(boolean openSlider) {
        this.switchSlider = openSlider;
        if (!openSlider) {
            this.smoothScrollTo(mMenuWidth, 0);
        } else {
            this.smoothScrollTo(mMenuWidth + mMenuWidth, 0);
        }
    }

    public void setTerminalViewSwitchSlider(boolean openSlider) {
        this.switchSlider = openSlider;
        if (!openSlider) {
            this.smoothScrollTo(mMenuWidth, 0);
        } else {
            this.smoothScrollTo(0, 0);
        }
    }

    public void changeLayoutOrientation(int landscapeOriention) {
        once = false;
        landscape = landscapeOriention == SCREEN_ORIENTATION_LANDSCAPE;
        requestLayout();
        requestFocus();
        showContent();
    }

    public void releaseSlider(boolean open) {
        this.switchSlider = open;
    }

    public void setHideCutout(boolean hide) {
        hideCutout = hide;
        requestLayout();
        requestFocus();
    }
    public void showContent(){
        this.scrollTo(mMenuWidth, 0);
    }
}
