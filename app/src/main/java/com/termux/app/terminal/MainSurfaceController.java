package com.termux.app.terminal;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.termux.view.TerminalView;
import com.termux.x11.TermuxScreenView;

public final class MainSurfaceController {
    private static final int INTERNAL_DRAWER_EDGE_INSET_DP = 24;
    private static final int INTERNAL_DRAWER_HOT_ZONE_WIDTH_DP = 72;
    private static final int INTERNAL_DRAWER_MIN_DISTANCE_DP = 96;
    private static final int INTERNAL_DRAWER_MAX_DISTANCE_DP = 220;
    private static final float INTERNAL_DRAWER_SHORT_SIDE_DISTANCE_RATIO = 0.24f;
    private static final long SURFACE_TRANSITION_ANIMATION_MS = 180;

    public enum SurfaceMode {
        TERMINAL,
        DISPLAY
    }

    public interface SurfaceGestureListener {
        void onTerminalEndSwipe();
        void onDisplayStartSwipe();
        void onDisplayEndSwipe();
    }

    @NonNull
    private final DrawerLayout mDrawerLayout;
    @NonNull
    private final FrameLayout mContainer;
    @NonNull
    private final TerminalView mTerminalView;
    @Nullable
    private TermuxScreenView mDisplayView;
    @NonNull
    private SurfaceMode mMode = SurfaceMode.TERMINAL;
    private boolean mTerminalCopyMode;
    private boolean mDisplayFloatBallMenuEnabled;
    private boolean mDisplaySidePanelsUnlocked;
    private int mTrackingInternalDrawerGravity;
    private float mInternalDrawerSwipeDownX;
    private float mInternalDrawerSwipeDownY;
    private final int mInternalDrawerEdgeInset;
    private final int mInternalDrawerHotZoneWidth;
    private final int mInternalDrawerMinDistance;
    private final int mInternalDrawerMaxDistance;
    @Nullable
    private DrawerLayout.DrawerListener mRestoreLockModeOnCloseListener;
    @Nullable
    private SurfaceGestureListener mSurfaceGestureListener;
    private int mSurfaceAnimationGeneration;
    @NonNull
    private final DecelerateInterpolator mSurfaceTransitionInterpolator = new DecelerateInterpolator();

    public MainSurfaceController(@NonNull DrawerLayout drawerLayout,
                                 @NonNull FrameLayout container,
                                 @NonNull TerminalView terminalView) {
        mDrawerLayout = drawerLayout;
        mContainer = container;
        mTerminalView = terminalView;

        float density = container.getResources().getDisplayMetrics().density;
        ViewConfiguration viewConfiguration = ViewConfiguration.get(container.getContext());
        mInternalDrawerEdgeInset = Math.round(INTERNAL_DRAWER_EDGE_INSET_DP * density);
        mInternalDrawerHotZoneWidth = Math.round(INTERNAL_DRAWER_HOT_ZONE_WIDTH_DP * density);
        mInternalDrawerMinDistance = Math.max(
            Math.round(INTERNAL_DRAWER_MIN_DISTANCE_DP * density),
            viewConfiguration.getScaledTouchSlop() * 4);
        mInternalDrawerMaxDistance = Math.round(INTERNAL_DRAWER_MAX_DISTANCE_DP * density);

        applyMode();
    }

