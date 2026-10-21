package com.termux.x11.controller.winhandler.metrics;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.termux.x11.controller.core.CPUStatus;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Feeds a {@link PerformanceMetricsSampler} once per second while it is started. The sysfs CPU
 * read, the memory query, the temperature read and the frame-counter read run on a single daemon worker thread; the
 * results are delivered to the sampler on the main thread. {@link #stop()} shuts the worker down and
 * drops any result still in flight, so nothing keeps running (or posts) after the host pauses.
 *
 * <p>Lifecycle: the host calls {@link #start()} when the session is resumed and running and
 * {@link #stop()} when it pauses or is destroyed; both are idempotent. History is kept across
 * pause/resume of one session; a new recorder/sampler (a new session) starts empty.
 */
public final class PerformanceMetricsRecorder {
    /** Cumulative presented-frame counter; negative when unavailable. */
    public interface FrameCounterSource {
        long frameCount();
    }

    private final Context appContext;
    private final PerformanceMetricsSampler sampler;
    private final FrameCounterSource frames;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService worker;
    /** Main-thread only; bumped on every start/stop so late results of an old worker are ignored. */
    private int generation;

    public PerformanceMetricsRecorder(@NonNull Context context, @NonNull PerformanceMetricsSampler sampler,
                                      @NonNull FrameCounterSource frames) {
        this.appContext = context.getApplicationContext();
        this.sampler = sampler;
        this.frames = frames;
    }

    public boolean isRunning() {
        return worker != null;
    }

    /** Starts the 1 Hz sampling; no-op when already running. Main thread. */
    public void start() {
        if (worker != null) return;
        final int token = ++generation;
        final FpsCounter fpsCounter = new FpsCounter();
        final ActivityManager activityManager =
            (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "PerfMetricsSampler");
            thread.setDaemon(true);
            return thread;
        });
        worker = executor;
        executor.scheduleAtFixedRate(() -> {
            try {
                final double fps = fpsCounter.update(frames.frameCount(), SystemClock.elapsedRealtimeNanos());
                final double cpu = CPUStatus.getUsagePercent();
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(info);
                final long total = info.totalMem;
                final long used = Math.max(0L, total - info.availMem);
                final double memoryPercent = total > 0 ? used * 100d / total : 0d;
                // An unreadable sensor is NaN for this second; it never drops the other metrics.
                final double temperature = TemperatureReader.readBatteryCelsius(appContext);
                mainHandler.post(() -> {
                    if (token == generation) sampler.addSample(fps, cpu, memoryPercent, used, total, temperature);
                });
            } catch (RuntimeException ignored) {
                // A failed read must not kill the schedule; the second is simply skipped.
            }
        }, 0L, 1000L, TimeUnit.MILLISECONDS);
    }

    /** Cancels the sampling and discards in-flight results; no-op when stopped. Main thread. */
    public void stop() {
        generation++;
        if (worker == null) return;
        worker.shutdownNow();
        worker = null;
        mainHandler.removeCallbacksAndMessages(null);
    }
}
