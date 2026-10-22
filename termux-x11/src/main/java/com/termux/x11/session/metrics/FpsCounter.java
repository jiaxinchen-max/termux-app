package com.termux.x11.session.metrics;

/**
 * Turns a cumulative presented-frame counter into frames per second by differencing consecutive
 * readings. Pure logic; the reader supplies the counter and a monotonic timestamp.
 */
public final class FpsCounter {
    private long lastCount = -1L;
    private long lastNanos;
    private boolean hasLast;

    /**
     * @param count cumulative frame counter, or a negative value when the source is unavailable
     * @return the rate since the previous reading, or NaN for the first reading, an unavailable
     * source, a counter that went backwards, or a non-advancing clock
     */
    public double update(long count, long nowNanos) {
        if (count < 0) {
            reset();
            return Double.NaN;
        }
        double result = Double.NaN;
        if (hasLast && count >= lastCount && nowNanos > lastNanos) {
            result = (count - lastCount) * 1_000_000_000d / (nowNanos - lastNanos);
        }
        lastCount = count;
        lastNanos = nowNanos;
        hasLast = true;
        return result;
    }

    /** Forgets the previous reading (use after a pause so the gap is not averaged into one point). */
    public void reset() {
        hasLast = false;
        lastCount = -1L;
    }
}
