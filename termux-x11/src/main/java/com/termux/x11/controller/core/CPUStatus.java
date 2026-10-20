package com.termux.x11.controller.core;

public abstract class CPUStatus {
    public static short[] getCurrentClockSpeeds() {
        int numProcessors = Runtime.getRuntime().availableProcessors();
        short[] clockSpeeds = new short[numProcessors];
        for (int i = 0; i < numProcessors; i++) {
            int currFreq = FileUtils.readInt("/sys/devices/system/cpu/cpu"+i+"/cpufreq/scaling_cur_freq");
            clockSpeeds[i] = (short)(currFreq / 1000);
        }
        return clockSpeeds;
    }

    public static short getMaxClockSpeed(int cpuIndex) {
        int maxFreq = FileUtils.readInt("/sys/devices/system/cpu/cpu"+cpuIndex+"/cpufreq/cpuinfo_max_freq");
        return (short)(maxFreq / 1000);
    }

    /**
     * The dashboard's CPU figure: the average current clock as a percentage of the highest per-core
     * maximum clock (0-100). Returns 0 when the frequencies cannot be read (shows as 0/0 MHz).
     */
    public static int computeUsagePercent(short[] currentClockSpeeds, short[] maxClockSpeeds) {
        if (currentClockSpeeds == null || currentClockSpeeds.length == 0 || maxClockSpeeds == null) return 0;
        int total = 0;
        int maxClock = 0;
        for (int i = 0; i < currentClockSpeeds.length; i++) {
            total += currentClockSpeeds[i];
            if (i < maxClockSpeeds.length) maxClock = Math.max(maxClock, maxClockSpeeds[i]);
        }
        if (maxClock <= 0) return 0;
        int average = total / currentClockSpeeds.length;
        int percent = (int) (((float) average / maxClock) * 100.0f);
        return Math.max(0, Math.min(100, percent));
    }

    /** Reads the sysfs frequencies and returns {@link #computeUsagePercent}. Safe off the main thread. */
    public static int getUsagePercent() {
        short[] current = getCurrentClockSpeeds();
        short[] max = new short[current.length];
        for (int i = 0; i < max.length; i++) max[i] = getMaxClockSpeed(i);
        return computeUsagePercent(current, max);
    }
}
