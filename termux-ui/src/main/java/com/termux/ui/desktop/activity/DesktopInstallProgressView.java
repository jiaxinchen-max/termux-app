package com.termux.ui.desktop.activity;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Build;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.termux.ui.R;

/** One directional installation sweep. Hidden, detached and paused cards do no animation work. */
public final class DesktopInstallProgressView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final Rect visibleArea = new Rect();
    private final ViewTreeObserver.OnPreDrawListener visibilityListener = () -> {
        syncAnimation();
        return true;
    };
    private ValueAnimator animator;
    private LinearGradient gradient;
    private float phase = 0.55f;
    private boolean hostActive;

    public DesktopInstallProgressView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void show() { setVisibility(VISIBLE); syncAnimation(); }
    public void hide() { setVisibility(GONE); syncAnimation(); }
    public void setHostActive(boolean active) { hostActive = active; syncAnimation(); }

    private boolean animationsEnabled() {
        if (Build.VERSION.SDK_INT >= 26) return ValueAnimator.areAnimatorsEnabled();
        return Settings.Global.getFloat(getContext().getContentResolver(),
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
    }

    private void syncAnimation() {
        boolean animate = hostActive && isAttachedToWindow() && isShown()
            && getWindowVisibility() == VISIBLE && getGlobalVisibleRect(visibleArea)
            && animationsEnabled();
        if (!animate) {
            boolean changed = animator != null || phase != 0.55f;
            if (animator != null) { animator.cancel(); animator = null; }
            phase = 0.55f;
            if (changed) invalidate();
        } else if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1200);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new AccelerateDecelerateInterpolator());
            animator.addUpdateListener(value -> { phase = (float) value.getAnimatedValue(); invalidate(); });
            animator.start();
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(visibilityListener);
        syncAnimation();
    }
    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(visibilityListener);
        if (animator != null) { animator.cancel(); animator = null; }
        super.onDetachedFromWindow();
    }
    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        // View's constructor may invoke this before this class's field initializers.
        if (paint != null) syncAnimation();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (paint != null) syncAnimation();
    }
    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        gradient = null;
        syncAnimation();
    }
    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) { gradient = null; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int accent = ContextCompat.getColor(getContext(), R.color.local_games_primary);
        float width = getWidth(), height = getHeight(), tail = width * 0.38f;
        if (width <= 0 || height <= 0) return;
        paint.setShader(null);
        paint.setColor(ColorUtils.setAlphaComponent(accent, 45));
        canvas.drawRoundRect(0, 0, width, height, height / 2, height / 2, paint);
        if (gradient == null) gradient = new LinearGradient(0, 0, tail, 0,
            new int[]{ColorUtils.setAlphaComponent(accent, 0), accent,
                ColorUtils.blendARGB(accent, Color.WHITE, 0.65f)},
            new float[]{0f, 0.8f, 1f}, Shader.TileMode.CLAMP);
        float head = (width + 2 * tail) * phase - tail;
        matrix.setTranslate(head - tail, 0);
        gradient.setLocalMatrix(matrix);
        paint.setShader(gradient);
        canvas.save();
        canvas.clipRect(0, 0, width, height);
        canvas.drawRect(head - tail, 0, head, height, paint);
        canvas.restore();
    }
}
