package com.termux.app.localgames;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import androidx.annotation.Nullable;

import com.termux.app.TermuxActivity;

/** Isolated-process trampoline that performs an actual main-process restart. */
public final class TermuxRestartActivity extends Activity {
    static final String EXTRA_MAIN_PID = "main_pid";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int mainPid = getIntent().getIntExtra(EXTRA_MAIN_PID, -1);
        if (mainPid > 0 && mainPid != Process.myPid()) Process.killProcess(mainPid);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(this, TermuxActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finishAndRemoveTask();
        }, 250);
    }
}
