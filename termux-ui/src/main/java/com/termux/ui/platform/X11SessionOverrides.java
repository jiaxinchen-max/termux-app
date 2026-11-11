package com.termux.ui.platform;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Per-game and per-desktop settings. Only desktops can inherit the X11 touch mode or
 * {@link X11OrientationSettings}. Games keep independent touch and orientation choices.
 *
 * <p>The X11 view only reads the global {@code touchMode} preference, so a session applies its
 * override by writing it there. {@link #rememberGlobalTouchMode} keeps the real global value until
 * the session ends, and a value left behind by a crashed session is restored on the next start.
 */
public final class X11SessionOverrides {

    /** X11 {@code touchMode} values: 1 trackpad, 2 simulated touch screen, 3 direct touch. */
    public static final String TOUCH_TRACKPAD = "1";
    public static final String TOUCH_SIMULATED = "2";
    public static final String TOUCH_DIRECT = "3";

    // Keep the existing file and desktop keys when adding game settings.
    private static final String PREFERENCES = "desktop_session_overrides";
    private static final String PENDING_GLOBAL_TOUCH = "pendingGlobalTouchMode";

    private final SharedPreferences preferences;

    public X11SessionOverrides(@NonNull Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES,
            Context.MODE_PRIVATE);
    }

    /** Game keys never overlap desktop IDs or another game's settings. */
    @NonNull
    public static String gameKey(@NonNull String gameId) {
        if (gameId.isEmpty()) throw new IllegalArgumentException("gameId required");
        return "game:" + gameId;
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

    /** Missing game choices, including the former "follow X11" choice, start in trackpad mode. */
    @NonNull
    public String getGameTouchMode(@NonNull String gameId) {
        String value = preferences.getString(gameKey(gameId) + ".touchMode", TOUCH_TRACKPAD);
        if (!isTouchMode(value)) throw new IllegalStateException("Invalid program touch mode");
        return value;
    }

    public void setGameTouchMode(@NonNull String gameId, @NonNull String value) {
        if (!isTouchMode(value)) throw new IllegalArgumentException("Invalid program touch mode");
        edit(gameKey(gameId) + ".touchMode", value);
    }

    /** The desktop's own orientation, or null to follow the X11 session setting. */
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

    /** A game's own choice. Games without a saved choice start in landscape, never a global mode. */
    @NonNull
    public OrientationPreference getGameOrientation(@NonNull String gameId) {
        String value = preferences.getString(gameKey(gameId) + ".orientation",
            OrientationPreference.HORIZONTAL.getStorageValue());
        for (OrientationPreference choice : OrientationPreference.values()) {
            if (choice.getStorageValue().equals(value)) return choice;
        }
        throw new IllegalStateException("Invalid program orientation");
    }

    public void setGameOrientation(@NonNull String gameId, @NonNull OrientationPreference value) {
        edit(gameKey(gameId) + ".orientation", value.getStorageValue());
    }

    /** Commit both imported overrides before publishing the new desktop inventory record. */
    public void restore(@NonNull String desktopId, @Nullable String touchMode,
                        @Nullable String orientation) throws java.io.IOException {
        if (!com.termux.ui.desktop.domain.DesktopDistro.isValidId(desktopId)
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

    /** The desktop's own orientation, otherwise the independent X11 session setting. */
    @NonNull
    public OrientationPreference effectiveOrientation(@NonNull Context context,
                                                      @NonNull String desktopId) {
        OrientationPreference own = getOrientation(desktopId);
        return own != null ? own : X11OrientationSettings.load(context);
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
