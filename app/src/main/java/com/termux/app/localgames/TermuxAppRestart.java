package com.termux.app.localgames;

import android.content.Context;
import android.content.Intent;
import android.os.Process;

import androidx.annotation.NonNull;

/** Hands a cold restart to an isolated process so the launcher survives main-process death. */
final class TermuxAppRestart {
    private TermuxAppRestart() { }

    static void coldRestart(@NonNull Context context) {
        Context applicationContext = context.getApplicationContext();
        applicationContext.startActivity(new Intent(applicationContext,
            TermuxRestartActivity.class)
            .putExtra(TermuxRestartActivity.EXTRA_MAIN_PID, Process.myPid())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
}
