package com.termux.ui.activity;

import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.ui.R;
import com.termux.ui.api.LaunchHostException;
import com.termux.ui.api.XServerTasks;
import com.termux.ui.domain.XServerBackendType;

import java.io.IOException;

/**
 * The Settings row that picks the X server for game launches (legacy in-process server or the
 * independent Xlorie package). Kept out of LocalGamesActivity so the activity only gains one call.
 */
final class XServerSettings {

    private XServerSettings() {}

    static void render(AppCompatActivity activity, SettingsMenuBuilder menu, Runnable rerender) {
        XServerTasks tasks = new XServerTasks(activity);
        XServerBackendType selected = tasks.selected();
        String[] labels = {activity.getString(R.string.local_games_x_server_legacy),
            activity.getString(R.string.local_games_x_server_xlorie)};
        menu.choice(activity.getString(R.string.local_games_settings_x_server), labels,
            selected == XServerBackendType.XLORIE_EXTERNAL ? 1 : 0, which -> choose(activity,
                tasks, which == 1 ? XServerBackendType.XLORIE_EXTERNAL
                    : XServerBackendType.LEGACY_IN_PROCESS, rerender));
    }

    private static void choose(AppCompatActivity activity, XServerTasks tasks,
                               XServerBackendType type, Runnable rerender) {
        if (type == XServerBackendType.XLORIE_EXTERNAL) {
            if (!tasks.isSupported(type)) {
                Toast.makeText(activity, R.string.local_games_x_server_abi_unsupported,
                    Toast.LENGTH_LONG).show();
                return;
            }
            if (!tasks.isInstalled(type)) {
                // Selecting an X server that is not there would only fail the next launch.
                new FrostedDialogBuilder(activity)
                    .setTitle(R.string.local_games_x_server_xlorie)
                    .setMessage(R.string.local_games_x_server_install_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.desktop_action_install, (dialog, which) ->
                        new Thread(() -> install(activity, tasks), "XServerInstall").start())
                    .show();
                return;
            }
        }
        try {
            tasks.select(type);
        } catch (IOException error) {
            Toast.makeText(activity, activity.getString(R.string.desktop_error,
                "x_server_select_failed"), Toast.LENGTH_LONG).show();
        }
        rerender.run();
    }

    private static void install(AppCompatActivity activity, XServerTasks tasks) {
        String message;
        try {
            tasks.installXlorie();
            message = activity.getString(R.string.desktop_install_started);
        } catch (LaunchHostException error) {
            message = activity.getString(R.string.desktop_error, error.getErrorCode());
        }
        String shown = message;
        activity.runOnUiThread(() -> Toast.makeText(activity, shown, Toast.LENGTH_LONG).show());
    }
}
