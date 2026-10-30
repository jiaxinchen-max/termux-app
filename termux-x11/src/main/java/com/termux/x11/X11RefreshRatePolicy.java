package com.termux.x11;

/** One session's explicit output rate; null retains the legacy display-driven behavior. */
final class X11RefreshRatePolicy {
    private Integer explicitRate;

    static void validateExplicit(Integer rate) {
        if (rate != null && (rate < 1 || rate > 120))
            throw new IllegalArgumentException("Explicit X11 refresh rate must be between 1 and 120 Hz");
    }

    void setExplicit(Integer rate) {
        validateExplicit(rate);
        explicitRate = rate;
    }

    int resolve(float displayRate) {
        return explicitRate != null ? explicitRate : (int) displayRate;
    }
}