    public void attachDisplayView(@NonNull TermuxScreenView displayView) {
        if (mDisplayView == displayView)
            return;

        if (displayView.getParent() != mContainer) {
            if (displayView.getParent() instanceof ViewGroup)
                ((ViewGroup) displayView.getParent()).removeView(displayView);
            mContainer.addView(displayView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        }

        mDisplayView = displayView;
        applyMode();
    }

    public void detachDisplayView() {
        if (mDisplayView != null && mDisplayView.getParent() == mContainer)
            mContainer.removeView(mDisplayView);
        mDisplayView = null;
        if (mMode == SurfaceMode.DISPLAY)
            showTerminal();
    }

    public void showTerminal() {
        SurfaceMode previousMode = mMode;
        mMode = SurfaceMode.TERMINAL;
        applyMode(previousMode == SurfaceMode.DISPLAY, previousMode);
        mTerminalView.requestFocus();
    }

    public void showDisplay() {
        if (mDisplayView == null)
            return;
        SurfaceMode previousMode = mMode;
        mMode = SurfaceMode.DISPLAY;
        applyMode(previousMode == SurfaceMode.TERMINAL, previousMode);
        mDisplayView.getLorieView().requestFocus();
    }

    @NonNull
    public SurfaceMode getMode() {
        return mMode;
    }

    public boolean isDisplayMode() {
        return mMode == SurfaceMode.DISPLAY;
    }

    public void setTerminalCopyMode(boolean copyMode) {
        mTerminalCopyMode = copyMode;
        applyDrawerLockMode();
    }

    public void setSurfaceGestureListener(@Nullable SurfaceGestureListener listener) {
        mSurfaceGestureListener = listener;
    }

    public void setDisplaySidePanelPolicy(boolean floatBallMenuEnabled, boolean sidePanelsUnlocked) {
        if (mDisplayFloatBallMenuEnabled == floatBallMenuEnabled
            && mDisplaySidePanelsUnlocked == sidePanelsUnlocked)
            return;
        mDisplayFloatBallMenuEnabled = floatBallMenuEnabled;
        mDisplaySidePanelsUnlocked = sidePanelsUnlocked;
        applyDrawerLockMode();
    }

    public void openStartDrawerExplicitly() {
        mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START);
        ensureRestoreLockModeOnCloseListener();
        mDrawerLayout.openDrawer(GravityCompat.START);
    }

