package com.termux.x11.session.metrics;

/**
 * Holds the last minute of FPS / CPU % / memory % / temperature (one point per second) for the
 * Performance page, plus the latest memory used/total bytes. Pure Java; mutate and read on the UI thread only.
 */
public final class PerformanceMetricsSampler {
    /** Called on the mutating thread after a sample was added or the buffers were cleared. */
    public interface Listener {
        void onMetricsChanged();
    }

    private final MetricSeries fps = new MetricSeries();
    private final MetricSeries cpu = new MetricSeries();
    private final MetricSeries memory = new MetricSeries();
    private final MetricSeries temperature = new MetricSeries();
    private long memoryUsedBytes = -1L;
    private long memoryTotalBytes = -1L;
    private long version;
    private Listener listener;

    public MetricSeries fps() { return fps; }
    public MetricSeries cpu() { return cpu; }
    public MetricSeries memory() { return memory; }
    /** Degrees Celsius; NaN for seconds without a reading. */
    public MetricSeries temperature() { return temperature; }

    public long memoryUsedBytes() { return memoryUsedBytes; }
    public long memoryTotalBytes() { return memoryTotalBytes; }

    /** Increments on every change; views redraw only when it moved. */
    public long version() { return version; }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Adds one second of data without a temperature reading. */
    public void addSample(double fpsValue, double cpuPercent, double memoryPercent,
                          long memoryUsed, long memoryTotal) {
        addSample(fpsValue, cpuPercent, memoryPercent, memoryUsed, memoryTotal, Double.NaN);
    }

    /**
     * Adds one second of data. {@code fpsValue} NaN or negative means FPS is unavailable; CPU and
     * memory percentages are clamped to 0-100; {@code temperatureCelsius} NaN, infinite or outside
     * the plausible range is stored as "no reading".
     */
    public void addSample(double fpsValue, double cpuPercent, double memoryPercent,
                          long memoryUsed, long memoryTotal, double temperatureCelsius) {
        fps.append(Double.isNaN(fpsValue) || fpsValue < 0 ? Double.NaN : fpsValue);
        cpu.append(clampPercent(cpuPercent));
        memory.append(clampPercent(memoryPercent));
        temperature.append(TemperatureReader.sanitize(temperatureCelsius));
        memoryUsedBytes = memoryUsed;
        memoryTotalBytes = memoryTotal;
        changed();
    }

    /** Drops all history (a new game session starts with empty charts). */
    public void clear() {
        fps.clear();
        cpu.clear();
        memory.clear();
        temperature.clear();
        memoryUsedBytes = -1L;
        memoryTotalBytes = -1L;
        changed();
    }

    private void changed() {
        version++;
        if (listener != null) listener.onMetricsChanged();
    }

    static double clampPercent(double value) {
        if (Double.isNaN(value)) return 0d;
        return Math.max(0d, Math.min(100d, value));
    }
}
