package com.termux.app.terminal;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
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

import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_APP;
import com.termux.view.TerminalView;
import com.termux.x11.TermuxScreenView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MainSurfaceController {
    private static final int INTERNAL_DRAWER_HOT_ZONE_WIDTH_DP = 72;
    private static final int INTERNAL_DRAWER_MIN_DISTANCE_DP = 96;
    private static final int INTERNAL_DRAWER_MAX_DISTANCE_DP = 220;
    private static final float INTERNAL_DRAWER_SHORT_SIDE_DISTANCE_RATIO = 0.24f;
    private static final float SURFACE_SWITCH_COMMIT_RATIO = 0.5f;
    private static final long SURFACE_TRANSITION_ANIMATION_MS = 180;

    public enum SurfaceMode {
        TERMINAL,
        DISPLAY
    }

    public interface SurfaceGestureListener {
        void onTerminalEndSwipe();
        void onDisplayStartSwipe();
        void onDisplayEndSwipe();
        void onSurfaceModeChanged(@NonNull SurfaceMode mode);
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
    private boolean mDisplayConnected;
    private int mTrackingInternalDrawerGravity;
    private boolean mInternalDrawerSwipeConsumed;
    private boolean mTrackingSurfaceSwitchDrag;
    @NonNull
    private SurfaceMode mSurfaceSwitchTargetMode = SurfaceMode.DISPLAY;
    private int mSurfaceSwitchWidth;
    private float mSurfaceSwitchDragDistance;
    private boolean mSurfaceSwitchFromLandscapeTerminalOverlay;
    private boolean mSurfaceSwitchToLandscapeTerminalOverlay;
    private float mInternalDrawerSwipeDownX;
    private float mInternalDrawerSwipeDownY;
    private final int mInternalDrawerHotZoneWidth;
    private final int mInternalDrawerMinDistance;
    private final int mInternalDrawerMaxDistance;
    @Nullable
    private DrawerLayout.DrawerListener mRestoreLockModeOnCloseListener;
    @Nullable
    private SurfaceGestureListener mSurfaceGestureListener;
    private int mSurfaceAnimationGeneration;
    private boolean mLandscapeTerminalOverlayEnabled;
    private int mLandscapeTerminalOverlayWidthPercent = TERMUX_APP.DEFAULT_VALUE_LANDSCAPE_TERMINAL_OVERLAY_WIDTH_PERCENT;
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
        mInternalDrawerHotZoneWidth = Math.round(INTERNAL_DRAWER_HOT_ZONE_WIDTH_DP * density);
        mInternalDrawerMinDistance = Math.max(
            Math.round(INTERNAL_DRAWER_MIN_DISTANCE_DP * density),
            viewConfiguration.getScaledTouchSlop() * 4);
        mInternalDrawerMaxDistance = Math.round(INTERNAL_DRAWER_MAX_DISTANCE_DP * density);

        mContainer.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
                applyMode();
                updateSystemGestureExclusionRects();
            }
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

    public void setDisplaySidePanelPolicy(boolean floatBallMenuEnabled, boolean sidePanelsUnlocked, boolean displayConnected) {
        if (mDisplayFloatBallMenuEnabled == floatBallMenuEnabled
            && mDisplaySidePanelsUnlocked == sidePanelsUnlocked
            && mDisplayConnected == displayConnected)
            return;
        mDisplayFloatBallMenuEnabled = floatBallMenuEnabled;
        mDisplaySidePanelsUnlocked = sidePanelsUnlocked;
        mDisplayConnected = displayConnected;
        applyDrawerLockMode();
        updatePreparedDisplayVisibilityForTerminalMode();
    }

    public void setLandscapeTerminalOverlayWidthPercent(int percent) {
        int clampedPercent = Math.max(TERMUX_APP.MIN_VALUE_LANDSCAPE_TERMINAL_OVERLAY_WIDTH_PERCENT,
            Math.min(percent, TERMUX_APP.MAX_VALUE_LANDSCAPE_TERMINAL_OVERLAY_WIDTH_PERCENT));
        if (mLandscapeTerminalOverlayWidthPercent == clampedPercent)
            return;

        mLandscapeTerminalOverlayWidthPercent = clampedPercent;
        if (shouldUseLandscapeTerminalOverlay() || mTrackingSurfaceSwitchDrag)
            applyMode();
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
        if (mTrackingSurfaceSwitchDrag)
            return handleSurfaceSwitchDragEvent(event);

        if (mInternalDrawerSwipeConsumed) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP) {
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
                    return false;
                }

                if (shouldLetDrawerLayoutHandleEdgeSwipe(event)) {
                    mTrackingInternalDrawerGravity = 0;
                    return false;
                }

                if (startSurfaceSwitchDragIfNeeded(event)) {
                    mTrackingInternalDrawerGravity = 0;
                    return true;
                }

                mTrackingInternalDrawerGravity = getInternalDrawerSwipeGravity(event);
                mInternalDrawerSwipeConsumed = mTrackingInternalDrawerGravity != 0;
                return mInternalDrawerSwipeConsumed;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                mTrackingInternalDrawerGravity = 0;
                mInternalDrawerSwipeConsumed = false;
                return false;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                if (mTrackingInternalDrawerGravity == 0)
                    return false;
                if (shouldOpenDrawerFromInternalSwipe(event, mTrackingInternalDrawerGravity)) {
                    int drawerGravity = mTrackingInternalDrawerGravity;
                    mTrackingInternalDrawerGravity = 0;
                    mInternalDrawerSwipeConsumed = event.getActionMasked() != MotionEvent.ACTION_UP;
                    handleInternalDrawerSwipeAction(drawerGravity);
                    return true;
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    mTrackingInternalDrawerGravity = 0;
                    return false;
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
        boolean prepareDisplayForTerminalSwitch = shouldPrepareDisplayForTerminalSwitch(terminalOverlay);
        applySurfaceLayout(terminalOverlay);

        mTerminalSurfaceView.setVisibility(mMode == SurfaceMode.TERMINAL ? View.VISIBLE : View.GONE);
        mTerminalSurfaceView.setTranslationX(0);
        if (mDisplayView != null) {
            mDisplayView.setVisibility((mMode == SurfaceMode.DISPLAY || terminalOverlay || prepareDisplayForTerminalSwitch) ? View.VISIBLE : View.GONE);
            mDisplayView.setTranslationX(prepareDisplayForTerminalSwitch ? getPreparedDisplayTranslationX() : 0);
            if (mMode == SurfaceMode.DISPLAY) {
                mDisplayView.bringToFront();
            } else if (terminalOverlay) {
                mDisplayView.bringToFront();
                mTerminalSurfaceView.bringToFront();
            } else if (prepareDisplayForTerminalSwitch) {
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

    private boolean shouldPrepareDisplayForTerminalSwitch(boolean terminalOverlay) {
        return mMode == SurfaceMode.TERMINAL
            && mDisplayView != null
            && !terminalOverlay
            && mDisplayConnected
            && mDisplaySidePanelsUnlocked;
    }

    private void updatePreparedDisplayVisibilityForTerminalMode() {
        if (mDisplayView == null || mTrackingSurfaceSwitchDrag || mMode != SurfaceMode.TERMINAL)
            return;

        boolean terminalOverlay = shouldUseLandscapeTerminalOverlay();
        boolean prepareDisplayForTerminalSwitch = shouldPrepareDisplayForTerminalSwitch(terminalOverlay);
        if (terminalOverlay)
            return;

        mDisplayView.setVisibility(prepareDisplayForTerminalSwitch ? View.VISIBLE : View.GONE);
        mDisplayView.setTranslationX(prepareDisplayForTerminalSwitch ? getPreparedDisplayTranslationX() : 0);
        if (prepareDisplayForTerminalSwitch)
            mDisplayView.bringToFront();
        mTerminalSurfaceView.bringToFront();
    }

    private float getPreparedDisplayTranslationX() {
        int width = mContainer.getWidth();
        if (width <= 0)
            width = mContainer.getResources().getDisplayMetrics().widthPixels;
        return getEndEdgeDirection() * Math.max(1, width);
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
        float widthRatio = mLandscapeTerminalOverlayWidthPercent / 100f;
        if (width > 0)
            return Math.max(1, Math.round(Math.min(width, displayShortSide) * widthRatio));
        return Math.max(1, Math.round(displayShortSide * widthRatio));
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

    private boolean startSurfaceSwitchDragIfNeeded(@NonNull MotionEvent event) {
        if (mDisplayView == null
            || mTerminalCopyMode
            || mDrawerLayout.isDrawerOpen(GravityCompat.START)
            || mDrawerLayout.isDrawerOpen(GravityCompat.END)
            || !isTouchInsideContainer(event)) {
            return false;
        }

        if (mMode == SurfaceMode.TERMINAL) {
            boolean terminalOverlay = shouldUseLandscapeTerminalOverlay();
            if (!terminalOverlay && !mDisplaySidePanelsUnlocked)
                return false;
            View edgeView = terminalOverlay ? mTerminalSurfaceView : mContainer;
            if (!isInViewEdge(edgeView, event, GravityCompat.END))
                return false;
            beginSurfaceSwitchDrag(SurfaceMode.DISPLAY);
            return true;
        }

        if (mMode == SurfaceMode.DISPLAY
            && !mDisplayFloatBallMenuEnabled
            && mDisplaySidePanelsUnlocked
            && isInViewEdge(mContainer, event, GravityCompat.START)) {
            beginSurfaceSwitchDrag(SurfaceMode.TERMINAL);
            return true;
        }

        return false;
    }

    private void beginSurfaceSwitchDrag(@NonNull SurfaceMode targetMode) {
        if (mDisplayView == null)
            return;

        cancelSurfaceAnimations();
        mTrackingSurfaceSwitchDrag = true;
        mSurfaceSwitchTargetMode = targetMode;
        mSurfaceSwitchDragDistance = 0;
        mSurfaceSwitchFromLandscapeTerminalOverlay = mMode == SurfaceMode.TERMINAL && shouldUseLandscapeTerminalOverlay();
        mSurfaceSwitchToLandscapeTerminalOverlay = targetMode == SurfaceMode.TERMINAL && isLandscapeLayout();
        mSurfaceSwitchWidth = getSurfaceSwitchWidth(targetMode);
        if (mSurfaceSwitchWidth <= 0)
            mSurfaceSwitchWidth = Math.max(1, mContainer.getWidth());

        boolean terminalOverlayLayout = mSurfaceSwitchFromLandscapeTerminalOverlay || mSurfaceSwitchToLandscapeTerminalOverlay;
        applySurfaceLayout(terminalOverlayLayout);

        mDisplayView.setVisibility(View.VISIBLE);
        mDisplayView.setTranslationX(0);

        mTerminalSurfaceView.setVisibility(View.VISIBLE);
        if (targetMode == SurfaceMode.TERMINAL) {
            mTerminalSurfaceView.setTranslationX(getStartEdgeDirection() * mSurfaceSwitchWidth);
            mDisplayView.bringToFront();
            mTerminalSurfaceView.bringToFront();
        } else {
            mTerminalSurfaceView.setTranslationX(0);
            if (!mSurfaceSwitchFromLandscapeTerminalOverlay)
                mDisplayView.setTranslationX(getEndEdgeDirection() * mSurfaceSwitchWidth);
            mTerminalSurfaceView.bringToFront();
            if (!mSurfaceSwitchFromLandscapeTerminalOverlay)
                mDisplayView.bringToFront();
        }
    }

    private boolean handleSurfaceSwitchDragEvent(@NonNull MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                updateSurfaceSwitchDrag(event);
                return true;
            case MotionEvent.ACTION_UP:
                updateSurfaceSwitchDrag(event);
                finishSurfaceSwitchDrag(mSurfaceSwitchDragDistance >= mSurfaceSwitchWidth * SURFACE_SWITCH_COMMIT_RATIO);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                finishSurfaceSwitchDrag(false);
                return true;
            default:
                return true;
        }
    }

    private void updateSurfaceSwitchDrag(@NonNull MotionEvent event) {
        if (mDisplayView == null)
            return;

        float distance = getSurfaceSwitchInwardDistance(event);
        mSurfaceSwitchDragDistance = Math.max(0, Math.min(distance, mSurfaceSwitchWidth));
        applySurfaceSwitchDragProgress(mSurfaceSwitchDragDistance);
    }

    private void applySurfaceSwitchDragProgress(float distance) {
        if (mDisplayView == null)
            return;

        if (mSurfaceSwitchTargetMode == SurfaceMode.DISPLAY) {
            int endDirection = getEndEdgeDirection();
            if (mSurfaceSwitchFromLandscapeTerminalOverlay) {
                mTerminalSurfaceView.setTranslationX(-endDirection * distance);
                mDisplayView.setTranslationX(0);
            } else {
                mTerminalSurfaceView.setTranslationX(-endDirection * distance);
                mDisplayView.setTranslationX(endDirection * (mSurfaceSwitchWidth - distance));
            }
            return;
        }

        int startDirection = getStartEdgeDirection();
        mTerminalSurfaceView.setTranslationX(startDirection * (mSurfaceSwitchWidth - distance));
        if (mSurfaceSwitchToLandscapeTerminalOverlay) {
            mDisplayView.setTranslationX(0);
        } else {
            mDisplayView.setTranslationX(-startDirection * distance);
        }
    }

    private void finishSurfaceSwitchDrag(boolean commit) {
        if (mDisplayView == null) {
            mTrackingSurfaceSwitchDrag = false;
            return;
        }

        int animationGeneration = ++mSurfaceAnimationGeneration;
        float terminalEndX = getSurfaceSwitchTerminalEndTranslation(commit);
        float displayEndX = getSurfaceSwitchDisplayEndTranslation(commit);

        mTerminalSurfaceView.animate().cancel();
        mDisplayView.animate().cancel();

        mTerminalSurfaceView.animate()
            .translationX(terminalEndX)
            .setDuration(SURFACE_TRANSITION_ANIMATION_MS)
            .setInterpolator(mSurfaceTransitionInterpolator)
            .withEndAction(() -> finishSurfaceSwitchDragAnimation(animationGeneration, commit))
            .start();

        mDisplayView.animate()
            .translationX(displayEndX)
            .setDuration(SURFACE_TRANSITION_ANIMATION_MS)
            .setInterpolator(mSurfaceTransitionInterpolator)
            .start();
    }

    private void finishSurfaceSwitchDragAnimation(int animationGeneration, boolean commit) {
        if (animationGeneration != mSurfaceAnimationGeneration)
            return;

        if (commit)
            mMode = mSurfaceSwitchTargetMode;

        mTrackingSurfaceSwitchDrag = false;
        updateLandscapeTerminalOverlayEnabled();
        applyMode(false, mMode, mLandscapeTerminalOverlayEnabled);

        if (commit && mSurfaceGestureListener != null)
            mSurfaceGestureListener.onSurfaceModeChanged(mMode);

        if (commit && mMode == SurfaceMode.TERMINAL) {
            mTerminalView.requestFocus();
        } else if (commit && mMode == SurfaceMode.DISPLAY && mDisplayView != null) {
            mDisplayView.getLorieView().requestFocus();
        }
    }

    private float getSurfaceSwitchTerminalEndTranslation(boolean commit) {
        if (mSurfaceSwitchTargetMode == SurfaceMode.DISPLAY)
            return commit ? -getEndEdgeDirection() * mSurfaceSwitchWidth : 0;
        return commit ? 0 : getStartEdgeDirection() * mSurfaceSwitchWidth;
    }

    private float getSurfaceSwitchDisplayEndTranslation(boolean commit) {
        if (mDisplayView == null)
            return 0;

        if (mSurfaceSwitchTargetMode == SurfaceMode.DISPLAY)
            return commit || mSurfaceSwitchFromLandscapeTerminalOverlay ? 0 : getEndEdgeDirection() * mSurfaceSwitchWidth;
        if (mSurfaceSwitchToLandscapeTerminalOverlay)
            return 0;
        return commit ? -getStartEdgeDirection() * mSurfaceSwitchWidth : 0;
    }

    private int getSurfaceSwitchWidth(@NonNull SurfaceMode targetMode) {
        if (mMode == SurfaceMode.TERMINAL && shouldUseLandscapeTerminalOverlay())
            return getLandscapeTerminalOverlayWidth();
        if (targetMode == SurfaceMode.TERMINAL && isLandscapeLayout())
            return getLandscapeTerminalOverlayWidth();
        return mContainer.getWidth();
    }

    private float getSurfaceSwitchInwardDistance(@NonNull MotionEvent event) {
        if (mSurfaceSwitchTargetMode == SurfaceMode.DISPLAY)
            return getEndEdgeDirection() * (mInternalDrawerSwipeDownX - event.getRawX());
        return -getStartEdgeDirection() * (event.getRawX() - mInternalDrawerSwipeDownX);
    }

    private int getStartEdgeDirection() {
        return mContainer.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 1 : -1;
    }

    private int getEndEdgeDirection() {
        return mContainer.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1;
    }

    private boolean canOpenDrawerFromInternalSwipe(int drawerGravity) {
        if (mDrawerLayout.isDrawerOpen(GravityCompat.START) || mDrawerLayout.isDrawerOpen(GravityCompat.END))
            return false;
        if (mTerminalCopyMode)
            return false;
        if (mMode == SurfaceMode.TERMINAL)
            return drawerGravity == GravityCompat.START;
        if (mMode == SurfaceMode.DISPLAY)
            return canOpenDisplayEndDrawer()
                && drawerGravity == GravityCompat.END;
        return false;
    }

    private boolean shouldLetDrawerLayoutHandleEdgeSwipe(@NonNull MotionEvent event) {
        return canDrawerLayoutHandleEdge(GravityCompat.START, event)
            || canDrawerLayoutHandleEdge(GravityCompat.END, event);
    }

    private boolean canDrawerLayoutHandleEdge(int drawerGravity, @NonNull MotionEvent event) {
        return mDrawerLayout.getDrawerLockMode(drawerGravity) == DrawerLayout.LOCK_MODE_UNLOCKED
            && isInViewEdge(mContainer, event, drawerGravity);
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

    private boolean isInViewEdge(@NonNull View view, @NonNull MotionEvent event, int drawerGravity) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float x = event.getRawX() - location[0];
        int width = view.getWidth();
        if (x < 0 || x > width)
            return false;

        boolean rtl = mContainer.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        boolean useRightEdge = (drawerGravity == GravityCompat.START && rtl)
            || (drawerGravity == GravityCompat.END && !rtl);

        if (useRightEdge)
            return x >= width - mInternalDrawerHotZoneWidth;

        return x <= mInternalDrawerHotZoneWidth;
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
        return isInViewEdge(getInternalDrawerSwipeHotZoneView(drawerGravity), event, drawerGravity);
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
        boolean startDrawerCanOpen = mMode == SurfaceMode.TERMINAL
            && !mTerminalCopyMode;
        boolean endDrawerCanOpen = mMode == SurfaceMode.DISPLAY
            && canOpenDisplayEndDrawer();
        mDrawerLayout.setDrawerLockMode(
            startDrawerCanOpen ? DrawerLayout.LOCK_MODE_UNLOCKED : DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
            GravityCompat.START);
        mDrawerLayout.setDrawerLockMode(
            endDrawerCanOpen ? DrawerLayout.LOCK_MODE_UNLOCKED : DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
            GravityCompat.END);
        updateSystemGestureExclusionRects();
    }

    private boolean canOpenDisplayEndDrawer() {
        return !mDisplayFloatBallMenuEnabled
            && (!mDisplayConnected || mDisplaySidePanelsUnlocked);
    }

    private void updateSystemGestureExclusionRects() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            return;

        int width = mContainer.getWidth();
        int height = mContainer.getHeight();
        if (width <= 0 || height <= 0) {
            mContainer.setSystemGestureExclusionRects(Collections.emptyList());
            return;
        }

        List<Rect> rects = new ArrayList<>(2);
        if (mMode == SurfaceMode.TERMINAL && !mTerminalCopyMode)
            addSystemGestureExclusionRect(rects, GravityCompat.START, width, height);
        if (mMode == SurfaceMode.DISPLAY && canOpenDisplayEndDrawer())
            addSystemGestureExclusionRect(rects, GravityCompat.END, width, height);

        if (mDisplaySidePanelsUnlocked && mDisplayView != null) {
            if (mMode == SurfaceMode.TERMINAL) {
                addSystemGestureExclusionRect(rects, GravityCompat.END, width, height);
            } else if (mMode == SurfaceMode.DISPLAY && !mDisplayFloatBallMenuEnabled) {
                addSystemGestureExclusionRect(rects, GravityCompat.START, width, height);
            }
        }

        mContainer.setSystemGestureExclusionRects(rects);
    }

    private void addSystemGestureExclusionRect(@NonNull List<Rect> rects, int drawerGravity, int width, int height) {
        int edgeWidth = Math.min(mInternalDrawerHotZoneWidth, width);
        boolean rtl = mContainer.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        boolean useRightEdge = (drawerGravity == GravityCompat.START && rtl)
            || (drawerGravity == GravityCompat.END && !rtl);
        if (useRightEdge)
            rects.add(new Rect(width - edgeWidth, 0, width, height));
        else
            rects.add(new Rect(0, 0, edgeWidth, height));
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
