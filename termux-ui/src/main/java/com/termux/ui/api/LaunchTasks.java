package com.termux.ui.api;

import android.content.Context;
import android.content.Intent;

import com.termux.ui.service.LocalGameOrchestratorService;

/** Stable command constants for the persistent launch service. */
public final class LaunchTasks {

    public static final String ACTION_EXECUTE = "com.termux.ui.action.EXECUTE_LAUNCH";
    public static final String ACTION_CANCEL = "com.termux.ui.action.CANCEL_LAUNCH";
    public static final String ACTION_RECONCILE = "com.termux.ui.action.RECONCILE_LAUNCH";
    public static final String ACTION_DISPLAY_CONNECTION = "com.termux.ui.action.DISPLAY_CONNECTION";
    public static final String ACTION_FIRST_FRAME = "com.termux.ui.action.FIRST_FRAME";
    public static final String EXTRA_TASK_ID = "com.termux.ui.extra.LAUNCH_TASK_ID";
    public static final String EXTRA_FORCE = "com.termux.ui.extra.LAUNCH_FORCE";
    public static final String EXTRA_DISPLAY_CONNECTED = "com.termux.ui.extra.DISPLAY_CONNECTED";

    /** Launch-task id prefixes carry the launch mode durably (survives process restart and
     *  retry) without a LaunchTask schema field: desktop-only vs. run-the-game. */
    public static final String GAME_TASK_PREFIX = "launch-";
    public static final String DESKTOP_TASK_PREFIX = "desktop-";

    /** True when the task was created to open the bare Wine desktop rather than run the game. */
    public static boolean isDesktopTaskId(String taskId) {
        return taskId != null && taskId.startsWith(DESKTOP_TASK_PREFIX);
    }

    private LaunchTasks() {}

    public static Intent command(Context context, String action, String taskId) {
        return new Intent(context, LocalGameOrchestratorService.class)
            .setAction(action)
            .putExtra(EXTRA_TASK_ID, taskId);
    }
}
