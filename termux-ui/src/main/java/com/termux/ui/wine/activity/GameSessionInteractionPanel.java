package com.termux.ui.wine.activity;

import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.ui.R;
import com.termux.ui.activity.GameSessionOrientation;
import com.termux.ui.activity.SettingsMenuBuilder;
import com.termux.ui.platform.OrientationPreference;
import com.termux.ui.platform.X11SessionOverrides;
import com.termux.x11.X11SessionView;

/** Per-game choices in the Operation drawer. The shared X11 touch value is leased while resumed. */
final class GameSessionInteractionPanel {
    private static final String[] TOUCH_MODES = {X11SessionOverrides.TOUCH_TRACKPAD,
        X11SessionOverrides.TOUCH_SIMULATED, X11SessionOverrides.TOUCH_DIRECT};
    private static final OrientationPreference[] ORIENTATIONS = {
        OrientationPreference.HORIZONTAL, OrientationPreference.VERTICAL, OrientationPreference.AUTO};

    private final AppCompatActivity activity;
    private final X11SessionView session;
    private final LinearLayout host;
    private final X11SessionOverrides overrides;
    private final String gameId;
    @Nullable private String globalTouchMode;

    GameSessionInteractionPanel(AppCompatActivity activity, X11SessionView session,
                                LinearLayout parent, String gameId) {
        this.activity = activity;
        this.session = session;
        this.overrides = new X11SessionOverrides(activity);
        this.gameId = gameId;
        host = new LinearLayout(activity);
        host.setOrientation(LinearLayout.VERTICAL);
        parent.addView(host, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        render();
    }

    void resume() {
        applyOrientation();
        applyTouchMode();
        render();
    }

    void pause() {
        if (globalTouchMode == null) return;
        session.setTouchModeValue(globalTouchMode);
        overrides.clearPendingGlobalTouchMode();
        globalTouchMode = null;
    }

    void render() {
        SettingsMenuBuilder menu = new SettingsMenuBuilder(activity, host);
        String[] touchLabels = {activity.getString(R.string.desktop_touch_trackpad),
            activity.getString(R.string.desktop_touch_simulated),
            activity.getString(R.string.desktop_touch_direct)};
        menu.choice(activity.getString(R.string.desktop_session_touch_mode_title), touchLabels,
            indexOf(TOUCH_MODES, overrides.getGameTouchMode(gameId)), which -> {
                overrides.setGameTouchMode(gameId, TOUCH_MODES[which]);
                applyTouchMode();
                render();
            });
        String[] orientationLabels = {
            activity.getString(R.string.local_games_orientation_horizontal),
            activity.getString(R.string.local_games_orientation_vertical),
            activity.getString(R.string.local_games_orientation_auto)};
        menu.choice(activity.getString(R.string.desktop_session_orientation_title), orientationLabels,
            indexOf(ORIENTATIONS, overrides.getGameOrientation(gameId)), which -> {
                overrides.setGameOrientation(gameId, ORIENTATIONS[which]);
                applyOrientation();
                render();
            });
    }

    private void applyOrientation() {
        activity.setRequestedOrientation(GameSessionOrientation.requestedFor(
            overrides.getGameOrientation(gameId)));
    }

    private void applyTouchMode() {
        if (globalTouchMode == null) {
            String pending = overrides.getPendingGlobalTouchMode();
            if (pending != null) {
                session.setTouchModeValue(pending);
                overrides.clearPendingGlobalTouchMode();
            }
            globalTouchMode = session.getTouchModeValue();
        }
        overrides.rememberGlobalTouchMode(globalTouchMode);
        session.setTouchModeValue(overrides.getGameTouchMode(gameId));
    }

    private static <T> int indexOf(T[] values, @Nullable T selected) {
        for (int i = 0; i < values.length; i++) {
            if (java.util.Objects.equals(values[i], selected)) return i;
        }
        throw new IllegalArgumentException("Unknown session setting");
    }
}