    public void openEndDrawerExplicitly() {
        mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.END);
        ensureRestoreLockModeOnCloseListener();
        mDrawerLayout.openDrawer(GravityCompat.END);
    }

    public void toggleStartDrawerExplicitly() {
        if (mDrawerLayout.isDrawerOpen(GravityCompat.START))
            mDrawerLayout.closeDrawer(GravityCompat.START);
        else
            openStartDrawerExplicitly();
    }

    public void restoreDrawerLockMode() {
        applyDrawerLockMode();
    }

    public void openCurrentSurfaceDrawerExplicitly() {
        if (mMode == SurfaceMode.TERMINAL) {
            openStartDrawerExplicitly();
        } else if (mMode == SurfaceMode.DISPLAY && mDisplayView != null) {
            openEndDrawerExplicitly();
        }
    }

    public void handleInternalDrawerSwipe(@NonNull MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTrackingInternalDrawerGravity = getInternalDrawerSwipeGravity(event);
                mInternalDrawerSwipeDownX = event.getRawX();
                mInternalDrawerSwipeDownY = event.getRawY();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                mTrackingInternalDrawerGravity = 0;
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                if (mTrackingInternalDrawerGravity == 0)
                    return;
                if (shouldOpenDrawerFromInternalSwipe(event, mTrackingInternalDrawerGravity)) {
                    int drawerGravity = mTrackingInternalDrawerGravity;
                    mTrackingInternalDrawerGravity = 0;
                    handleInternalDrawerSwipeAction(drawerGravity);
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    mTrackingInternalDrawerGravity = 0;
                }
                break;
        }
    }

    private void applyMode() {
        applyMode(false, mMode);
    }

    private void applyMode(boolean animate, @NonNull SurfaceMode previousMode) {
        if (animate && previousMode != mMode && mDisplayView != null && mContainer.getWidth() > 0) {
            animateModeChange();
            applyDrawerLockMode();
            return;
        }

        cancelSurfaceAnimations();
        mTerminalView.setVisibility(mMode == SurfaceMode.TERMINAL ? View.VISIBLE : View.GONE);
        mTerminalView.setTranslationX(0);
        if (mDisplayView != null)
            mDisplayView.setVisibility(mMode == SurfaceMode.DISPLAY ? View.VISIBLE : View.GONE);
        if (mDisplayView != null)
            mDisplayView.setTranslationX(0);
        applyDrawerLockMode();
    }

    private void animateModeChange() {
        if (mDisplayView == null)
            return;

        int width = mContainer.getWidth();
        View incomingView = mMode == SurfaceMode.TERMINAL ? mTerminalView : mDisplayView;
        View outgoingView = mMode == SurfaceMode.TERMINAL ? mDisplayView : mTerminalView;
        float incomingStartX = mMode == SurfaceMode.TERMINAL ? -width : width;
        float outgoingEndX = mMode == SurfaceMode.TERMINAL ? width : -width;
        int animationGeneration = ++mSurfaceAnimationGeneration;

        mTerminalView.animate().cancel();
        mDisplayView.animate().cancel();

        incomingView.setVisibility(View.VISIBLE);
        incomingView.setTranslationX(incomingStartX);
        incomingView.bringToFront();

        outgoingView.setVisibility(View.VISIBLE);
        outgoingView.setTranslationX(0);

        incomingView.animate()
            .translationX(0)
            .setDuration(SURFACE_TRANSITION_ANIMATION_MS)
            .setInterpolator(mSurfaceTransitionInterpolator)
            .withEndAction(() -> {
                if (animationGeneration == mSurfaceAnimationGeneration)
                    incomingView.setTranslationX(0);
            })
            .start();

        outgoingView.animate()
            .translationX(outgoingEndX)
            .setDuration(SURFACE_TRANSITION_ANIMATION_MS)
            .setInterpolator(mSurfaceTransitionInterpolator)
            .withEndAction(() -> {
                if (animationGeneration != mSurfaceAnimationGeneration)
                    return;
                outgoingView.setVisibility(View.GONE);
                outgoingView.setTranslationX(0);
            })
            .start();
    }

    private void cancelSurfaceAnimations() {
        mSurfaceAnimationGeneration++;
        mTerminalView.animate().cancel();
        if (mDisplayView != null)
            mDisplayView.animate().cancel();
    }

    private boolean canOpenDrawerFromInternalSwipe(int drawerGravity) {
        if (mDrawerLayout.isDrawerOpen(GravityCompat.START) || mDrawerLayout.isDrawerOpen(GravityCompat.END))
            return false;
        if (mTerminalCopyMode)
            return false;
        if (mMode == SurfaceMode.TERMINAL)
            return drawerGravity == GravityCompat.START
                || (drawerGravity == GravityCompat.END && mDisplayView != null);
        if (mMode == SurfaceMode.DISPLAY)
            return !mDisplayFloatBallMenuEnabled
                && mDisplaySidePanelsUnlocked
                && (drawerGravity == GravityCompat.START || drawerGravity == GravityCompat.END);
        return false;
    }

    private void handleInternalDrawerSwipeAction(int drawerGravity) {
        if (mMode == SurfaceMode.TERMINAL) {
            if (drawerGravity == GravityCompat.START) {
                openStartDrawerExplicitly();
            } else if (mSurfaceGestureListener != null) {
                mSurfaceGestureListener.onTerminalEndSwipe();
            }
            return;
        }

        if (mMode != SurfaceMode.DISPLAY)
            return;

        if (drawerGravity == GravityCompat.START) {
            if (mSurfaceGestureListener != null)
                mSurfaceGestureListener.onDisplayStartSwipe();
        } else if (drawerGravity == GravityCompat.END) {
            openEndDrawerExplicitly();
            if (mSurfaceGestureListener != null)
                mSurfaceGestureListener.onDisplayEndSwipe();
        }
    }

    private boolean isTouchInsideContainer(@NonNull MotionEvent event) {
        int[] location = new int[2];
        mContainer.getLocationOnScreen(location);
        float x = event.getRawX();
        float y = event.getRawY();
        return x >= location[0]
            && x <= location[0] + mContainer.getWidth()
            && y >= location[1]
            && y <= location[1] + mContainer.getHeight();
    }

    private int getInternalDrawerSwipeGravity(@NonNull MotionEvent event) {
        if (!isTouchInsideContainer(event))
            return 0;
        if (canOpenDrawerFromInternalSwipe(GravityCompat.START) && isInInternalDrawerHotZone(event, GravityCompat.START))
            return GravityCompat.START;
        if (canOpenDrawerFromInternalSwipe(GravityCompat.END) && isInInternalDrawerHotZone(event, GravityCompat.END))
            return GravityCompat.END;
        return 0;
    }

    private boolean isInInternalDrawerHotZone(@NonNull MotionEvent event, int drawerGravity) {
        int[] location = new int[2];
        mContainer.getLocationOnScreen(location);
        float x = event.getRawX() - location[0];
        int width = mContainer.getWidth();
        boolean rtl = mContainer.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        boolean useRightEdge = (drawerGravity == GravityCompat.START && rtl) || (drawerGravity == GravityCompat.END && !rtl);

        if (useRightEdge)
            return x <= width - mInternalDrawerEdgeInset
                && x >= width - mInternalDrawerEdgeInset - mInternalDrawerHotZoneWidth;

        return x >= mInternalDrawerEdgeInset
            && x <= mInternalDrawerEdgeInset + mInternalDrawerHotZoneWidth;
    }

    private boolean shouldOpenDrawerFromInternalSwipe(@NonNull MotionEvent event, int drawerGravity) {
        boolean rtl = mContainer.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        boolean opensFromRight = (drawerGravity == GravityCompat.START && rtl) || (drawerGravity == GravityCompat.END && !rtl);
        float inwardDistance = opensFromRight
            ? mInternalDrawerSwipeDownX - event.getRawX()
            : event.getRawX() - mInternalDrawerSwipeDownX;
        float verticalDistance = Math.abs(event.getRawY() - mInternalDrawerSwipeDownY);
        int shortSide = Math.min(mContainer.getWidth(), mContainer.getHeight());
        int minDistance = Math.max(
            mInternalDrawerMinDistance,
            Math.min(Math.round(shortSide * INTERNAL_DRAWER_SHORT_SIDE_DISTANCE_RATIO), mInternalDrawerMaxDistance));
        return inwardDistance >= minDistance
            && inwardDistance > verticalDistance * 1.25f;
    }

    private void applyDrawerLockMode() {
        boolean startDrawerCanOpen = mMode == SurfaceMode.TERMINAL && !mTerminalCopyMode;
        boolean endDrawerCanOpen = mMode == SurfaceMode.DISPLAY
            && !mDisplayFloatBallMenuEnabled
            && mDisplaySidePanelsUnlocked;
        mDrawerLayout.setDrawerLockMode(
            startDrawerCanOpen ? DrawerLayout.LOCK_MODE_UNLOCKED : DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
            GravityCompat.START);
        mDrawerLayout.setDrawerLockMode(
            endDrawerCanOpen ? DrawerLayout.LOCK_MODE_UNLOCKED : DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
            GravityCompat.END);
    }

    private void ensureRestoreLockModeOnCloseListener() {
        if (mRestoreLockModeOnCloseListener != null)
            return;

        mRestoreLockModeOnCloseListener = new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerClosed(@NonNull View drawerView) {
                if (mRestoreLockModeOnCloseListener == null)
                    return;
                mDrawerLayout.removeDrawerListener(mRestoreLockModeOnCloseListener);
                mRestoreLockModeOnCloseListener = null;
                restoreDrawerLockMode();
            }
        };
        mDrawerLayout.addDrawerListener(mRestoreLockModeOnCloseListener);
    }
}
