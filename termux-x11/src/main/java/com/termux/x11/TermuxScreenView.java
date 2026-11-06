package com.termux.x11;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class TermuxScreenView extends FrameLayout {
    public enum IdlePresentation {
        TERMINAL(R.layout.view_x11_idle_terminal),
        UI(R.layout.view_x11_idle_ui);

        private final int layout;

        IdlePresentation(int layout) {
            this.layout = layout;
        }
    }

    @NonNull private final IdlePresentation idlePresentation;
    private static final long START_DRAWER_DOUBLE_GESTURE_TIMEOUT_MS = 1200;
    @Nullable
    private Runnable mStartDrawerGestureListener;
    private float mStartDrawerDownX;
    private float mStartDrawerDownY;
    private long mLastStartDrawerGestureTime;
    private int mStartDrawerEdgeSize;
    private int mStartDrawerMinSwipeDistance;
    private boolean mStartDrawerGestureUnlocked;
    private boolean mTrackingStartDrawerGesture;

    public TermuxScreenView(@NonNull Context context, @NonNull IdlePresentation idlePresentation) {
        super(context);
        this.idlePresentation = java.util.Objects.requireNonNull(idlePresentation);
        init(context);
    }

    private void init(@NonNull Context context) {
        setClipToPadding(false);
        LayoutInflater.from(context).inflate(R.layout.view_x11_display, this, true);
        LayoutInflater.from(context).inflate(idlePresentation.layout,
            (FrameLayout) findViewById(R.id.stub), true);

        ViewConfiguration viewConfiguration = ViewConfiguration.get(context);
        float density = getResources().getDisplayMetrics().density;
        mStartDrawerEdgeSize = Math.max(viewConfiguration.getScaledEdgeSlop(), Math.round(24 * density));
        mStartDrawerMinSwipeDistance = Math.max(viewConfiguration.getScaledTouchSlop() * 4, Math.round(72 * density));
    }

    public void setTerminalIdleActions(@NonNull Runnable preferences, @NonNull Runnable start,
                                       @NonNull Runnable exit) {
        if (idlePresentation != IdlePresentation.TERMINAL)
            throw new IllegalStateException("Terminal actions require the terminal idle layout");
        findViewById(R.id.preferences_button).setOnClickListener(view -> preferences.run());
        findViewById(R.id.help_button).setOnClickListener(view -> start.run());
        findViewById(R.id.exit_button).setOnClickListener(view -> exit.run());
    }

    public void setStartDrawerGestureListener(@Nullable Runnable listener) {
        mStartDrawerGestureListener = listener;
        mTrackingStartDrawerGesture = false;
        mLastStartDrawerGestureTime = 0;
        mStartDrawerGestureUnlocked = false;
    }

    public void setStartDrawerGestureUnlocked(boolean unlocked) {
        mStartDrawerGestureUnlocked = unlocked;
        if (unlocked)
            mLastStartDrawerGestureTime = 0;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        handleStartDrawerGesture(event);
        return super.dispatchTouchEvent(event);
    }

    private void handleStartDrawerGesture(@NonNull MotionEvent event) {
        if (mStartDrawerGestureListener == null || getWidth() <= 0)
            return;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTrackingStartDrawerGesture = isInStartEdge(event.getX());
                mStartDrawerDownX = event.getX();
                mStartDrawerDownY = event.getY();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                mTrackingStartDrawerGesture = false;
                break;
            case MotionEvent.ACTION_UP:
                if (!mTrackingStartDrawerGesture)
                    return;
                mTrackingStartDrawerGesture = false;
                handleStartDrawerGestureUp(event);
                break;
        }
    }

    private boolean isInStartEdge(float x) {
        if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL)
            return x >= getWidth() - mStartDrawerEdgeSize;
        return x <= mStartDrawerEdgeSize;
    }

    private void handleStartDrawerGestureUp(@NonNull MotionEvent event) {
        float inwardDistance = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
            ? mStartDrawerDownX - event.getX()
            : event.getX() - mStartDrawerDownX;
        float verticalDistance = Math.abs(event.getY() - mStartDrawerDownY);
        if (inwardDistance < mStartDrawerMinSwipeDistance || inwardDistance <= verticalDistance * 1.25f)
            return;

        if (mStartDrawerGestureUnlocked) {
            mLastStartDrawerGestureTime = 0;
            mStartDrawerGestureListener.run();
            return;
        }

        long eventTime = event.getEventTime();
        if (eventTime - mLastStartDrawerGestureTime <= START_DRAWER_DOUBLE_GESTURE_TIMEOUT_MS) {
            mLastStartDrawerGestureTime = 0;
            mStartDrawerGestureListener.run();
        } else {
            mLastStartDrawerGestureTime = eventTime;
        }
    }

    @NonNull
    public View getDisplayRoot() {
        return this;
    }

    @NonNull
    public FrameLayout getDisplayFrame() {
        FrameLayout view = findViewById(R.id.frame);
        if (view == null)
            throw new IllegalStateException("X11 display frame is missing");
        return view;
    }

    @NonNull
    public LorieView getLorieView() {
        LorieView view = findViewById(R.id.lorieView);
        if (view == null)
            throw new IllegalStateException("LorieView is missing");
        return view;
    }
}
