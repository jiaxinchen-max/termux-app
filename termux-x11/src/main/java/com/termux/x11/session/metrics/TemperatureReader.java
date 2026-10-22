package com.termux.x11.session.metrics;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

/**
 * Reads the device temperature for the Performance page. The battery temperature (sticky
 * {@code ACTION_BATTERY_CHANGED}, tenths of a degree Celsius) is the one sensor every app may read
 * without a permission; it is also what the emulator reports. Any reading that is missing or not
 * plausible becomes {@link Double#NaN}, which the page shows as unavailable.
 */
public final class TemperatureReader {
    /** Plausible battery/skin range in degrees Celsius; anything outside is treated as no reading. */
    static final double MIN_PLAUSIBLE_CELSIUS = -40d;
    static final double MAX_PLAUSIBLE_CELSIUS = 150d;

    private TemperatureReader() { }

    /** Current battery temperature in degrees Celsius, or NaN when it cannot be read. Any thread. */
    public static double readBatteryCelsius(Context context) {
        try {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return Double.NaN;
            return fromBatteryTenths(battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE));
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    /**
     * Converts the battery broadcast value (tenths of a degree Celsius) to degrees. A missing extra
     * ({@link Integer#MIN_VALUE}) and 0 (what devices without a sensor report) mean "no reading".
     */
    static double fromBatteryTenths(int tenths) {
        if (tenths == Integer.MIN_VALUE || tenths == 0) return Double.NaN;
        return sanitize(tenths / 10d);
    }

    /** NaN for non-finite or implausible values, otherwise the value itself. */
    static double sanitize(double celsius) {
        if (Double.isNaN(celsius) || Double.isInfinite(celsius)) return Double.NaN;
        if (celsius < MIN_PLAUSIBLE_CELSIUS || celsius > MAX_PLAUSIBLE_CELSIUS) return Double.NaN;
        return celsius;
    }
}
