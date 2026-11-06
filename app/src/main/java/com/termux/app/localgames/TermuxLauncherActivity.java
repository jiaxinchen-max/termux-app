package com.termux.app.localgames;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.termux.app.TermuxActivity;

/** Launcher aliases live in their own task so disabling an alias cannot dismiss the app task. */
public final class TermuxLauncherActivity extends Activity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, TermuxActivity.class)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
