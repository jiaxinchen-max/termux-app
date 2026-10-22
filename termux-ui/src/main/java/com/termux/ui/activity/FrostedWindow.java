package com.termux.ui.activity;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import com.termux.ui.R;

/**
 * Shared "warm frosted matte" helpers: a translucent fill with a hairline and small corners for
 * programmatic surfaces, and window blur for dialog windows on API 31+. Below API 31 only the
 * translucent fill is used (minSdk is 21, so nothing above it is called unguarded).
 */
final class FrostedWindow {

    /** Background blur radius for dialog windows (design: about 20-24 dp). */
    static final int BLUR_RADIUS_DP = 22;
    /** Corner radius of menu rows (design token). */
    static final int ROW_RADIUS_DP = 6;
    /** Corner radius of cards, panels, dialogs, popups and the in-game overlay (design token). */
    static final int PANEL_RADIUS_DP = 8;

    private static final int FIRST_BLUR_API = 31;
    /** Same value as {@code R.dimen.local_games_dialog_dim_amount}, which the dialog theme applies to Material dialogs. */
    private static final float DIM_WITH_BLUR = 0.30f;
    private static final float DIM_WITHOUT_BLUR = 0.60f;

    private FrostedWindow() {}

    /** Pure rule behind {@link #isBlurSupported()}; kept separate so it can be unit tested. */
    static boolean isBlurSupported(int sdkInt) {
        return sdkInt >= FIRST_BLUR_API;
    }

    @ChecksSdkIntAtLeast(api = FIRST_BLUR_API)
    static boolean isBlurSupported() {
        return Build.VERSION.SDK_INT >= FIRST_BLUR_API;
    }

    static int blurRadiusPx(float density) {
        return Math.round(BLUR_RADIUS_DP * density);
    }

    /** Pixel corner radius of the panel shape; kept separate so it can be unit tested. */
    static float panelRadiusPx(float density) {
        return PANEL_RADIUS_DP * density;
    }

    /** With a real blur behind the window a light dim is enough; without it dim a bit more. */
    static float dimAmount(int sdkInt) {
        return isBlurSupported(sdkInt) ? DIM_WITH_BLUR : DIM_WITHOUT_BLUR;
    }

    /**
     * Window dim for a dialog. A black dim turns the warm light page grey and dirty, so in light
     * mode the window dim is off and a warm scrim is drawn instead ({@link #applyScrim}); dark
     * mode keeps {@link #dimAmount(int)}.
     */
    static float dimAmount(int sdkInt, boolean night) {
        return night ? dimAmount(sdkInt) : 0f;
    }

    /** Pure rule behind the mode check; kept separate so it can be unit tested. */
    static boolean isNight(int uiMode) {
        return (uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Scrim colour resource: a lighter warm tint when the window blur softens the page, a stronger one without. */
    @ColorRes
    static int scrimColorRes(int sdkInt) {
        return isBlurSupported(sdkInt) ? R.color.local_games_dialog_scrim : R.color.local_games_dialog_scrim_noblur;
    }

    /** Blurs the content behind {@code window} on API 31+; does nothing below. */
    static void applyBlur(@Nullable Window window, float density) {
        if (window == null || !isBlurSupported()) return;
        applyBlurApi31(window, blurRadiusPx(density));
    }

    @RequiresApi(FIRST_BLUR_API)
    private static void applyBlurApi31(Window window, int radiusPx) {
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        window.setBackgroundBlurRadius(radiusPx);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.setBlurBehindRadius(radiusPx);
        window.setAttributes(attributes);
    }

    /**
     * Makes the window background the ONE glass fill layer of a dialog: the shared dialog shape
     * (8 dp radius, hairline, no inset). Content containers must stay transparent. A plain shape
     * (not an inset wrapper) is also what gives the API 31+ blur region its rounded corners.
     */
    static void applyDialogSurface(@Nullable Window window, @NonNull Context context) {
        if (window == null) return;
        window.setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.local_games_bg_dialog));
        applyBlur(window, context.getResources().getDisplayMetrics().density);
    }

    /**
     * Window surface of a self-drawn dialog (console dialogs): the rounded 8 dp panel shape with the
     * given fill and the hairline as the window background, so the API 31+ blur region is rounded
     * like the panel instead of the full square window. Pair with {@link #clipToPanel(View, Context)}
     * for content that draws to its edges.
     */
    static void applyPanelSurface(@Nullable Window window, @NonNull Context context, @ColorRes int fillRes) {
        if (window == null) return;
        window.setBackgroundDrawable(surface(context, fillRes, PANEL_RADIUS_DP));
        applyBlur(window, context.getResources().getDisplayMetrics().density);
    }

    /** Clips {@code view} (and its children) to the panel's rounded corners without drawing a fill. */
    static void clipToPanel(@NonNull View view, @NonNull Context context) {
        final float radius = panelRadiusPx(context.getResources().getDisplayMetrics().density);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View target, Outline outline) {
                outline.setRoundRect(0, 0, target.getWidth(), target.getHeight(), radius);
            }
        });
        view.setClipToOutline(true);
    }

    /**
     * Light mode: no black window dim, instead a warm scrim view over the host activity for as
     * long as the dialog window is attached. Dark mode: only the window dim.
     */
    static void applyScrim(@NonNull Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        Context context = dialog.getContext();
        boolean night = isNight(context.getResources().getConfiguration().uiMode);
        window.setDimAmount(dimAmount(Build.VERSION.SDK_INT, night));
        // Night: the one window dim, whichever builder made the dialog (Material or self-drawn).
        if (night) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            return;
        }
        Activity host = findActivity(context);
        if (host == null) return;
        int color = ContextCompat.getColor(context, scrimColorRes(Build.VERSION.SDK_INT));
        View decor = window.getDecorView();
        ScrimController controller = new ScrimController(host, color);
        decor.addOnAttachStateChangeListener(controller);
        if (decor.isAttachedToWindow()) controller.onViewAttachedToWindow(decor);
    }

    @Nullable
    private static Activity findActivity(@Nullable Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** Adds a full-window tint to the host activity while the dialog window is attached. */
    private static final class ScrimController implements View.OnAttachStateChangeListener {
        private final Activity host;
        private final int color;
        @Nullable private View scrim;

        ScrimController(Activity host, int color) {
            this.host = host;
            this.color = color;
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            if (scrim != null) return;
            View hostDecor = host.getWindow().getDecorView();
            if (!(hostDecor instanceof ViewGroup)) return;
            scrim = new View(host);
            scrim.setBackgroundColor(color);
            ((ViewGroup) hostDecor).addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            if (scrim == null) return;
            ViewGroup parent = (ViewGroup) scrim.getParent();
            if (parent != null) parent.removeView(scrim);
            scrim = null;
        }
    }

    /** A translucent rounded surface with a hairline outline; fill is a colour resource. */
    static GradientDrawable surface(@NonNull Context context, @ColorRes int fillRes, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(ContextCompat.getColor(context, fillRes));
        shape.setCornerRadius(dp(context, radiusDp));
        shape.setStroke(Math.round(dp(context, 1)), ContextCompat.getColor(context, R.color.local_games_outline));
        return shape;
    }

    static float dp(@NonNull Context context, int value) {
        return value * context.getResources().getDisplayMetrics().density;
    }
}
