package com.termux.ui.platform;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Per-desktop session settings that win over the global ones. A desktop with nothing stored here
 * follows the global setting: the X11 {@code touchMode} preference for the touch mode and
 * {@link OrientationPreference} for the screen orientation.
 *
 * <p>The X11 view only reads the global {@code touchMode} preference, so a session applies its
 * override by writing it there. {@link #rememberGlobalTouchMode} keeps the real global value until
 * the session ends, and a value left behind by a crashed session is restored on the next start.
 */
public final class DesktopSessionOverrides {

    /** X11 {@code touchMode} values: 1 trackpad, 2 simulated touch screen, 3 direct touch. */
    public static final String TOUCH_TRACKPAD = "1";
    public static final String TOUCH_SIMULATED = "2";
    public static final String TOUCH_DIRECT = "3";

    private static final String PREFERENCES = "desktop_session_overrides";
    private static final String PENDING_GLOBAL_TOUCH = "pendingGlobalTouchMode";

    private final SharedPreferences preferences;

    public DesktopSessionOverrides(@NonNull Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES,
            Context.MODE_PRIVATE);
    }

    public static boolean isTouchMode(@Nullable String value) {
        return TOUCH_TRACKPAD.equals(value) || TOUCH_SIMULATED.equals(value)
            || TOUCH_DIRECT.equals(value);
    }

    /** The desktop's own touch mode, or null to follow the global setting. */
    @Nullable
    public String getTouchMode(@NonNull String desktopId) {
        String value = preferences.getString(desktopId + ".touchMode", null);
        return isTouchMode(value) ? value : null;
    }

    public void setTouchMode(@NonNull String desktopId, @Nullable String value) {
        edit(desktopId + ".touchMode", isTouchMode(value) ? value : null);
    }

    /** The desktop's own orientation, or null to follow the global setting. */
    @Nullable
    public OrientationPreference getOrientation(@NonNull String desktopId) {
        String value = preferences.getString(desktopId + ".orientation", null);
        if (value == null) return null;
        for (OrientationPreference choice : OrientationPreference.values()) {
            if (choice.getStorageValue().equals(value)) return choice;
        }
        return null;
    }

    public void setOrientation(@NonNull String desktopId, @Nullable OrientationPreference value) {
        edit(desktopId + ".orientation", value == null ? null : value.getStorageValue());
    }

    /** Commit both imported overrides before publishing the new desktop inventory record. */
    public void restore(@NonNull String desktopId, @Nullable String touchMode,
                        @Nullable String orientation) throws java.io.IOException {
        if (!com.termux.ui.domain.DesktopDistro.isValidId(desktopId)
            || (touchMode != null && !isTouchMode(touchMode))) {
            throw new java.io.IOException("desktop_archive_settings_invalid");
        }
        if (orientation != null) {
            boolean known = false;
            for (OrientationPreference choice : OrientationPreference.values()) {
                if (choice.getStorageValue().equals(orientation)) known = true;
            }
            if (!known) throw new java.io.IOException("desktop_archive_settings_invalid");
        }
        SharedPreferences.Editor editor = preferences.edit();
        if (touchMode == null) editor.remove(desktopId + ".touchMode");
        else editor.putString(desktopId + ".touchMode", touchMode);
        if (orientation == null) editor.remove(desktopId + ".orientation");
        else editor.putString(desktopId + ".orientation", orientation);
        if (!editor.commit()) throw new java.io.IOException("desktop_archive_settings_write_failed");
    }

    /** The orientation a session of this desktop runs in: its own, else the global one. */
    @NonNull
    public OrientationPreference effectiveOrientation(@NonNull Context context,
                                                      @NonNull String desktopId) {
        OrientationPreference own = getOrientation(desktopId);
        return own != null ? own : OrientationPreference.load(context);
    }

    /** The value to put in the X11 touchMode preference for this desktop. */
    @NonNull
    public static String effectiveTouchMode(@Nullable String own, @NonNull String global) {
        return isTouchMode(own) ? own : global;
    }

    /** The global touch mode saved by a session that has replaced it, or null. */
    @Nullable
    public String getPendingGlobalTouchMode() {
        String value = preferences.getString(PENDING_GLOBAL_TOUCH, null);
        return isTouchMode(value) ? value : null;
    }

    /** Remembers the global value before a session overwrites it; keeps the first one saved. */
    public void rememberGlobalTouchMode(@NonNull String global) {
        if (getPendingGlobalTouchMode() == null) edit(PENDING_GLOBAL_TOUCH, global);
    }

    public void clearPendingGlobalTouchMode() {
        edit(PENDING_GLOBAL_TOUCH, null);
    }

    private void edit(String key, @Nullable String value) {
        SharedPreferences.Editor editor = preferences.edit();
        if (value == null) editor.remove(key); else editor.putString(key, value);
        editor.apply();
    }
}
