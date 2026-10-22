package com.termux.x11.controller.widget;

import com.termux.x11.controller.inputcontrols.ControlsOrientation;

/**
 * Where the controller elements of a profile are laid out inside the overlay view.
 *
 * <p>Elements live in a layout box of the profile's own orientation: {@code long x short} edge of
 * the view for a landscape profile, {@code short x long} for a portrait profile. When that
 * orientation is the view's current orientation the box is the view and nothing is scaled
 * ({@link #scale} is exactly 1). Otherwise the box is scaled uniformly to fit inside the view and
 * centered, so its buttons keep their relative positions and neither overlap nor leave the screen.
 * The snapping grid is one hundredth of the long edge in both cases, so element sizes do not
 * depend on the orientation.</p>
 */
public final class ControlLayoutGeometry {
    /** Width of the layout box, in layout units (a multiple of {@link #snappingSize}). */
    public final int layoutWidth;
    /** Height of the layout box, in layout units (a multiple of {@link #snappingSize}). */
    public final int layoutHeight;
    public final int snappingSize;
    /** Layout unit to view pixel factor; exactly 1 when the profile matches the view orientation. */
    public final float scale;
    public final float offsetX;
    public final float offsetY;

    private ControlLayoutGeometry(int layoutWidth, int layoutHeight, int snappingSize,
                                  float scale, float offsetX, float offsetY) {
        this.layoutWidth = layoutWidth;
        this.layoutHeight = layoutHeight;
        this.snappingSize = snappingSize;
        this.scale = scale;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    /** True when a profile of {@code profile} orientation is shown unscaled on a view of this size. */
    public static boolean isUnscaled(ControlsOrientation profile, int viewWidth, int viewHeight) {
        return profile == ControlsOrientation.ofSize(viewWidth, viewHeight);
    }

    public static ControlLayoutGeometry compute(ControlsOrientation profile, int viewWidth, int viewHeight) {
        int longEdge = Math.max(viewWidth, viewHeight);
        int shortEdge = Math.min(viewWidth, viewHeight);
        int snap = Math.max(1, longEdge / 100);
        int boxWidth = profile == ControlsOrientation.LANDSCAPE ? longEdge : shortEdge;
        int boxHeight = profile == ControlsOrientation.LANDSCAPE ? shortEdge : longEdge;
        int width = snapDown(boxWidth, snap);
        int height = snapDown(boxHeight, snap);
        float scale = isUnscaled(profile, viewWidth, viewHeight)
            ? 1.0f
            : Math.min((float) viewWidth / width, (float) viewHeight / height);
        return new ControlLayoutGeometry(width, height, snap, scale,
            (viewWidth - width * scale) * 0.5f, (viewHeight - height * scale) * 0.5f);
    }

    private static int snapDown(int value, int snap) {
        return Math.max(snap, value / snap * snap);
    }

    public float toLayoutX(float viewX) {
        return (viewX - offsetX) / scale;
    }

    public float toLayoutY(float viewY) {
        return (viewY - offsetY) / scale;
    }

    public float toViewX(float layoutX) {
        return layoutX * scale + offsetX;
    }

    public float toViewY(float layoutY) {
        return layoutY * scale + offsetY;
    }
}
