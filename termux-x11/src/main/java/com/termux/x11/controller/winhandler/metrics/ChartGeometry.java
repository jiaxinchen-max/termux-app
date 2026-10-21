package com.termux.x11.controller.winhandler.metrics;

/** Pure mapping from samples to plot coordinates for {@code MetricChartView}. */
public final class ChartGeometry {
    private static final double[] FPS_STEPS = {30, 60, 90, 120, 144, 165, 240, 360};
    private static final double[] TEMPERATURE_STEPS = {50, 60, 80, 100, 120};

    private ChartGeometry() { }

    /** Fixed 0-100 axis for percentage metrics. */
    public static double percentAxisMax() {
        return 100d;
    }

    /**
     * Upper axis bound for FPS: the smallest common refresh step that holds {@code peak}; always at
     * least 30. NaN/non-positive peaks (no data) give 30.
     */
    public static double fpsAxisMax(double peak) {
        if (Double.isNaN(peak) || peak <= 0) return FPS_STEPS[0];
        for (double step : FPS_STEPS) {
            if (peak <= step) return step;
        }
        return Math.ceil(peak / 60d) * 60d;
    }

    /**
     * Upper axis bound in degrees Celsius (the lower bound is a fixed 0): the smallest step that
     * holds {@code peak}; always at least 50. NaN/non-positive peaks (no data) give 50.
     */
    public static double temperatureAxisMax(double peak) {
        if (Double.isNaN(peak) || peak <= 0) return TEMPERATURE_STEPS[0];
        for (double step : TEMPERATURE_STEPS) {
            if (peak <= step) return step;
        }
        return Math.ceil(peak / 20d) * 20d;
    }

    /**
     * X of sample {@code index} when {@code count} samples are shown in a window of
     * {@link MetricSeries#CAPACITY} slots: the newest sample sits at the right edge, a short history
     * is right-aligned (older slots stay empty).
     */
    public static float xFor(int index, int count, float left, float width) {
        int slots = MetricSeries.CAPACITY;
        int slot = slots - count + index;
        return left + width * slot / (slots - 1);
    }

    /** Y of {@code value} on a 0..axisMax axis drawn in [top, top+height]; clamped to the plot. */
    public static float yFor(double value, double axisMax, float top, float height) {
        double max = axisMax > 0 ? axisMax : 1d;
        double ratio = Math.max(0d, Math.min(1d, value / max));
        return (float) (top + height * (1d - ratio));
    }
}
