package com.termux.localgames.api;

import android.content.Context;
import android.content.Intent;

import com.termux.localgames.service.LocalGameOrchestratorService;

/** Stable command constants for the persistent launch service. */
public final class LaunchTasks {

    public static final String ACTION_EXECUTE = "com.termux.localgames.action.EXECUTE_LAUNCH";
    public static final String ACTION_CANCEL = "com.termux.localgames.action.CANCEL_LAUNCH";
    public static final String ACTION_RECONCILE = "com.termux.localgames.action.RECONCILE_LAUNCH";
    public static final String ACTION_DISPLAY_CONNECTION = "com.termux.localgames.action.DISPLAY_CONNECTION";
    public static final String ACTION_FIRST_FRAME = "com.termux.localgames.action.FIRST_FRAME";
    public static final String EXTRA_TASK_ID = "com.termux.localgames.extra.LAUNCH_TASK_ID";
    public static final String EXTRA_FORCE = "com.termux.localgames.extra.LAUNCH_FORCE";
    public static final String EXTRA_DISPLAY_CONNECTED = "com.termux.localgames.extra.DISPLAY_CONNECTED";

    private LaunchTasks() {}

    public static Intent command(Context context, String action, String taskId) {
        return new Intent(context, LocalGameOrchestratorService.class)
            .setAction(action)
            .putExtra(EXTRA_TASK_ID, taskId);
    }
}
