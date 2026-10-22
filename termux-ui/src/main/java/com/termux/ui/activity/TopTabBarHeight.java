package com.termux.ui.activity;

/**
 * Height of the landscape top tab bar: it is exactly as tall as the system status bar, so the
 * icon-only row reads as an extension of it. Pure arithmetic so the fallback order is unit-testable.
 */
final class TopTabBarHeight {

    private TopTabBarHeight() {}

    /**
     * The first positive value among the live WindowInsets {@code statusBars} height, the
     * {@code status_bar_height} system resource and the fallback; never a hard-coded pixel count, so
     * it follows the device, the cutout and the system UI.
     *
     * @param insetsStatusBarPx WindowInsets statusBars top inset in px, or {@code <= 0} when unknown
     *                          (windows fitted between the bars are not given the inset)
     * @param resourceStatusBarPx {@code status_bar_height} in px, or {@code <= 0} when unreadable
     * @param fallbackPx used when neither source yields a positive height (24 dp in px)
     */
    static int resolve(int insetsStatusBarPx, int resourceStatusBarPx, int fallbackPx) {
        if (insetsStatusBarPx > 0) return insetsStatusBarPx;
        if (resourceStatusBarPx > 0) return resourceStatusBarPx;
        return Math.max(1, fallbackPx);
    }

    /**
     * Whether the bar's layout height has to be (re)applied. The bar is sized from every path that
     * can change the status bar height (chrome updates, each window-insets dispatch, configuration
     * changes and layout passes). Passes that follow a visibility or configuration change are
     * {@code force}d: the "current == target" comparison alone is not trusted there, because the
     * current value may itself have been resolved from the previous orientation's sources and the
     * view must be laid out again against the new ones. An unusable target never shrinks the bar.
     *
     * @param currentLayoutHeightPx the bar's current LayoutParams height
     * @param targetPx a value returned by {@link #resolve}
     * @param force {@code true} after a visibility/configuration change: apply even if equal
     */
    static boolean needsApply(int currentLayoutHeightPx, int targetPx, boolean force) {
        return targetPx > 0 && (force || currentLayoutHeightPx != targetPx);
    }

    /** Same as {@link #needsApply(int, int, boolean)} without forcing (plain re-validation). */
    static boolean needsApply(int currentLayoutHeightPx, int targetPx) {
        return needsApply(currentLayoutHeightPx, targetPx, false);
    }

    /**
     * The status bar inset of a window-insets dispatch, but only if it was received in the
     * orientation that is current now. An inset cached from the other orientation (portrait while
     * the window has already rotated to landscape) is exactly the stale 136 px value this bar must
     * never be sized from.
     *
     * @return the cached inset, or {@code -1} when unknown or recorded in another orientation
     */
    static int usableCachedInset(int cachedInsetPx, boolean cachedWasLandscape,
                                 boolean landscapeNow) {
        if (cachedInsetPx < 0 || cachedWasLandscape != landscapeNow) return -1;
        return cachedInsetPx;
    }

    /**
     * Whether the root window's insets may be read directly. Between a configuration change and the
     * next insets dispatch they still describe the old orientation, so they are not trusted then.
     */
    static boolean rootInsetsTrusted(boolean dispatchedSinceConfigChange) {
        return dispatchedSinceConfigChange;
    }

    /**
     * Whether a pass should size the bar now. A hidden bar is not sized (nothing is drawn and the
     * sources may describe the orientation it is not shown in); it is sized the moment it becomes
     * visible and again after the next layout/insets dispatch.
     */
    static boolean shouldApplyNow(boolean barVisible) {
        return barVisible;
    }

    /**
     * Names of the status bar height dimens, most specific first: the orientation-specific
     * framework dimen, then the generic one. The generic {@code status_bar_height} follows the
     * device's default orientation, not necessarily the window's.
     */
    static String[] statusBarResourceNames(boolean landscape) {
        return landscape
            ? new String[] {"status_bar_height_landscape", "status_bar_height"}
            : new String[] {"status_bar_height_portrait", "status_bar_height"};
    }
}
