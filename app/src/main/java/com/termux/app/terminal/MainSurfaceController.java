package com.termux.app.terminal;

import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowManager;
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
    private final View mTerminalSurfaceView;
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
    private boolean mInternalDrawerSwipeConsumed;
    private boolean mTrackingTerminalDisplaySwitchGesture;
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
    private boolean mLandscapeTerminalOverlayEnabled;
    @NonNull
    private final DecelerateInterpolator mSurfaceTransitionInterpolator = new DecelerateInterpolator();

    public MainSurfaceController(@NonNull DrawerLayout drawerLayout,
                                 @NonNull FrameLayout container,
                                 @NonNull View terminalSurfaceView,
                                 @NonNull TerminalView terminalView) {
        mDrawerLayout = drawerLayout;
        mContainer = container;
        mTerminalSurfaceView = terminalSurfaceView;
        mTerminalView = terminalView;

        float density = container.getResources().getDisplayMetrics().density;
        ViewConfiguration viewConfiguration = ViewConfiguration.get(container.getContext());
        mInternalDrawerEdgeInset = Math.round(INTERNAL_DRAWER_EDGE_INSET_DP * density);
        mInternalDrawerHotZoneWidth = Math.round(INTERNAL_DRAWER_HOT_ZONE_WIDTH_DP * density);
        mInternalDrawerMinDistance = Math.max(
            Math.round(INTERNAL_DRAWER_MIN_DISTANCE_DP * density),
            viewConfiguration.getScaledTouchSlop() * 4);
        mInternalDrawerMaxDistance = Math.round(INTERNAL_DRAWER_MAX_DISTANCE_DP * density);

        mContainer.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop))
                applyMode();
        });
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
        boolean previousLandscapeTerminalOverlayEnabled = shouldUseLandscapeTerminalOverlay();
        mMode = SurfaceMode.TERMINAL;
        updateLandscapeTerminalOverlayEnabled();
        applyMode(previousMode == SurfaceMode.DISPLAY, previousMode, previousLandscapeTerminalOverlayEnabled);
        mTerminalView.requestFocus();
    }

    public void showDisplay() {
        if (mDisplayView == null)
            return;
        SurfaceMode previousMode = mMode;
        boolean previousLandscapeTerminalOverlayEnabled = shouldUseLandscapeTerminalOverlay();
        mMode = SurfaceMode.DISPLAY;
        updateLandscapeTerminalOverlayEnabled();
        applyMode(previousMode == SurfaceMode.TERMINAL, previousMode, previousLandscapeTerminalOverlayEnabled);
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

    public boolean handleInternalDrawerSwipe(@NonNull MotionEvent event) {
        if (mInternalDrawerSwipeConsumed) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP) {
                if (handleUnlockedDisplaySwipeUp(event)) {
                    mTrackingInternalDrawerGravity = 0;
                    mInternalDrawerSwipeConsumed = false;
                    return true;
                }
                if (mTrackingInternalDrawerGravity != 0
                    && shouldOpenDrawerFromInternalSwipe(event, mTrackingInternalDrawerGravity)) {
                    int drawerGravity = mTrackingInternalDrawerGravity;
                    mTrackingInternalDrawerGravity = 0;
                    mInternalDrawerSwipeConsumed = false;
                    handleInternalDrawerSwipeAction(drawerGravity);
                    return true;
                }
                mTrackingInternalDrawerGravity = 0;
                mInternalDrawerSwipeConsumed = false;
            } else if (action == MotionEvent.ACTION_CANCEL) {
                mTrackingInternalDrawerGravity = 0;
                mInternalDrawerSwipeConsumed = false;
            }
            return true;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mInternalDrawerSwipeConsumed = false;
                mInternalDrawerSwipeDownX = event.getRawX();
                mInternalDrawerSwipeDownY = event.getRawY();
                if (isTouchInsideTerminalToolbarArea(event)) {
                    mTrackingInternalDrawerGravity = 0;
                    mTrackingTerminalDisplaySwitchGesture = false;
                    return false;
                }
                mTrackingTerminalDisplaySwitchGesture = shouldTrackTerminalDisplaySwitchInput()
                    && isTouchInsideTerminalSwitchArea(event);

                if (shouldCaptureUnlockedDisplayInput() && isTouchInsideContainer(event)) {
                    mTrackingInternalDrawerGravity = 0;
                    mInternalDrawerSwipeConsumed = true;
                    return true;
                }

                mTrackingInternalDrawerGravity = getInternalDrawerSwipeGravity(event);
                mInternalDrawerSwipeConsumed = mTrackingInternalDrawerGravity != 0;
                return mInternalDrawerSwipeConsumed;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                mTrackingInternalDrawerGravity = 0;
                mInternalDrawerSwipeConsumed = false;
                mTrackingTerminalDisplaySwitchGesture = false;
                return false;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                if (mTrackingInternalDrawerGravity == 0)
                    return handleTerminalDisplaySwitchSwipeUp(event);
                if (shouldOpenDrawerFromInternalSwipe(event, mTrackingInternalDrawerGravity)) {
                    int drawerGravity = mTrackingInternalDrawerGravity;
                    mTrackingInternalDrawerGravity = 0;
                    mInternalDrawerSwipeConsumed = event.getActionMasked() != MotionEvent.ACTION_UP;
                    mTrackingTerminalDisplaySwitchGesture = false;
                    handleInternalDrawerSwipeAction(drawerGravity);
                    return true;
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    mTrackingInternalDrawerGravity = 0;
                    return handleTerminalDisplaySwitchSwipeUp(event);
                }
                break;
        }
        return false;
    }

    private void applyMode() {
        applyMode(false, mMode, mLandscapeTerminalOverlayEnabled);
    }

    private void applyMode(boolean animate, @NonNull SurfaceMode previousMode, boolean previousLandscapeTerminalOverlayEnabled) {
        updateLandscapeTerminalOverlayEnabled();

        if (animate && previousMode != mMode && mDisplayView != null && mContainer.getWidth() > 0) {
            animateModeChange(previousLandscapeTerminalOverlayEnabled);
            applyDrawerLockMode();
            return;
        }

        cancelSurfaceAnimations();
        boolean terminalOverlay = shouldUseLandscapeTerminalOverlay();
        applySurfaceLayout(terminalOverlay);

        mTerminalSurfaceView.setVisibility(mMode == SurfaceMode.TERMINAL ? View.VISIBLE : View.GONE);
        mTerminalSurfaceView.setTranslationX(0);
        if (mDisplayView != null) {
            mDisplayView.setVisibility((mMode == SurfaceMode.DISPLAY || terminalOverlay) ? View.VISIBLE : View.GONE);
            mDisplayView.setTranslationX(0);
            if (mMode == SurfaceMode.DISPLAY) {
                mDisplayView.bringToFront();
            } else if (terminalOverlay) {
                mDisplayView.bringToFront();
                mTerminalSurfaceView.bringToFront();
            } else {
                mTerminalSurfaceView.bringToFront();
            }
        }
        applyDrawerLockMode();
    }

    private void animateModeChange(boolean previousLandscapeTerminalOverlayEnabled) {
        if (mDisplayView == null)
            return;

        boolean terminalOverlay = shouldUseLandscapeTerminalOverlay();
        if (mMode == SurfaceMode.TERMINAL && terminalOverlay) {
            animateLandscapeTerminalOverlayIn();
            return;
        }
        if (mMode == SurfaceMode.DISPLAY && previousLandscapeTerminalOverlayEnabled && isLandscapeLayout()) {
            animateLandscapeTerminalOverlayOut();
            return;
        }

        applySurfaceLayout(false);
        int width = mContainer.getWidth();
        View incomingView = mMode == SurfaceMode.TERMINAL ? mTerminalSurfaceView : mDisplayView;
        View outgoingView = mMode == SurfaceMode.TERMINAL ? mDisplayView : mTerminalSurfaceView;
        float incomingStartX = mMode == SurfaceMode.TERMINAL ? -width : width;
        float outgoingEndX = mMode == SurfaceMode.TERMINAL ? width : -width;
        int animationGeneration = ++mSurfaceAnimationGeneration;

        mTerminalSurfaceView.animate().cancel();
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

    private void animateLandscapeTerminalOverlayIn() {
        if (mDisplayView == null)
            return;

        applySurfaceLayout(true);
        int terminalWidth = getLandscapeTerminalOverlayWidth();
        int animationGeneration = ++mSurfaceAnimationGeneration;

        mTerminalSurfaceView.animate().cancel();
        mDisplayView.animate().cancel();

        mDisplayView.setVisibility(View.VISIBLE);
        mDisplayView.setTranslationX(0);
        mDisplayView.bringToFront();

        mTerminalSurfaceView.setVisibility(View.VISIBLE);
        mTerminalSurfaceView.setTranslationX(-terminalWidth);
        mTerminalSurfaceView.bringToFront();

        mTerminalSurfaceView.animate()
            .translationX(0)
            .setDuration(SURFACE_TRANSITION_ANIMATION_MS)
            .setInterpolator(mSurfaceTransitionInterpolator)
            .withEndAction(() -> {
                if (animationGeneration == mSurfaceAnimationGeneration)
                    mTerminalSurfaceView.setTranslationX(0);
            })
            .start();
    }

    private void animateLandscapeTerminalOverlayOut() {
        if (mDisplayView == null)
            return;

        applySurfaceLayout(true);
        int terminalWidth = getLandscapeTerminalOverlayWidth();
        int animationGeneration = ++mSurfaceAnimationGeneration;

        mTerminalSurfaceView.animate().cancel();
        mDisplayView.animate().cancel();

        mTerminalSurfaceView.setVisibility(View.VISIBLE);
        mTerminalSurfaceView.setTranslationX(0);
        mTerminalSurfaceView.bringToFront();

        mTerminalSurfaceView.animate()
            .translationX(-terminalWidth)
            .setDuration(SURFACE_TRANSITION_ANIMATION_MS)
            .setInterpolator(mSurfaceTransitionInterpolator)
            .withEndAction(() -> {
                if (animationGeneration != mSurfaceAnimationGeneration)
                    return;
                applySurfaceLayout(false);
                mTerminalSurfaceView.setVisibility(View.GONE);
                mTerminalSurfaceView.setTranslationX(0);
            })
            .start();
    }

    private boolean shouldUseLandscapeTerminalOverlay() {
        return mMode == SurfaceMode.TERMINAL
            && mDisplayView != null
            && mLandscapeTerminalOverlayEnabled
            && isLandscapeLayout();
    }

    private void updateLandscapeTerminalOverlayEnabled() {
        mLandscapeTerminalOverlayEnabled = mMode == SurfaceMode.TERMINAL
            && mDisplayView != null
            && isLandscapeLayout();
    }

    private boolean isLandscapeLayout() {
        if (mContainer.getWidth() > 0 && mContainer.getHeight() > 0)
            return mContainer.getWidth() > mContainer.getHeight();
        return mContainer.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private int getLandscapeTerminalOverlayWidth() {
        int width = mContainer.getWidth();
        int displayShortSide = getDisplayShortSideWidth();
        if (width > 0)
            return Math.min(width, displayShortSide);
        return displayShortSide;
    }

    private int getDisplayShortSideWidth() {
        WindowManager windowManager = (WindowManager) mContainer.getContext().getSystemService(android.content.Context.WINDOW_SERVICE);
        if (windowManager != null) {
            DisplayMetrics realMetrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(realMetrics);
            return Math.min(realMetrics.widthPixels, realMetrics.heightPixels);
        }

        DisplayMetrics metrics = mContainer.getResources().getDisplayMetrics();
        return Math.min(metrics.widthPixels, metrics.heightPixels);
    }

    private void applySurfaceLayout(boolean terminalOverlay) {
        ViewGroup.LayoutParams layoutParams = mTerminalSurfaceView.getLayoutParams();
        FrameLayout.LayoutParams params = layoutParams instanceof FrameLayout.LayoutParams
            ? (FrameLayout.LayoutParams) layoutParams
            : new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        int targetWidth = terminalOverlay ? getLandscapeTerminalOverlayWidth() : FrameLayout.LayoutParams.MATCH_PARENT;
        int targetGravity = terminalOverlay ? Gravity.START : Gravity.NO_GRAVITY;
        if (params.width != targetWidth
            || params.height != FrameLayout.LayoutParams.MATCH_PARENT
            || params.gravity != targetGravity) {
            params.width = targetWidth;
            params.height = FrameLayout.LayoutParams.MATCH_PARENT;
            params.gravity = targetGravity;
            mTerminalSurfaceView.setLayoutParams(params);
        }
    }

    private void cancelSurfaceAnimations() {
        mSurfaceAnimationGeneration++;
        mTerminalSurfaceView.animate().cancel();
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

    private boolean shouldCaptureUnlockedDisplayInput() {
        return mMode == SurfaceMode.DISPLAY
            && !mDisplayFloatBallMenuEnabled
            && mDisplaySidePanelsUnlocked
            && !mTerminalCopyMode
            && !mDrawerLayout.isDrawerOpen(GravityCompat.START)
            && !mDrawerLayout.isDrawerOpen(GravityCompat.END);
    }

    private boolean shouldTrackTerminalDisplaySwitchInput() {
        return mMode == SurfaceMode.TERMINAL
            && mDisplayView != null
            && !mTerminalCopyMode
            && !mDrawerLayout.isDrawerOpen(GravityCompat.START)
            && !mDrawerLayout.isDrawerOpen(GravityCompat.END);
    }

    private boolean handleTerminalDisplaySwitchSwipeUp(@NonNull MotionEvent event) {
        if (!mTrackingTerminalDisplaySwitchGesture || event.getActionMasked() != MotionEvent.ACTION_UP)
            return false;

        mTrackingTerminalDisplaySwitchGesture = false;
        if (!shouldTrackTerminalDisplaySwitchInput() || !isTouchInsideTerminalSwitchArea(event))
            return false;

        float horizontalDistance = event.getRawX() - mInternalDrawerSwipeDownX;
        float verticalDistance = Math.abs(event.getRawY() - mInternalDrawerSwipeDownY);
        if (Math.abs(horizontalDistance) < getInternalDrawerMinDistance()
            || Math.abs(horizontalDistance) <= verticalDistance * 1.25f) {
            return false;
        }

        boolean rtl = mContainer.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        boolean swipeToEnd = rtl ? horizontalDistance > 0 : horizontalDistance < 0;
        if (!swipeToEnd)
            return false;

        handleInternalDrawerSwipeAction(GravityCompat.END);
        return true;
    }

    private boolean handleUnlockedDisplaySwipeUp(@NonNull MotionEvent event) {
        if (!shouldCaptureUnlockedDisplayInput() || !isTouchInsideContainer(event))
            return false;

        float horizontalDistance = event.getRawX() - mInternalDrawerSwipeDownX;
        float verticalDistance = Math.abs(event.getRawY() - mInternalDrawerSwipeDownY);
        if (Math.abs(horizontalDistance) < getInternalDrawerMinDistance()
            || Math.abs(horizontalDistance) <= verticalDistance * 1.25f) {
            return false;
        }

        boolean rtl = mContainer.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        boolean swipeToRight = horizontalDistance > 0;
        int drawerGravity = swipeToRight == !rtl ? GravityCompat.START : GravityCompat.END;
        handleInternalDrawerSwipeAction(drawerGravity);
        return true;
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

    private boolean isTouchInsideTerminalSwitchArea(@NonNull MotionEvent event) {
        View switchArea = shouldUseLandscapeTerminalOverlay() ? mTerminalSurfaceView : mContainer;
        int[] location = new int[2];
        switchArea.getLocationOnScreen(location);
        float x = event.getRawX();
        float y = event.getRawY();
        return x >= location[0]
            && x <= location[0] + switchArea.getWidth()
            && y >= location[1]
            && y <= location[1] + switchArea.getHeight();
    }

    private boolean isTouchInsideTerminalToolbarArea(@NonNull MotionEvent event) {
        if (mMode != SurfaceMode.TERMINAL || mTerminalSurfaceView.getVisibility() != View.VISIBLE)
            return false;
        return isTouchInsideView(mTerminalSurfaceView, event)
            && !isTouchInsideView(mTerminalView, event);
    }

    private boolean isTouchInsideView(@NonNull View view, @NonNull MotionEvent event) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float x = event.getRawX();
        float y = event.getRawY();
        return x >= location[0]
            && x <= location[0] + view.getWidth()
            && y >= location[1]
            && y <= location[1] + view.getHeight();
    }

    @NonNull
    private View getInternalDrawerSwipeHotZoneView(int drawerGravity) {
        if (mMode == SurfaceMode.TERMINAL
            && drawerGravity == GravityCompat.END
            && shouldUseLandscapeTerminalOverlay()) {
            return mTerminalSurfaceView;
        }
        return mContainer;
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
        View hotZoneView = getInternalDrawerSwipeHotZoneView(drawerGravity);
        int[] location = new int[2];
        hotZoneView.getLocationOnScreen(location);
        float x = event.getRawX() - location[0];
        int width = hotZoneView.getWidth();
        if (x < 0 || x > width)
            return false;
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
        return inwardDistance >= getInternalDrawerMinDistance()
            && inwardDistance > verticalDistance * 1.25f;
    }

    private int getInternalDrawerMinDistance() {
        int shortSide = Math.min(mContainer.getWidth(), mContainer.getHeight());
        return Math.max(
            mInternalDrawerMinDistance,
            Math.min(Math.round(shortSide * INTERNAL_DRAWER_SHORT_SIDE_DISTANCE_RATIO), mInternalDrawerMaxDistance));
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
