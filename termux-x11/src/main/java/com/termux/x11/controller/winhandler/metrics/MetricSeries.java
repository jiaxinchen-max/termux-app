package com.termux.x11.controller.winhandler.metrics;

/**
 * Fixed-capacity ring buffer of one-sample-per-second metric values (the most recent
 * {@link #CAPACITY} seconds). A sample may be {@link Double#NaN}, meaning "no data for that second"
 * (for example FPS unavailable); NaN is kept in the timeline but ignored by min/max.
 * Plain Java, not thread-safe: callers use it from one thread (the UI thread).
 */
public final class MetricSeries {
    public static final int CAPACITY = 60;

    private final double[] values = new double[CAPACITY];
    private int start;
    private int size;

    /** Appends a sample; once full the oldest sample is dropped. */
    public void append(double value) {
        if (size < CAPACITY) {
            values[(start + size) % CAPACITY] = value;
            size++;
        } else {
            values[start] = value;
            start = (start + 1) % CAPACITY;
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        start = 0;
        size = 0;
    }

    /** Samples oldest to newest (a copy of length {@link #size()}). */
    public double[] snapshot() {
        double[] out = new double[size];
        for (int i = 0; i < size; i++) out[i] = values[(start + i) % CAPACITY];
        return out;
    }

    /** The newest sample, or NaN when empty or when the newest second had no data. */
    public double latest() {
        return size == 0 ? Double.NaN : values[(start + size - 1) % CAPACITY];
    }

    /** True when at least one sample holds a real value. */
    public boolean hasData() {
        return !Double.isNaN(max());
    }

    /** Largest real value, or NaN when there is none. */
    public double max() {
        double max = Double.NaN;
        for (int i = 0; i < size; i++) {
            double v = values[(start + i) % CAPACITY];
            if (!Double.isNaN(v) && (Double.isNaN(max) || v > max)) max = v;
        }
        return max;
    }

    /** Smallest real value, or NaN when there is none. */
    public double min() {
        double min = Double.NaN;
        for (int i = 0; i < size; i++) {
            double v = values[(start + i) % CAPACITY];
            if (!Double.isNaN(v) && (Double.isNaN(min) || v < min)) min = v;
        }
        return min;
    }
}
